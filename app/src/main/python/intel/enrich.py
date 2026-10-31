"""Kind-specific enrichment from public / offline data (never private records)."""
from __future__ import annotations

import re
import socket
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional
from urllib.parse import urlparse

import phonenumbers
from phonenumbers import carrier as pn_carrier
from phonenumbers import geocoder as pn_geocoder
from phonenumbers import timezone as pn_timezone

from .config import provider_keys
from .detect import clean_domain, clean_url, is_ip, registrable_domain
from .extract import parse_page, security_header_summary
from .net import http_get_json, robots_allowed, safe_get

FREE_EMAIL = {"gmail.com", "googlemail.com", "yahoo.com", "ymail.com", "outlook.com", "hotmail.com", "live.com",
              "msn.com", "icloud.com", "me.com", "aol.com", "proton.me", "protonmail.com", "zoho.com",
              "gmx.com", "mail.com", "yandex.com", "yahoo.co.uk", "rocketmail.com"}
DISPOSABLE = {"mailinator.com", "guerrillamail.com", "10minutemail.com", "tempmail.com", "temp-mail.org",
              "yopmail.com", "trashmail.com", "sharklasers.com", "getnada.com", "throwawaymail.com",
              "maildrop.cc", "dispostable.com", "fakeinbox.com", "mailnesia.com", "mintemail.com"}
ROLE_LOCALS = {"info", "admin", "support", "sales", "contact", "hello", "help", "office", "billing", "accounts",
               "careers", "jobs", "hr", "press", "media", "marketing", "noreply", "no-reply", "webmaster",
               "postmaster", "abuse", "security", "enquiries", "inquiries", "service", "team"}
SHORTENERS = {"bit.ly", "t.co", "tinyurl.com", "goo.gl", "ow.ly", "is.gd", "buff.ly", "rebrand.ly", "cutt.ly",
              "shorturl.at", "tiny.cc", "rb.gy", "wa.me", "lnkd.in"}
RISKY_TLDS = {"zip", "mov", "top", "xyz", "click", "country", "gq", "tk", "ml", "cf", "ga", "work", "support",
              "loan", "men", "rest", "icu", "cyou", "buzz", "monster"}

_TYPE_MAP = {
    phonenumbers.PhoneNumberType.FIXED_LINE: "Fixed line",
    phonenumbers.PhoneNumberType.MOBILE: "Mobile",
    phonenumbers.PhoneNumberType.FIXED_LINE_OR_MOBILE: "Fixed line or mobile",
    phonenumbers.PhoneNumberType.TOLL_FREE: "Toll free",
    phonenumbers.PhoneNumberType.PREMIUM_RATE: "Premium rate",
    phonenumbers.PhoneNumberType.SHARED_COST: "Shared cost",
    phonenumbers.PhoneNumberType.VOIP: "VoIP",
    phonenumbers.PhoneNumberType.PERSONAL_NUMBER: "Personal number",
    phonenumbers.PhoneNumberType.PAGER: "Pager",
    phonenumbers.PhoneNumberType.UAN: "UAN (company-wide number)",
    phonenumbers.PhoneNumberType.VOICEMAIL: "Voicemail",
    phonenumbers.PhoneNumberType.UNKNOWN: "Unknown",
}
_REASON_TEXT = {
    phonenumbers.ValidationResult.IS_POSSIBLE: "Length is plausible",
    phonenumbers.ValidationResult.IS_POSSIBLE_LOCAL_ONLY: "Plausible as a local-only number",
    phonenumbers.ValidationResult.INVALID_COUNTRY_CODE: "Invalid country code",
    phonenumbers.ValidationResult.TOO_SHORT: "Too short for this country",
    phonenumbers.ValidationResult.TOO_LONG: "Too long for this country",
    phonenumbers.ValidationResult.INVALID_LENGTH: "Length does not match any known format",
}


