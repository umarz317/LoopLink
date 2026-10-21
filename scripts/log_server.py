#!/usr/bin/env python3
"""Receives logs streamed by a LoopLink test build and appends them to a file.

LOG_STREAM_TOKEN=... python3 scripts/log_server.py [--port 8787] [--out .private/logstream/looplink.log]
"""
import argparse
import datetime
import hmac
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BODY = 2 * 1024 * 1024


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--out", default=".private/logstream/looplink.log")
    args = parser.parse_args()
    token = os.environ.get("LOG_STREAM_TOKEN", "")
    if not token:
        sys.exit("Set LOG_STREAM_TOKEN to the token baked into the APK.")
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"LoopLink log server\n")

        def do_POST(self):
            if not hmac.compare_digest(self.headers.get("X-LoopLink-Token", ""), token):
                self.send_response(403)
                self.end_headers()
                return
            length = int(self.headers.get("Content-Length") or 0)
            if length <= 0 or length > MAX_BODY:
                self.send_response(413)
                self.end_headers()
                return
            body = self.rfile.read(length).decode("utf-8", "replace")
            device = self.headers.get("X-LoopLink-Device", "unknown device").replace("\n", " ")[:200]
            received = datetime.datetime.now().isoformat(timespec="seconds")
            with lock, open(args.out, "a", encoding="utf-8") as out:
                for line in body.splitlines():
                    out.write(f"{received} [{device}] {line}\n")
            self.send_response(204)
            self.end_headers()

        def log_message(self, *_):
            pass

    print(f"Writing to {os.path.abspath(args.out)} on 127.0.0.1:{args.port}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
