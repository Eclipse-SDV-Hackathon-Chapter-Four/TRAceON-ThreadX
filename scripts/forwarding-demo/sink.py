#!/usr/bin/env python3
"""Tiny HTTP sink for the forwarding demo.

Binds 0.0.0.0:<port> (default 8080) and prints each POSTed LogEntry, one per
line, with a running counter. Replies 200. Suppresses the default access log.

Usage:  python3 sink.py [port]
"""
from __future__ import annotations

import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
_count = 0


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):  # silence default access logging
        pass

    def do_POST(self):
        global _count
        n = int(self.headers.get("content-length", 0))
        body = self.rfile.read(n).decode("utf-8", "replace")
        _count += 1
        try:
            entry = json.loads(body)
            sev = entry.get("severity", "?")
            ctx = entry.get("context")
            ctx_id = ctx.get("context_id") if isinstance(ctx, dict) else ctx
            msg = entry.get("msg", "")
            print(f"#{_count:<4} {sev:<10} [{ctx_id}] {msg}", flush=True)
        except Exception:
            print(f"#{_count:<4} (non-JSON) {body}", flush=True)
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")


def main() -> None:
    print(f"[sink] listening on 0.0.0.0:{PORT} (POST /logs)", flush=True)
    HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
