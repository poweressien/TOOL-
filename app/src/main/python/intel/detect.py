"""Input detection and normalisation."""
from __future__ import annotations

import ipaddress
import re
from typing import Optional
from urllib.parse import urlparse

import phonenumbers

EMAIL_RE = re.compile(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)*\.[A-Za-z]{2,}")
DOMAIN_RE = re.compile(r"(?:[A-Za-z0-9](?:[A-Za-z0-9\-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,24}")
USERNAME_RE = re.compile(r"[A-Za-z0-9_.\-]{2,40}")

COMPANY_MARKERS = {
    "inc", "ltd", "llc", "llp", "plc", "corp", "corporation", "group", "company", "co", "holdings",
    "partners", "systems", "solutions", "technologies", "technology", "labs", "studio", "studios",
    "agency", "network", "consulting", "capital", "foundation", "association", "university",
    "limited", "gmbh", "bank", "ventures", "enterprises", "industries", "services", "international",
    "nig", "nigeria",
}

_MULTI_SUFFIXES = {
    "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "co.nz", "co.za", "com.ng",
    "org.ng", "gov.ng", "edu.ng", "net.ng", "com.br", "com.mx", "co.in", "co.jp", "com.cn", "com.hk",
    "com.sg", "co.ke", "com.gh", "com.eg", "com.tr", "com.ar", "co.id", "com.my", "com.pk", "com.ph",
    "co.il", "co.kr", "com.ua", "com.pl",
}
_TLDX = None


def normalize_whitespace(value: Optional[str]) -> str:
    return re.sub(r"\s+", " ", (value or "")).strip()


def is_ip(value: str) -> bool:
    try:
        ipaddress.ip_address(value.strip().strip("[]"))
        return True
    except ValueError:
        return False


def clean_email(raw: str) -> str:
    value = normalize_whitespace(raw).lower().replace("mailto:", "")
    if not EMAIL_RE.fullmatch(value):
        raise ValueError("Invalid email format.")
    return value


def clean_domain(raw: str) -> str:
    value = normalize_whitespace(raw).lower()
    if not value:
        return ""
    if "://" not in value:
        value = "//" + value
    host = (urlparse(value).hostname or "").rstrip(".")
    if host.startswith("www."):
        host = host[4:]
    try:
        host = host.encode("idna").decode("ascii")
    except Exception:
        pass
    return host


def clean_url(raw: str) -> str:
    value = normalize_whitespace(raw)
    if not value:
        return ""
    if not re.match(r"^https?://", value, re.I):
        value = "https://" + value
    return value


def clean_username(raw: str) -> str:
    return normalize_whitespace(raw).lstrip("@")


def slugify(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", (value or "").lower()).strip("-")[:60] or "result"


def registrable_domain(host: str) -> str:
    """eTLD+1 (best effort, offline)."""
    global _TLDX
    host = clean_domain(host)
    if not host or is_ip(host):
        return host
    try:
        if _TLDX is None:
            import tldextract

            _TLDX = tldextract.TLDExtract(suffix_list_urls=(), cache_dir=None)
        ext = _TLDX(host)
        if ext.domain and ext.suffix:
            return f"{ext.domain}.{ext.suffix}"
    except Exception:
        _TLDX = False  # do not retry a broken extractor
    parts = host.split(".")
    if len(parts) >= 3 and ".".join(parts[-2:]) in _MULTI_SUFFIXES:
        return ".".join(parts[-3:])
    return ".".join(parts[-2:]) if len(parts) >= 2 else host


def domain_root_label(host: str) -> str:
    reg = registrable_domain(host)
    return reg.split(".")[0] if reg else ""


def brandify(label: str) -> str:
    """'acme-plumbing' -> 'Acme Plumbing'."""
    parts = [p for p in re.split(r"[-_\s]+", label) if p]
    return " ".join(p.capitalize() for p in parts)


def looks_like_company(value: str) -> bool:
    tokens = {t.strip(".,").lower() for t in normalize_whitespace(value).split()}
    return bool(tokens & COMPANY_MARKERS)


def is_person_like_name(value: str) -> bool:
    cleaned = normalize_whitespace(value)
    if not cleaned or re.search(r"[\d@/:]", cleaned) or looks_like_company(cleaned):
        return False
    words = cleaned.split()
    if not 2 <= len(words) <= 4:
        return False
    return all(re.fullmatch(r"[A-Za-z][A-Za-z'’.\-]+", w) for w in words)


def classify_query_label(query: str, kind: str) -> str:
    if kind in {"email", "phone", "domain", "url", "number", "username", "ip"}:
        return kind
    if looks_like_company(query):
        return "company"
    if is_person_like_name(query):
        return "person"
    return "entity"


def detect_phone(value: str, default_region: Optional[str] = None) -> bool:
    v = normalize_whitespace(value)
    if not re.fullmatch(r"\+?[\d\s().\-/]{6,}", v):
        return False
    digits = re.sub(r"\D", "", v)
    if not 7 <= len(digits) <= 15:
        return False
    if v.startswith("+") or v.startswith("00"):
        try:
            parsed = phonenumbers.parse("+" + digits.lstrip("0") if v.startswith("00") else v, None)
            return phonenumbers.is_possible_number(parsed)
        except Exception:
            return False
    region = (default_region or "").upper() or None
    if region:
        try:
            if phonenumbers.is_possible_number(phonenumbers.parse(v, region)):
                return True
        except Exception:
            pass
    # Pure digits that form a valid international number (country code included)
    if re.fullmatch(r"\d+", v) and len(digits) >= 10:
        try:
            if phonenumbers.is_valid_number(phonenumbers.parse("+" + digits, None)):
                return True
        except Exception:
            pass
        return False
    # Formatted (spaces, dashes, brackets) with phone-like length but no region known
    return bool(re.search(r"[\s().\-]", v)) and 10 <= len(digits) <= 13


def detect_type(raw: str, default_region: Optional[str] = None) -> str:
    v = normalize_whitespace(raw)
    if not v:
        return "unknown"
    if EMAIL_RE.fullmatch(v):
        return "email"
    if is_ip(v):
        return "ip"
    if re.match(r"^https?://\S+$", v, re.I) or re.match(r"^www\.\S+$", v, re.I):
        return "url"
    if DOMAIN_RE.fullmatch(v):
        return "domain"
    if re.match(r"^(?:[A-Za-z0-9\-]+\.)+[A-Za-z]{2,24}[/?#]\S*$", v):
        return "url"
    if detect_phone(v, default_region):
        return "phone"
    if v.startswith("@") and USERNAME_RE.fullmatch(v[1:]):
        return "username"
    if re.fullmatch(r"[\d\s.\-]+", v) and len(re.sub(r"\D", "", v)) >= 4:
        return "number"
    if " " in v:
        return "person_or_company"
    return "keyword"


def normalize_kind(category: str, raw_value: str, default_region: Optional[str] = None) -> str:
    mapping = {"person": "person_or_company", "company": "person_or_company"}
    if not category or category == "all":
        return detect_type(raw_value, default_region)
    return mapping.get(category, category)
