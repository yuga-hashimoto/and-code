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
import time
from pathlib import Path
from urllib.parse import urlparse

COMMAND_FILE = Path(".and-code") / "browser-command.json"
STATUS_FILE = Path(".and-code") / "browser-status.json"


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


AGENT_CONTROL_HINT = (
    "Open the Guest Browser first (browser_show) and turn on "
    '"Let the agent control the Guest Browser" in Settings.'
)


def _wait_for_socket(timeout: float) -> str | None:
    """The app builds the WebView just after browser_show returns, so give it a moment to appear."""
    deadline = time.monotonic() + timeout
    while True:
        name = _find_devtools_socket()
        if name is not None or time.monotonic() >= deadline:
            return name
        time.sleep(0.25)


def _session() -> CdpSession:
    name = _find_devtools_socket()
    if name is None:
        raise CdpError("WebView devtools socket not found. " + AGENT_CONTROL_HINT)
    return CdpSession(name)


def _write_command(payload: dict) -> None:
    COMMAND_FILE.parent.mkdir(parents=True, exist_ok=True)
    COMMAND_FILE.write_text(json.dumps(payload), encoding="utf-8")


def _read_status() -> str | None:
    try:
        state = json.loads(STATUS_FILE.read_text(encoding="utf-8")).get("state")
    except (OSError, ValueError, AttributeError):
        return None
    return state if isinstance(state, str) else None


def _require_web_url(url: str) -> str:
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        raise CdpError(f"refusing to open non-web URL: {url}")
    return url


def tool_show(args: dict) -> str:
    url = _require_web_url(args["url"])
    _write_command({"action": "open", "url": url})
    return f"requested the app to open {url}"


def tool_preview(args: dict) -> str:
    url = _require_web_url(args["url"])
    _write_command({"action": "preview", "url": url})
    return f"requested the app to show {url} as an inline preview"


def tool_handoff(args: dict) -> str:
    """Hand the open page to the user and block until they hand it back.

    This is the only way the agent can ask for something it must not do itself, signing in above
    all: the user operates the same visible WebView, then taps "Hand back to agent". The app and
    the agent share no memory, so the outcome travels through the status file.
    """
    if _wait_for_socket(10) is None:
        raise CdpError("the guest browser is not open yet. " + AGENT_CONTROL_HINT)
    # Stay below the MCP client's timeout (LocalRuntimeInstaller.BROWSER_MCP_TIMEOUT_MILLIS = 120 s)
    # so this tool's own timeout reply can reach the agent instead of the client killing the call.
    timeout = max(1.0, min(float(args.get("timeout_s") or 90), 110.0))
    try:
        STATUS_FILE.unlink()
    except FileNotFoundError:
        pass
    except OSError as exc:
        # A leftover "agent" would make the next handoff return immediately, so never ignore this.
        raise CdpError(f"could not clear the previous handoff status: {exc}") from exc
    _write_command({"action": "handoff", "url": ""})
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        state = _read_status()
        if state == "agent":
            return "the user handed the browser back; continue with the page as it is now"
        if state == "dismissed":
            return "the user cancelled the handoff; do not continue with the page"
        time.sleep(0.5)
    return (
        'timed out waiting for the user to hand the browser back; ask them to finish and tap '
        '"Hand back to agent"'
    )


def tool_status(args: dict) -> str:
    name = _find_devtools_socket()
    if name is None:
        return "not connected: no WebView devtools socket. " + AGENT_CONTROL_HINT
    pages = [t.get("url", "") for t in CdpSession(name).list_targets() if t.get("type") in ("document", "page")]
    return f"connected: {name} / pages: {pages}"


def tool_navigate(args: dict) -> str:
    url = _require_web_url(args["url"])
    page = _session().open_page_session()
    try:
        page.call("Page.navigate", url=url)
        return f"navigated to {url}"
    finally:
        page.close()


def _find_file_input(
    page: "_PageSession",
    selector: str,
) -> str | None:
    page.call("DOM.enable")
    result = page.call(
        "Runtime.evaluate",
        expression=f"(() => document.querySelector({json.dumps(selector)}))()",
        returnByValue=False,
        objectGroup="upload",
    )
    obj = result.get("result", {})
    if "objectId" not in obj or obj.get("subtype") in ("null", "undefined"):
        return None
    return obj["objectId"]


