"""Public-intel extraction: names, contacts, structured page data, evidence contexts."""
from __future__ import annotations

import difflib
import html as htmllib
import json
import re
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import urljoin, urlparse

import phonenumbers
from bs4 import BeautifulSoup

from .detect import EMAIL_RE, brandify, domain_root_label, looks_like_company, is_person_like_name

LEGAL_SUFFIXES = {"inc", "llc", "ltd", "limited", "plc", "corp", "corporation", "co", "company", "gmbh",
                  "sa", "nig", "llp", "lp", "ag", "bv", "pty", "the"}
GENERIC_SEGMENTS = {
    "home", "homepage", "index", "login", "log in", "sign in", "sign up", "register", "contact", "contact us",
    "support", "help", "faq", "about", "about us", "services", "products", "pricing", "news", "blog",
    "search", "results", "directory", "official site", "official website", "welcome", "careers", "jobs",
    "team", "our team", "customer service", "customer care", "page not found", "404", "profile", "reviews",
}
STOP_WORDS = {
    "the", "this", "that", "search", "results", "page", "website", "about", "contact", "support", "home",
    "login", "sign", "news", "business", "company", "site", "official", "welcome", "help", "services",
    "products", "pricing", "profile", "directory", "find", "people", "team", "solutions", "platform",
    "media", "blog", "newsroom", "research", "portal", "monday", "tuesday", "wednesday", "thursday",
    "friday", "saturday", "sunday", "january", "february", "march", "april", "may", "june", "july",
    "august", "september", "october", "november", "december", "read", "more", "click", "here", "view",
    "call", "email", "phone", "address", "we", "our", "you", "your", "all", "get", "new", "free", "best",
    "top", "how", "what", "why", "who", "for", "and", "with", "from", "in", "on", "at", "is", "are", "its",
    "it", "a", "an", "of", "to", "by", "if", "us", "mr", "mrs", "ms", "dr", "online", "number", "copyright",
}
SOCIAL_PATTERNS = {
    "linkedin": re.compile(r"https?://(?:[a-z]{2,3}\.)?linkedin\.com/(?:company|in|school)/[A-Za-z0-9_\-%.]+", re.I),
    "x": re.compile(r"https?://(?:www\.)?(?:twitter|x)\.com/[A-Za-z0-9_]{1,15}(?![A-Za-z0-9_/])", re.I),
    "facebook": re.compile(r"https?://(?:www\.|m\.)?facebook\.com/[A-Za-z0-9_.\-]+", re.I),
    "instagram": re.compile(r"https?://(?:www\.)?instagram\.com/[A-Za-z0-9_.]+", re.I),
    "youtube": re.compile(r"https?://(?:www\.)?youtube\.com/(?:@|c/|channel/|user/)[A-Za-z0-9_\-]+", re.I),
    "github": re.compile(r"https?://(?:www\.)?github\.com/[A-Za-z0-9\-]+(?![A-Za-z0-9/\-])", re.I),
    "tiktok": re.compile(r"https?://(?:www\.)?tiktok\.com/@[A-Za-z0-9_.]+", re.I),
    "telegram": re.compile(r"https?://t\.me/[A-Za-z0-9_]+", re.I),
}
_IGNORED_SOCIAL = {"share", "sharer", "intent", "home", "login", "plugins", "dialog", "tr"}
_FILE_LIKE_EMAIL = re.compile(r"\.(png|jpe?g|gif|svg|webp|css|js)$|@\d+x", re.I)

