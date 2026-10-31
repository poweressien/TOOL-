"""Entity profiles, usage inference and evidence-backed claims."""
from __future__ import annotations

import math
import re
from typing import Any, Dict, List, Optional

from .detect import brandify, classify_query_label, domain_root_label, registrable_domain
from .extract import (brand_from_title, capitalized_phrases, classify_entity_name, nearby_names,
                      normalize_entity_key, parse_platform_title, same_entity)
from .scoring import band

USAGE_BUCKETS = {
    "Business / company": [r"\bcompany\b", r"\bbusiness\b", r"\boffice\b", r"\benterprise\b", r"\bfirm\b", r"\bltd\b", r"\binc\b"],
    "Sales": [r"\bsales\b", r"\bquote\b", r"\bpricing\b", r"\bdemo\b", r"\bbuy\b", r"\border online\b"],
    "Customer support": [r"\bsupport\b", r"\bhelp ?desk\b", r"\bcustomer (?:care|service|support)\b", r"\bhotline\b", r"\bcall cent(?:er|re)\b"],
    "Bookings": [r"\bbooking\b", r"\bappointment\b", r"\breservation\b"],
    "Recruiting": [r"\bhiring\b", r"\bcareers?\b", r"\brecruit\w*\b", r"\bvacanc\w+\b"],
    "Marketing / advertising": [r"\bmarketing\b", r"\badvertis\w+\b", r"\bpromotion\b", r"\bagency\b"],
    "Real estate": [r"\breal estate\b", r"\bproperty\b", r"\bproperties\b", r"\bestate agent\b"],
    "Delivery / logistics": [r"\bdelivery\b", r"\bcourier\b", r"\bshipment\b", r"\blogistics\b", r"\bdispatch\b"],
    "Healthcare": [r"\bclinic\b", r"\bhospital\b", r"\bpharmacy\b", r"\bdoctor\b", r"\bmedical\b"],
    "Finance / payments": [r"\bbank\b", r"\bloan\b", r"\bpayments?\b", r"\bfintech\b", r"\binsurance\b", r"\bbilling\b"],
    "Messaging contact": [r"\bwhats ?app\b", r"\btelegram\b", r"\bsms\b"],
    "Education": [r"\buniversity\b", r"\bschool\b", r"\bcollege\b", r"\bcourses?\b", r"\btraining\b"],
    "Government / public service": [r"\bgovernment\b", r"\bministry\b", r"\bagency\b", r"\bcommission\b"],
    "Technology / software": [r"\bsoftware\b", r"\bapp\b", r"\bplatform\b", r"\bdeveloper\b", r"\bsaas\b", r"\bapi\b"],
    "Media / content": [r"\bblog\b", r"\bnews\b", r"\bpodcast\b", r"\bvideo\b", r"\bchannel\b"],
    "Personal / social profile": [r"\bprofile\b", r"\bfollowers\b", r"\bpersonal\b"],
}
_USAGE_RE = {k: [re.compile(p, re.I) for p in v] for k, v in USAGE_BUCKETS.items()}
REPUTATION_WORDS = re.compile(r"\b(scam|scammer|fraud\w*|phishing|spam\w*|fake|complaints?|blacklist\w*|"
                              r"malware|warning|beware|rip-?off|stolen|harass\w*)\b", re.I)


# ---------------------------------------------------------------- mentions
def mentions_from_result(res: Dict[str, Any], ctx: Dict[str, Any]) -> List[Dict[str, Any]]:
    """Candidate entity names from one scored result, each with a weight and a method."""
    if res.get("source") == "people_search":
        return []
    out: List[Dict[str, Any]] = []
    title, snippet, host = res.get("title", ""), res.get("snippet", ""), res.get("domain", "")
    plat = parse_platform_title(title, host, res.get("link", ""))
    if plat and plat.get("name"):
        out.append({"name": plat["name"], "type": plat.get("type", "unknown"), "weight": 1.0,
                    "method": plat["source"], "role": plat.get("role"), "org": plat.get("org")})
        if plat.get("org"):
            out.append({"name": plat["org"], "type": "organization", "weight": 0.6, "method": "profile_employer"})
    else:
        brand = brand_from_title(title, host)
        if brand and res.get("source") not in {"reputation", "search"}:
            name, w = brand
            if res.get("source") == "social" or res.get("source") == "directory":
                w *= 0.8
            out.append({"name": name, "type": classify_entity_name(name), "weight": w, "method": "title_brand"})
    # proximity: names written next to the identifier
    if ctx.get("proximity_needles") and res.get("exact_match"):
        text = f"{title}. {snippet}"
        for needle in ctx["proximity_needles"]:
            if needle.lower() in text.lower():
                for n in nearby_names(text, needle)[:3]:
                    out.append({"name": n, "type": classify_entity_name(n), "weight": 0.7, "method": "near_identifier"})
                break
    # page metadata (deep scan)
    page = res.get("page") or {}
    if page.get("site_name"):
        out.append({"name": page["site_name"], "type": "organization", "weight": 1.0, "method": "og_site_name"})
    for j in page.get("jsonld", [])[:3]:
        out.append({"name": j["name"], "type": j.get("type", "unknown"), "weight": 1.0, "method": "structured_data",
                    "jsonld": j})
    # last resort: the registrable-domain brand when the domain *is* the subject
    if res.get("registrable_domain") in ctx.get("official_domains", set()):
        out.append({"name": brandify(domain_root_label(res["registrable_domain"])), "type": "unknown",
                    "weight": 0.35, "method": "domain_brand"})
    for n in capitalized_phrases(snippet, limit=4):
        out.append({"name": n, "type": classify_entity_name(n), "weight": 0.15, "method": "snippet_phrase"})
    return [m for m in out if m["name"] and 2 < len(m["name"]) <= 80]


