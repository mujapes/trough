#!/usr/bin/env python3
"""A scripted model API for TroughAgent's oracle: Anthropic Messages and OpenAI Chat Completions,
both streamed as SSE, with the real APIs' structural rules enforced (HTTP 400 otherwise).
No model: the reply is a script keyed on the conversation.
  last message is a tool result -> text "saw: <result>"
  user says READ <path>         -> read_file call
  user says RUN <command>       -> bash call
  anything else                 -> text "trough-native", in several deltas
Every request body is appended to <log> as one JSON line.
Usage: fake_provider.py <port> <log>"""
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT, LOG = int(sys.argv[1]), sys.argv[2]


def plan(last_user, last_tool):
    if last_tool is not None:
        return ("text", "saw: " + last_tool[:200])
    if last_user.startswith("READ "):
        return ("call", "read_file", {"path": last_user[5:]})
    if last_user.startswith("RUN "):
        return ("call", "bash", {"command": last_user[4:]})
    return ("text", "trough-native")


def anthropic(body):
    ms = body["messages"]
    if not ms or ms[0]["role"] != "user": return "first message must be user"
    for a, b in zip(ms, ms[1:]):
        if a["role"] == b["role"]: return "roles must alternate"
    for i, m in enumerate(ms):
        for blk in m["content"]:
            if blk["type"] == "tool_result":
                prev = [x["id"] for x in ms[i - 1]["content"] if x["type"] == "tool_use"] if i else []
                if blk["tool_use_id"] not in prev: return "tool_result without matching tool_use"
    last = ms[-1]["content"]
    tool = next((b["content"] for b in last if b["type"] == "tool_result"), None)
    user = next((b["text"] for b in last if b["type"] == "text"), "")
    return plan(user, tool)


def openai(body):
    ms = body["messages"]
    if ms[0]["role"] != "system": return "first message must be system"
    for i, m in enumerate(ms):
        if m["role"] == "tool":
            j = i - 1
            while ms[j]["role"] == "tool": j -= 1
            ids = [c["id"] for c in ms[j].get("tool_calls") or []]
            if ms[j]["role"] != "assistant" or m["tool_call_id"] not in ids: return "tool message without matching tool_call"
    last = ms[-1]
    return plan(last.get("content") or "" if last["role"] == "user" else "", last["content"] if last["role"] == "tool" else None)


class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["content-length"])))
        open(LOG, "a").write(json.dumps({"path": self.path, "body": body}) + "\n")
        kind = "anthropic" if self.path.endswith("/v1/messages") else "openai"
        p = (anthropic if kind == "anthropic" else openai)(body)
        if isinstance(p, str):
            self.send_response(400); self.end_headers(); self.wfile.write(json.dumps({"error": {"message": p}}).encode()); return
        self.send_response(200)
        self.send_header("content-type", "text/event-stream")
        self.end_headers()
        ev = lambda o: self.wfile.write(("data: " + json.dumps(o) + "\n\n").encode())
        if kind == "anthropic":
            ev({"type": "message_start", "message": {"id": "m", "role": "assistant"}})
            if p[0] == "text":
                ev({"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}})
                for k in range(0, len(p[1]), 4): ev({"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": p[1][k:k + 4]}})
                ev({"type": "content_block_stop", "index": 0})
                ev({"type": "message_delta", "delta": {"stop_reason": "end_turn"}})
            else:
                ev({"type": "content_block_start", "index": 0, "content_block": {"type": "tool_use", "id": "toolu_1", "name": p[1], "input": {}}})
                a = json.dumps(p[2])
                for k in range(0, len(a), 5): ev({"type": "content_block_delta", "index": 0, "delta": {"type": "input_json_delta", "partial_json": a[k:k + 5]}})
                ev({"type": "content_block_stop", "index": 0})
                ev({"type": "message_delta", "delta": {"stop_reason": "tool_use"}})
            ev({"type": "message_stop"})
        else:
            if p[0] == "text":
                for k in range(0, len(p[1]), 4): ev({"choices": [{"index": 0, "delta": {"content": p[1][k:k + 4]}}]})
                ev({"choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]})
            else:
                a = json.dumps(p[2])
                ev({"choices": [{"index": 0, "delta": {"tool_calls": [{"index": 0, "id": "call_1", "type": "function", "function": {"name": p[1], "arguments": ""}}]}}]})
                for k in range(0, len(a), 5): ev({"choices": [{"index": 0, "delta": {"tool_calls": [{"index": 0, "function": {"arguments": a[k:k + 5]}}]}}]})
                ev({"choices": [{"index": 0, "delta": {}, "finish_reason": "tool_calls"}]})
            self.wfile.write(b"data: [DONE]\n\n")


HTTPServer(("127.0.0.1", PORT), H).serve_forever()
