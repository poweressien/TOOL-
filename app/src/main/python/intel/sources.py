"""Extra public-data sources that add real, checkable detail to a scan.

Every function returns ``{"status": "ok"|"none"|"error", ...}`` and never raises.
All sources are public APIs / public pages; nothing private or leaked is queried.
"""
from __future__ import annotations

import hashlib
import re
import socket
import ssl
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from typing import Any, Callable, Dict, List, Optional
from urllib.parse import quote, urlparse

from .net import http_get_json, robots_allowed, safe_get

try:
    from ddgs import DDGS
except Exception:  # pragma: no cover
    DDGS = None

UA_API = {"Accept": "application/json"}


def _ok(**kw) -> Dict[str, Any]:
    return {"status": "ok", **kw}


def _none(note: str = "") -> Dict[str, Any]:
    return {"status": "none", "note": note}


def _err(exc: Exception) -> Dict[str, Any]:
    return {"status": "error", "note": f"{type(exc).__name__}: {exc}"[:160]}


# ------------------------------------------------------------------ news
def news(term: str, limit: int = 10) -> Dict[str, Any]:
    if DDGS is None:
        return _none("ddgs not installed")
    try:
        items = list(DDGS().news(term, max_results=limit))
    except Exception as exc:
        return _err(exc)
    out = [{"title": i.get("title", ""), "url": i.get("url", ""), "source": i.get("source", ""),
            "date": (i.get("date") or "")[:10], "snippet": (i.get("body") or "")[:260]}
           for i in items if i.get("url")]
    return _ok(items=out) if out else _none("No recent news found")


# ------------------------------------------------------------------ images (public web images, links only)
def images(term: str, limit: int = 8) -> Dict[str, Any]:
    if DDGS is None:
        return _none("ddgs not installed")
    try:
        items = list(DDGS().images(term, max_results=limit))
    except Exception as exc:
        return _err(exc)
    out = [{"title": i.get("title", ""), "thumbnail": i.get("thumbnail", ""), "page": i.get("url", ""),
            "source": i.get("source", "")} for i in items if i.get("thumbnail")]
    return _ok(items=out) if out else _none("No images found")


# ------------------------------------------------------------------ Wikidata
def wikidata(term: str, limit: int = 5) -> Dict[str, Any]:
    data = http_get_json("https://www.wikidata.org/w/api.php",
                         params={"action": "wbsearchentities", "search": term, "language": "en", "format": "json",
                                 "limit": limit, "type": "item"}, ttl=3600)
    hits = (data or {}).get("search", []) if isinstance(data, dict) else []
    if not hits:
        return _none("No Wikidata entity")
    ids = "|".join(h["id"] for h in hits[:3])
    full = http_get_json("https://www.wikidata.org/w/api.php",
                         params={"action": "wbgetentities", "ids": ids, "format": "json", "props": "claims|labels|descriptions",
                                 "languages": "en"}, ttl=3600) or {}
    ents = full.get("entities", {}) if isinstance(full, dict) else {}

    def claim(e, pid):
        for c in e.get("claims", {}).get(pid, [])[:1]:
            v = c.get("mainsnak", {}).get("datavalue", {}).get("value")
            if isinstance(v, dict):
                return v.get("id") or v.get("time") or v.get("amount") or v.get("text")
            return v
        return None

    out = []
    for h in hits[:3]:
        e = ents.get(h["id"], {})
        out.append({"id": h["id"], "label": h.get("label", ""), "description": h.get("description", ""),
                    "url": f"https://www.wikidata.org/wiki/{h['id']}",
                    "official_website": claim(e, "P856"), "inception": (claim(e, "P571") or "")[:11].lstrip("+"),
                    "headquarters_id": claim(e, "P159"), "country_id": claim(e, "P17")})
    return _ok(items=out)