def build_entities(results: List[Dict[str, Any]], ctx: Dict[str, Any], limit: int = 8) -> List[Dict[str, Any]]:
    clusters: List[Dict[str, Any]] = []
    per_domain: Dict[tuple, int] = {}
    for idx, res in enumerate(results):
        strength = (res.get("score", 0) / 100) ** 0.8
        for m in mentions_from_result(res, ctx):
            target = next((c for c in clusters if same_entity(c["name"], m["name"])), None)
            if target is None:
                target = {"name": m["name"], "type_votes": {}, "aliases": {}, "score": 0.0, "domains": set(),
                          "direct": False, "structured": False, "evidence": [], "methods": set(), "roles": set(),
                          "orgs": set(), "jsonld": []}
                clusters.append(target)
            # one site must not dominate: its 3rd+ mention of an entity counts for much less
            dkey = (id(target), res.get("registrable_domain", ""))
            per_domain[dkey] = per_domain.get(dkey, 0) + 1
            damp = 1.0 if per_domain[dkey] <= 2 else 0.35
            target["aliases"][m["name"]] = target["aliases"].get(m["name"], 0) + m["weight"]
            target["score"] += m["weight"] * (0.25 + strength) * damp
            if res.get("registrable_domain"):
                target["domains"].add(res["registrable_domain"])
            target["direct"] |= res.get("evidence_level") == "direct"
            target["structured"] |= m["method"] in {"linkedin_profile", "linkedin_company", "og_site_name",
                                                    "structured_data", "x_profile", "github_profile"}
            target["methods"].add(m["method"])
            tv = target["type_votes"]
            tv[m["type"]] = tv.get(m["type"], 0) + m["weight"]
            if m.get("role"):
                target["roles"].add(m["role"])
            if m.get("org"):
                target["orgs"].add(m["org"])
            if m.get("jsonld"):
                target["jsonld"].append(m["jsonld"])
            if len(target["evidence"]) < 4 and not any(e["url"] == res.get("link") for e in target["evidence"]):
                target["evidence"].append({"url": res.get("link", ""), "title": res.get("title", ""),
                                           "snippet": res.get("snippet", "")[:220], "method": m["method"],
                                           "result_score": res.get("score", 0), "result_index": idx})
    total = sum(c["score"] for c in clusters) or 1.0
    # the entity named in the query is the anchor for name searches
    out = []
    for c in clusters:
        share = c["score"] / total
        n_domains = len(c["domains"])
        factors = [("independent sources", min(35, 12 * max(0, n_domains - 1)) if n_domains else 0)]
        factors.append(("baseline", 15))
        factors.append(("direct evidence present", 20 if c["direct"] else 0))
        factors.append(("structured/profile source", 15 if c["structured"] else 0))
        factors.append(("dominance among candidates", round(25 * min(1.0, share * 2))))
        conf = max(0, min(100, sum(v for _, v in factors)))
        types = c["type_votes"]
        etype = max((t for t in types if t != "unknown"), key=lambda t: types[t], default="unknown")
        best = max(c["aliases"].items(), key=lambda kv: (kv[1], len(kv[0])))[0]
        out.append({"name": best, "type": etype, "aliases": sorted(a for a in c["aliases"] if a != best)[:5],
                    "score": round(c["score"], 3), "share": round(share, 3), "confidence": conf,
                    "confidence_label": band(conf), "independent_sources": n_domains,
                    "source_domains": sorted(c["domains"])[:8], "direct_evidence": c["direct"],
                    "methods": sorted(c["methods"]), "roles": sorted(c["roles"])[:3],
                    "employers": sorted(c["orgs"])[:3], "evidence": c["evidence"],
                    "structured": c["jsonld"][:1], "confidence_factors": [
                        {"factor": f, "points": p} for f, p in factors if p]})
    out.sort(key=lambda e: (e["confidence"], e["score"]), reverse=True)
    out = [e for e in out if e["score"] >= 0.12 or e["confidence"] >= 45][:limit]
    if len(out) > 1 and out[1]["share"] >= 0.7 * out[0]["share"] and out[0]["confidence"] > 20:
        out[0]["contested"] = True
        out[0]["confidence"] = max(0, out[0]["confidence"] - 12)
        out[0]["confidence_label"] = band(out[0]["confidence"])
        out[0]["confidence_factors"].append({"factor": "competing candidate", "points": -12})
    return out


