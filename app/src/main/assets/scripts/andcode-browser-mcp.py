#!/usr/bin/env python3
"""AndCode guest-browser MCP server (stdlib only).

Bridges the agent to the in-app Guest Browser of the AndCode Android app:

- ``browser_show`` asks the app (via a command file in the active workspace) to
  open the guest browser so the user can watch and operate the page.
- The remaining tools drive the same WebView over CDP. The app enables WebView
  debugging, which exposes ``webview_devtools_remote_<pid>`` as a Linux abstract
  socket; the guest shares the kernel (and the app UID), so it can attach.

Speaks MCP over stdio (newline-delimited JSON-RPC) with no third-party deps so
it runs on the bare runtime rootfs python3.
"""

import base64
import json
import os
import socket
import struct
import sys
from pathlib import Path
from urllib.parse import urlparse

COMMAND_FILE = Path(".and-code") / "browser-command.json"


def _find_devtools_socket() -> str | None:
    try:
        with open("/proc/net/unix", encoding="ascii") as fh:
            lines = fh.readlines()
    except OSError:
        return None
    for line in lines[1:]:
        parts = line.split()
        if len(parts) < 8:
            continue
        try:
            name = bytes.fromhex(parts[-1]).decode("ascii")
        except (ValueError, UnicodeDecodeError):
            continue
        if name.startswith("webview_devtools_remote_"):
            return name
    return None


class CdpError(RuntimeError):
    pass


