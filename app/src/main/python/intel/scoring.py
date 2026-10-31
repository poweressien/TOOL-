"""Result scoring with an explicit, human-readable evidence breakdown."""
from __future__ import annotations

import re
from typing import Any, Dict, List, Optional
from urllib.parse import urlparse

from .detect import registrable_domain
from .extract import STOP_WORDS, is_generic_segment, split_title

SOURCE_CLASSES = {
    "linkedin": {"linkedin.com"},
    "social": {"facebook.com", "instagram.com", "x.com", "twitter.com", "tiktok.com", "youtube.com", "threads.net",
               "pinterest.com", "t.me", "reddit.com", "snapchat.com", "wa.me"},
    "code": {"github.com", "gitlab.com", "bitbucket.org", "stackoverflow.com", "npmjs.com", "pypi.org"},
    "reference": {"wikipedia.org", "wikidata.org", "britannica.com", "crunchbase.com", "opencorporates.com",
                  "bloomberg.com", "dnb.com", "sec.gov", "companieshouse.gov.uk", "archive.org"},
    "news": {"reuters.com", "bbc.com", "bbc.co.uk", "apnews.com", "theguardian.com", "nytimes.com", "ft.com",
             "cnn.com", "aljazeera.com", "techcrunch.com", "forbes.com", "punchng.com", "premiumtimesng.com",
             "vanguardngr.com", "guardian.ng", "thecable.ng", "businessday.ng"},
    "reputation": {"trustpilot.com", "scamadviser.com", "shouldianswer.com", "tellows.com", "800notes.com",
                   "who-called.co.uk", "whocalledme.com", "reportscam.com", "scamwatch.gov.au", "bbb.org",
                   "ftc.gov", "sitejabber.com", "urlvoid.com", "virustotal.com", "abuseipdb.com"},
    "directory": {"yellowpages.com", "yelp.com", "manta.com", "mapquest.com", "foursquare.com", "bizapedia.com",
                  "businesslist.com.ng", "ngyellowpages.com", "cylex.us.com", "hotfrog.com", "clutch.co",
                  "zoominfo.com", "rocketreach.co", "apollo.io", "signalhire.com"},
    # Data-broker / people-search sites: never used for identity inference.
    "people_search": {"whitepages.com", "spokeo.com", "truepeoplesearch.com", "fastpeoplesearch.com",
                      "radaris.com", "beenverified.com", "mylife.com", "intelius.com", "peoplefinders.com",
                      "sync.me", "truecaller.com", "getcontact.com", "numlookup.com", "that's them.com",
                      "thatsthem.com", "peekyou.com", "pipl.com", "usphonebook.com", "instantcheckmate.com",
                      "anywho.com", "411.com", "zabasearch.com", "clustrmaps.com", "nuwber.com", "idcrawl.com"},
    "search": {"google.com", "bing.com", "duckduckgo.com", "yahoo.com", "baidu.com"},
}
_HOST_TO_CLASS = {h: c for c, hosts in SOURCE_CLASSES.items() for h in hosts}
AUTHORITY_POINTS = {"official": 18, "reference": 12, "institution": 10, "linkedin": 9, "news": 8, "code": 6,
                    "social": 6, "reputation": 4, "directory": 2, "general": 0, "search": -4,
                    "people_search": -10}
GENERIC_PAGE_WORDS = ("login", "sign in", "sign up", "register", "download pdf", "page not found", "404")
IDENTIFIER_KINDS = {"phone", "email", "number", "ip"}


def classify_source(host: str) -> str:
    reg = registrable_domain(host)
    if reg in _HOST_TO_CLASS:
        return _HOST_TO_CLASS[reg]
    host = (host or "").lower()
    if host in _HOST_TO_CLASS:
        return _HOST_TO_CLASS[host]
    parts = host.split(".")
    tld = parts[-1] if parts else ""
    if tld in {"gov", "edu", "mil", "int"} or ".gov." in host or ".edu." in host or ".ac." in host \
            or host.endswith((".gov.ng", ".edu.ng", ".gov.uk", ".ac.uk", ".gov.au", ".edu.au")):
        return "institution"
    return "general"


