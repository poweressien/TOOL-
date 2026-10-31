"""Export helpers shared by the web app, desktop app and CLI."""
from __future__ import annotations

import json
from io import BytesIO
from typing import Any, Dict, List

import csv
import io


def evidence_rows(result: Dict[str, Any]) -> List[Dict[str, Any]]:
    rows = []
    for r in result.get("ranked_results", []):
        rows.append({"title": r.get("title", ""), "link": r.get("link", ""), "domain": r.get("domain", ""),
                     "source": r.get("source", ""), "score": r.get("score", 0), "confidence": r.get("confidence", ""),
                     "evidence_level": r.get("evidence_level", ""), "exact_match": r.get("exact_match", False),
                     "why": r.get("explanation", ""), "snippet": r.get("snippet", ""),
                     "found_by_queries": len(r.get("queries", []))})
    return rows


def to_json_bytes(data: Any) -> bytes:
    return json.dumps(data, indent=2, ensure_ascii=False, default=str).encode("utf-8")


def _flat(v: Any) -> Any:
    return ", ".join(str(x) for x in v) if isinstance(v, list) else v


def to_csv_bytes(result: Dict[str, Any]) -> bytes:
    rows = evidence_rows(result)
    buf = io.StringIO()
    cols = list(rows[0].keys()) if rows else ["title", "link", "domain", "source", "score", "confidence"]
    w = csv.DictWriter(buf, fieldnames=cols)
    w.writeheader()
    for r in rows:
        w.writerow(r)
    return buf.getvalue().encode("utf-8")


def _sheet(wb, title: str, rows: List[Dict[str, Any]]) -> None:
    ws = wb.create_sheet(title)
    cols: List[str] = []
    for r in rows:
        for k in r:
            if k not in cols:
                cols.append(k)
    ws.append(cols)
    for r in rows:
        ws.append([_flat(r.get(c, "")) if not isinstance(r.get(c, ""), (dict, set)) else str(r.get(c)) for c in cols])


def to_excel_bytes(result: Dict[str, Any]) -> bytes:
    from openpyxl import Workbook

    wb = Workbook()
    wb.remove(wb.active)
    summary = [("Input", result.get("input")), ("Type", result.get("type")), ("Mode", result.get("mode")),
               ("Confidence", f"{result['confidence']['score']} ({result['confidence']['label']})"),
               ("Risk indicators", result["risk"]["risk_level"]), ("Headline", result["narrative"]["headline"])]
    _sheet(wb, "summary", [{"field": k, "value": v} for k, v in summary])
    keep = {"name", "type", "confidence", "confidence_label", "independent_sources", "source_domains", "aliases", "roles", "employers"}
    _sheet(wb, "entities", [{k: v for k, v in e.items() if k in keep} for e in result.get("entities", [])])
    _sheet(wb, "claims", list(result.get("claims", [])))
    _sheet(wb, "evidence", evidence_rows(result))
    _sheet(wb, "score_breakdown", [{"link": r["link"], **b} for r in result.get("ranked_results", []) for b in r.get("score_breakdown", [])])
    _sheet(wb, "risk_signals", list(result["risk"]["signals"]))
    buf = BytesIO()
    wb.save(buf)
    return buf.getvalue()


def bulk_row(value: str, result: Dict[str, Any]) -> Dict[str, Any]:
    ent = (result.get("entities") or [None])[0]
    return {"input": value, "type": result.get("type"), "primary_entity": ent["name"] if ent else "",
            "entity_type": ent["type"] if ent else "", "confidence_score": result["confidence"]["score"],
            "confidence": result["confidence"]["label"], "risk": result["risk"]["risk_level"],
            "usage": result["likely_usage"][0] if result.get("likely_usage") else "",
            "public_results": len(result.get("ranked_results", [])),
            "direct_evidence": sum(1 for r in result.get("ranked_results", []) if r.get("evidence_level") == "direct"),
            "headline": result["narrative"]["headline"], "errors": "; ".join(result.get("errors", []))}