class _UnixWs:
    """Minimal WebSocket client (text frames) over an AF_UNIX abstract socket."""

    def __init__(self, sock: socket.socket, host: str, path: str):
        self.sock = sock
        key = base64.b64encode(os.urandom(16)).decode()
        req = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {host}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        )
        sock.sendall(req.encode())
        resp = b""
        while b"\r\n\r\n" not in resp:
            chunk = sock.recv(4096)
            if not chunk:
                raise CdpError("socket closed during ws handshake")
            resp += chunk
        if b"101" not in resp.split(b"\r\n")[0]:
            raise CdpError(f"ws handshake failed: {resp[:120]!r}")

    def send_text(self, text: str) -> None:
        payload = text.encode()
        mask = os.urandom(4)
        header = b"\x81"
        n = len(payload)
        if n < 126:
            header += bytes([0x80 | n])
        elif n < 65536:
            header += bytes([0x80 | 126]) + struct.pack(">H", n)
        else:
            header += bytes([0x80 | 127]) + struct.pack(">Q", n)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(header + mask + masked)

    def _recv_exact(self, n: int) -> bytes:
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise CdpError("socket closed")
            buf += chunk
        return buf

    def recv_text(self) -> str:
        while True:
            b1, b2 = self._recv_exact(2)
            opcode = b1 & 0x0F
            masked = b2 & 0x80
            n = b2 & 0x7F
            if n == 126:
                n = struct.unpack(">H", self._recv_exact(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", self._recv_exact(8))[0]
            mask = self._recv_exact(4) if masked else b""
            payload = self._recv_exact(n)
            if mask:
                payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
            if opcode == 0x1:
                return payload.decode()
            if opcode == 0x8:
                raise CdpError("server closed ws")
            if opcode == 0x9:
                self._send_pong(payload)

    def _send_pong(self, payload: bytes) -> None:
        mask = os.urandom(4)
        header = b"\x8a" + bytes([0x80 | len(payload)])
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(header + mask + masked)

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass


class CdpSession:
    def __init__(self, socket_name: str):
        self.socket_name = socket_name

    def _connect(self) -> socket.socket:
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        sock.settimeout(10)
        sock.connect("\0" + self.socket_name)
        return sock

    def list_targets(self) -> list[dict]:
        sock = self._connect()
        try:
            sock.sendall(b"GET /json HTTP/1.1\r\nHost: localhost\r\n\r\n")
            data = b""
            while not data.endswith(b"]"):
                chunk = sock.recv(65536)
                if not chunk:
                    break
                data += chunk
        finally:
            sock.close()
        body = data.split(b"\r\n\r\n", 1)[1] if b"\r\n\r\n" in data else data
        if b"\r\n0\r\n\r\n" in body:
            chunks = []
            rest = body
            while rest:
                size_end = rest.find(b"\r\n")
                if size_end < 0:
                    break
                size = int(rest[:size_end], 16)
                if size == 0:
                    break
                chunks.append(rest[size_end + 2 : size_end + 2 + size])
                rest = rest[size_end + 2 + size + 2 :]
            body = b"".join(chunks)
        return json.loads(body)

    def open_page_session(self) -> "_PageSession":
        targets = [t for t in self.list_targets() if t.get("type") in ("document", "page")]
        if not targets:
            raise CdpError("no WebView page target found; is the guest browser open?")
        ws_url = targets[0]["webSocketDebuggerUrl"]
        parsed = urlparse(ws_url)
        ws = _UnixWs(self._connect(), parsed.netloc or "localhost", parsed.path or "/")
        return _PageSession(ws)


class _PageSession:
    def __init__(self, ws: _UnixWs):
        self.ws = ws

    def call(self, method: str, **params) -> dict:
        self.ws.send_text(json.dumps({"id": 1, "method": method, "params": params}))
        while True:
            msg = json.loads(self.ws.recv_text())
            if msg.get("id") == 1:
                if "error" in msg:
                    raise CdpError(f"{method}: {msg['error']}")
                return msg.get("result", {})

    def close(self) -> None:
        self.ws.close()


def _session() -> CdpSession:
    name = _find_devtools_socket()
    if name is None:
        raise CdpError(
            "WebView devtools socket not found. Open the Guest Browser first (browser_show) "
            "and make sure the app build enables WebView debugging."
        )
    return CdpSession(name)


def tool_show(args: dict) -> str:
    url = args["url"]
    COMMAND_FILE.parent.mkdir(parents=True, exist_ok=True)
    COMMAND_FILE.write_text(json.dumps({"action": "open", "url": url}), encoding="utf-8")
    return f"requested the app to open {url}"


def tool_status(args: dict) -> str:
    name = _find_devtools_socket()
    if name is None:
        return "not connected: no WebView devtools socket; call browser_show first"
    pages = [t.get("url", "") for t in CdpSession(name).list_targets() if t.get("type") in ("document", "page")]
    return f"connected: {name} / pages: {pages}"


def tool_navigate(args: dict) -> str:
    page = _session().open_page_session()
    try:
        page.call("Page.navigate", url=args["url"])
        return f"navigated to {args['url']}"
    finally:
        page.close()


def tool_click(args: dict) -> str:
    page = _session().open_page_session()
    try:
        expr = (
            "(() => { const el = document.elementFromPoint(%f, %f); "
            "if (!el) return 'no element'; el.click(); return el.tagName; })()"
            % (float(args["x"]), float(args["y"]))
        )
        result = page.call("Runtime.evaluate", expression=expr, returnByValue=True)
        return f"clicked: {result.get('result', {}).get('value')}"
    finally:
        page.close()


def tool_type(args: dict) -> str:
    page = _session().open_page_session()
    try:
        page.call("Runtime.evaluate", expression="document.activeElement && document.activeElement.focus()")
        page.call("Input.insertText", text=args["text"])
        return "typed"
    finally:
        page.close()


def tool_screenshot(args: dict) -> str:
    save_path = args.get("save_path") or "/tmp/andcode-browser.png"
    page = _session().open_page_session()
    try:
        result = page.call("Page.captureScreenshot", format="png")
        target = Path(save_path)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(base64.b64decode(result["data"]))
        return str(target)
    finally:
        page.close()


def tool_info(args: dict) -> str:
    page = _session().open_page_session()
    try:
        result = page.call(
            "Runtime.evaluate",
            expression="JSON.stringify({url: location.href, title: document.title})",
            returnByValue=True,
        )
        return result.get("result", {}).get("value", "{}")
    finally:
        page.close()


TOOLS = [
    {
        "name": "browser_show",
        "description": (
            "Open the AndCode in-app Guest Browser at the given URL so the user can watch and "
            "operate the page. Call this before other browser_* tools."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {"url": {"type": "string"}},
            "required": ["url"],
        },
    },
    {
        "name": "browser_status",
        "description": "Report CDP connectivity to the Guest Browser WebView and the current pages.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "browser_navigate",
        "description": "Navigate the open Guest Browser to a URL.",
        "inputSchema": {
            "type": "object",
            "properties": {"url": {"type": "string"}},
            "required": ["url"],
        },
    },
    {
        "name": "browser_click",
        "description": "Click/tap viewport coordinates (x, y) in the Guest Browser page.",
        "inputSchema": {
            "type": "object",
            "properties": {"x": {"type": "number"}, "y": {"type": "number"}},
            "required": ["x", "y"],
        },
    },
    {
        "name": "browser_type",
        "description": "Type text into the focused input of the Guest Browser page.",
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string"}},
            "required": ["text"],
        },
    },
    {
        "name": "browser_screenshot",
        "description": "Capture the Guest Browser page as PNG and return the saved path.",
        "inputSchema": {
            "type": "object",
            "properties": {"save_path": {"type": "string"}},
        },
    },
    {
        "name": "browser_info",
        "description": "Return the current URL and title of the Guest Browser page.",
        "inputSchema": {"type": "object", "properties": {}},
    },
]

HANDLERS = {
    "browser_show": tool_show,
    "browser_status": tool_status,
    "browser_navigate": tool_navigate,
    "browser_click": tool_click,
    "browser_type": tool_type,
    "browser_screenshot": tool_screenshot,
    "browser_info": tool_info,
}


def respond(obj: dict) -> None:
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


def main() -> None:
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except json.JSONDecodeError:
            continue
        method = msg.get("method")
        mid = msg.get("id")
        if method == "initialize":
            respond(
                {
                    "jsonrpc": "2.0",
                    "id": mid,
                    "result": {
                        "protocolVersion": msg.get("params", {}).get("protocolVersion", "2024-11-05"),
                        "capabilities": {"tools": {}},
                        "serverInfo": {"name": "and-code-browser", "version": "1.0.0"},
                    },
                }
            )
        elif method in ("notifications/initialized", "initialized"):
            continue
        elif method == "tools/list":
            respond({"jsonrpc": "2.0", "id": mid, "result": {"tools": TOOLS}})
        elif method == "tools/call":
            params = msg.get("params", {})
            name = params.get("name")
            args = params.get("arguments", {}) or {}
            handler = HANDLERS.get(name)
            if handler is None:
                respond(
                    {
                        "jsonrpc": "2.0",
                        "id": mid,
                        "result": {
                            "content": [{"type": "text", "text": f"unknown tool: {name}"}],
                            "isError": True,
                        },
                    }
                )
            else:
                try:
                    text = handler(args)
                    respond(
                        {"jsonrpc": "2.0", "id": mid, "result": {"content": [{"type": "text", "text": text}]}}
                    )
                except Exception as exc:  # noqa: BLE001 - report any failure to the agent
                    respond(
                        {
                            "jsonrpc": "2.0",
                            "id": mid,
                            "result": {
                                "content": [{"type": "text", "text": f"{type(exc).__name__}: {exc}"}],
                                "isError": True,
                            },
                        }
                    )
        elif mid is not None:
            respond(
                {
                    "jsonrpc": "2.0",
                    "id": mid,
                    "error": {"code": -32601, "message": f"method not found: {method}"},
                }
            )


if __name__ == "__main__":
    main()
