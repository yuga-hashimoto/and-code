# /// script
# requires-python = ">=3.11"
# dependencies = ["fastmcp>=2.0.0"]
# ///
"""AndCode guest-browser MCP server.

Bridges the agent to the in-app Guest Browser of the AndCode Android app:

- ``browser_show`` asks the app (via a command file in the active workspace) to
  open the guest browser so the user can watch and operate the page.
- The remaining tools drive the same WebView over CDP. The app enables WebView
  debugging, which exposes ``webview_devtools_remote_<pid>`` as an Linux abstract
  socket; the guest shares the kernel (and the app UID), so it can attach directly.

The CDP client (HTTP /json + WebSocket) is implemented on AF_UNIX sockets and
needs no extra dependencies.
"""

import base64
import json
import os
import socket
import struct
from pathlib import Path
from urllib.parse import urlparse

from fastmcp import FastMCP

mcp = FastMCP("and-code-browser")

COMMAND_FILE = Path(".and-code") / "browser-command.json"
CDP_BRIDGE_PORT_NOTE = "abstract socket only; no TCP bridge needed"


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
        hexname = parts[-1]
        try:
            name = bytes.fromhex(hexname).decode("ascii")
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
            # ignore pong/continuation fragments (CDP messages are single-frame)

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
        self._next_id = 0

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
            while b"\r\n0\r\n\r\n" not in data and not data.endswith(b"]"):
                chunk = sock.recv(65536)
                if not chunk:
                    break
                data += chunk
        finally:
            sock.close()
        body = data.split(b"\r\n\r\n", 1)[1] if b"\r\n\r\n" in data else data
        # chunked bodies: strip chunk-size lines if present
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
        sock = self._connect()
        ws = _UnixWs(sock, parsed.netloc or "localhost", parsed.path or "/")
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
            "WebView devtools socket not found. Open the Guest Browser in the app first "
            "(browser_show) and make sure the app build enables WebView debugging."
        )
    return CdpSession(name)


@mcp.tool()
def browser_show(url: str) -> str:
    """ユーザーの画面でゲストブラウザを開かせます(アプリがコマンドファイルを検知して起動)。

    Args:
        url: 表示するURL (例: http://127.0.0.1:8080/)
    """
    COMMAND_FILE.parent.mkdir(parents=True, exist_ok=True)
    COMMAND_FILE.write_text(json.dumps({"action": "open", "url": url}), encoding="utf-8")
    return f"アプリに {url} を開くよう要求しました(約1秒で表示されます)"


@mcp.tool()
def browser_status() -> str:
    """ゲストブラウザ(WebView)へのCDP接続状況と現在のページ情報を返します。"""
    name = _find_devtools_socket()
    if name is None:
        return "未接続: WebView のデバッグソケットが見つかりません。browser_show でブラウザを開かせてください。"
    session = CdpSession(name)
    targets = session.list_targets()
    pages = [t.get("url", "") for t in targets if t.get("type") in ("document", "page")]
    return f"接続可: {name} / ページ: {pages}"


@mcp.tool()
def browser_navigate(url: str) -> str:
    """開いているゲストブラウザで URL へ遷移します。"""
    page = _session().open_page_session()
    try:
        page.call("Page.navigate", url=url)
        return f"{url} へ遷移しました"
    finally:
        page.close()


@mcp.tool()
def browser_click(x: float, y: float) -> str:
    """ページ内のビューポート座標 (x, y) をタップ/クリックします。"""
    page = _session().open_page_session()
    try:
        expr = (
            "(() => { const el = document.elementFromPoint(%f, %f); "
            "if (!el) return 'no element'; el.click(); return el.tagName; })()" % (x, y)
        )
        result = page.call("Runtime.evaluate", expression=expr, returnByValue=True)
        return f"クリックしました: {result.get('result', {}).get('value')}"
    finally:
        page.close()


@mcp.tool()
def browser_type(text: str) -> str:
    """フォーカス中の入力要素に文字列を入力します。"""
    page = _session().open_page_session()
    try:
        page.call("Runtime.evaluate", expression="document.activeElement && document.activeElement.focus()")
        page.call("Input.insertText", text=text)
        return "入力しました"
    finally:
        page.close()


@mcp.tool()
def browser_screenshot(save_path: str = "/tmp/opencode/browser.png") -> str:
    """現在のページを PNG で保存し、パスを返します(Read で確認できます)。"""
    page = _session().open_page_session()
    try:
        result = page.call("Page.captureScreenshot", format="png")
        target = Path(save_path)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(base64.b64decode(result["data"]))
        return str(target)
    finally:
        page.close()


@mcp.tool()
def browser_info() -> str:
    """現在のページの URL とタイトルを返します。"""
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


if __name__ == "__main__":
    mcp.run()