# ------------------------------------------------------------------ GitHub (public API)
def github_user(handle: str) -> Dict[str, Any]:
    handle = handle.strip().lstrip("@")
    if not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})", handle):
        return _none("Not a valid GitHub handle")
    d = http_get_json(f"https://api.github.com/users/{quote(handle)}", headers=UA_API, ttl=1800)
    if not isinstance(d, dict) or not d.get("login"):
        return _none("No GitHub account with this handle")
    repos = http_get_json(f"https://api.github.com/users/{quote(handle)}/repos",
                          params={"sort": "pushed", "per_page": 8}, headers=UA_API, ttl=1800) or []
    return _ok(login=d["login"], type=d.get("type"), name=d.get("name"), company=d.get("company"),
               blog=d.get("blog"), location=d.get("location"), bio=d.get("bio"), followers=d.get("followers"),
               public_repos=d.get("public_repos"), created=(d.get("created_at") or "")[:10], url=d.get("html_url"),
               avatar=d.get("avatar_url"),
               repos=[{"name": r["name"], "url": r["html_url"], "stars": r.get("stargazers_count"),
                       "language": r.get("language"), "description": (r.get("description") or "")[:140]}
                      for r in repos if isinstance(r, dict)][:8])


def github_search_org(term: str) -> Dict[str, Any]:
    d = http_get_json("https://api.github.com/search/users", params={"q": f"{term} type:org", "per_page": 4},
                      headers=UA_API, ttl=1800)
    items = (d or {}).get("items", []) if isinstance(d, dict) else []
    if not items:
        return _none("No matching GitHub organisations")
    return _ok(items=[{"login": i["login"], "url": i["html_url"], "avatar": i.get("avatar_url")} for i in items])


# ------------------------------------------------------------------ Hacker News mentions
def hackernews(term: str, limit: int = 8) -> Dict[str, Any]:
    d = http_get_json("https://hn.algolia.com/api/v1/search", params={"query": f'"{term}"', "hitsPerPage": limit,
                                                                      "tags": "(story,comment)"}, ttl=1800)
    hits = (d or {}).get("hits", []) if isinstance(d, dict) else []
    out = []
    for h in hits:
        oid = h.get("objectID")
        out.append({"title": h.get("title") or (h.get("comment_text") or "")[:120], "author": h.get("author"),
                    "points": h.get("points"), "date": (h.get("created_at") or "")[:10],
                    "url": f"https://news.ycombinator.com/item?id={oid}"})
    return _ok(items=out, total=(d or {}).get("nbHits", len(out))) if out else _none("No Hacker News mentions")


# ------------------------------------------------------------------ Gravatar (only if the owner made it public)
def gravatar(email: str) -> Dict[str, Any]:
    h = hashlib.sha256(email.strip().lower().encode()).hexdigest()
    d = http_get_json(f"https://api.gravatar.com/v3/profiles/{h}", ttl=3600)
    if not isinstance(d, dict) or not d.get("profile_url"):
        return _none("No public Gravatar profile for this address")
    return _ok(display_name=d.get("display_name"), location=d.get("location"), about=(d.get("description") or "")[:300],
               url=d.get("profile_url"), avatar=d.get("avatar_url"),
               accounts=[{"service": a.get("service_label"), "url": a.get("url")} for a in d.get("verified_accounts", [])][:10])


