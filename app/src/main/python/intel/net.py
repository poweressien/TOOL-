"""Safe, cached HTTP helpers.

Every outbound request that touches a user-supplied host goes through
``safe_get``: it only allows http(s), re-validates every redirect hop and
refuses private / loopback / link-local targets (SSRF protection - important
when the app is deployed on a public host).
"""
from __future__ import annotations

import ipaddress
import json
import socket
import threading
import time
from dataclasses import dataclass, field
from typing import Any, Dict, Optional
from urllib.parse import urljoin, urlparse
from urllib import robotparser

import requests

from .config import HTTP_TIMEOUT, MAX_PAGE_BYTES, USER_AGENT

_CACHE: Dict[str, Any] = {}
_CACHE_LOCK = threading.Lock()
_ROBOTS: Dict[str, Optional[robotparser.RobotFileParser]] = {}


def cache_get(key: str, ttl: int = 900):
    with _CACHE_LOCK:
        hit = _CACHE.get(key)
    if hit and time.time() - hit[0] < ttl:
        return hit[1]
    return None


def cache_set(key: str, value: Any) -> None:
    with _CACHE_LOCK:
        if len(_CACHE) > 600:
            _CACHE.clear()
        _CACHE[key] = (time.time(), value)


def clear_cache() -> None:
    with _CACHE_LOCK:
        _CACHE.clear()
    _ROBOTS.clear()


def is_public_host(host: str) -> bool:
    host = (host or "").strip("[]").strip()
    if not host:
        return False
    try:
        infos = socket.getaddrinfo(host, None)
    except Exception:
        return False
    ips = {info[4][0].split("%")[0] for info in infos}
    if not ips:
        return False
    for ip in ips:
        try:
            addr = ipaddress.ip_address(ip)
        except ValueError:
            return False
        if (addr.is_private or addr.is_loopback or addr.is_link_local or addr.is_reserved
                or addr.is_multicast or addr.is_unspecified):
            return False
    return True


@dataclass
class Fetched:
    url: str
    final_url: str
    status: int
    headers: Dict[str, str]
    text: str
    content_type: str = ""
    redirects: list = field(default_factory=list)


def safe_get(url: str, params: Optional[Dict[str, Any]] = None, headers: Optional[Dict[str, str]] = None,
             timeout: int = HTTP_TIMEOUT, max_bytes: int = MAX_PAGE_BYTES, max_redirects: int = 4,
             check_public: bool = True) -> Optional[Fetched]:
    hdrs = {"User-Agent": USER_AGENT, "Accept": "text/html,application/json;q=0.9,*/*;q=0.8"}
    hdrs.update(headers or {})
    current = url
    chain = []
    try:
        for _ in range(max_redirects + 1):
            parsed = urlparse(current)
            if parsed.scheme not in ("http", "https") or not parsed.hostname:
                return None
            if check_public and not is_public_host(parsed.hostname):
                return None
            resp = requests.get(current, params=params if current == url else None, headers=hdrs,
                                timeout=timeout, allow_redirects=False, stream=True)
            if resp.status_code in (301, 302, 303, 307, 308) and resp.headers.get("Location"):
                chain.append(current)
                current = urljoin(current, resp.headers["Location"])
                resp.close()
                continue
            body = b""
            for chunk in resp.iter_content(16384):
                body += chunk
                if len(body) >= max_bytes:
                    break
            resp.close()
            encoding = resp.encoding or "utf-8"
            text = body.decode(encoding, errors="replace")
            return Fetched(url=url, final_url=current, status=resp.status_code,
                           headers={k.lower(): v for k, v in resp.headers.items()}, text=text,
                           content_type=resp.headers.get("Content-Type", ""), redirects=chain)
    except Exception:
        return None
    return None


def http_get_json(url: str, params: Optional[Dict[str, Any]] = None, headers: Optional[Dict[str, str]] = None,
                  ttl: int = 900, timeout: int = HTTP_TIMEOUT) -> Optional[Any]:
    key = f"json:{url}:{json.dumps(params, sort_keys=True, default=str)}"
    cached = cache_get(key, ttl)
    if cached is not None:
        return cached or None
    resp = safe_get(url, params=params, headers={"Accept": "application/json", **(headers or {})},
                    timeout=timeout, max_bytes=6_000_000)
    data = None
    if resp and resp.status < 400:
        try:
            data = json.loads(resp.text)
        except Exception:
            data = None
    cache_set(key, data if data is not None else False)
    return data


def http_get_text(url: str, headers: Optional[Dict[str, str]] = None, ttl: int = 900,
                  timeout: int = HTTP_TIMEOUT) -> Optional[str]:
    key = f"text:{url}"
    cached = cache_get(key, ttl)
    if cached is not None:
        return cached or None
    resp = safe_get(url, headers=headers, timeout=timeout)
    text = resp.text if resp and resp.status < 400 else None
    cache_set(key, text if text is not None else False)
    return text


def robots_allowed(url: str) -> bool:
    """Best-effort robots.txt check. Unknown / unreachable => allowed."""
    parsed = urlparse(url)
    base = f"{parsed.scheme}://{parsed.netloc}"
    if base not in _ROBOTS:
        parser = None
        resp = safe_get(base + "/robots.txt", timeout=6, max_bytes=200_000)
        if resp and resp.status == 200 and "html" not in resp.content_type.lower():
            parser = robotparser.RobotFileParser()
            parser.parse(resp.text.splitlines())
        _ROBOTS[base] = parser
    parser = _ROBOTS[base]
    if parser is None:
        return True
    try:
        return parser.can_fetch(USER_AGENT, url) or parser.can_fetch("*", url)
    except Exception:
        return True
