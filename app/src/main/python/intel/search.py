"""Search providers, query planning, merging and provenance tracking."""
from __future__ import annotations

import random
import re
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from typing import Any, Callable, Dict, List, Optional
from urllib.parse import parse_qsl, urlencode, urlparse, urlunparse

from .config import provider_keys
from .net import cache_get, cache_set, http_get_json

try:
    from ddgs import DDGS  # maintained successor of duckduckgo_search
except Exception:  # pragma: no cover
    try:
        from duckduckgo_search import DDGS
    except Exception:
        DDGS = None

_DDG_GATE = threading.Semaphore(2)
_TRACKING = {"utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "fbclid", "gclid", "ref", "ref_src"}
_ENGINE_HOSTS = ("google.", "bing.com", "duckduckgo.com", "yahoo.com", "baidu.com", "yandex.")


@dataclass
class QuerySpec:
    text: str
    purpose: str


# ---------------------------------------------------------------- providers
def _norm(items, title_k, link_k, snip_k, provider, limit) -> List[Dict[str, str]]:
    out = []
    for it in (items or [])[:limit]:
        link = it.get(link_k) or ""
        if not link:
            continue
        out.append({"title": (it.get(title_k) or "").strip(), "link": link,
                    "snippet": (it.get(snip_k) or "").strip(), "provider": provider})
    return out


def serpapi_search(query: str, limit: int = 8, keys: Optional[Dict[str, str]] = None) -> List[Dict[str, str]]:
    key = provider_keys(keys)["serpapi"]
    if not key:
        return []
    data = http_get_json("https://serpapi.com/search", params={"engine": "google", "q": query, "api_key": key,
                                                                "num": min(limit, 20)}, ttl=900)
    if not isinstance(data, dict) or data.get("error"):
        raise RuntimeError(f"SerpAPI: {(data or {}).get('error', 'request failed')}")
    return _norm(data.get("organic_results"), "title", "link", "snippet", "serpapi", limit)


def google_cse_search(query: str, limit: int = 8, keys: Optional[Dict[str, str]] = None) -> List[Dict[str, str]]:
    k = provider_keys(keys)
    if not (k["google_key"] and k["google_cx"]):
        return []
    out: List[Dict[str, str]] = []
    for start in range(1, min(limit, 20) + 1, 10):  # CSE returns max 10 per request
        data = http_get_json("https://www.googleapis.com/customsearch/v1",
                             params={"key": k["google_key"], "cx": k["google_cx"], "q": query,
                                     "num": min(10, limit - start + 1), "start": start}, ttl=900)
        if not isinstance(data, dict) or data.get("error"):
            if out:
                break
            raise RuntimeError("Google CSE: " + str(((data or {}).get("error") or {}).get("message", "request failed"))[:120])
        out += _norm(data.get("items"), "title", "link", "snippet", "google_cse", limit)
        if len(data.get("items", [])) < 10:
            break
    return out[:limit]


def brave_search(query: str, limit: int = 8, keys: Optional[Dict[str, str]] = None) -> List[Dict[str, str]]:
    key = provider_keys(keys)["brave"]
    if not key:
        return []
    data = http_get_json("https://api.search.brave.com/res/v1/web/search", params={"q": query, "count": min(limit, 20)},
                         headers={"X-Subscription-Token": key, "Accept": "application/json"}, ttl=900)
    if not isinstance(data, dict):
        raise RuntimeError("Brave Search request failed")
    items = [{"title": x.get("title"), "url": x.get("url"), "description": re.sub(r"<[^>]+>", "", x.get("description", ""))}
             for x in data.get("web", {}).get("results", [])]
    return _norm(items, "title", "url", "description", "brave", limit)


def duckduckgo_search(query: str, limit: int = 8, keys: Optional[Dict[str, str]] = None) -> List[Dict[str, str]]:
    if DDGS is None:
        raise RuntimeError("ddgs package not installed")
    last: Optional[Exception] = None
    for attempt in range(2):  # one retry with back-off: DDG rate limits are transient
        try:
            with _DDG_GATE:
                time.sleep(random.uniform(0.2, 0.5) + attempt * 1.2)
                items = list(DDGS().text(query, max_results=limit))
            if items or attempt:
                return _norm(items, "title", "href", "body", "duckduckgo", limit)
        except Exception as exc:
            last = exc
    if last:
        raise last
    return []


PROVIDERS: Dict[str, Callable[..., List[Dict[str, str]]]] = {
    "serpapi": serpapi_search,
    "google_cse": google_cse_search,
    "brave": brave_search,
    "duckduckgo": duckduckgo_search,
}


def enabled_providers(keys: Optional[Dict[str, str]] = None) -> List[str]:
    k = provider_keys(keys)
    on = {"serpapi": bool(k["serpapi"]), "google_cse": bool(k["google_key"] and k["google_cx"]),
          "brave": bool(k["brave"]), "duckduckgo": True}
    return [n for n in PROVIDERS if on.get(n, True)]


def test_providers(keys: Optional[Dict[str, str]] = None) -> Dict[str, Dict[str, Any]]:
    """Run one cheap query per configured provider and report what happened."""
    out: Dict[str, Dict[str, Any]] = {}
    for name in enabled_providers(keys):
        t0 = time.time()
        try:
            res = PROVIDERS[name]("wikipedia", 3, keys)
            out[name] = {"ok": bool(res), "results": len(res), "seconds": round(time.time() - t0, 1),
                         "note": "" if res else "Connected but returned no results"}
        except Exception as exc:
            out[name] = {"ok": False, "results": 0, "seconds": round(time.time() - t0, 1),
                         "note": f"{type(exc).__name__}: {exc}"[:160]}
    return out


# ---------------------------------------------------------------- query planning
def build_queries(kind: str, ctx: Dict[str, Any], max_queries: int) -> List[QuerySpec]:
    q: List[QuerySpec] = []

    def add(text: str, purpose: str) -> None:
        if text and all(text != s.text for s in q):
            q.append(QuerySpec(text, purpose))

    if kind == "phone":
        v = ctx.get("phone_variants", [])
        e164 = next((x for x in v if x.startswith("+") and " " not in x), "")
        spaced = [x for x in v if " " in x]
        digits = next((x for x in v if x.isdigit() and not x.startswith("+")), "")
        for x in [e164] + spaced[:2] + [digits]:
            add(f'"{x}"' if x else "", "exact number")
        first = e164 or (v[0] if v else "")
        add(f'"{first}" contact', "contact context")
        add(f'"{first}" whatsapp OR telegram', "messaging context")
        add(f'"{first}" scam OR spam OR fraud OR complaint', "reputation")
        add(f'"{first}" company OR business OR office', "business context")
        add(f'"{first}" site:linkedin.com OR site:facebook.com', "social context")
    elif kind == "email":
        email, local, domain = ctx["email"], ctx["local"], ctx["domain"]
        add(f'"{email}"', "exact email")
        add(f'"{local}" "{domain}"', "split form")
        add(f'"{email.replace("@", " [at] ")}" OR "{email.replace("@", "(at)")}"', "obfuscated form")
        add(f'"{email}" contact OR support', "contact context")
        add(f'"{email}" scam OR spam OR phishing', "reputation")
        add(f'site:{domain} contact', "domain contact page")
        add(f'"{domain}" about company', "domain operator")
    elif kind == "domain":
        d = ctx["domain"]
        add(f'"{d}"', "exact domain")
        add(f'site:{d}', "indexed pages")
        add(f'"{d}" about company', "operator")
        add(f'"{d}" reviews OR scam OR complaints', "reputation")
        add(f'"{d}" contact', "contact")
        add(f'"{d}" site:linkedin.com', "social/company page")
        add(f'"{ctx.get("brand", d)}" official site', "brand")
    elif kind == "url":
        u, d = ctx["url"], ctx["domain"]
        add(f'"{u}"', "exact url")
        add(f'"{d}"', "host")
        add(f'site:{d}', "indexed pages")
        add(f'"{d}" about company', "operator")
        add(f'"{d}" scam OR phishing OR reviews', "reputation")
        add(f'"{d}" contact', "contact")
    elif kind == "ip":
        ip = ctx["ip"]
        add(f'"{ip}"', "exact ip")
        add(f'"{ip}" abuse OR blacklist OR malware', "reputation")
        add(f'"{ip}" hosting', "hosting context")
    elif kind == "username":
        u = ctx["username"]
        add(f'"@{u}"', "handle")
        add(f'"{u}"', "bare username")
        add(f'"{u}" site:github.com OR site:linkedin.com', "professional profiles")
        add(f'"{u}" site:x.com OR site:instagram.com OR site:facebook.com', "social profiles")
        add(f'"{u}" profile', "profile pages")
        add(f'"{u}" site:youtube.com OR site:tiktok.com OR site:medium.com', "content profiles")
    elif kind == "number":
        d = ctx["digits"]
        for x in (ctx.get("raw"), d):
            if x:
                add(f'"{x}"', "exact number")
        for w in ("reference", "invoice OR order", "account", "tracking", "customer"):
            add(f'"{d}" {w}', w)
    else:  # person_or_company / keyword
        n = ctx["name"]
        add(f'"{n}"', "exact name")
        add(f'"{n}" official website', "official site")
        add(f'"{n}" site:linkedin.com', "linkedin")
        add(f'"{n}" about OR company OR founder', "background")
        add(f'"{n}" contact', "contact")
        add(f'"{n}" site:crunchbase.com OR site:opencorporates.com OR site:wikipedia.org', "reference")
        add(f'"{n}" news OR interview', "news")
        add(f'"{n}" site:facebook.com OR site:instagram.com OR site:x.com', "social")
        add(f'"{n}" reviews OR complaints', "reputation")
        add(f'"{n}" registered OR incorporated OR CAC OR "company number"', "registration")
    # recall extras (only reached in the larger modes because of the max_queries cut)
    anchor = {"phone": ctx.get("phone_variants", [""])[0] if ctx.get("phone_variants") else "",
              "email": ctx.get("email", ""), "domain": ctx.get("domain", ""), "url": ctx.get("domain", ""),
              "username": ctx.get("username", ""), "number": ctx.get("digits", ""), "ip": ctx.get("ip", "")
              }.get(kind, ctx.get("name", ""))
    if anchor:
        add(f'"{anchor}" filetype:pdf', "documents (brochures, tenders, invoices)")
        add(f'"{anchor}" inurl:contact OR inurl:about', "contact/about pages")
        add(f'"{anchor}" site:facebook.com OR site:instagram.com OR site:x.com', "social mentions")
        add(f'"{anchor}" directory OR listing OR yellowpages', "business directories")
    if kind == "phone":
        nsn, cc = ctx.get("nsn", ""), ctx.get("cc", "")
        if nsn and len(nsn) >= 9:
            add(f'"{nsn[:3]} {nsn[3:6]} {nsn[6:]}"', "national spaced form")
            add(f'"{nsn[:3]}-{nsn[3:6]}-{nsn[6:]}"', "dashed form")
        if nsn and cc:
            add(f'"{cc} {nsn}"', "country code + number")
    if kind in {"person_or_company", "keyword"}:
        words = ctx["name"].split()
        if 2 <= len(words) <= 3:
            add(f'"{" ".join(reversed(words))}"', "reordered name")
        add(ctx["name"], "unquoted (broad)")
    return q[:max_queries]


# ---------------------------------------------------------------- execution / merging
def canonical_url(link: str) -> str:
    try:
        p = urlparse(link)
        host = p.netloc.lower().removeprefix("www.")
        query = urlencode([(k, v) for k, v in parse_qsl(p.query) if k.lower() not in _TRACKING])
        return urlunparse((p.scheme.lower(), host, p.path.rstrip("/") or "/", "", query, ""))
    except Exception:
        return link


_ENGINE_RE = re.compile(r"(?:^|\.)(?:google\.[a-z.]{2,6}|bing\.com|duckduckgo\.com|yahoo\.com|baidu\.com|yandex\.[a-z]{2,3})$")


def is_engine_junk(link: str) -> bool:
    """Search-result pages of engines (not e.g. 'acmeplumbing.com', which merely contains 'bing.com')."""
    p = urlparse(link)
    host = p.netloc.lower().split(":")[0]
    return bool(_ENGINE_RE.search(host)) and ("/search" in p.path or "/url" in p.path or p.path in ("", "/"))


def run_queries(specs: List[QuerySpec], per_query: int, diagnostics: Dict[str, Any],
                progress: Optional[Callable[[str], None]] = None,
                keys: Optional[Dict[str, str]] = None) -> List[Dict[str, Any]]:
    names = [n for n in PROVIDERS if n in enabled_providers(keys) or n not in {"serpapi", "google_cse", "brave", "duckduckgo"}]
    if not keys and set(PROVIDERS) != {"serpapi", "google_cse", "brave", "duckduckgo"}:
        names = list(PROVIDERS)  # custom/test providers are always enabled
    tasks = [(spec, name, PROVIDERS[name]) for spec in specs for name in names]
    diag = diagnostics.setdefault("providers", {})
    trail = diagnostics.setdefault("trail", [])
    for name in names:
        diag.setdefault(name, {"queries": 0, "ok": 0, "errors": 0, "results": 0, "last_error": ""})
    raw: List[Dict[str, Any]] = []

    def work(spec: QuerySpec, name: str, fn):
        t0 = time.time()
        spec, name, res, err = _work(spec, name, fn)
        trail.append({"query": spec.text, "purpose": spec.purpose, "provider": name, "results": len(res),
                      "error": err or "", "seconds": round(time.time() - t0, 2),
                      "top_domains": list(dict.fromkeys(urlparse(x.get("link", "")).netloc.lower().replace("www.", "")
                                                        for x in res))[:4]})
        return spec, name, res, err

    def _work(spec: QuerySpec, name: str, fn):
        ck = f"search:{name}:{spec.text}:{per_query}:{hash(tuple(sorted((keys or {}).items())))}"
        hit = cache_get(ck, 900)
        if hit is not None:
            return spec, name, hit, None
        try:
            res = fn(spec.text, per_query, keys)
            cache_set(ck, res)
            return spec, name, res, None
        except Exception as exc:  # provider failures must never break a scan
            return spec, name, [], f"{type(exc).__name__}: {exc}"[:160]

    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = [pool.submit(work, *t) for t in tasks]
        for fut in as_completed(futures):
            spec, name, res, err = fut.result()
            d = diag[name]
            d["queries"] += 1
            if err:
                d["errors"] += 1
                d["last_error"] = err
            else:
                d["ok"] += 1
                d["results"] += len(res)
            for r in res:
                raw.append({**r, "query": spec.text, "purpose": spec.purpose})
            if progress:
                progress(f"{name}: {spec.purpose}")
    return raw


def merge_results(raw: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    merged: Dict[str, Dict[str, Any]] = {}
    for r in raw:
        link = r.get("link", "")
        if not link or is_engine_junk(link):
            continue
        key = canonical_url(link)
        cur = merged.get(key)
        if cur is None:
            merged[key] = {"title": r["title"], "link": link, "snippet": r["snippet"],
                           "providers": {r["provider"]}, "queries": [r["query"]], "purposes": [r["purpose"]]}
        else:
            if len(r["snippet"]) > len(cur["snippet"]):
                cur["snippet"] = r["snippet"]
            if not cur["title"]:
                cur["title"] = r["title"]
            cur["providers"].add(r["provider"])
            if r["query"] not in cur["queries"]:
                cur["queries"].append(r["query"])
                cur["purposes"].append(r["purpose"])
    out = list(merged.values())
    for item in out:
        item["providers"] = sorted(item["providers"])
    return out


# ---------------------------------------------------------------- wikipedia (no extra dependency)
def wikipedia_lookup(term: str) -> Dict[str, Any]:
    term = (term or "").strip()
    if len(term) < 2:
        return {"status": "not_found"}
    data = http_get_json("https://en.wikipedia.org/w/api.php",
                         params={"action": "query", "list": "search", "srsearch": term, "srlimit": 3,
                                 "format": "json"}, ttl=3600)
    hits = (data or {}).get("query", {}).get("search", []) if isinstance(data, dict) else []
    tokens = set(re.findall(r"[a-z0-9]+", term.lower()))
    for hit in hits:
        title = hit.get("title", "")
        ttoks = set(re.findall(r"[a-z0-9]+", title.lower()))
        if not ttoks or not tokens:
            continue
        overlap = len(tokens & ttoks) / max(len(tokens), len(ttoks))
        if overlap < 0.6:
            continue
        summ = http_get_json("https://en.wikipedia.org/api/rest_v1/page/summary/" + title.replace(" ", "_"), ttl=3600)
        if not isinstance(summ, dict) or summ.get("type") == "disambiguation":
            continue
        return {"status": "ok", "title": summ.get("title", title), "summary": (summ.get("extract") or "")[:1200],
                "description": summ.get("description", ""), "url": (summ.get("content_urls", {}).get("desktop", {})
                                                                      .get("page", "")),
                "match": "strong" if overlap >= 0.99 else "partial"}
    return {"status": "not_found"}