TECH_MARKERS = {
    "WordPress": [r"wp-content", r"wp-includes", r"content=\"wordpress"],
    "Shopify": [r"cdn\.shopify\.com", r"shopify\.theme"],
    "Wix": [r"wixstatic\.com", r"wix\.com/"],
    "Squarespace": [r"squarespace\.com", r"static1\.squarespace"],
    "Webflow": [r"webflow\.(?:com|io)", r"data-wf-page"],
    "Next.js": [r"__next_data__", r"/_next/static"],
    "Nuxt": [r"__nuxt", r"/_nuxt/"],
    "React": [r"data-reactroot", r"react-dom", r"_reactlistening"],
    "Vue": [r"vue(?:\.min)?\.js", r"data-v-[0-9a-f]{6,}"],
    "Angular": [r"ng-version=", r"<app-root"],
    "Bootstrap": [r"bootstrap(?:\.min)?\.(?:css|js)", r"cdn\.jsdelivr\.net/npm/bootstrap"],
    "Tailwind CSS": [r"tailwindcss", r"cdn\.tailwindcss\.com"],
    "jQuery": [r"jquery(?:\.min)?\.js", r"jquery-\d"],
    "Django": [r"csrfmiddlewaretoken"],
    "Laravel": [r"laravel_session", r"/vendor/livewire"],
    "Google Analytics/Tag Manager": [r"googletagmanager\.com", r"google-analytics\.com", r"gtag\("],
    "Meta Pixel": [r"connect\.facebook\.net/.*fbevents", r"fbq\("],
    "Stripe": [r"js\.stripe\.com"],
    "PayPal": [r"paypal\.com/sdk", r"paypalobjects\.com"],
    "Cloudflare": [r"cdnjs\.cloudflare\.com", r"cloudflare-static"],
    "Google Fonts": [r"fonts\.googleapis\.com"],
}
HEADER_TECH = {
    "server": {"cloudflare": "Cloudflare", "nginx": "nginx", "apache": "Apache", "litespeed": "LiteSpeed",
               "microsoft-iis": "IIS", "vercel": "Vercel", "netlify": "Netlify"},
    "x-powered-by": {"php": "PHP", "express": "Express", "asp.net": "ASP.NET", "next.js": "Next.js"},
}


# ---------------------------------------------------------------- names
def normalize_entity_key(name: str) -> str:
    value = htmllib.unescape(name or "").lower()
    value = re.sub(r"[^\w\s&]", " ", value)
    tokens = [t for t in value.split() if t not in LEGAL_SUFFIXES]
    return " ".join(tokens).strip()


def same_entity(a: str, b: str, threshold: float = 0.88) -> bool:
    ka, kb = normalize_entity_key(a), normalize_entity_key(b)
    if not ka or not kb:
        return False
    if ka == kb or ka.replace(" ", "") == kb.replace(" ", ""):
        return True
    return difflib.SequenceMatcher(None, ka, kb).ratio() >= threshold


def split_title(title: str) -> List[str]:
    parts = re.split(r"\s+[|\-–—·•:]\s+|\s*\|\s*|\s+/\s+", htmllib.unescape(title or ""))
    return [p.strip(" -–—|·•") for p in parts if p and p.strip(" -–—|·•")]


def is_generic_segment(segment: str) -> bool:
    s = segment.lower().strip()
    return s in GENERIC_SEGMENTS or len(s) < 2 or bool(re.fullmatch(r"[\d\W]+", s))


def parse_platform_title(title: str, domain: str, link: str = "") -> Optional[Dict[str, str]]:
    """Platform-specific title formats give the most reliable names."""
    t = htmllib.unescape(title or "").strip()
    d = (domain or "").lower()
    path = urlparse(link).path.lower() if link else ""
    if d.endswith("linkedin.com"):
        t = re.sub(r"\s*\|\s*LinkedIn\s*$", "", t, flags=re.I)
        segs = [s.strip() for s in re.split(r"\s+[-–—]\s+", t) if s.strip()]
        if "/in/" in path and segs:
            out = {"name": segs[0], "type": "person", "source": "linkedin_profile"}
            if len(segs) > 1:
                out["role"] = segs[1]
            if len(segs) > 2:
                out["org"] = segs[2]
            return out
        if "/company/" in path or "/school/" in path:
            return {"name": segs[0] if segs else t, "type": "organization", "source": "linkedin_company"}
    if d.endswith(("twitter.com", "x.com")):
        m = re.match(r"^(.*?)\s*\(@([A-Za-z0-9_]+)\)", t)
        if m:
            return {"name": m.group(1).strip(), "handle": m.group(2), "type": "unknown", "source": "x_profile"}
    if d.endswith(("instagram.com", "facebook.com")):
        m = re.match(r"^(.*?)\s*\(@([A-Za-z0-9_.]+)\)", t)
        if m:
            return {"name": m.group(1).strip(), "handle": m.group(2), "type": "unknown", "source": "social_profile"}
        m = re.match(r"^(.*?)\s*[-–|]\s*(?:Home|Facebook|Instagram)", t)
        if m:
            return {"name": m.group(1).strip(), "type": "unknown", "source": "social_profile"}
    if d.endswith("github.com"):
        m = re.match(r"^([A-Za-z0-9\-]+)\s*\((.+?)\)", t)
        if m:
            return {"name": m.group(2).strip(), "handle": m.group(1), "type": "unknown", "source": "github_profile"}
    if d.endswith("wikipedia.org"):
        name = re.sub(r"\s*[-–]\s*Wikipedia.*$", "", t)
        return {"name": name, "type": "unknown", "source": "wikipedia"}
    return None


