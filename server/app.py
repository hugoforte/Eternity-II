"""Eternity II Lab - web server.

Pure Python standard library: no pip install, no virtualenv. Start it with

    python3 server/app.py            # then open http://localhost:8420

Endpoints
---------
GET  /                          the single page app
GET  /css/... /js/...           static assets
GET  /api/bootstrap             everything the page needs on first load
GET  /api/stream                Server-Sent Events: live board + lifecycle
GET  /api/attempts              paged history
GET  /api/attempts/<id>         one attempt with its full placement order
GET  /api/insights              what the learner has concluded
POST /api/config                pin a user configuration (restarts the attempt)
POST /api/config/optimal        go back to the learner's optimal settings
POST /api/control               {"action": "pause"|"resume"|"skip"}
POST /api/history/clear         wipe all stored attempts and lessons
"""

import argparse
import json
import os
import queue
import socketserver
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import schema                      # noqa: E402
from db import Db                  # noqa: E402
from supervisor import Supervisor, Broker, find_java   # noqa: E402
from analytics import Analyzer     # noqa: E402

WEB_DIR = os.path.join(ROOT, "web")
CLASSES_DIR = os.path.join(ROOT, "java", "classes")
DB_PATH = os.path.join(ROOT, "data", "eternity2.sqlite")

CONTENT_TYPES = {
    ".html": "text/html; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".js": "application/javascript; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".svg": "image/svg+xml",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".ico": "image/x-icon",
    ".woff2": "font/woff2",
}