def host_of(link: str) -> str:
    try:
        return urlparse(link).netloc.lower().split("@")[-1].split(":")[0].removeprefix("www.")
    except Exception:
        return ""


def query_tokens(text: str) -> List[str]:
    value = re.sub(r"[^a-z0-9\s]", " ", (text or "").lower())
    return [t for t in value.split() if len(t) > 1 and t not in STOP_WORDS]


def fold(text: str) -> str:
    """Lower-case and strip accents/punctuation noise so 'José' matches 'Jose'."""
    import unicodedata

    t = unicodedata.normalize("NFKD", text or "")
    return "".join(c for c in t if not unicodedata.combining(c)).lower()


def exact_match(text: str, ctx: Dict[str, Any]) -> bool:
    """Does the *identifier itself* appear in text (format-insensitive)?"""
    if not text:
        return False
    low = fold(text)
    kind = ctx["kind"]
    if kind == "phone":
        digits = re.sub(r"\D", "", text)
        nsn = ctx.get("nsn", "")
        return bool(nsn) and len(nsn) >= 7 and nsn in digits
    if kind == "number":
        return ctx["digits"] in re.sub(r"\D", "", text)
    if kind == "email":
        from .extract import deobfuscate

        return ctx["email"] in deobfuscate(low)
    if kind == "ip":
        return re.search(rf"(?<![\d.]){re.escape(ctx['ip'])}(?![\d.])", low) is not None
    if kind in {"domain", "url"}:
        return ctx["domain"] in low
    if kind == "username":
        return re.search(rf"(?<![A-Za-z0-9_]){re.escape(fold(ctx['username']))}(?![A-Za-z0-9_])", low) is not None
    name = fold(ctx["name"])
    if name in low:
        return True
    toks = name.split()  # reordered names ("Doe John") still count as exact
    from .detect import looks_like_company

    return len(toks) in (2, 3) and not looks_like_company(name) and all(re.search(rf"\b{re.escape(t)}\b", low) for t in toks) and len(low) < 400


def band(score: float) -> str:
    if score >= 75:
        return "high"
    if score >= 50:
        return "medium"
    if score >= 25:
        return "low"
    return "very low"