# ------------------------------------------------------------------ username presence (public profile URLs)
PLATFORMS = [
    ("GitHub", "https://github.com/{u}"), ("GitLab", "https://gitlab.com/{u}"), ("Reddit", "https://www.reddit.com/user/{u}"),
    ("Medium", "https://medium.com/@{u}"), ("Dev.to", "https://dev.to/{u}"), ("Hacker News", "https://news.ycombinator.com/user?id={u}"),
    ("Keybase", "https://keybase.io/{u}"), ("Docker Hub", "https://hub.docker.com/u/{u}"), ("npm", "https://www.npmjs.com/~{u}"),
    ("PyPI", "https://pypi.org/user/{u}/"), ("Replit", "https://replit.com/@{u}"), ("CodePen", "https://codepen.io/{u}"),
    ("Dribbble", "https://dribbble.com/{u}"), ("Behance", "https://www.behance.net/{u}"), ("SoundCloud", "https://soundcloud.com/{u}"),
    ("Twitch", "https://www.twitch.tv/{u}"), ("YouTube", "https://www.youtube.com/@{u}"), ("Pinterest", "https://www.pinterest.com/{u}/"),
    ("Telegram", "https://t.me/{u}"), ("Mastodon.social", "https://mastodon.social/@{u}"), ("About.me", "https://about.me/{u}"),
    ("Linktree", "https://linktr.ee/{u}"), ("Patreon", "https://www.patreon.com/{u}"), ("Gravatar", "https://gravatar.com/{u}"),
    ("Product Hunt", "https://www.producthunt.com/@{u}"), ("Kaggle", "https://www.kaggle.com/{u}"), ("Spotify", "https://open.spotify.com/user/{u}"),
    ("Flickr", "https://www.flickr.com/people/{u}"), ("Vimeo", "https://vimeo.com/{u}"), ("Chess.com", "https://www.chess.com/member/{u}"),
]


def username_presence(handle: str) -> Dict[str, Any]:
    handle = handle.strip().lstrip("@")
    if not re.fullmatch(r"[A-Za-z0-9._-]{2,40}", handle):
        return _none("Not a valid handle")

    def probe(item):
        name, tpl = item
        url = tpl.format(u=quote(handle))
        resp = safe_get(url, timeout=7, max_bytes=60_000)
        if resp is None:
            return {"platform": name, "url": url, "state": "unknown"}
        if resp.status == 200 and not re.search(r"(page not found|user not found|doesn.t exist|404)",
                                               resp.text[:4000], re.I):
            state = "found"
        elif resp.status in (404, 410):
            state = "not_found"
        else:
            state = "unknown"
        return {"platform": name, "url": url, "state": state}

    with ThreadPoolExecutor(max_workers=10) as pool:
        rows = list(pool.map(probe, PLATFORMS))
    found = [r for r in rows if r["state"] == "found"]
    return _ok(checked=len(rows), found=found, not_found=sum(r["state"] == "not_found" for r in rows),
               unknown=sum(r["state"] == "unknown" for r in rows),
               caveat="A page existing only shows the handle is taken; it does not prove it is the same person.")


# ------------------------------------------------------------------ domain extras
def tls_certificate(domain: str) -> Dict[str, Any]:
    try:
        ctx = ssl.create_default_context()
        with socket.create_connection((domain, 443), timeout=7) as sock:
            with ctx.wrap_socket(sock, server_hostname=domain) as s:
                c = s.getpeercert()
                proto = s.version()
    except Exception as exc:
        return _err(exc)
    def rdn(seq):
        return {k: v for t in seq for k, v in t}
    nb = datetime.strptime(c["notBefore"], "%b %d %H:%M:%S %Y %Z")
    na = datetime.strptime(c["notAfter"], "%b %d %H:%M:%S %Y %Z")
    return _ok(issuer=rdn(c.get("issuer", ())).get("organizationName", ""), subject=rdn(c.get("subject", ())).get("commonName", ""),
               valid_from=nb.date().isoformat(), valid_to=na.date().isoformat(),
               days_left=(na - datetime.utcnow()).days, protocol=proto,
               alt_names=[v for k, v in c.get("subjectAltName", ()) if k == "DNS"][:25])


