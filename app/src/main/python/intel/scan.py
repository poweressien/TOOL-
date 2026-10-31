"""Scan orchestration: detect -> enrich -> search -> score -> verify -> profile -> narrate."""
from __future__ import annotations

import re
from concurrent.futures import ThreadPoolExecutor
import time as _time
from typing import Any, Callable, Dict, List, Optional
from urllib.parse import urlparse

from . import enrich, pivot, search, sources
from .config import MODES, provider_status
from .detect import (brandify, classify_query_label, clean_domain, clean_email, clean_url, clean_username,
                     domain_root_label, looks_like_company, normalize_kind, normalize_whitespace,
                     registrable_domain)
from .extract import extract_social_links
from .narrative import build_narrative
from .net import robots_allowed, safe_get
from .extract import parse_page
from .profile import (apply_site_evidence, build_claims, build_entities, build_entity_profile, infer_usage,
                      reputation_signals)
from .risk import build_risk
from .scoring import IDENTIFIER_KINDS, band, classify_source, query_tokens, score_result

SCHEMA_VERSION = 2
CC_NAMES = {"ng": "Nigeria", "gh": "Ghana", "ke": "Kenya", "za": "South Africa", "uk": "United Kingdom",
            "us": "United States", "ca": "Canada", "au": "Australia", "in": "India", "de": "Germany",
            "fr": "France", "br": "Brazil", "cn": "China", "jp": "Japan", "ae": "United Arab Emirates",
            "eg": "Egypt", "es": "Spain", "it": "Italy", "nl": "Netherlands", "ie": "Ireland", "sg": "Singapore"}
FETCH_SKIP = {"social", "linkedin", "people_search", "search", "reputation"}
Progress = Optional[Callable[[str], None]]


def _say(progress: Progress, msg: str) -> None:
    if progress:
        try:
            progress(msg)
        except Exception:
            pass


# ---------------------------------------------------------------- context per kind
def build_context(kind: str, value: str, region: Optional[str]) -> Dict[str, Any]:
    ctx: Dict[str, Any] = {"kind": kind, "official_domains": set(), "tokens": query_tokens(value),
                           "proximity_needles": []}
    enrichment: Dict[str, Any] = {}
    if kind == "phone":
        ph = enrich.parse_phone_details(value, region)
        enrichment["phone"] = ph
        if ph["status"] == "ok":
            ctx.update(nsn=ph["national_significant_number"], cc=str(ph["country_code"]), phone_variants=ph["variants"], tokens=[])
            ctx["proximity_needles"] = [v for v in ph["variants"] if " " in v] + ph["variants"][:1]
        else:
            digits = re.sub(r"\D", "", value)
            ctx.update(kind="number", digits=digits, raw=value, phone_variants=[], nsn=digits, tokens=[])
            ctx["fallback_kind"] = "number"
    elif kind == "email":
        email = clean_email(value)
        local, domain = email.split("@", 1)
        ctx.update(email=email, local=local, domain=domain, tokens=[])
        ctx["proximity_needles"] = [email]
        enrichment["email"] = enrich.email_details(email)
        if domain not in enrich.FREE_EMAIL:
            ctx["official_domains"] = {registrable_domain(domain)}
    elif kind == "domain":
        d = clean_domain(value)
        ctx.update(domain=d, brand=brandify(domain_root_label(d)), tokens=[])
        ctx["official_domains"] = {registrable_domain(d)}
    elif kind == "url":
        url = clean_url(value)
        enrichment["url"] = enrich.url_details(url)
        d = enrichment["url"]["host"]
        ctx.update(url=url, domain=clean_domain(d), tokens=[])
        ctx["official_domains"] = {registrable_domain(d)}
    elif kind == "ip":
        ctx.update(ip=value.strip().strip("[]"), tokens=[])
    elif kind == "username":
        ctx.update(username=clean_username(value), tokens=[clean_username(value).lower()])
    elif kind == "number":
        digits = re.sub(r"\D", "", value)
        ctx.update(digits=digits, raw=value, tokens=[], nsn=digits)
    else:
        ctx.update(name=value)
    return ctx, enrichment


