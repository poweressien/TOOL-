"""Transparent risk *indicators* (not verdicts) from public signals."""
from __future__ import annotations

from typing import Any, Dict, List


def build_risk(kind: str, ctx: Dict[str, Any], enrichment: Dict[str, Any], reputation: List[Dict[str, Any]],
               results: List[Dict[str, Any]]) -> Dict[str, Any]:
    signals: List[Dict[str, Any]] = []

    def add(signal: str, points: int, evidence: str = "") -> None:
        signals.append({"signal": signal, "points": points, "evidence": evidence})

    phone = enrichment.get("phone") or {}
    if phone.get("status") == "ok":
        if not phone.get("is_valid"):
            add("Number is not valid in the numbering plan", 3, phone.get("validity_note", ""))
        if phone.get("type") in {"Premium rate", "VoIP", "Shared cost", "Personal number"}:
            add(f"Line type: {phone['type']}", 2, "Often used for call-back or throw-away numbers")
    em = enrichment.get("email") or {}
    if em.get("is_disposable_domain"):
        add("Disposable e-mail domain", 5, em.get("domain", ""))
    if em.get("has_plus_tag"):
        add("Plus-addressed mailbox", 1, "Alias tag in the local part")
    for flag in (enrichment.get("url") or {}).get("flags", []):
        add(flag, 2)
    reg = enrichment.get("registration") or {}
    age = reg.get("age_days")
    if isinstance(age, int):
        if age < 30:
            add("Domain registered less than 30 days ago", 4, reg.get("created", ""))
        elif age < 180:
            add("Domain registered less than 6 months ago", 2, reg.get("created", ""))
    dns = enrichment.get("dns") or {}
    if dns.get("status") == "unresolved":
        add("Domain does not resolve in DNS", 2)
    mail = dns.get("mail_posture")
    if mail and mail.get("mx") and not mail.get("spf") and not mail.get("dmarc"):
        add("Mail enabled without SPF or DMARC", 1, "Easier to spoof")
    site = enrichment.get("website") or {}
    if site.get("status") == "ok" and site.get("security_headers", {}).get("score", "6/6").startswith("0/"):
        add("No browser security headers on the website", 1)
    if reputation:
        pts = min(8, 3 + 2 * len(reputation))
        add(f"{len(reputation)} public result(s) pair the identifier with warning terms", pts,
            ", ".join(sorted({t for r in reputation for t in r["terms"]})[:6]))
    if any(r.get("source") == "reputation" and r.get("exact_match") for r in results):
        add("Listed on a reputation/scam-report site", 2)
    if kind == "ip" and (enrichment.get("ip") or {}).get("network", {}).get("type", "").lower() == "reassigned":
        add("Address block is reassigned", 1)

    score = sum(s["points"] for s in signals)
    level = "high" if score >= 9 else "medium" if score >= 4 else "low"
    return {"risk_level": level, "score": score, "signals": signals,
            "notes": ["Indicators, not a verdict. Absence of signals does not mean safe.",
                      "Reputation terms come from search snippets and may refer to look-alike identifiers or "
                      "unrelated events."]}