def tool_upload(args: dict) -> str:
    """Attach a file from device storage to the page's file input without the Android picker.

    The WebView process reads the file itself, so the path must be one it can open: device storage
    under /sdcard or /storage. Best effort: only an input reachable from document.querySelector is
    found; pass click_selector for a picker the site creates lazily. The file chooser dialog is
    intercepted so clicking such a button does not open the native Android chooser.
    """
    path = os.path.realpath(os.path.expanduser(args["path"]))
    if not os.path.isfile(path):
        raise CdpError(f"no such file: {path}")
    roots = ("/sdcard", "/storage")
    if not any(path == root or path.startswith(root + os.sep) for root in roots):
        raise CdpError("attaching is limited to device storage under /sdcard or /storage")
    selector = args.get("selector") or "input[type=file]"
    click_selector = args.get("click_selector")
    page = _session().open_page_session()
    try:
        try:
            page.call("Page.setInterceptFileChooserDialog", enabled=True)
        except CdpError:
            pass
        object_id = _find_file_input(page, selector)
        if object_id is None and click_selector:
            page.call(
                "Runtime.evaluate",
                expression=(
                    f"(() => {{ const el = document.querySelector({json.dumps(click_selector)}); "
                    "if (el) el.click(); return !!el; })()"
                ),
                returnByValue=True,
                userGesture=True,
            )
            time.sleep(0.5)
            object_id = _find_file_input(page, selector)
        if object_id is None:
            raise CdpError(
                "no file input found: pass the site's button as click_selector, or click the "
                "upload button yourself and retry with a selector that matches the hidden input"
            )
        page.call("DOM.setFileInputFiles", files=[path], objectId=object_id)
        return f"attached {os.path.basename(path)} to {selector}"
    finally:
        try:
            page.call("Page.setInterceptFileChooserDialog", enabled=False)
        except CdpError:
            pass
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
        # Credentials are the user's to type: a password field means the agent should have handed
        # the page over instead of filling it in.
        focused = page.call(
            "Runtime.evaluate",
            expression=(
                "(() => { const el = document.activeElement; "
                "return !!el && (el.type === 'password' || el.autocomplete === 'current-password'); })()"
            ),
            returnByValue=True,
        )
        if focused.get("result", {}).get("value"):
            return (
                "refused: the focused field is a password field. Ask the user to type their own "
                "password, or call browser_handoff so they can."
            )
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
            "Open the AndCode in-app Guest Browser full-screen at the given URL so the user can "
            "watch and operate the page. Use browser_preview instead when the page should stay "
            "pinned inside the chat. Call this before other browser_* tools."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {"url": {"type": "string"}},
            "required": ["url"],
        },
    },
    {
        "name": "browser_preview",
        "description": (
            "Show a page as a live, interactive preview pinned inside the AndCode chat. Loopback "
            "URLs (localhost / 127.0.0.1) are always allowed; a public https URL is allowed only "
            "when the user has enabled 'Let the agent control the Guest Browser' in Settings."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {"url": {"type": "string"}},
            "required": ["url"],
        },
    },
    {
        "name": "browser_handoff",
        "description": (
            "Hand the open Guest Browser to the user for a step only they can do (signing in with "
            "their own credentials, approving a prompt, solving a captcha). Waits until the user "
            "taps 'Hand back to agent' in the app, then returns so you can continue. Call "
            "browser_show first. The page the user is on is left exactly as it is."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "timeout_s": {
                    "type": "number",
                    "description": "How long to wait for the user, in seconds (default 90, max 110).",
                },
            },
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
        "description": (
            "Type text into the focused input of the Guest Browser page. Refuses a password field "
            "it can see (best effort: fields inside iframes or a shadow root are not inspected). "
            "Use browser_handoff when credentials are needed."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string"}},
            "required": ["text"],
        },
    },
    {
        "name": "browser_upload",
        "description": (
            "Attach a file from device storage (/sdcard or /storage) to the page's file input, "
            "bypassing the Android file picker. Best effort: only an input reachable from "
            "document.querySelector is found; pass click_selector to open the site's upload button "
            "when the input is created lazily."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Absolute device-storage path, e.g. /sdcard/DCIM/Camera/photo.jpg",
                },
                "selector": {
                    "type": "string",
                    "description": "CSS selector for the file input (default input[type=file]).",
                },
                "click_selector": {
                    "type": "string",
                    "description": "Optional CSS selector for the button that reveals the file input.",
                },
            },
            "required": ["path"],
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
    "browser_preview": tool_preview,
    "browser_handoff": tool_handoff,
    "browser_status": tool_status,
    "browser_navigate": tool_navigate,
    "browser_click": tool_click,
    "browser_type": tool_type,
    "browser_upload": tool_upload,
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