def site_files(domain: str) -> Dict[str, Any]:
    out: Dict[str, Any] = {}
    for key, path in (("robots", "/robots.txt"), ("sitemap", "/sitemap.xml"), ("security_txt", "/.well-known/security.txt"),
                      ("humans", "/humans.txt")):
        resp = safe_get(f"https://{domain}{path}", timeout=7, max_bytes=200_000)
        if resp and resp.status == 200 and "<html" not in resp.text[:300].lower():
            info: Dict[str, Any] = {"present": True, "url": resp.final_url}
            if key == "sitemap":
                locs = re.findall(r"<loc>\s*([^<\s]+)\s*</loc>", resp.text)
                info.update(urls=len(locs), sample=locs[:12])
            elif key == "robots":
                info.update(sitemaps=re.findall(r"(?im)^sitemap:\s*(\S+)", resp.text)[:5],
                            disallow_rules=len(re.findall(r"(?im)^disallow:", resp.text)))
            else:
                info["text"] = resp.text[:500]
            out[key] = info
        else:
            out[key] = {"present": False}
    return _ok(**out)


def archive_timeline(domain: str) -> Dict[str, Any]:
    d = http_get_json("https://web.archive.org/cdx/search/cdx",
                      params={"url": domain, "output": "json", "fl": "timestamp,statuscode", "collapse": "timestamp:6",
                              "limit": 1500}, ttl=3600, timeout=20)
    if not isinstance(d, list) or len(d) < 2:
        return _none("No archive captures")
    years: Dict[str, int] = {}
    for ts, _ in d[1:]:
        years[ts[:4]] = years.get(ts[:4], 0) + 1
    ts = [r[0] for r in d[1:]]
    return _ok(first=f"{ts[0][:4]}-{ts[0][4:6]}", last=f"{ts[-1][:4]}-{ts[-1][4:6]}", months_captured=len(ts),
               by_year=years)


def lookalike_domains(domain: str, limit: int = 40) -> Dict[str, Any]:
    """Resolve simple typo / homoglyph variants. Registered variants are a phishing-risk signal, not proof."""
    name, _, tld = domain.rpartition(".")
    if not name or len(name) > 30:
        return _none("Skipped")
    variants = set()
    for i in range(len(name)):
        variants.add(name[:i] + name[i + 1:])                            # omission
        if i < len(name) - 1:
            variants.add(name[:i] + name[i + 1] + name[i] + name[i + 2:])  # swap
        variants.add(name[:i] + name[i] * 2 + name[i + 1:])               # duplication
    for a, b in (("l", "1"), ("o", "0"), ("i", "l"), ("rn", "m"), ("vv", "w"), ("e", "3"), ("a", "4")):
        if a in name:
            variants.add(name.replace(a, b, 1))
    variants.discard(name)
    variants = {v for v in variants if len(v) >= 2 and not v.startswith("-") and not v.endswith("-")}
    cands = sorted(variants)[:limit] + [f"{name}.{t}" for t in ("com", "net", "org", "co") if t != tld][:0]
    full = [f"{v}.{tld}" for v in cands]

    def resolve(h):
        try:
            return h, socket.gethostbyname(h)
        except Exception:
            return h, None

    with ThreadPoolExecutor(max_workers=16) as pool:
        res = list(pool.map(resolve, full))
    live = [{"domain": h, "ip": ip} for h, ip in res if ip]
    return _ok(checked=len(full), registered=live)


def domain_mentions(domain: str) -> Dict[str, Any]:
    """Pages that link to / mention the domain (via public search, not a backlink index)."""
    if DDGS is None:
        return _none("ddgs not installed")
    try:
        items = list(DDGS().text(f'"{domain}" -site:{domain}', max_results=10))
    except Exception as exc:
        return _err(exc)
    out = [{"title": i.get("title", ""), "url": i.get("href", ""), "snippet": (i.get("body") or "")[:220]} for i in items]
    return _ok(items=out) if out else _none("No outside mentions found")


