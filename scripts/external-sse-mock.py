#!/usr/bin/env python3
"""极简 External Agent SSE mock（演示 S9 流式透传）。

约定（与 ExternalChatClient.forwardStream 一致）：
  POST /api/v1/messages:stream
  Body: {agentId, tenantId, userId, sessionId, message}
  Resp: text/event-stream（逐行 SSE 帧）

另兼容非流式：
  POST /api/v1/messages  → {"reply": "..."}

用法:
  python scripts/external-sse-mock.py            # 默认 127.0.0.1:19099
  python scripts/external-sse-mock.py --port 19099

再跑: powershell -File scripts/demo-external-stream.ps1
"""

from __future__ import annotations

import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 19099
CHUNKS = ["外部", " mock ", "流式", "演示", "完成"]


class Handler(BaseHTTPRequestHandler):
    server_version = "ExternalSseMock/1.0"

    def log_message(self, fmt, *args):  # 安静一点
        print(f"[mock] {self.address_string()} {fmt % args}")

    def _read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        try:
            return json.loads(raw.decode("utf-8") or "{}")
        except json.JSONDecodeError:
            return {}

    def _write_json(self, code: int, payload: dict):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        path = self.path.split("?", 1)[0]
        req = self._read_json()
        # 可选：校验 X-Callback-Key（与注册 callbackKey 对齐时打开）
        # if self.headers.get("X-Callback-Key") != "demo-callback":
        #     return self._write_json(401, {"code": "UNAUTHORIZED"})

        if path.endswith("/api/v1/messages:stream") or path.endswith("/messages:stream"):
            return self._sse(req)
        if path.endswith("/api/v1/messages") or path.endswith("/messages"):
            reply = f"mock-reply:{req.get('message', '')}"
            return self._write_json(200, {"reply": reply, "source": "external-sse-mock"})
        return self._write_json(404, {"code": "NOT_FOUND", "path": path})

    def do_GET(self):
        # 健康检查
        if self.path.startswith("/health") or self.path == "/":
            return self._write_json(200, {"status": "UP", "endpoints": [
                "POST /api/v1/messages",
                "POST /api/v1/messages:stream",
            ]})
        self._write_json(404, {"code": "NOT_FOUND"})

    def _sse(self, req: dict):
        """返回 text/event-stream：逐帧 token + done。"""
        session = str(req.get("sessionId") or "mock")
        message = str(req.get("message") or "hello")
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        try:
            for i, chunk in enumerate(CHUNKS):
                frame = {
                    "type": "token",
                    "text": chunk if i else f"{chunk}({message[:24]})",
                    "seq": i,
                }
                data = f"id: {session}:{i}\ndata: {json.dumps(frame, ensure_ascii=False)}\n\n"
                self.wfile.write(data.encode("utf-8"))
                self.wfile.flush()
                time.sleep(0.15)
            done = {"type": "done", "reply": "".join(CHUNKS) + f"::{message[:24]}"}
            data = f"id: {session}:99\ndata: {json.dumps(done, ensure_ascii=False)}\n\n"
            self.wfile.write(data.encode("utf-8"))
            self.wfile.flush()
        except BrokenPipeError:
            self.log_message("sse client disconnected")


def main():
    parser = argparse.ArgumentParser(description="External SSE mock for S9 stream demo")
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    args = parser.parse_args()
    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"[mock] External SSE mock listening on http://{args.host}:{args.port}")
    print("[mock] POST /api/v1/messages  |  POST /api/v1/messages:stream")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n[mock] bye")
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