# ---------------------------------------------------------------- phone
def parse_phone_details(raw: str, default_region: Optional[str] = None) -> Dict[str, Any]:
    s = (raw or "").strip()
    if s.startswith("00"):
        s = "+" + s[2:]
    region = (default_region or "").upper() or None
    parsed = None
    try:
        parsed = phonenumbers.parse(s, region)
    except phonenumbers.NumberParseException:
        digits = re.sub(r"\D", "", s)
        try:
            parsed = phonenumbers.parse("+" + digits, None)
        except phonenumbers.NumberParseException:
            parsed = None
    if parsed is None:
        return {"status": "unparsed", "raw": raw,
                "note": "Could not parse. Add the country code (e.g. +234...) or set a default region."}
    fmt = phonenumbers.PhoneNumberFormat
    nsn = str(parsed.national_number)
    e164 = phonenumbers.format_number(parsed, fmt.E164)
    intl = phonenumbers.format_number(parsed, fmt.INTERNATIONAL)
    national = phonenumbers.format_number(parsed, fmt.NATIONAL)
    ntype = phonenumbers.number_type(parsed)
    valid = phonenumbers.is_valid_number(parsed)
    possible = phonenumbers.is_possible_number(parsed)
    reason = phonenumbers.is_possible_number_with_reason(parsed)
    variants = list(dict.fromkeys([e164, intl, national, re.sub(r"\D", "", national), re.sub(r"\D", "", e164)]))
    return {
        "status": "ok", "raw": raw, "e164": e164, "international": intl, "national": national,
        "country_code": parsed.country_code, "national_significant_number": nsn,
        "region": phonenumbers.region_code_for_number(parsed) or "Unknown",
        "country": pn_geocoder.country_name_for_number(parsed, "en") or "Unknown",
        "location": pn_geocoder.description_for_number(parsed, "en") or "",
        "carrier_original": pn_carrier.name_for_number(parsed, "en") or "",
        "timezones": list(pn_timezone.time_zones_for_number(parsed)),
        "type": _TYPE_MAP.get(ntype, "Unknown"),
        "is_valid": valid, "is_possible": possible,
        "validity_note": _REASON_TEXT.get(reason, "Unknown"),
        "variants": variants,
        "caveat": "Carrier/location come from numbering-plan prefixes. They show the original allocation, "
                  "not the current subscriber, and say nothing about who owns the line.",
    }


def numverify_lookup(phone_e164: str, keys: Optional[Dict[str, str]] = None) -> Dict[str, Any]:
    key = provider_keys(keys)["numverify"]
    if not key:
        return {"status": "disabled", "note": "NUMVERIFY_API_KEY not configured"}
    params = {"access_key": key, "number": phone_e164.lstrip("+"), "format": 1}
    data = None
    for scheme in ("https", "http"):  # free plans only serve http
        data = http_get_json(f"{scheme}://apilayer.net/api/validate", params=params)
        if data:
            break
    if not data or data.get("success") is False:
        return {"status": "error", "note": "NumVerify request failed"}
    keep = ("valid", "number", "international_format", "local_format", "country_prefix", "country_code",
            "country_name", "location", "carrier", "line_type")
    return {"status": "ok", **{k: data.get(k) for k in keep}}


# ---------------------------------------------------------------- email
def email_details(email: str) -> Dict[str, Any]:
    local, domain = email.split("@", 1)
    base = local.split("+")[0]
    return {
        "local_part": local, "domain": domain,
        "registrable_domain": registrable_domain(domain),
        "is_free_provider": domain in FREE_EMAIL,
        "is_disposable_domain": domain in DISPOSABLE,
        "is_role_account": base.lower() in ROLE_LOCALS,
        "has_plus_tag": "+" in local,
        "note": ("Free-mail address: the mailbox holder cannot be inferred from the domain."
                 if domain in FREE_EMAIL else
                 "Domain-hosted address: the domain's operator is public, the individual mailbox holder is not."),
    }


# ---------------------------------------------------------------- DNS / RDAP
def _resolver():
    try:
        import dns.resolver

        r = dns.resolver.Resolver()
        r.lifetime = 4
        r.timeout = 3
        return r
    except Exception:
        return None


def dns_records(domain: str, deep: bool = False) -> Dict[str, Any]:
    out: Dict[str, Any] = {"status": "ok"}
    res = _resolver()
    if res is None:
        try:
            infos = socket.getaddrinfo(domain, None)
            out["A"] = sorted({i[4][0] for i in infos})[:8]
        except Exception:
            out["status"] = "unresolved"
        out["note"] = "dnspython not installed - only basic A lookup performed"
        return out

    def q(name, rtype):
        try:
            return [r.to_text().strip('"') for r in res.resolve(name, rtype)]
        except Exception:
            return []

    out["A"] = q(domain, "A")[:8]
    out["AAAA"] = q(domain, "AAAA")[:4]
    out["MX"] = q(domain, "MX")[:6]
    out["NS"] = q(domain, "NS")[:6]
    txt = q(domain, "TXT")
    out["SPF"] = next((t for t in txt if t.lower().startswith("v=spf1")), "")
    out["DMARC"] = next((t for t in q(f"_dmarc.{domain}", "TXT") if t.lower().startswith("v=dmarc1")), "")
    if deep:
        out["CAA"] = q(domain, "CAA")[:4]
    if not out["A"] and not out["AAAA"] and not out["MX"] and not out["NS"]:
        out["status"] = "unresolved"
    out["mail_posture"] = {"mx": bool(out["MX"]), "spf": bool(out["SPF"]), "dmarc": bool(out["DMARC"])}
    return out


