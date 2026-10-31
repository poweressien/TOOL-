"""Tiny DuckDuckGo shim used on Android (the real `ddgs` package needs native code).

Provides the subset of the DDGS API the engine uses: text(), news(), images().
Uses DuckDuckGo's public HTML endpoint through requests + BeautifulSoup.
"""
from __future__ import annotations

from typing import Any, Dict, Iterator, List
from urllib.parse import parse_qs, unquote, urlparse

import requests
from bs4 import BeautifulSoup

_UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"


def _real_url(href: str) -> str:
    if href.startswith("//"):
        href = "https:" + href
    q = parse_qs(urlparse(href).query)
    if "uddg" in q:
        return unquote(q["uddg"][0])
    return href


class DDGS:
    def __init__(self, *a: Any, **k: Any) -> None:
        pass

    def _html(self, query: str, limit: int) -> List[Dict[str, str]]:
        out: List[Dict[str, str]] = []
        r = requests.post("https://html.duckduckgo.com/html/", data={"q": query}, headers={"User-Agent": _UA}, timeout=15)
        if r.status_code != 200:
            raise RuntimeError(f"DuckDuckGo returned HTTP {r.status_code}")
        soup = BeautifulSoup(r.text, "html.parser")
        for res in soup.select("div.result"):
            a = res.select_one("a.result__a")
            if not a or not a.get("href"):
                continue
            sn = res.select_one(".result__snippet")
            out.append({"title": a.get_text(" ", strip=True), "href": _real_url(a["href"]),
                        "body": sn.get_text(" ", strip=True) if sn else ""})
            if len(out) >= limit:
                break
        return out

    def text(self, query: str, max_results: int = 10, **_: Any) -> Iterator[Dict[str, str]]:
        return iter(self._html(query, max_results))

    def news(self, query: str, max_results: int = 10, **_: Any) -> Iterator[Dict[str, str]]:
        res = self._html(query + " news", max_results)
        return iter([{"title": x["title"], "url": x["href"], "source": urlparse(x["href"]).netloc, "date": "", "body": x["body"]} for x in res])

    def images(self, query: str, max_results: int = 10, **_: Any) -> Iterator[Dict[str, str]]:
        return iter([])
