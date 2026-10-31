"""Starts the SuperSmart (Entity Intelligence) Flask site on 127.0.0.1 inside the EKPE app."""
import os
import threading

_server = None
_lock = threading.Lock()
PORT = 8765


def start(data_dir: str) -> int:
    global _server
    with _lock:
        if _server is not None:
            return PORT
        os.makedirs(data_dir, exist_ok=True)
        os.environ["DB_PATH"] = os.path.join(data_dir, "scans.db")
        os.environ.setdefault("SITE_NAME", "SuperSmart")
        os.environ["HOME"] = data_dir
        import webapp
        from werkzeug.serving import make_server

        _server = make_server("127.0.0.1", PORT, webapp.app, threaded=True)
        threading.Thread(target=_server.serve_forever, daemon=True).start()
        return PORT


def set_keys(serpapi: str, cse_key: str, cse_cx: str, brave: str) -> None:
    for k, v in (("SERPAPI_API_KEY", serpapi), ("GOOGLE_CSE_KEY", cse_key), ("GOOGLE_CSE_CX", cse_cx), ("BRAVE_API_KEY", brave)):
        if v:
            os.environ[k] = v