def _parse_dt(value: Any) -> Optional[datetime]:
    if not value:
        return None
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=timezone.utc)
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except Exception:
        return None


def _vcard(entity: Dict[str, Any]) -> Dict[str, str]:
    out = {}
    try:
        for item in entity.get("vcardArray", [None, []])[1]:
            if item[0] in ("fn", "org") and item[3]:
                out[item[0]] = item[3][0] if isinstance(item[3], list) else str(item[3])
    except Exception:
        pass
    return out


def rdap_domain(domain: str) -> Optional[Dict[str, Any]]:
    data = http_get_json(f"https://rdap.org/domain/{registrable_domain(domain)}", ttl=3600)
    if not isinstance(data, dict):
        return None
    events = {e.get("eventAction"): e.get("eventDate") for e in data.get("events", []) if isinstance(e, dict)}
    registrar = registrant = ""
    for ent in data.get("entities", []):
        roles = ent.get("roles", [])
        card = _vcard(ent)
        if "registrar" in roles:
            registrar = card.get("fn") or card.get("org", "")
        if "registrant" in roles:
            registrant = card.get("org") or card.get("fn", "")
    redacted = (not registrant) or bool(re.search(r"redact|privacy|proxy|withheld|not disclosed|data protected",
                                                  registrant, re.I))
    created = _parse_dt(events.get("registration"))
    return {
        "source": "rdap", "created": created.isoformat() if created else "",
        "updated": (_parse_dt(events.get("last changed")) or datetime.min.replace(tzinfo=timezone.utc)).isoformat()
        if events.get("last changed") else "",
        "expires": (_parse_dt(events.get("expiration")).isoformat() if _parse_dt(events.get("expiration")) else ""),
        "age_days": (datetime.now(timezone.utc) - created).days if created else None,
        "registrar": registrar, "registrant": "" if redacted else registrant, "registrant_redacted": redacted,
        "status": data.get("status", [])[:6],
        "nameservers": [n.get("ldhName", "").lower() for n in data.get("nameservers", [])][:6],
    }


def whois_domain(domain: str) -> Optional[Dict[str, Any]]:
    try:
        import whois

        info = whois.whois(registrable_domain(domain))
    except Exception:
        return None

    def first(v):
        return v[0] if isinstance(v, list) and v else v

    created = _parse_dt(first(info.get("creation_date")))
    org = first(info.get("org")) or ""
    return {
        "source": "whois", "created": created.isoformat() if created else "",
        "expires": str(first(info.get("expiration_date")) or ""),
        "age_days": (datetime.now(timezone.utc) - created).days if created else None,
        "registrar": str(first(info.get("registrar")) or ""),
        "registrant": "" if re.search(r"redact|privacy|proxy", str(org), re.I) else str(org),
        "registrant_redacted": not org or bool(re.search(r"redact|privacy|proxy", str(org), re.I)),
        "status": [str(s) for s in (info.get("status") if isinstance(info.get("status"), list) else [info.get("status")]) if s][:4],
        "nameservers": [str(n).lower() for n in (info.get("name_servers") or [])][:6]
        if isinstance(info.get("name_servers"), (list, set)) else [],
    }


def domain_registration(domain: str) -> Dict[str, Any]:
    return rdap_domain(domain) or whois_domain(domain) or {
        "source": "none", "note": "No public registration data could be retrieved (registry offline, "
                                  "rate-limited or unsupported TLD)."}


def cert_transparency(domain: str, limit: int = 30) -> Dict[str, Any]:
    data = http_get_json(f"https://crt.sh/?q=%25.{registrable_domain(domain)}&output=json", ttl=3600, timeout=25)
    if not isinstance(data, list):
        return {"status": "error", "note": "crt.sh lookup failed or timed out"}
    reg = registrable_domain(domain)
    names, earliest = [], None
    for entry in data:
        for row in str(entry.get("name_value", "")).splitlines():
            row = row.strip().lower().lstrip("*.")
            if row and (row == reg or row.endswith("." + reg)) and row not in names:
                names.append(row)
        dt = _parse_dt(entry.get("not_before"))
        if dt and (earliest is None or dt < earliest):
            earliest = dt
    return {"status": "ok", "domain": reg, "hostnames": sorted(names)[:limit], "hostname_count": len(names),
            "certificates_seen": len(data), "earliest_certificate": earliest.date().isoformat() if earliest else ""}


