"""Plain-language narrative, findings, limitations and next steps built from structured facts."""
from __future__ import annotations

from typing import Any, Dict, List


def _age_text(days) -> str:
    if not isinstance(days, int):
        return ""
    if days < 60:
        return f"{days} days"
    if days < 730:
        return f"{round(days / 30)} months"
    return f"{round(days / 365.25, 1)} years"


def build_narrative(r: Dict[str, Any]) -> Dict[str, Any]:
    kind = r["type"]
    value = r["input"]
    conf = r["confidence"]
    ent = (r.get("entities") or [None])[0]
    enr = r.get("enrichment", {})
    results = r.get("ranked_results", [])
    direct = [x for x in results if x.get("evidence_level") == "direct" and x.get("source") != "people_search"]
    ind_domains = len({x["registrable_domain"] for x in direct})
    findings: List[str] = []
    limits: List[str] = []
    nxt: List[str] = []
    paras: List[str] = []

    # ---- what the input is (technical facts first)
    ph = enr.get("phone") or {}
    if kind == "phone" and ph.get("status") == "ok":
        s = (f"{ph['international']} is a {'valid' if ph['is_valid'] else 'not fully valid'} "
             f"{ph['type'].lower()} number in {ph['country']}")
        if ph.get("location") and ph["location"] != ph["country"]:
            s += f" (area: {ph['location']})"
        if ph.get("carrier_original"):
            s += f", originally allocated to {ph['carrier_original']}"
        paras.append(s + ".")
        findings.append("Numbering-plan data is deterministic; it shows allocation, not the current subscriber.")
    elif kind == "phone":
        paras.append(f"“{value}” looks like a phone number but could not be parsed reliably.")
        nxt.append("Re-run with a country code (e.g. +234…) or set a default region in the sidebar.")
    elif kind == "email":
        e = enr.get("email", {})
        s = f"{value} is a mailbox at {e.get('domain', '?')}"
        s += " (free mail provider)" if e.get("is_free_provider") else " (own/organisation domain)"
        if e.get("is_role_account"):
            s += ", and the local part is a role account (shared inbox), not an individual"
        paras.append(s + ".")
    elif kind in {"domain", "url"}:
        reg = enr.get("registration", {})
        bits = [f"{r.get('domain', value)} is a public web domain"]
        if reg.get("created"):
            bits.append(f"registered {reg['created'][:10]} ({_age_text(reg.get('age_days'))} ago)")
        if reg.get("registrar"):
            bits.append(f"via {reg['registrar']}")
        paras.append(", ".join(bits) + ".")
        if reg.get("registrant_redacted"):
            findings.append("Registrant details are redacted/private, as is normal under current privacy rules.")
        dns = enr.get("dns", {})
        if dns.get("status") == "ok" and dns.get("mail_posture") is not None:
            mp = dns["mail_posture"]
            findings.append("Mail: " + ("MX present" if mp["mx"] else "no MX") + f", SPF {'yes' if mp['spf'] else 'no'}, "
                            f"DMARC {'yes' if mp['dmarc'] else 'no'}.")
        site = enr.get("website", {})
        if site.get("title"):
            findings.append(f"Website title: “{site['title'][:90]}”" +
                            (f"; technologies: {', '.join(site['technologies'][:5])}." if site.get("technologies") else "."))
        ct = enr.get("certificates", {})
        if ct.get("status") == "ok" and ct.get("hostname_count"):
            findings.append(f"Certificate transparency lists {ct['hostname_count']} hostname(s) under this domain.")
        wb = enr.get("wayback", {})
        if wb.get("status") == "ok":
            findings.append(f"Earliest web-archive snapshot: {wb['first_snapshot']}.")
    elif kind == "ip":
        net = (enr.get("ip") or {}).get("network", {})
        paras.append(f"{value} belongs to the network “{net.get('name', 'unknown')}”"
                     f"{' in ' + net['country'] if net.get('country') else ''}.")
    else:
        paras.append(f"“{value}” was researched across public web sources.")

    # ---- what public sources say
    if not results:
        paras.append("The search providers returned no usable public results, so nothing can be said about "
                     "who or what this refers to.")
        if r.get("diagnostics", {}).get("degraded"):
            limits.append("Search providers returned errors or empty responses (rate limits are common). "
                          "Retry later or configure SerpAPI / Google CSE keys.")
    else:
        if ent and ent["confidence"] >= 25:
            paras.append(
                f"Across {len(results)} public result(s) ({len(direct)} containing the exact value, from {ind_domains} "
                f"independent site(s)), the most consistent name is “{ent['name']}”"
                f"{' (' + ent['type'] + ')' if ent['type'] != 'unknown' else ''}, with {ent['confidence_label']} "
                f"confidence ({ent['confidence']}/100).")
            if ent.get("contested"):
                alt = r["entities"][1]["name"]
                limits.append(f"“{alt}” scored almost as high - the value may be shared or ambiguous.")
            if ent.get("roles"):
                findings.append(f"Role mentioned alongside the name: {', '.join(ent['roles'])}.")
        else:
            paras.append(f"{len(results)} public result(s) were found but none points consistently to one "
                         f"named entity.")
        if r.get("usage"):
            top = r["usage"][0]
            paras.append(f"The surrounding wording most often relates to “{top['label']}” "
                         f"({top['confidence_label']} confidence).")
    attribution = next((c for c in r.get("claims", []) if c["type"] == "attribution"), None)
    if attribution:
        findings.append(attribution["claim"] + (" " + attribution["caveat"] if attribution["caveat"] else ""))
    rep = r.get("reputation_signals", [])
    if rep:
        findings.append(f"{len(rep)} direct result(s) combine this value with warning terms "
                        f"({', '.join(sorted({t for x in rep for t in x['terms']})[:5])}). Review them before trusting the value.")
    elif results:
        findings.append("No direct public result pairs this value with scam/spam/fraud vocabulary.")
    contacts = r.get("public_contacts", {})
    if contacts.get("emails") or contacts.get("phones"):
        findings.append(f"Public organisation contact points on its own pages: "
                        f"{len(contacts.get('emails', []))} e-mail(s), {len(contacts.get('phones', []))} phone(s).")

    # ---- limitations
    if kind in {"phone", "email"} and not (attribution and attribution["confidence"] >= 60):
        limits.append("Public data rarely identifies the holder of a phone number or personal mailbox; "
                      "this tool does not query private, leaked or data-broker records.")
    if r.get("excluded_people_search"):
        limits.append(f"{r['excluded_people_search']} data-broker/people-search result(s) were ignored for identity inference.")
    if r.get("mode") != "deep":
        nxt.append("Run a Deep scan to confirm hits on the actual pages and add certificate/archive/DNS checks.")
    if r.get("ranked_results") and not direct and kind in {"phone", "email", "number", "ip"}:
        limits.append("None of the results contain the exact value; all hits are fuzzy matches and were down-scored.")
    if direct:
        nxt.append("Open the top direct-evidence sources and verify context manually before relying on them.")
    if kind in {"domain", "url"} and enr.get("registration", {}).get("registrant_redacted"):
        nxt.append("To identify the operator, use the site's own imprint/contact page, company registries, or the registrar's abuse process.")
    limits.append("Confidence reflects strength and consistency of public evidence, not proof of identity.")

    headline = _headline(r, ent, conf)
    return {"headline": headline, "paragraphs": paras, "key_findings": findings, "limitations": limits,
            "next_steps": nxt, "text": headline + "\n\n" + "\n\n".join(paras)}