STATE = {"db": None, "supervisor": None}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "Eternity2Lab/1.0"

    # ------------------------------------------------------------- plumbing

    def log_message(self, fmt, *args):
        # keep the console readable: only report problems
        if str(args[1] if len(args) > 1 else "").startswith(("4", "5")):
            sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    def _send_json(self, payload, status=200):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _send_bytes(self, body, content_type, status=200, cache=False):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control",
                         "public, max-age=300" if cache else "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self):
        """Read and discard-or-parse the request body. Always drains the socket."""
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except (TypeError, ValueError):
            length = 0
        if length <= 0:
            return {}
        raw = self.rfile.read(length)
        try:
            parsed = json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return {}
        return parsed if isinstance(parsed, dict) else {}

    # ------------------------------------------------------------------ GET

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        try:
            if path == "/api/stream":
                return self._stream()
            if path == "/api/bootstrap":
                return self._bootstrap()
            if path == "/api/attempts":
                return self._attempts(parse_qs(parsed.query))
            if path.startswith("/api/attempts/"):
                return self._attempt_detail(path.rsplit("/", 1)[-1])
            if path == "/api/insights":
                return self._insights()
            if path == "/api/findings":
                return self._findings()
            if path == "/api/lessons":
                return self._lessons()
            if path == "/api/status":
                return self._send_json(STATE["supervisor"].status())
            if path.startswith("/api/"):
                return self._send_json({"error": "unknown endpoint"}, 404)
            return self._static(path)
        except BrokenPipeError:
            return
        except ConnectionResetError:
            return
        except Exception as exc:
            sys.stderr.write("GET %s failed: %r\n" % (path, exc))
            try:
                self._send_json({"error": str(exc)}, 500)
            except Exception:
                pass

    # ----------------------------------------------------------------- POST

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path
        sup = STATE["supervisor"]
        # The request body must ALWAYS be consumed, even by endpoints that do not
        # need it. On a keep-alive connection any bytes left unread become the
        # start of the next request line, which the server then rejects with a
        # baffling 501.
        body = self._read_json()
        try:
            if path == "/api/config":
                cfg = sup.set_config(body.get("config") or {}, user_defined=True)
                return self._send_json({"ok": True, "config": cfg, "userDefined": True})
            if path == "/api/config/optimal":
                cfg = sup.use_optimal()
                return self._send_json({"ok": True, "config": cfg, "userDefined": False})
            if path == "/api/control":
                action = (body.get("action") or "").lower()
                if action == "pause":
                    sup.pause()
                elif action == "resume":
                    sup.resume()
                elif action == "skip":
                    sup.skip()
                else:
                    return self._send_json({"error": "unknown action"}, 400)
                return self._send_json({"ok": True, "status": sup.status()})
            if path == "/api/history/clear":
                STATE["db"].clear_history()
                sup.tuner.after_attempt()
                sup.skip()
                return self._send_json({"ok": True, "stats": STATE["db"].stats()})
            if path == "/api/analyze":
                return self._analyze()
            if path.startswith("/api/lessons/") and path.endswith("/dismiss"):
                lid = path[len("/api/lessons/"):-len("/dismiss")]
                sup.lessons.dismiss(lid)
                return self._send_json({"ok": True, "lessons": sup.lessons.listing()})
            if path.startswith("/api/lessons/") and path.endswith("/restore"):
                lid = path[len("/api/lessons/"):-len("/restore")]
                sup.lessons.restore(lid)
                return self._send_json({"ok": True, "lessons": sup.lessons.listing()})
            return self._send_json({"error": "unknown endpoint"}, 404)
        except BrokenPipeError:
            return
        except Exception as exc:
            sys.stderr.write("POST %s failed: %r\n" % (path, exc))
            try:
                self._send_json({"error": str(exc)}, 500)
            except Exception:
                pass

    # -------------------------------------------------------------- handlers

    def _bootstrap(self):
        db = STATE["db"]
        sup = STATE["supervisor"]
        payload = {
            "settings": schema.SETTINGS,
            "groupOrder": schema.GROUP_ORDER,
            "defaults": schema.defaults(),
            "optimal": sup.tuner.optimal_config(),
            "optimalDetails": sup.tuner.optimal_details(),
            "meta": sup.meta,
            "live": sup.status(),
            "attempts": db.attempt_summaries(limit=80),
            "attemptCount": db.attempt_count(),
            "stats": db.stats(),
            "insights": db.load_insights(),
            "breakdown": sup.tuner.setting_breakdown(),
            "findings": db.latest_findings(),
            "analysis": db.latest_analysis(),
            "lessons": sup.lessons.listing(),
            "activeRules": sup.lessons.active_rules(),
        }
        self._send_json(payload)

    def _attempts(self, params):
        db = STATE["db"]
        limit = _int_param(params, "limit", 80, 1, 500)
        offset = _int_param(params, "offset", 0, 0, 10 ** 9)
        self._send_json({
            "attempts": db.attempt_summaries(limit=limit, offset=offset),
            "total": db.attempt_count(),
            "stats": db.stats(),
        })

    def _attempt_detail(self, raw_id):
        try:
            attempt_id = int(raw_id)
        except ValueError:
            return self._send_json({"error": "bad id"}, 400)
        detail = STATE["db"].attempt_detail(attempt_id)
        if detail is None:
            return self._send_json({"error": "not found"}, 404)
        self._send_json(detail)

    def _insights(self):
        db = STATE["db"]
        sup = STATE["supervisor"]
        self._send_json({
            "insights": db.load_insights(),
            "breakdown": sup.tuner.setting_breakdown(),
            "optimalDetails": sup.tuner.optimal_details(),
            "stats": db.stats(),
        })

    def _findings(self):
        db = STATE["db"]
        self._send_json({
            "findings": db.latest_findings(),
            "analysis": db.latest_analysis(),
            "stats": db.stats(),
        })

    def _lessons(self):
        sup = STATE["supervisor"]
        self._send_json({
            "lessons": sup.lessons.listing(),
            "activeRules": sup.lessons.active_rules(),
        })

    def _analyze(self):
        db = STATE["db"]
        sup = STATE["supervisor"]
        analyzer = Analyzer(db)
        result = analyzer.run()
        analysis_id = db.record_analysis(
            result["attempts"], result["duration_ms"],
            result["findings"], result.get("note"))
        lesson_changes = sup.lessons.ingest(result["findings"])
        sup.broker.publish("analysis_complete", {
            "analysisId": analysis_id,
            "findings": len(result["findings"]),
            "lessons": lesson_changes,
        })
        self._send_json({
            "ok": True,
            "analysisId": analysis_id,
            "findings": result["findings"],
            "analysis": db.latest_analysis(),
            "lessons": sup.lessons.listing(),
            "lessonChanges": lesson_changes,
            "note": result.get("note"),
        })

    def _static(self, path):
        if path == "/" or path == "":
            path = "/index.html"
        safe = os.path.normpath(path).lstrip("/\\")
        full = os.path.join(WEB_DIR, safe)
        if not os.path.abspath(full).startswith(os.path.abspath(WEB_DIR)):
            return self._send_json({"error": "forbidden"}, 403)
        if not os.path.isfile(full):
            return self._send_json({"error": "not found", "path": path}, 404)
        ext = os.path.splitext(full)[1].lower()
        with open(full, "rb") as handle:
            body = handle.read()
        self._send_bytes(body, CONTENT_TYPES.get(ext, "application/octet-stream"),
                         cache=(ext not in (".html",)))

    # ------------------------------------------------------------------- SSE

    def _stream(self):
        sup = STATE["supervisor"]
        q = sup.broker.subscribe()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache, no-store")
        self.send_header("X-Accel-Buffering", "no")
        self.send_header("Connection", "close")
        self.end_headers()

        def write_event(name, data):
            chunk = "event: %s\ndata: %s\n\n" % (name, json.dumps(data))
            self.wfile.write(chunk.encode("utf-8"))
            self.wfile.flush()

        try:
            write_event("hello", {"status": sup.status(), "meta": sup.meta})
            last_ping = time.time()
            while True:
                try:
                    name, data = q.get(timeout=5.0)
                    write_event(name, data)
                except queue.Empty:
                    now = time.time()
                    if now - last_ping > 10:
                        last_ping = now
                        # comment frame keeps proxies and browsers from timing out
                        self.wfile.write(b": ping\n\n")
                        self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError, ValueError):
            pass
        finally:
            sup.broker.unsubscribe(q)