def wayback_first_seen(domain: str) -> Dict[str, Any]:
    data = http_get_json("https://archive.org/wayback/available",
                         params={"url": domain, "timestamp": "19960101"}, ttl=3600)
    snap = (data or {}).get("archived_snapshots", {}).get("closest") if isinstance(data, dict) else None
    if not snap:
        return {"status": "none"}
    ts = snap.get("timestamp", "")
    return {"status": "ok", "first_snapshot": f"{ts[:4]}-{ts[4:6]}-{ts[6:8]}" if len(ts) >= 8 else ts,
            "url": snap.get("url", "")}


# ---------------------------------------------------------------- website
def website_fingerprint(url: str, needles: Optional[List[str]] = None, region: Optional[str] = None,
                        deep: bool = False) -> Dict[str, Any]:
    url = clean_url(url)
    if not url:
        return {"status": "error", "url": url, "note": "Empty URL"}
    if not robots_allowed(url):
        return {"status": "blocked", "url": url, "note": "robots.txt disallows automated access to this URL"}
    resp = safe_get(url)
    if not resp:
        return {"status": "error", "url": url, "note": "Page request failed or target is not a public host"}
    page = parse_page(resp.text, resp.final_url, resp.headers, needles=needles, region=region)
    out = {
        "status": "ok" if resp.status < 400 else "http_error", "url": url, "final_url": resp.final_url,
        "http_status": resp.status, "redirects": resp.redirects, "server": resp.headers.get("server", ""),
        "security_headers": security_header_summary(resp.headers), **{k: page[k] for k in (
            "title", "description", "site_name", "generator", "canonical", "favicon", "language", "jsonld",
            "social_links", "emails", "phones", "technologies", "contexts", "exact_on_page")},
        "tech_hints": page["technologies"], "meta_tags": {},
    }
    if deep:
        host = urlparse(resp.final_url).netloc
        sec = safe_get(f"https://{host}/.well-known/security.txt", timeout=6, max_bytes=50_000)
        out["security_txt"] = bool(sec and sec.status == 200 and "contact" in sec.text.lower())
    return out


# ---------------------------------------------------------------- url / ip
def url_details(url: str) -> Dict[str, Any]:
    parsed = urlparse(url)
    host = (parsed.hostname or "").lower()
    reg = registrable_domain(host)
    tld = host.rsplit(".", 1)[-1] if "." in host else ""
    flags: List[str] = []
    if reg in SHORTENERS or host in SHORTENERS:
        flags.append("URL shortener - the final destination is hidden")
    if host.startswith("xn--") or ".xn--" in host:
        flags.append("Punycode/IDN hostname (check for look-alike characters)")
    if is_ip(host):
        flags.append("Host is a raw IP address")
    if parsed.username:
        flags.append("Contains credentials/userinfo (@ trick)")
    if parsed.port and parsed.port not in (80, 443):
        flags.append(f"Non-standard port {parsed.port}")
    if tld in RISKY_TLDS:
        flags.append(f".{tld} is a TLD frequently abused for throw-away sites")
    if host.count(".") >= 4:
        flags.append("Unusually deep subdomain nesting")
    if len(url) > 200:
        flags.append("Very long URL")
    return {"host": host, "registrable_domain": reg, "path": parsed.path, "query": parsed.query,
            "scheme": parsed.scheme, "flags": flags}


def ip_details(ip: str) -> Dict[str, Any]:
    out: Dict[str, Any] = {"ip": ip}
    try:
        out["reverse_dns"] = socket.gethostbyaddr(ip)[0]
    except Exception:
        out["reverse_dns"] = ""
    data = http_get_json(f"https://rdap.org/ip/{ip}", ttl=3600)
    if isinstance(data, dict):
        out["network"] = {"name": data.get("name", ""), "handle": data.get("handle", ""),
                          "country": data.get("country", ""), "type": data.get("type", ""),
                          "range": f"{data.get('startAddress', '')} - {data.get('endAddress', '')}"}
        for ent in data.get("entities", []):
            card = _vcard(ent)
            if card.get("fn") and "registrant" in ent.get("roles", []) + ["registrant"]:
                out["network"]["organization"] = card["fn"]
                break
    return out