# ------------------------------------------------------------------ orchestration
def gather(kind: str, value: str, ctx: Dict[str, Any], deep: bool,
           progress: Optional[Callable[[str], None]] = None) -> Dict[str, Any]:
    """Run every source that is relevant to ``kind`` concurrently; returns {name: result}."""
    tasks: Dict[str, Callable[[], Dict[str, Any]]] = {}
    domain = ctx.get("domain") if kind in {"domain", "url", "email"} else None
    free = {"gmail.com", "yahoo.com", "outlook.com", "hotmail.com", "icloud.com", "proton.me", "protonmail.com", "live.com", "aol.com"}
    if domain in free:
        domain = None
    if kind in {"person_or_company", "keyword"}:
        tasks.update(news=lambda: news(value), images=lambda: images(value), wikidata=lambda: wikidata(value),
                     hackernews=lambda: hackernews(value), github_orgs=lambda: github_search_org(value))
    if domain:
        brand = domain.split(".")[0]
        tasks.update(news=lambda: news(brand if kind != "domain" else domain), tls=lambda: tls_certificate(domain),
                     site_files=lambda: site_files(domain), archive=lambda: archive_timeline(domain),
                     hackernews=lambda: hackernews(domain), mentions=lambda: domain_mentions(domain),
                     wikidata=lambda: wikidata(brand), github_orgs=lambda: github_search_org(brand))
        if deep:
            tasks["lookalikes"] = lambda: lookalike_domains(domain)
    if kind == "email":
        tasks["gravatar"] = lambda: gravatar(ctx["email"])
        tasks["username_presence"] = lambda: username_presence(ctx["local"])
    if kind == "username":
        u = ctx["username"]
        tasks.update(github=lambda: github_user(u), username_presence=lambda: username_presence(u),
                     hackernews=lambda: hackernews(u), news=lambda: news(u))
    if kind in {"phone", "number", "ip"}:
        tasks["news"] = lambda: news(value)
    if not tasks:
        return {}
    if progress:
        progress(f"Collecting extra public data ({len(tasks)} sources)…")
    out: Dict[str, Any] = {}

    def run(item):
        name, fn = item
        t0 = time.time()
        try:
            res = fn()
        except Exception as exc:  # never break a scan
            res = _err(exc)
        res["seconds"] = round(time.time() - t0, 2)
        return name, res

    with ThreadPoolExecutor(max_workers=8) as pool:
        for name, res in pool.map(run, tasks.items()):
            out[name] = res
    return out


def extras_findings(extras: Dict[str, Any]) -> List[str]:
    f: List[str] = []
    n = extras.get("news", {})
    if n.get("status") == "ok":
        f.append(f"{len(n['items'])} recent news item(s) found; latest: {n['items'][0]['title'][:90]} ({n['items'][0]['source']}).")
    t = extras.get("tls", {})
    if t.get("status") == "ok":
        f.append(f"TLS certificate issued by {t['issuer'] or 'unknown'}, valid until {t['valid_to']} ({t['days_left']} days left).")
    a = extras.get("archive", {})
    if a.get("status") == "ok":
        f.append(f"Web archive captures from {a['first']} to {a['last']} ({a['months_captured']} months captured).")
    g = extras.get("github", {})
    if g.get("status") == "ok":
        f.append(f"GitHub account {g['login']} exists ({g['public_repos']} public repos, created {g['created']}).")
    u = extras.get("username_presence", {})
    if u.get("status") == "ok" and u["found"]:
        f.append(f"Handle is in use on {len(u['found'])} of {u['checked']} checked platforms.")
    gr = extras.get("gravatar", {})
    if gr.get("status") == "ok":
        f.append(f"Public Gravatar profile exists ({gr.get('display_name') or 'no display name'}).")
    l = extras.get("lookalikes", {})
    if l.get("status") == "ok" and l["registered"]:
        f.append(f"{len(l['registered'])} look-alike domain variant(s) resolve - review for phishing risk.")
    wd = extras.get("wikidata", {})
    if wd.get("status") == "ok":
        f.append(f"Wikidata entity: {wd['items'][0]['label']} - {wd['items'][0]['description'] or 'no description'}.")
    return f