def _headline(r: Dict[str, Any], ent, conf: Dict[str, Any]) -> str:
    label = conf["label"].title()
    if ent and ent["confidence"] >= 25:
        return f"{label} confidence ({conf['score']}/100): {r['input']} → {ent['name']}"
    return f"{label} confidence ({conf['score']}/100): no clear public attribution for {r['input']}"


def to_markdown(r: Dict[str, Any]) -> str:
    n = r["narrative"]
    lines = [f"# Entity Intelligence report: {r['input']}", "", f"**Type:** {r['type']}  ",
             f"**Mode:** {r.get('mode', 'standard')}  ", f"**Confidence:** {r['confidence']['score']}/100 ({r['confidence']['label']})  ",
             f"**Risk indicators:** {r['risk']['risk_level']}", "", "## Summary", "", *n["paragraphs"], ""]
    for title, key in (("Key findings", "key_findings"), ("Limitations", "limitations"), ("Suggested next steps", "next_steps")):
        if n[key]:
            lines += [f"## {title}", ""] + [f"- {x}" for x in n[key]] + [""]
    if r.get("claims"):
        lines += ["## Claims and confidence", ""]
        for c in r["claims"]:
            lines.append(f"- **{c['claim']}** - {c['confidence']}/100 ({c['confidence_label']}), "
                         f"{c['independent_sources']} independent source(s)." + (f" _{c['caveat']}_" if c["caveat"] else ""))
        lines.append("")
    if r.get("entities"):
        lines += ["## Entity profiles", ""]
        for e in r["entities"][:5]:
            lines.append(f"- **{e['name']}** ({e['type']}) - {e['confidence']}/100, {e['independent_sources']} source(s): "
                         + ", ".join(e["source_domains"][:4]))
        lines.append("")
    lines += ["## Top evidence", ""]
    for x in r.get("ranked_results", [])[:10]:
        lines.append(f"- [{x['title'] or x['link']}]({x['link']}) - score {x['score']} ({x['evidence_level']}). {x['explanation']}")
    lines += ["", "---", "_Public-source analysis only. Not identity verification._"]
    return "\n".join(lines)
