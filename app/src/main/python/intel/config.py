"""Configuration, secrets and shared constants."""
from __future__ import annotations

import os
from typing import Dict, Optional

try:  # python-dotenv is optional at runtime
    from dotenv import load_dotenv

    load_dotenv()
except Exception:  # pragma: no cover
    pass

USER_AGENT = "Mozilla/5.0 (compatible; EntityIntelligence/2.2; public-source research)"
HTTP_TIMEOUT = 12
MAX_PAGE_BYTES = 1_500_000

# Scan modes. "deep" additionally fetches public pages to confirm evidence and
# runs slower enrichment (certificate transparency, web archive, extra DNS).
MODES: Dict[str, Dict] = {
    "standard": {"max_queries": 3, "per_query": 8, "fetch_pages": 0, "deep": False, "pivot_rounds": 0, "site_probe": False,
                 "label": "Standard", "help": "Fast. A few targeted searches plus offline/cheap enrichment."},
    "extended": {"max_queries": 6, "per_query": 10, "fetch_pages": 0, "deep": False, "pivot_rounds": 1, "site_probe": False,
                 "label": "Extended", "help": "More query variants and more results per query."},
    "deep": {"max_queries": 18, "per_query": 15, "fetch_pages": 16, "deep": True, "pivot_rounds": 2, "site_probe": True,
             "label": "Deep", "help": "Maximum recall: 18 query variants, 15 results each, two follow-up (pivot) search "
                                       "rounds on discovered organisations, page-level verification of the top 16 pages, "
                                       "official-site probing, certificate transparency, web archive and DNS."},
}

_ST = None
_ST_TRIED = False


def _streamlit():
    global _ST, _ST_TRIED
    if not _ST_TRIED:
        _ST_TRIED = True
        try:
            import streamlit as st  # noqa: WPS433

            _ST = st
        except Exception:
            _ST = None
    return _ST


def get_secret(name: str, default: str = "") -> str:
    """Read a key from Streamlit secrets first, then the environment.

    NOTE: ``st.secrets`` is not a ``dict`` subclass, so the previous
    ``isinstance(..., dict)`` check silently ignored Streamlit Cloud secrets.
    """
    st = _streamlit()
    if st is not None:
        try:
            if name in st.secrets:
                value = st.secrets[name]
                if value not in (None, ""):
                    return str(value).strip()
        except Exception:
            pass
    value = os.getenv(name)
    if value not in (None, ""):
        return str(value).strip()
    return default


KEY_FIELDS = {"serpapi": "SERPAPI_API_KEY", "google_key": "GOOGLE_CSE_KEY", "google_cx": "GOOGLE_CSE_CX",
              "numverify": "NUMVERIFY_API_KEY", "brave": "BRAVE_API_KEY"}


def provider_keys(override: Optional[Dict[str, str]] = None) -> Dict[str, str]:
    """Keys from secrets/env, overridden per call by ``override`` (e.g. keys typed into the UI).

    Overrides are passed explicitly instead of being stored globally so that one
    visitor's keys can never leak to another session on a shared deployment.
    """
    keys = {name: get_secret(env) for name, env in KEY_FIELDS.items()}
    for name, value in (override or {}).items():
        if name in keys and value and str(value).strip():
            keys[name] = str(value).strip()
    return keys


def provider_status(override: Optional[Dict[str, str]] = None) -> Dict[str, bool]:
    keys = provider_keys(override)
    try:
        from .search import DDGS

        ddg = DDGS is not None
    except Exception:  # pragma: no cover
        ddg = False
    return {
        "serpapi": bool(keys["serpapi"]),
        "google_cse": bool(keys["google_key"] and keys["google_cx"]),
        "brave": bool(keys["brave"]),
        "duckduckgo": ddg,
        "numverify": bool(keys["numverify"]),
    }