def _int_param(params, name, default, lo, hi):
    values = params.get(name)
    if not values:
        return default
    try:
        value = int(values[0])
    except (TypeError, ValueError):
        return default
    return max(lo, min(hi, value))


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True
    # SSE holds a thread per client; keep the listen backlog generous.
    request_queue_size = 64


def main():
    parser = argparse.ArgumentParser(description="Eternity II Lab web server")
    parser.add_argument("--port", type=int, default=8420)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--db", default=DB_PATH)
    parser.add_argument("--classes", default=CLASSES_DIR)
    parser.add_argument("--no-solve", action="store_true",
                        help="serve the UI without starting the solver")
    args = parser.parse_args()

    if not os.path.isdir(args.classes):
        sys.stderr.write(
            "Compiled Java classes not found at %s\n"
            "Build them first:  sh build.sh   (or build.bat on Windows)\n" % args.classes)
        return 2

    java = find_java()
    if java is None:
        sys.stderr.write("No 'java' on PATH and JAVA_HOME is unset. Install a JDK 17+.\n")
        return 2

    db = Db(args.db)
    supervisor = Supervisor(db, args.classes, Broker(), java_bin=java)
    STATE["db"] = db
    STATE["supervisor"] = supervisor

    if not args.no_solve:
        supervisor.start()

    httpd = Server((args.host, args.port), Handler)
    banner = "http://%s:%d" % (
        "localhost" if args.host in ("127.0.0.1", "0.0.0.0") else args.host, args.port)
    print("=" * 62)
    print(" Eternity II Lab")
    print("=" * 62)
    print(" java    : %s" % java)
    print(" classes : %s" % args.classes)
    print(" database: %s" % args.db)
    print(" open    : %s" % banner)
    print("=" * 62)
    sys.stdout.flush()

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nshutting down...")
    finally:
        supervisor.shutdown()
        httpd.server_close()
        db.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