def brand_from_title(title: str, domain: str) -> Optional[Tuple[str, float]]:
    """Pick the brand-like segment of a page title; returns (name, weight)."""
    segs = [s for s in split_title(title) if not is_generic_segment(s)]
    if not segs:
        return None
    root = domain_root_label(domain).lower().replace("-", "")
    best, best_ratio = None, 0.0
    for seg in segs:
        key = normalize_entity_key(seg).replace(" ", "")
        if not key or not root:
            continue
        ratio = 1.0 if (key == root or root in key or (len(key) > 3 and key in root)) else \
            difflib.SequenceMatcher(None, key, root).ratio()
        if ratio > best_ratio:
            best, best_ratio = seg, ratio
    if best and best_ratio >= 0.72:
        return best[:80], 0.9
    if len(segs) == 1:
        return segs[0][:80], 0.55
    # no domain agreement: titles are usually "Page - Brand", so prefer the last segment
    return segs[-1][:80], 0.3


def capitalized_phrases(text: str, limit: int = 8) -> List[str]:
    cleaned = re.sub(r"https?://\S+|\S+@\S+", " ", text or "")
    pattern = r"\b(?:[A-Z][A-Za-z'’&\-]+)(?:\s+(?:of|and|&|the)?\s*[A-Z][A-Za-z'’&\-]+){0,3}\b"
    found, seen = [], set()
    for m in re.finditer(pattern, cleaned):
        phrase = m.group(0).strip()
        words = phrase.split()
        while words and words[0].lower() in STOP_WORDS:
            words = words[1:]
        while words and words[-1].lower() in STOP_WORDS:
            words = words[:-1]
        phrase = " ".join(words)
        if len(phrase) < 3 or phrase.lower() in STOP_WORDS or len(words) == 1 and phrase.lower() in STOP_WORDS:
            continue
        key = phrase.lower()
        if key not in seen:
            seen.add(key)
            found.append(phrase)
    return found[:limit]


def classify_entity_name(name: str) -> str:
    if looks_like_company(name):
        return "organization"
    if is_person_like_name(name):
        return "person"
    return "unknown"


# ---------------------------------------------------------------- evidence contexts
def find_contexts(text: str, needles: List[str], width: int = 90, limit: int = 3) -> List[str]:
    out: List[str] = []
    low = (text or "").lower()
    for needle in needles:
        n = (needle or "").lower()
        if not n:
            continue
        start = 0
        while len(out) < limit:
            idx = low.find(n, start)
            if idx < 0:
                break
            a, b = max(0, idx - width), min(len(text), idx + len(n) + width)
            snippet = re.sub(r"\s+", " ", text[a:b]).strip()
            if snippet and snippet not in out:
                out.append(("…" if a else "") + snippet + ("…" if b < len(text) else ""))
            start = idx + len(n) + width
        if len(out) >= limit:
            break
    return out


