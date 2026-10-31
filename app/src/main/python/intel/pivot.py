"""Follow-up (pivot) searches and official-site probing for discovered organisations.

Pivoting only ever targets *organisations* and their own published pages - never a
private individual - so the extra recall does not turn into person tracking.
"""
from __future__ import annotations

import difflib
from typing import Any, Dict, List, Optional, Set
from urllib.parse import urlparse

from .detect import registrable_domain
from .extract import normalize_entity_key
from .net import robots_allowed, safe_get
from .extract import parse_page
from .scoring import exact_match
from .search import QuerySpec

NON_OFFICIAL = {"social", "linkedin", "directory", "people_search", "news", "reference", "reputation", "search", "code"}


def eligible_orgs(entities: List[Dict[str, Any]], ctx: Dict[str, Any], limit: int) -> List[Dict[str, Any]]:
    out = []
    qkey = normalize_entity_key(ctx.get("name", "")) if ctx.get("name") else ""
    for e in entities:
        if e["type"] == "person" or e["confidence"] < 30 or len(e["name"]) < 3:
            continue
        if qkey and normalize_entity_key(e["name"]) == qkey and ctx["kind"] in {"person_or_company", "keyword"}:
            pass  # the queried organisation itself is a valid pivot target
        out.append(e)
        if len(out) >= limit:
            break
    return out


def identifier_variants(ctx: Dict[str, Any]) -> List[str]:
    k = ctx["kind"]
    if k == "phone":
        v = ctx.get("phone_variants", [])
        return [x for x in v if " " in x][:1] or v[:1]
    if k == "email":
        return [ctx["email"]]
    if k == "number":
        return [ctx["digits"]]
    return []


def discover_official_domain(entity: Dict[str, Any], ranked: List[Dict[str, Any]]) -> Optional[str]:
    key = normalize_entity_key(entity["name"]).replace(" ", "")
    if len(key) < 3:
        return None
    best, best_score = None, 0.0
    urls = {e["url"] for e in entity["evidence"]}
    for r in ranked:
        if r["source"] in NON_OFFICIAL or not r.get("registrable_domain"):
            continue
        root = r["registrable_domain"].split(".")[0].replace("-", "")
        ratio = 1.0 if key == root else (0.92 if (root in key or key in root) and min(len(root), len(key)) >= 4
                                        else difflib.SequenceMatcher(None, key, root).ratio())
        if r["link"] in urls or r["domain"] in entity["source_domains"]:
            ratio += 0.05
        if ratio > best_score:
            best, best_score = r["registrable_domain"], ratio
    return best if best_score >= 0.75 else None


def pivot_queries(round_no: int, entities: List[Dict[str, Any]], ctx: Dict[str, Any], ranked: List[Dict[str, Any]],
                  done: Set[str]) -> List[QuerySpec]:
    specs: List[QuerySpec] = []
    ident = identifier_variants(ctx)
    for e in eligible_orgs(entities, ctx, limit=2 if round_no == 0 else 3):
        n = e["name"]
        cand = [(f'"{n}" official website', "pivot: official site"), (f'"{n}" contact us', "pivot: contact page"),
                (f'"{n}" site:linkedin.com/company', "pivot: company profile")]
        for i in ident[:1]:
            cand.insert(0, (f'"{n}" "{i}"', "pivot: entity + identifier"))
        if round_no >= 1:
            dom = discover_official_domain(e, ranked)
            if dom:
                cand = [(f"site:{dom} contact OR about", "pivot: official site pages")] + \
                       ([(f'site:{dom} "{ident[0]}"', "pivot: identifier on official site")] if ident else []) + cand[:2]
        for text, purpose in cand:
            if text not in done:
                done.add(text)
                specs.append(QuerySpec(text, purpose))
    return specs


def probe_site(domain: str, ctx: Dict[str, Any], region: Optional[str], limit: int = 5) -> Dict[str, Any]:
    """Fetch the organisation's own homepage/contact/about pages (robots.txt respected)."""
    out: Dict[str, Any] = {"domain": domain, "pages": {}, "emails": [], "phones": [], "social": {}, "jsonld": [],
                           "technologies": [], "description": "", "title": "", "contexts": [], "identifier_on_site": False}
    needles = identifier_variants(ctx) + ctx.get("phone_variants", [])[:5]
    for path in ("/", "/contact", "/contact-us", "/about", "/about-us")[:limit]:
        url = f"https://{domain}{path}"
        if not robots_allowed(url):
            continue
        resp = safe_get(url, max_bytes=900_000, timeout=8)
        if not resp or resp.status >= 400 or ("html" not in resp.content_type.lower() and resp.content_type):
            continue
        page = parse_page(resp.text, resp.final_url, resp.headers, needles=needles, region=region)
        if ctx["kind"] in {"phone", "number"}:
            page["exact_on_page"] = exact_match(page["text_sample"] + " " + " ".join(page["phones"]), ctx) or page["exact_on_page"]
        elif ctx["kind"] == "email":
            page["exact_on_page"] = ctx["email"] in [e.lower() for e in page["emails"]] or page["exact_on_page"]
        page["status"] = "ok"
        out["pages"][resp.final_url] = page
        out["title"] = out["title"] or page["title"]
        out["description"] = out["description"] or page["description"]
        out["emails"] += [e for e in page["emails"] if e not in out["emails"]]
        out["phones"] += [p for p in page["phones"] if p not in out["phones"]]
        for plat, links in page["social_links"].items():
            out["social"].setdefault(plat, [])
            out["social"][plat] += [l for l in links if l not in out["social"][plat]]
        out["jsonld"] += page["jsonld"]
        out["technologies"] += [t for t in page["technologies"] if t not in out["technologies"]]
        if page["exact_on_page"]:
            out["identifier_on_site"] = True
            out["contexts"] += page["contexts"][:2]
    out["emails"], out["phones"] = out["emails"][:10], out["phones"][:8]
    return out
