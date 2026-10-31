"""SuperSmart website - Flask front end for the `intel` research engine.

Run:  python webapp.py        (dev)    |   waitress-serve --port=8000 webapp:app   (production)
"""
from __future__ import annotations

import json
import os
import secrets
import sqlite3
import threading
import time
from collections import defaultdict, deque
from typing import Any, Dict, List
from urllib.parse import urlparse

from flask import (Flask, Response, abort, jsonify, redirect, render_template, request, url_for)

from intel import __version__
from intel.config import MODES, provider_status
from intel.detect import detect_type, slugify
from intel.export import to_csv_bytes, to_excel_bytes, to_json_bytes
from intel.narrative import to_markdown
from intel.scan import run_scan
from intel.search import test_providers

BASE = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.environ.get("DB_PATH", os.path.join(BASE, "data", "scans.db"))
SITE_NAME = os.environ.get("SITE_NAME", "SuperSmart")
SITE_TAGLINE = os.environ.get("SITE_TAGLINE", "Search anything. See the evidence.")
RATE_LIMIT = int(os.environ.get("RATE_LIMIT_PER_HOUR", "40"))
MAX_WORKERS = int(os.environ.get("MAX_CONCURRENT_SCANS", "4"))

app = Flask(__name__)
app.config["JSON_SORT_KEYS"] = False

CATEGORIES = [
    {"id": "all", "name": "Everything", "icon": "✦", "hint": "Auto-detect the type", "example": "Apple Inc.",
     "desc": "Paste anything. The engine detects whether it is a phone, e-mail, domain, URL, handle, IP or a name."},
    {"id": "person", "name": "Person / Company", "icon": "👤", "hint": "Names & brands", "example": "Dangote Group",
     "desc": "News, Wikipedia & Wikidata, official site discovery, social profiles, roles and corroborated entity profiles."},
    {"id": "phone", "name": "Phone", "icon": "📞", "hint": "Any format", "example": "+234 803 123 4567",
     "desc": "Carrier, line type, country & region, time zones, plus public pages that publish the exact number."},
    {"id": "email", "name": "E-mail", "icon": "✉️", "hint": "Address", "example": "info@example.com",
     "desc": "Mail posture (MX/SPF/DMARC), domain data, public Gravatar, handle presence and pages that show the address."},
    {"id": "domain", "name": "Domain", "icon": "🌐", "hint": "example.com", "example": "example.com",
     "desc": "WHOIS/RDAP, DNS, TLS certificate, tech stack, archive history, sitemap, subdomains and look-alike domains."},
    {"id": "url", "name": "URL", "icon": "🔗", "hint": "Full link", "example": "https://www.microsoft.com",
     "desc": "Link anatomy, redirects, shorteners, punycode tricks, page fingerprint and security headers."},
    {"id": "username", "name": "Username", "icon": "@", "hint": "Handle", "example": "torvalds",
     "desc": "Checks 30 public platforms, pulls the public GitHub profile, repos and Hacker News activity."},
    {"id": "ip", "name": "IP address", "icon": "📡", "hint": "IPv4 / IPv6", "example": "8.8.8.8",
     "desc": "Network owner, range and country (RDAP), reverse DNS, and public pages that mention the address."},
    {"id": "number", "name": "Number / ID", "icon": "#", "hint": "Any numeric ID", "example": "RC123456",
     "desc": "Exact-match search for registration numbers, account or reference IDs across public sources."},
]
CAT_IDS = {c["id"] for c in CATEGORIES}
MODE_LIST = [{"id": k, **v} for k, v in MODES.items()]


# ------------------------------------------------------------------ storage
_db_lock = threading.Lock()


def db() -> sqlite3.Connection:
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    conn = sqlite3.connect(DB_PATH, timeout=15)
    conn.row_factory = sqlite3.Row
    conn.execute("""CREATE TABLE IF NOT EXISTS scans (
        id TEXT PRIMARY KEY, value TEXT, category TEXT, mode TEXT, kind TEXT, status TEXT,
        confidence INTEGER, risk TEXT, headline TEXT, created REAL, seconds REAL, result TEXT)""")
    return conn


def save_scan(row: Dict[str, Any]) -> None:
    with _db_lock, db() as c:
        c.execute("""INSERT OR REPLACE INTO scans (id,value,category,mode,kind,status,confidence,risk,headline,created,seconds,result)
                     VALUES (:id,:value,:category,:mode,:kind,:status,:confidence,:risk,:headline,:created,:seconds,:result)""", row)


def load_scan(scan_id: str):
    with _db_lock, db() as c:
        return c.execute("SELECT * FROM scans WHERE id=?", (scan_id,)).fetchone()