def nearby_names(text: str, needle: str, width: int = 90) -> List[str]:
    """Capitalised names that co-occur with the identifier (proximity extraction)."""
    names: List[str] = []
    for ctx in find_contexts(text, [needle], width=width, limit=3):
        masked = ctx.replace(needle, " ")
        names.extend(capitalized_phrases(masked, limit=4))
    return list(dict.fromkeys(names))[:6]


# ---------------------------------------------------------------- contacts
def deobfuscate(text: str) -> str:
    value = re.sub(r"\s*[\[(]\s*at\s*[\])]\s*", "@", text, flags=re.I)
    value = re.sub(r"\s*[\[(]\s*dot\s*[\])]\s*", ".", value, flags=re.I)
    return value


def extract_emails(text: str, limit: int = 20) -> List[str]:
    found = []
    for m in EMAIL_RE.finditer(deobfuscate(text or "")):
        e = m.group(0).lower().strip(".")
        if _FILE_LIKE_EMAIL.search(e) or e in found:
            continue
        found.append(e)
        if len(found) >= limit:
            break
    return found


def extract_phones(text: str, region: Optional[str] = None, limit: int = 10) -> List[str]:
    found: List[str] = []
    try:
        matcher = phonenumbers.PhoneNumberMatcher((text or "")[:150_000], region or None,
                                                  leniency=phonenumbers.Leniency.VALID)
        for match in matcher:
            e164 = phonenumbers.format_number(match.number, phonenumbers.PhoneNumberFormat.E164)
            if e164 not in found:
                found.append(e164)
            if len(found) >= limit:
                break
    except Exception:
        pass
    return found


def extract_social_links(text_or_links: str) -> Dict[str, List[str]]:
    out: Dict[str, List[str]] = {}
    for platform, pattern in SOCIAL_PATTERNS.items():
        hits = []
        for m in pattern.finditer(text_or_links or ""):
            url = m.group(0).rstrip("/.,")
            last = url.rsplit("/", 1)[-1].lower()
            if last in _IGNORED_SOCIAL or url in hits:
                continue
            hits.append(url)
        if hits:
            out[platform] = hits[:3]
    return out


# ---------------------------------------------------------------- page parsing
def _flatten_jsonld(data: Any) -> List[Dict[str, Any]]:
    items: List[Dict[str, Any]] = []
    if isinstance(data, list):
        for x in data:
            items.extend(_flatten_jsonld(x))
    elif isinstance(data, dict):
        if "@graph" in data:
            items.extend(_flatten_jsonld(data["@graph"]))
        items.append(data)
    return items


def _type_list(item: Dict[str, Any]) -> List[str]:
    t = item.get("@type", [])
    return [t] if isinstance(t, str) else [str(x) for x in t]


def parse_jsonld(soup: BeautifulSoup) -> List[Dict[str, Any]]:
    entities = []
    for tag in soup.find_all("script", attrs={"type": re.compile("ld\\+json", re.I)}):
        raw = (tag.string or tag.get_text() or "").strip()
        if not raw:
            continue
        try:
            data = json.loads(raw)
        except Exception:
            continue
        for item in _flatten_jsonld(data):
            types = _type_list(item)
            if not any(t in {"Organization", "Corporation", "LocalBusiness", "Person", "NGO", "EducationalOrganization",
                             "GovernmentOrganization", "ProfessionalService", "Store", "Restaurant", "WebSite"}
                       or t.endswith("Business") for t in types):
                continue
            is_person = "Person" in types
            ent: Dict[str, Any] = {"types": types, "name": str(item.get("name") or "")[:120],
                                   "url": item.get("url") if isinstance(item.get("url"), str) else "",
                                   "type": "person" if is_person else "organization"}
            same_as = item.get("sameAs")
            if isinstance(same_as, str):
                same_as = [same_as]
            ent["same_as"] = [s for s in (same_as or []) if isinstance(s, str)][:10]
            for key in ("telephone", "email", "foundingDate", "description", "legalName"):
                if isinstance(item.get(key), str):
                    ent[key] = item[key][:300]
            addr = item.get("address")
            if not is_person and isinstance(addr, dict):  # business addresses only, never personal ones
                parts = [str(addr.get(k)) for k in ("streetAddress", "addressLocality", "addressRegion",
                                                    "postalCode", "addressCountry") if addr.get(k) and not isinstance(addr.get(k), dict)]
                if parts:
                    ent["address"] = ", ".join(parts)[:240]
                if isinstance(addr.get("addressCountry"), str):
                    ent["country"] = addr["addressCountry"]
            if ent["name"]:
                entities.append(ent)
    return entities[:6]