def run_enrichment(kind: str, ctx: Dict[str, Any], enrichment: Dict[str, Any], deep: bool, region: Optional[str],
                   progress: Progress, keys: Optional[Dict[str, str]] = None) -> None:
    tasks: Dict[str, Callable[[], Any]] = {}
    needles = [ctx.get("domain", "")]
    if kind == "phone" and enrichment["phone"]["status"] == "ok":
        tasks["numverify"] = lambda: enrich.numverify_lookup(enrichment["phone"]["e164"], keys)
    domain = ctx.get("domain") if kind in {"domain", "url"} else None
    if kind == "email" and not enrichment["email"]["is_free_provider"]:
        domain = ctx["domain"]
    if domain:
        tasks["registration"] = lambda: enrich.domain_registration(domain)
        tasks["dns"] = lambda: enrich.dns_records(domain, deep=deep)
        if kind in {"domain", "url"} or deep:
            target = ctx.get("url") if kind == "url" else f"https://{domain}"
            tasks["website"] = lambda: enrich.website_fingerprint(target, needles=None, region=region, deep=deep)
        if deep:
            tasks["certificates"] = lambda: enrich.cert_transparency(domain)
            tasks["wayback"] = lambda: enrich.wayback_first_seen(domain)
    if kind == "email" and enrichment["email"]["is_free_provider"]:
        tasks["dns"] = lambda: enrich.dns_records(ctx["domain"])
    if kind == "ip":
        tasks["ip"] = lambda: enrich.ip_details(ctx["ip"])
    if not tasks:
        return
    _say(progress, "Running enrichment (DNS, registration, website)…")
    with ThreadPoolExecutor(max_workers=6) as pool:
        futs = {k: pool.submit(fn) for k, fn in tasks.items()}
        for k, f in futs.items():
            try:
                enrichment[k] = f.result(timeout=60)
            except Exception as exc:
                enrichment[k] = {"status": "error", "note": f"{type(exc).__name__}: {exc}"[:160]}