def score_result(item: Dict[str, Any], ctx: Dict[str, Any], page: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    title, snippet, link = item.get("title", ""), item.get("snippet", ""), item.get("link", "")
    host = host_of(link)
    reg = registrable_domain(host)
    source = classify_source(host)
    kind = ctx["kind"]
    breakdown: List[Dict[str, Any]] = []

    def add(factor: str, points: int, note: str) -> None:
        if points:
            breakdown.append({"factor": factor, "points": points, "note": note})

    in_title = exact_match(title, ctx)
    in_snippet = exact_match(snippet, ctx)
    in_url = exact_match(link, ctx) if kind in {"domain", "url", "username", "ip"} else False
    exact_any = in_title or in_snippet or in_url
    page_confirmed = bool(page and page.get("exact_on_page"))

    if in_title:
        add("exact match in title", 34, "The searched value appears verbatim in the page title")
    elif in_snippet:
        add("exact match in snippet", 26, "The searched value appears verbatim in the result text")
    elif in_url:
        add("exact match in URL", 22, "The searched value appears in the page address")
    elif page_confirmed:
        add("exact match on page", 24, "Confirmed on the fetched page, not just in the search snippet")

    tokens = ctx.get("tokens", [])
    if tokens and kind not in IDENTIFIER_KINDS:
        hay = f"{title} {snippet} {host}".lower()
        cover = sum(1 for t in tokens if t in hay) / len(tokens)
        add("query term coverage", round(cover * 16), f"{round(cover * 100)}% of the query terms appear")

    official = ctx.get("official_domains", set())
    if reg and reg in official:
        add("official/own domain", AUTHORITY_POINTS["official"], f"{reg} is the domain under investigation")
        source = "official"
    else:
        add(f"source type: {source}", AUTHORITY_POINTS.get(source, 0),
            {"linkedin": "Professional network profile", "social": "Social platform", "reference": "Reference / registry source",
             "institution": "Government or academic domain", "news": "News outlet", "code": "Code-hosting platform",
             "reputation": "Reputation / scam-report site", "directory": "Business directory (self-reported data)",
             "people_search": "Data-broker; excluded from identity inference", "search": "Search engine page",
             "general": "General website"}.get(source, ""))

    low_title = title.lower()
    if any(w in low_title for w in ("contact", "about", "team", "official", "customer care", "support")):
        add("contact/about page", 5, "Page type usually carries organisation details")
    if not title.strip() or all(is_generic_segment(s) for s in split_title(title)):
        add("generic title", -8, "Title carries no entity information")
    if any(w in low_title for w in GENERIC_PAGE_WORDS):
        add("login/utility page", -10, "Utility page rather than informational content")
    if len(item.get("queries", [])) > 1:
        n = len(item["queries"])
        add("corroborated across queries", min(8, 3 * (n - 1)), f"Returned for {n} different queries")
    if len(item.get("providers", [])) > 1:
        add("corroborated across engines", 4, "Returned by more than one search engine")
    if not snippet.strip():
        add("no snippet", -3, "No supporting text returned")
    if page is not None and not page_confirmed and kind in IDENTIFIER_KINDS:
        add("not present on page", -10, "Page was fetched but the identifier is not on it (search engine fuzzy match)")

    score = max(0, min(100, sum(b["points"] for b in breakdown)))
    capped = False
    if kind in IDENTIFIER_KINDS and not exact_any and not page_confirmed and score > 22:
        score, capped = 22, True
        breakdown.append({"factor": "score cap", "points": 0,
                          "note": "Identifier not found verbatim; search engines often return fuzzy matches"})

    if exact_any or page_confirmed:
        level = "direct"
    elif kind not in IDENTIFIER_KINDS and tokens and sum(1 for t in tokens if t in f"{title} {snippet}".lower()) / len(tokens) >= 0.6:
        level = "contextual"
    else:
        level = "weak"

    positives = sorted((b for b in breakdown if b["points"] > 0), key=lambda b: -b["points"])
    reasons = [b["factor"] for b in positives[:4]] or ["public reference"]
    if score < 25:
        reasons.append("weak match")
    return {**item, "domain": host, "registrable_domain": reg, "source": source, "score": score,
            "confidence": band(score), "evidence_level": level, "exact_match": exact_any or page_confirmed,
            "score_breakdown": breakdown, "reasons": reasons[:5], "score_capped": capped,
            "explanation": explain(level, score, positives, source, capped)}


def explain(level: str, score: int, positives: List[Dict[str, Any]], source: str, capped: bool) -> str:
    lead = {"direct": "Direct evidence: the searched value itself appears in this source.",
            "contextual": "Contextual evidence: related terms appear, but not the exact identifier.",
            "weak": "Weak evidence: only a loose relationship to the search."}[level]
    parts = [lead]
    if positives:
        parts.append("Main factors: " + "; ".join(f"{b['factor']} (+{b['points']})" for b in positives[:3]) + ".")
    if capped:
        parts.append("Score capped because the identifier was not found verbatim.")
    if source == "people_search":
        parts.append("Data-broker site - not used to infer identity.")
    return " ".join(parts)


def filter_ranked_results(items: List[Dict[str, Any]], min_score: int = 0, domain_filter: str = "all",
                          source_filter: str = "all", direct_only: bool = False) -> List[Dict[str, Any]]:
    out = []
    for it in items:
        if it.get("score", 0) < min_score:
            continue
        if domain_filter not in ("", "all", None) and it.get("domain", "").lower() != domain_filter.lower():
            continue
        if source_filter not in ("", "all", None) and it.get("source", "").lower() != source_filter.lower():
            continue
        if direct_only and it.get("evidence_level") != "direct":
            continue
        out.append(it)
    return out
