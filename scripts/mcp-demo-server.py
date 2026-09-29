#!/usr/bin/env python3
"""Minimal MCP stdio server for AMP S4 connectivity e2e.

JSON-RPC 2.0 over stdio (newline-delimited).
Methods: initialize, notifications/initialized, tools/list, tools/call
"""
import json
import sys


def reply(id_, result):
    sys.stdout.write(json.dumps({"jsonrpc": "2.0", "id": id_, "result": result}) + "\n")
    sys.stdout.flush()


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except Exception:
            continue
        method = msg.get("method")
        mid = msg.get("id")
        if method == "initialize":
            reply(mid, {
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "amp-demo-mcp", "version": "0.1.0"},
            })
        elif method == "notifications/initialized":
            pass
        elif method == "tools/list":
            reply(mid, {
                "tools": [{
                    "name": "echo_time",
                    "description": "Return current time string (demo MCP tool)",
                    "inputSchema": {
                        "type": "object",
                        "properties": {
                            "prefix": {"type": "string", "description": "optional prefix"}
                        },
                    },
                }]
            })
        elif method == "tools/call":
            params = msg.get("params") or {}
            name = params.get("name")
            args = params.get("arguments") or {}
            if name == "echo_time":
                import datetime
                prefix = args.get("prefix") or "time"
                text = "%s=%s" % (prefix, datetime.datetime.utcnow().isoformat() + "Z")
                reply(mid, {"content": [{"type": "text", "text": text}], "isError": False})
            else:
                reply(mid, {"content": [{"type": "text", "text": "unknown tool"}], "isError": True})
        elif mid is not None:
            reply(mid, {"error": {"code": -32601, "message": "method not found: %s" % method}})
        # ignore notifications without id


if __name__ == "__main__":
    main()