def location_hint(kind: str, ctx: Dict[str, Any], enrichment: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    ph = enrichment.get("phone") or {}
    if ph.get("status") == "ok":
        loc = ph["location"] if ph.get("location") and ph["location"] != ph["country"] else ""
        value = f"{ph['country']}{' - ' + loc if loc else ''}"
        return {"value": value, "confidence": 85 if ph["is_valid"] else 45,
                "basis": "Numbering-plan prefix; shows where the number was issued, not where the holder is."}
    dom = ctx.get("domain", "")
    tld = dom.rsplit(".", 1)[-1] if "." in dom else ""
    if tld in CC_NAMES:
        return {"value": CC_NAMES[tld], "confidence": 35,
                "basis": f"Country-code TLD .{tld}; many such domains are registered by foreign owners."}
    net = (enrichment.get("ip") or {}).get("network", {})
    if net.get("country"):
        return {"value": net["country"], "confidence": 60, "basis": "RDAP network registration country."}
    return None


# ---------------------------------------------------------------- page verification (deep)
def verify_pages(ranked: List[Dict[str, Any]], ctx: Dict[str, Any], limit: int, region: Optional[str],
                 progress: Progress) -> Dict[str, Dict[str, Any]]:
    kind = ctx["kind"]
    if kind == "person_or_company" and not looks_like_company(ctx.get("name", "")):
        return {}  # never crawl personal pages for a person-like name
    candidates = [r for r in ranked if r["source"] not in FETCH_SKIP and r.get("link", "").startswith("http")][:limit]
    if kind in IDENTIFIER_KINDS or kind == "username":
        needles = _page_needles(ctx)
    else:
        needles = [ctx.get("name") or ctx.get("domain") or ""]

    def fetch(res):
        url = res["link"]
        if not robots_allowed(url):
            return url, {"status": "robots_blocked"}
        resp = safe_get(url, max_bytes=900_000)
        if not resp or resp.status >= 400 or "html" not in resp.content_type.lower() and resp.content_type:
            return url, {"status": "unavailable"}
        page = parse_page(resp.text, resp.final_url, resp.headers, needles=needles, region=region)
        if ctx["kind"] in {"phone", "number"}:  # digits may be formatted arbitrarily on the page
            from .scoring import exact_match

            page["exact_on_page"] = exact_match(page["text_sample"] + " " + " ".join(page["phones"]), ctx) or \
                page["exact_on_page"]
        page["status"] = "ok"
        return url, page

    out: Dict[str, Dict[str, Any]] = {}
    _say(progress, f"Verifying {len(candidates)} top pages…")
    with ThreadPoolExecutor(max_workers=4) as pool:
        for url, page in pool.map(fetch, candidates):
            out[url] = page
    return out


def _page_needles(ctx: Dict[str, Any]) -> List[str]:
    if ctx["kind"] == "email":
        return [ctx["email"]]
    if ctx["kind"] == "phone":
        return ctx.get("phone_variants", [])
    if ctx["kind"] == "number":
        return [ctx["digits"]]
    if ctx["kind"] == "ip":
        return [ctx["ip"]]
    if ctx["kind"] == "username":
        return [ctx["username"]]
    return []


# ---------------------------------------------------------------- confidence
def overall_confidence(claims: List[Dict[str, Any]], diagnostics: Dict[str, Any], results: List[Dict[str, Any]],
                       enrichment: Dict[str, Any], kind: str) -> Dict[str, Any]:
    factors: List[Dict[str, Any]] = []
    primary = next((c for c in claims if c["type"] in {"attribution", "primary_entity"}), None)
    base = primary["confidence"] if primary else 10
    factors.append({"factor": "primary claim", "value": base})
    provs = diagnostics.get("providers", {})
    active = [p for p in provs.values() if p["queries"]]
    ok_ratio = (sum(p["ok"] for p in active) / max(1, sum(p["queries"] for p in active))) if active else 0
    got = sum(1 for p in active if p["results"])
    quality = round(100 * ok_ratio * min(1, len(results) / 6))
    factors.append({"factor": "search coverage", "value": quality})
    technical = 0
    if enrichment.get("phone", {}).get("is_valid"):
        technical = 100
    elif enrichment.get("registration", {}).get("created") or enrichment.get("dns", {}).get("status") == "ok":
        technical = 80
    elif enrichment.get("email"):
        technical = 70
    factors.append({"factor": "verifiable technical data", "value": technical})
    score = round(0.6 * base + 0.25 * quality + 0.15 * technical)
    if primary and primary.get("negative"):
        score = min(score, 40)  # "nothing found" must never read as a confident identification
    if active and not got:
        score = min(score, 20)
        diagnostics["degraded"] = True
    return {"score": max(0, min(100, score)), "label": band(score), "factors": factors}


def public_contacts(ranked: List[Dict[str, Any]], ctx: Dict[str, Any]) -> Dict[str, Any]:
    """Organisation-level public contact points, only from the entity's own pages."""
    kind = ctx["kind"]
    if kind not in {"domain", "url"} and not (kind == "person_or_company" and looks_like_company(ctx.get("name", ""))):
        return {}
    emails, phones, socials, addrs = {}, {}, {}, {}
    for r in ranked:
        page = r.get("page") or {}
        if page.get("status") != "ok":
            continue
        if kind in {"domain", "url"} and r["registrable_domain"] not in ctx["official_domains"]:
            continue
        for e in page.get("emails", []):
            emails.setdefault(e, r["link"])
        for p in page.get("phones", []):
            phones.setdefault(p, r["link"])
        for plat, links in page.get("social_links", {}).items():
            for l in links:
                socials.setdefault(l, plat)
        for j in page.get("jsonld", []):
            if j.get("address") and j.get("type") == "organization":
                addrs.setdefault(j["address"], r["link"])
    return {"emails": [{"value": k, "source": v} for k, v in list(emails.items())[:8]],
            "phones": [{"value": k, "source": v} for k, v in list(phones.items())[:6]],
            "social": [{"url": k, "platform": v} for k, v in list(socials.items())[:10]],
            "addresses": [{"value": k, "source": v} for k, v in list(addrs.items())[:3]]}


# ---------------------------------------------------------------- main entry
def run_scan(raw: str, category: str = "all", aggressive: bool = False, mode: Optional[str] = None,
             default_region: Optional[str] = None, progress: Progress = None,
             api_keys: Optional[Dict[str, str]] = None) -> Dict[str, Any]:
    value = normalize_whitespace(raw)
    mode = mode if mode in MODES else ("extended" if aggressive else "standard")
    cfg = MODES[mode]
    kind = normalize_kind(category, value, default_region)
    result: Dict[str, Any] = {"schema_version": SCHEMA_VERSION, "input": value, "type": kind, "mode": mode,
                              "provider_status": provider_status(api_keys), "errors": [], "diagnostics": {}}
    if kind == "unknown" or not value:
        result["errors"].append("Empty input.")
        return _empty(result)
    try:
        ctx, enrichment = build_context(kind, value, default_region)
    except ValueError as exc:
        result["errors"].append(str(exc))
        return _empty(result)
    kind = ctx["kind"]
    result["type"] = kind
    if ctx.get("fallback_kind"):
        result["errors"].append("Phone number could not be parsed; treated as a plain numeric value.")

    t_start = _time.time()
    extras_pool = ThreadPoolExecutor(max_workers=1)
    extras_future = extras_pool.submit(sources.gather, kind, value, ctx, cfg["deep"], progress)
    run_enrichment(kind, ctx, enrichment, cfg["deep"], default_region, progress, api_keys)

    # ---- search (+ pivot rounds on discovered organisations)
    _say(progress, "Searching public sources…")
    specs = search.build_queries(kind, ctx, cfg["max_queries"])
    queries_log = [{"query": q.text, "purpose": q.purpose} for q in specs]
    done = {q.text for q in specs}
    raw_results = search.run_queries(specs, cfg["per_query"], result["diagnostics"], progress, api_keys)
    ctx["official_domains"] = set(ctx["official_domains"])
    pages: Dict[str, Dict[str, Any]] = {}

    def rank(items, pgs):
        return sorted((score_result(m, ctx, pgs.get(m["link"]) if pgs.get(m["link"], {}).get("status") == "ok" else None)
                       for m in items), key=lambda x: x["score"], reverse=True)

    merged = search.merge_results(raw_results)
    ranked = rank(merged, pages)
    for round_no in range(cfg["pivot_rounds"]):
        ents = build_entities(ranked, ctx)
        pspecs = pivot.pivot_queries(round_no, ents, ctx, ranked, done)
        if not pspecs:
            break
        _say(progress, f"Pivot round {round_no + 1}: following up on {len(pspecs)} lead(s)…")
        queries_log += [{"query": q.text, "purpose": q.purpose} for q in pspecs]
        raw_results += search.run_queries(pspecs, cfg["per_query"], result["diagnostics"], progress, api_keys)
        merged = search.merge_results(raw_results)
        ranked = rank(merged, pages)
    result["queries"] = queries_log
    prov = result["diagnostics"].get("providers", {})
    if prov and not any(p["results"] for p in prov.values() if p["queries"]):
        result["diagnostics"]["degraded"] = True

    # ---- verify pages + probe the entity's own website (deep)
    site_probes: Dict[str, Dict[str, Any]] = {}
    if cfg["fetch_pages"] and ranked:
        pages = verify_pages(ranked, ctx, cfg["fetch_pages"], default_region, progress)
    if cfg["site_probe"] and ranked:
        ents = build_entities(ranked, ctx)
        targets = []
        if kind in {"domain", "url", "email"} and ctx["official_domains"]:
            targets = [(None, next(iter(ctx["official_domains"])))]
        for e in pivot.eligible_orgs(ents, ctx, 2):
            dom = pivot.discover_official_domain(e, ranked)
            if dom and dom not in {t[1] for t in targets}:
                targets.append((e["name"], dom))
        for name, dom in targets[:3]:
            _say(progress, f"Probing official site {dom}…")
            site_probes[dom] = pivot.probe_site(dom, ctx, default_region)
            for url, pg in site_probes[dom]["pages"].items():
                pages[url] = pg
                if not any(m["link"] == url for m in merged):
                    ctxs = pg.get("contexts") or []
                    merged.append({"title": pg.get("title") or url, "link": url, "snippet": (ctxs[0] if ctxs else pg.get("description", ""))[:300],
                                   "providers": ["site_probe"], "queries": ["official site probe"], "purposes": ["site probe"]})
    if pages:
        ranked = rank(merged, pages)
        for r in ranked:
            p = pages.get(r["link"])
            if p:
                r["page"] = {k: v for k, v in p.items() if k != "text_sample"}
    excluded = sum(1 for r in ranked if r["source"] == "people_search")
    result["excluded_people_search"] = excluded
    ranked = [r for r in ranked if r["source"] != "search"]
    result["ranked_results"] = ranked
    result["search_results"] = ranked  # backwards compatible

    # ---- wikipedia (names / brands only, labelled with match quality)
    result["wikipedia"] = {"status": "not_found"}
    if kind in {"person_or_company", "keyword"}:
        result["wikipedia"] = search.wikipedia_lookup(value)
    elif kind in {"domain", "url"} or (kind == "email" and ctx["official_domains"]):
        w = search.wikipedia_lookup(brandify(domain_root_label(ctx["domain"])))
        if w.get("status") == "ok" and w.get("match") == "strong":
            w["note"] = "Name match with the domain's brand label only - verify that it is the same organisation."
            result["wikipedia"] = w

    # ---- profile
    entities = build_entities(ranked, ctx)
    usage = infer_usage(ranked)
    official_for: Dict[str, str] = {}
    for e in entities[:4]:
        dom = pivot.discover_official_domain(e, ranked) if e["type"] != "person" else None
        if kind in {"domain", "url"} and ctx["official_domains"] and e is entities[0]:
            dom = next(iter(ctx["official_domains"]))
        if dom:
            official_for[e["name"]] = dom
            apply_site_evidence(e, site_probes.get(dom), kind)
    entities.sort(key=lambda e: e["confidence"], reverse=True)
    for e in entities[:3]:
        dom = official_for.get(e["name"])
        e["profile"] = build_entity_profile(e, ranked, site_probes.get(dom) if dom else None, usage, dom)
    result["site_probes"] = {d: {k: v for k, v in sp.items() if k != "pages"} | {"pages": list(sp["pages"])} for d, sp in site_probes.items()}
    rep = reputation_signals(ranked, ctx)
    loc = location_hint(kind, ctx, enrichment)
    if loc:
        enrichment["location"] = loc
    claims = build_claims(kind, ctx, entities, ranked, usage, enrichment)
    risk = build_risk(kind, ctx, enrichment, rep, ranked)
    conf = overall_confidence(claims, result["diagnostics"], ranked, enrichment, kind)

    result.update(enrichment=enrichment, entities=entities, usage=usage, reputation_signals=rep, claims=claims,
                  risk=risk, confidence=conf, public_contacts=public_contacts(ranked, ctx))
    _attach_legacy(result, ctx, enrichment, entities, usage, ranked)
    try:
        result["extras"] = extras_future.result(timeout=90)
    except Exception as exc:
        result["extras"] = {}
        result["errors"].append(f"Extra sources failed: {type(exc).__name__}")
    extras_pool.shutdown(wait=False)
    result["narrative"] = build_narrative(result)
    for line in sources.extras_findings(result["extras"]):
        result["narrative"]["key_findings"].append(line)
    result["summary"]["narrative"] = result["narrative"]["text"]
    result["seconds"] = round(_time.time() - t_start, 1)
    result["searches_run"] = len(result.get("queries", []))
    result["trail"] = result["diagnostics"].get("trail", [])
    return result


def _progress_wrap(progress: Progress):
    return progress


def _empty(result: Dict[str, Any]) -> Dict[str, Any]:
    result.update(ranked_results=[], search_results=[], entities=[], usage=[], claims=[], enrichment={},
                  reputation_signals=[], public_contacts={}, likely_owner=[], likely_usage=["Unknown"],
                  confidence={"score": 0, "label": "very low", "factors": []},
                  risk={"risk_level": "low", "score": 0, "signals": [], "notes": []}, wikipedia={"status": "not_found"},
                  extras={}, trail=[], seconds=0, searches_run=0,
                  summary={"confidence": "very low", "narrative": "; ".join(result["errors"])},
                  narrative={"headline": "No scan performed", "paragraphs": result["errors"], "key_findings": [],
                             "limitations": [], "next_steps": [], "text": "; ".join(result["errors"])})
    return result


def _attach_legacy(result, ctx, enrichment, entities, usage, ranked) -> None:
    """Keep the keys the old UI/CLI consumers relied on."""
    kind = result["type"]
    ph = enrichment.get("phone") or {}
    if ph.get("status") == "ok":
        result.update(parsed=ph, country=ph["country"], region=ph["region"], numverify=enrichment.get("numverify"))
    if kind in {"domain", "url"} or ctx.get("domain"):
        result["domain"] = ctx.get("domain")
        result["domain_info"] = enrichment.get("registration")
        result["dns"] = enrichment.get("dns")
        result["related_domains"] = enrichment.get("certificates")
    if kind == "url":
        result["url"] = ctx["url"]
        result["url_details"] = enrichment.get("url")
        result["website_fingerprint"] = enrichment.get("website")
    if kind == "email":
        result["email"] = ctx["email"]
    if kind == "number":
        result["number_details"] = {"digits": ctx["digits"], "length": len(ctx["digits"])}
    result["likely_owner"] = [e["name"] for e in entities[:8]]
    result["likely_usage"] = [u["label"] for u in usage] or ["Unknown"]
    domains: Dict[str, int] = {}
    sources: Dict[str, int] = {}
    for r in ranked:
        domains[r["domain"]] = domains.get(r["domain"], 0) + 1
        sources[r["source"]] = sources.get(r["source"], 0) + 1
    top = entities[0] if entities else None
    result["summary"] = {
        "entity_category": classify_query_label(result["input"], kind), "top_entities": result["likely_owner"],
        "top_domains": [d for d, _ in sorted(domains.items(), key=lambda x: -x[1])[:5]],
        "source_distribution": sources, "query_type": kind,
        "likely_name": top["name"] if top else result["input"],
        "likely_company": next((e["name"] for e in entities if e["type"] == "organization"), top["name"] if top else result["input"]),
        "likely_use_case": result["likely_usage"][0], "confidence": result["confidence"]["label"],
        "confidence_score": result["confidence"]["score"],
        "average_score": round(sum(r["score"] for r in ranked) / max(1, len(ranked)), 1),
    }
    result["public_profile"] = result["summary"]