def recent(limit: int = 12, q: str = "") -> List[sqlite3.Row]:
    with _db_lock, db() as c:
        if q:
            return c.execute("SELECT id,value,category,mode,kind,confidence,risk,headline,created FROM scans "
                             "WHERE status='done' AND value LIKE ? ORDER BY created DESC LIMIT ?", (f"%{q}%", limit)).fetchall()
        return c.execute("SELECT id,value,category,mode,kind,confidence,risk,headline,created FROM scans "
                         "WHERE status='done' ORDER BY created DESC LIMIT ?", (limit,)).fetchall()


# ------------------------------------------------------------------ jobs
JOBS: Dict[str, Dict[str, Any]] = {}
_jobs_lock = threading.Lock()
_slots = threading.BoundedSemaphore(MAX_WORKERS)
_hits: Dict[str, deque] = defaultdict(deque)


def client_ip() -> str:
    return (request.headers.get("X-Forwarded-For", request.remote_addr or "?").split(",")[0]).strip()


def rate_ok(ip: str) -> bool:
    now = time.time()
    q = _hits[ip]
    while q and now - q[0] > 3600:
        q.popleft()
    if len(q) >= RATE_LIMIT:
        return False
    q.append(now)
    return True


def start_job(value: str, category: str, mode: str, region: str, keys: Dict[str, str]) -> str:
    job_id = secrets.token_urlsafe(8)
    job = {"id": job_id, "value": value, "category": category, "mode": mode, "status": "queued", "log": [],
           "started": time.time(), "error": ""}
    with _jobs_lock:
        JOBS[job_id] = job
    save_scan({"id": job_id, "value": value, "category": category, "mode": mode, "kind": "", "status": "running",
               "confidence": 0, "risk": "", "headline": "", "created": time.time(), "seconds": 0, "result": ""})

    def say(msg: str) -> None:
        job["log"].append({"t": round(time.time() - job["started"], 1), "msg": msg})

    def work() -> None:
        with _slots:
            job["status"] = "running"
            say(f"Starting {MODES[mode]['label']} scan for “{value}”")
            try:
                res = run_scan(value, category=category, mode=mode, default_region=region or None, progress=say, api_keys=keys)
                job["status"] = "done"
                save_scan({"id": job_id, "value": value, "category": category, "mode": mode, "kind": res["type"],
                           "status": "done", "confidence": res["confidence"]["score"], "risk": res["risk"]["risk_level"],
                           "headline": res["narrative"]["headline"], "created": job["started"],
                           "seconds": res.get("seconds", 0), "result": json.dumps(res, default=str)})
            except Exception as exc:  # never show a raw traceback
                job["status"] = "error"
                job["error"] = f"{type(exc).__name__}: {exc}"[:300]
                save_scan({"id": job_id, "value": value, "category": category, "mode": mode, "kind": "", "status": "error",
                           "confidence": 0, "risk": "", "headline": job["error"], "created": job["started"],
                           "seconds": 0, "result": ""})

    threading.Thread(target=work, daemon=True).start()
    return job_id


# ------------------------------------------------------------------ template helpers
@app.template_filter("safe_url")
def safe_url(u: str) -> str:
    return u if isinstance(u, str) and u.lower().startswith(("http://", "https://")) else "#"


@app.template_filter("ago")
def ago(ts: float) -> str:
    d = time.time() - float(ts or 0)
    for unit, n in (("d", 86400), ("h", 3600), ("m", 60)):
        if d >= n:
            return f"{int(d // n)}{unit} ago"
    return "just now"


@app.template_filter("band_class")
def band_class(label: str) -> str:
    return {"high": "good", "medium": "mid", "low": "warn", "very low": "bad"}.get(label, "mid")


@app.template_filter("pretty")
def pretty(v: Any) -> str:
    return json.dumps(v, indent=2, ensure_ascii=False, default=str)


@app.context_processor
def inject():
    return {"SITE_NAME": SITE_NAME, "SITE_TAGLINE": SITE_TAGLINE, "VERSION": __version__, "CATEGORIES": CATEGORIES,
            "MODE_LIST": MODE_LIST}


# ------------------------------------------------------------------ pages
@app.route("/")
def home():
    return render_template("home.html", recent=recent(8), cat="all", mode="standard")


@app.route("/c/<cat>")
def category(cat: str):
    info = next((c for c in CATEGORIES if c["id"] == cat), None)
    if not info:
        abort(404)
    return render_template("category.html", info=info, cat=cat, mode="standard", recent=recent(6))


@app.route("/search")
def search():
    q = (request.args.get("q") or "").strip()
    cat = request.args.get("cat", "all")
    mode = request.args.get("mode", "standard")
    if not q:
        return redirect(url_for("home"))
    cat = cat if cat in CAT_IDS else "all"
    mode = mode if mode in MODES else "standard"
    # Re-use a fresh identical scan (<10 min) instead of burning search quota
    with _db_lock, db() as c:
        row = c.execute("SELECT id FROM scans WHERE value=? AND category=? AND mode=? AND status='done' AND created>? "
                        "ORDER BY created DESC LIMIT 1", (q, cat, mode, time.time() - 600)).fetchone()
    if row and not request.args.get("fresh"):
        return redirect(url_for("result", scan_id=row["id"]))
    return render_template("starting.html", q=q, cat=cat, mode=mode, region=request.args.get("region", ""))