def detect_technologies(html_text: str, headers: Optional[Dict[str, str]] = None) -> List[str]:
    low = (html_text or "").lower()
    tech = [name for name, pats in TECH_MARKERS.items() if any(re.search(p, low) for p in pats)]
    for header, mapping in HEADER_TECH.items():
        value = (headers or {}).get(header, "").lower()
        for token, name in mapping.items():
            if token in value and name not in tech:
                tech.append(name)
    if headers and "cf-ray" in headers and "Cloudflare" not in tech:
        tech.append("Cloudflare")
    return tech[:14]


def security_header_summary(headers: Dict[str, str]) -> Dict[str, Any]:
    wanted = ["strict-transport-security", "content-security-policy", "x-frame-options",
              "x-content-type-options", "referrer-policy", "permissions-policy"]
    present = [h for h in wanted if h in (headers or {})]
    return {"present": present, "missing": [h for h in wanted if h not in present],
            "score": f"{len(present)}/{len(wanted)}"}


def parse_page(html_text: str, url: str, headers: Optional[Dict[str, str]] = None,
               needles: Optional[List[str]] = None, region: Optional[str] = None) -> Dict[str, Any]:
    soup = BeautifulSoup(html_text or "", "html.parser")
    title = soup.title.get_text(" ", strip=True) if soup.title else ""
    meta: Dict[str, str] = {}
    for tag in soup.find_all("meta"):
        key = (tag.get("name") or tag.get("property") or "").lower()
        if key and tag.get("content"):
            meta[key] = tag["content"].strip()[:400]
    canonical = ""
    link_tag = soup.find("link", rel=lambda x: x and "canonical" in str(x).lower())
    if link_tag and link_tag.get("href"):
        canonical = urljoin(url, link_tag["href"])
    favicon = ""
    icon = soup.find("link", rel=lambda x: x and "icon" in str(x).lower())
    if icon and icon.get("href"):
        favicon = urljoin(url, icon["href"])
    hrefs = " ".join(a.get("href", "") for a in soup.find_all("a", href=True))
    mailtos = [a["href"][7:].split("?")[0] for a in soup.find_all("a", href=True) if a["href"].lower().startswith("mailto:")]
    tels = [a["href"][4:] for a in soup.find_all("a", href=True) if a["href"].lower().startswith("tel:")]
    for s in soup(["script", "style", "noscript"]):
        s.extract()
    body_text = soup.get_text(" ", strip=True)
    emails = list(dict.fromkeys([m.lower() for m in mailtos if EMAIL_RE.fullmatch(m)] + extract_emails(body_text)))[:12]
    phones = extract_phones(" ".join(tels) + " " + body_text, region)
    return {
        "title": title[:200],
        "description": meta.get("description") or meta.get("og:description", ""),
        "site_name": meta.get("og:site_name", ""),
        "og_title": meta.get("og:title", ""),
        "generator": meta.get("generator", ""),
        "canonical": canonical,
        "favicon": favicon,
        "language": (soup.html.get("lang") if soup.html else "") or "",
        "jsonld": parse_jsonld(BeautifulSoup(html_text or "", "html.parser")),
        "social_links": extract_social_links(hrefs),
        "emails": emails,
        "phones": phones,
        "technologies": detect_technologies(html_text, headers),
        "text_sample": body_text[:40000],
        "contexts": find_contexts(body_text, needles or [], limit=3) if needles else [],
        "exact_on_page": bool(needles) and any(n.lower() in body_text.lower() for n in needles if n),
    }