# ---------------------------------------------------------------- usage
def infer_usage(results: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    scores: Dict[str, Dict[str, Any]] = {}
    for res in results:
        if res.get("source") in {"people_search", "search"}:
            continue
        text = f"{res.get('title', '')} {res.get('snippet', '')}"
        weight = 0.3 + res.get("score", 0) / 100
        for label, pats in _USAGE_RE.items():
            hits = sum(1 for p in pats if p.search(text))
            if hits:
                s = scores.setdefault(label, {"label": label, "score": 0.0, "results": 0, "domains": set()})
                s["score"] += min(hits, 2) * weight
                s["results"] += 1
                s["domains"].add(res.get("registrable_domain", ""))
    out = []
    for s in scores.values():
        conf = min(90, 15 + 18 * min(len(s["domains"]), 3) + min(21, round(s["score"] * 4)))
        out.append({"label": s["label"], "score": round(s["score"], 2), "supporting_results": s["results"],
                    "independent_sources": len(s["domains"]), "confidence": conf, "confidence_label": band(conf)})
    out.sort(key=lambda x: (x["score"], x["supporting_results"]), reverse=True)
    return out[:6]


def reputation_signals(results: List[Dict[str, Any]], ctx: Dict[str, Any]) -> List[Dict[str, Any]]:
    """Results that pair the identifier with warning vocabulary (only direct matches count)."""
    sig = []
    for res in results:
        if not res.get("exact_match") or res.get("source") == "people_search":
            continue
        text = f"{res.get('title', '')} {res.get('snippet', '')}"
        words = sorted({w.lower() for w in REPUTATION_WORDS.findall(text)})
        if words:
            sig.append({"url": res["link"], "domain": res["domain"], "terms": words[:5], "score": res["score"],
                        "snippet": res.get("snippet", "")[:200]})
    return sig[:6]


# ---------------------------------------------------------------- claims
def build_claims(kind: str, ctx: Dict[str, Any], entities: List[Dict[str, Any]], results: List[Dict[str, Any]],
                 usage: List[Dict[str, Any]], enrichment: Dict[str, Any]) -> List[Dict[str, Any]]:
    claims: List[Dict[str, Any]] = []
    direct = [r for r in results if r.get("evidence_level") == "direct" and r.get("source") != "people_search"]
    direct_domains = {r["registrable_domain"] for r in direct}
    top = entities[0] if entities else None

    def claim(text, conf, support, caveat="", ctype="general", negative=False):
        conf = int(max(0, min(100, conf)))
        claims.append({"type": ctype, "negative": negative, "claim": text, "confidence": conf, "confidence_label": band(conf),
                       "independent_sources": len({registrable_domain(s.split("/")[2]) for s in support if "://" in s}),
                       "support": support[:5], "caveat": caveat})

    if kind in {"phone", "email", "number"}:
        label = {"phone": "number", "email": "address", "number": "value"}[kind]
        if top and top["direct_evidence"] and top["independent_sources"] >= 1:
            n = len(direct_domains)
            conf = 20 + 20 * min(n, 3) + (10 if top["structured"] else 0)
            if n < 2:
                conf = min(conf, 45)
            claim(f"This {label} is publicly associated with “{top['name']}” ({top['type']}).",
                  conf, [e["url"] for e in top["evidence"]],
                  "Single public source - treat as unverified." if n < 2 else
                  "Public association only; it does not prove current ownership.", "attribution")
        else:
            claim(f"No public source ties this {label} to a named person or organisation.", 70 if not direct else 35,
                  [], "Absence from indexed pages is not proof of anything - most private numbers/mailboxes "
                      "are simply not published.", "attribution", negative=True)
    elif top:
        label = "domain" if kind in {"domain", "url"} else "name"
        conf = top["confidence"]
        if kind in {"domain", "url"}:
            reg = enrichment.get("registration", {})
            if reg.get("registrant") and same_entity(reg["registrant"], top["name"]):
                conf = min(100, conf + 15)
        claim(f"The {label} is most consistently associated with “{top['name']}”.", conf,
              [e["url"] for e in top["evidence"]],
              "Several candidates scored similarly - the name may be ambiguous." if top.get("contested") else "",
              "primary_entity")
    if usage:
        u = usage[0]
        claim(f"Public context suggests use as: {u['label']}.", u["confidence"],
              [r["link"] for r in results if r.get("score", 0) >= 30][:3],
              "Inferred from keywords in titles/snippets, not from the owner's own statement.", "usage")
    loc = enrichment.get("location")
    if loc:
        claim(f"Geographic indicator: {loc['value']}.", loc["confidence"], [], loc["basis"], "location")
    return claims


# ---------------------------------------------------------------- official-site evidence & rich profiles
CORROBORATION_CLASSES = {"official site": {"official"}, "LinkedIn": {"linkedin"}, "social media": {"social"},
                         "news": {"news"}, "reference / registry": {"reference", "institution"},
                         "business directory": {"directory"}, "code platforms": {"code"}}


def apply_site_evidence(entity: Dict[str, Any], site: Optional[Dict[str, Any]], kind: str) -> None:
    """Official-site facts raise (or fail to raise) confidence in an entity."""
    if not site or not site.get("pages"):
        return
    if kind in {"phone", "email", "number"}:
        if site.get("identifier_on_site"):
            entity["confidence"] = min(100, entity["confidence"] + 22)
            entity["confidence_factors"].append({"factor": "identifier published on the entity's own website", "points": 22})
            entity["official_site_confirms"] = True
        else:
            entity["confidence_factors"].append({"factor": "identifier not found on the entity's own pages", "points": 0})
            entity["official_site_confirms"] = False
    else:
        entity["confidence"] = min(100, entity["confidence"] + 8)
        entity["confidence_factors"].append({"factor": "official website located and readable", "points": 8})
    entity["confidence_label"] = band(entity["confidence"])


def build_entity_profile(entity: Dict[str, Any], ranked: List[Dict[str, Any]], site: Optional[Dict[str, Any]],
                         usage: List[Dict[str, Any]], official_domain: Optional[str]) -> Dict[str, Any]:
    from .scoring import classify_source

    matrix = {label: False for label in CORROBORATION_CLASSES}
    for d in entity["source_domains"]:
        cls = classify_source(d)
        for label, classes in CORROBORATION_CLASSES.items():
            if cls in classes:
                matrix[label] = True
    if official_domain and site and site.get("pages"):
        matrix["official site"] = True
    jl = (site or {}).get("jsonld") or entity.get("structured") or []
    org = next((j for j in jl if j.get("type") == "organization"), jl[0] if jl else {})
    social: Dict[str, str] = {}
    for plat, links in ((site or {}).get("social") or {}).items():
        social.setdefault(plat, links[0])
    for u in org.get("same_as", []):
        for plat in ("linkedin", "facebook", "instagram", "twitter", "x.com", "youtube", "github", "tiktok"):
            if plat in u and plat.replace(".com", "") not in social:
                social[plat.replace(".com", "")] = u
    for ev in entity["evidence"]:
        host = ev["url"].split("/")[2] if "://" in ev["url"] else ""
        for plat in ("linkedin", "facebook", "instagram", "github"):
            if plat in host and plat not in social:
                social[plat] = ev["url"]
    own_usage = [u["label"] for u in usage[:3]]
    profile = {
        "official_domain": official_domain or "", "website": f"https://{official_domain}" if official_domain else "",
        "description": (org.get("description") or (site or {}).get("description") or "")[:400],
        "legal_name": org.get("legalName", ""), "founded": org.get("foundingDate", ""),
        "address": org.get("address", ""), "country": org.get("country", ""),
        "emails": ((site or {}).get("emails") or [])[:5], "phones": ((site or {}).get("phones") or [])[:4],
        "social": social, "technologies": ((site or {}).get("technologies") or [])[:8],
        "industries": own_usage, "corroboration": matrix,
        "independent_source_classes": sum(matrix.values()),
    }
    fields = ["official_domain", "description", "address", "emails", "phones", "social", "industries", "founded"]
    profile["completeness"] = round(100 * sum(1 for f in fields if profile.get(f)) / len(fields))
    return profile