@app.route("/api/scan", methods=["POST"])
def api_scan():
    data = request.get_json(silent=True) or {}
    value = str(data.get("q", "")).strip()[:300]
    cat = data.get("cat", "all") if data.get("cat") in CAT_IDS else "all"
    mode = data.get("mode", "standard") if data.get("mode") in MODES else "standard"
    region = str(data.get("region", ""))[:2].upper()
    keys = {k: str(v)[:200] for k, v in (data.get("keys") or {}).items() if k in
            {"serpapi", "google_key", "google_cx", "brave", "numverify"} and v}
    if not value:
        return jsonify(error="Enter something to search."), 400
    if not rate_ok(client_ip()):
        return jsonify(error=f"Rate limit reached ({RATE_LIMIT} scans/hour). Try again later."), 429
    return jsonify(id=start_job(value, cat, mode, region, keys))


@app.route("/api/job/<job_id>")
def api_job(job_id: str):
    job = JOBS.get(job_id)
    if not job:
        row = load_scan(job_id)
        if not row:
            return jsonify(status="missing"), 404
        return jsonify(status=row["status"], log=[], elapsed=row["seconds"], error=row["headline"] if row["status"] == "error" else "")
    since = int(request.args.get("since", 0))
    return jsonify(status=job["status"], log=job["log"][since:], n=len(job["log"]),
                   elapsed=round(time.time() - job["started"], 1), error=job["error"])


@app.route("/api/detect")
def api_detect():
    q = (request.args.get("q") or "").strip()
    return jsonify(type=detect_type(q, request.args.get("region") or None) if q else "")


@app.route("/api/providers", methods=["POST"])
def api_providers():
    data = request.get_json(silent=True) or {}
    keys = {k: str(v)[:200] for k, v in (data.get("keys") or {}).items() if v}
    if not rate_ok(client_ip()):
        return jsonify(error="Rate limit"), 429
    return jsonify(test=test_providers(keys), status=provider_status(keys))


@app.route("/r/<scan_id>")
def result(scan_id: str):
    row = load_scan(scan_id)
    if not row:
        abort(404)
    if row["status"] == "error":
        return render_template("error.html", row=row), 500
    if row["status"] != "done" or not row["result"]:
        return render_template("running.html", row=row, scan_id=scan_id)
    r = json.loads(row["result"])
    stats = {"direct": sum(1 for x in r["ranked_results"] if x.get("evidence_level") == "direct"),
             "domains": len({x["domain"] for x in r["ranked_results"]}),
             "extras_ok": sum(1 for v in (r.get("extras") or {}).values() if v.get("status") == "ok")}
    return render_template("result.html", r=r, scan_id=scan_id, row=row, stats=stats)


@app.route("/history")
def history():
    q = (request.args.get("q") or "").strip()
    return render_template("history.html", items=recent(100, q), q=q)


@app.route("/about")
def about():
    return render_template("about.html", providers=provider_status())


@app.route("/export/<scan_id>.<fmt>")
def export(scan_id: str, fmt: str):
    row = load_scan(scan_id)
    if not row or not row["result"]:
        abort(404)
    r = json.loads(row["result"])
    base = slugify(r["input"]) or "scan"
    if fmt == "json":
        return Response(to_json_bytes(r), mimetype="application/json", headers={"Content-Disposition": f"attachment; filename={base}.json"})
    if fmt == "csv":
        return Response(to_csv_bytes(r), mimetype="text/csv", headers={"Content-Disposition": f"attachment; filename={base}.csv"})
    if fmt == "xlsx":
        return Response(to_excel_bytes(r), mimetype="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        headers={"Content-Disposition": f"attachment; filename={base}.xlsx"})
    if fmt == "md":
        return Response(to_markdown(r), mimetype="text/markdown", headers={"Content-Disposition": f"attachment; filename={base}.md"})
    abort(404)


@app.route("/healthz")
def healthz():
    return jsonify(ok=True, version=__version__)


@app.errorhandler(404)
def nf(_e):
    return render_template("error.html", row=None, notfound=True), 404


@app.after_request
def headers(resp):
    resp.headers.setdefault("X-Content-Type-Options", "nosniff")
    resp.headers.setdefault("X-Frame-Options", "SAMEORIGIN")
    resp.headers.setdefault("Referrer-Policy", "no-referrer")
    return resp


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8000"))
    print(f"{SITE_NAME} running on http://localhost:{port}")
    app.run(host=os.environ.get("HOST", "127.0.0.1"), port=port, debug=bool(os.environ.get("DEBUG")), threaded=True)
