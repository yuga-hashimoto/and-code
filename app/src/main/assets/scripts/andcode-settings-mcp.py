#!/usr/bin/env python3
"""AndCode guest settings MCP server (stdlib only).

Lets an agent inspect and change the AndCode app's own settings, and manage the MCP servers
registered with every agent:

- Agents run inside the app's embedded Linux guest, where the app's settings live in private
  encrypted preferences and are unreachable.
- This server routes every call through a request/response file bridge under the workspace
  (``/workspace/.and-code/settings-bridge``). The app polls ``pending/``, validates and applies the
  operation against its real settings store, and writes the reply into ``responses/``.

Security-sensitive settings (permission auto-approval, browser control, analytics) and settings
that need an on-device permission flow are listed but read-only: only the user can change them in
the app. Secret values (API keys) are write-only; reads only say whether one is set.

Speaks MCP over stdio (newline-delimited JSON-RPC) with no third-party deps so it runs on the
bare runtime rootfs python3.
"""

import json
import os
import sys
import time
import uuid
from pathlib import Path

BRIDGE_ROOT = Path("/workspace/.and-code/settings-bridge")
PENDING_DIR = BRIDGE_ROOT / "pending"
RESPONSES_DIR = BRIDGE_ROOT / "responses"

# Keep in step with AgentSettingsBridge.REQUEST_EXPIRES_AFTER_MILLIS on the app side.
RESPONSE_TIMEOUT_S = 90
POLL_INTERVAL_S = 0.3


class BridgeError(RuntimeError):
    pass


def _write_atomic(file: Path, payload: dict) -> None:
    file.parent.mkdir(parents=True, exist_ok=True)
    tmp = file.with_name(file.name + ".tmp")
    tmp.write_text(json.dumps(payload), encoding="utf-8")
    os.replace(tmp, file)


def _call(op: str, args: dict) -> str:
    """Writes a request for the AndCode app and waits for its reply."""
    PENDING_DIR.mkdir(parents=True, exist_ok=True)
    request_id = uuid.uuid4().hex
    _write_atomic(
        PENDING_DIR / f"{request_id}.json",
        {"op": op, "args": args or {}, "createdAtMs": int(time.time() * 1000)},
    )
    response_file = RESPONSES_DIR / f"{request_id}.json"
    deadline = time.monotonic() + RESPONSE_TIMEOUT_S
    while time.monotonic() < deadline:
        if response_file.is_file():
            payload = json.loads(response_file.read_text(encoding="utf-8"))
            try:
                response_file.unlink()
            except OSError:
                pass
            if payload.get("ok"):
                return json.dumps(payload.get("data"), ensure_ascii=False, indent=2)
            raise BridgeError(payload.get("error") or "the app rejected the request")
        time.sleep(POLL_INTERVAL_S)
    raise BridgeError(f"no reply from the AndCode app within {RESPONSE_TIMEOUT_S} s")


def _tool(name: str, description: str, properties: dict, required: list = None) -> dict:
    schema = {"type": "object", "properties": properties}
    if required:
        schema["required"] = required
    return {"name": name, "description": description, "inputSchema": schema}


KEY = {"type": "string", "description": "Setting key from settings_list."}
AGENTS = {
    "type": "array",
    "items": {"type": "string", "enum": ["opencode", "claude", "antigravity"]},
    "description": "Agents to apply to. Defaults to all three.",
}

# name -> (bridge op, tool definition)
TOOL_TABLE = {
    "settings_list": (
        "list",
        _tool(
            "settings_list",
            "List the AndCode app settings with type, allowed values, current value and default. "
            "Entries with writable=false can only be changed by the user in the app.",
            {"category": {"type": "string", "description": "Optional category filter, e.g. appearance, chat, voice, model."}},
        ),
    ),
    "settings_get": ("get", _tool("settings_get", "Read one setting.", {"key": KEY}, ["key"])),
    "settings_set": (
        "set",
        _tool(
            "settings_set",
            "Change one setting. Use null for a nullable setting to clear it. "
            "List settings take the full new array (or use settings_update_list).",
            {"key": KEY, "value": {"description": "New value; must match the type from settings_list."}},
            ["key", "value"],
        ),
    ),
    "settings_set_many": (
        "setMany",
        _tool(
            "settings_set_many",
            "Change several settings at once. Everything is validated first; if any entry is invalid "
            "or read-only, nothing is changed.",
            {"values": {"type": "object", "description": "Map of setting key to new value."}},
            ["values"],
        ),
    ),
    "settings_reset": (
        "reset",
        _tool("settings_reset", "Restore one setting to its default.", {"key": KEY}, ["key"]),
    ),
    "settings_update_list": (
        "updateList",
        _tool(
            "settings_update_list",
            "Add and/or remove items of a list setting (favoriteModelKeys, hiddenModelKeys, pinnedSessionIds) "
            "without replacing the whole list.",
            {
                "key": KEY,
                "add": {"type": "array", "items": {"type": "string"}},
                "remove": {"type": "array", "items": {"type": "string"}},
            },
            ["key"],
        ),
    ),
    "mcp_servers_list": (
        "mcpList",
        _tool(
            "mcp_servers_list",
            "List the MCP servers registered with the OpenCode, Claude Code and Antigravity agents, "
            "and which agents each one is registered with. Environment values and headers are not shown.",
            {},
        ),
    ),
    "mcp_server_add": (
        "mcpAdd",
        _tool(
            "mcp_server_add",
            "Register an MCP server with the agents' configs (or replace a user-added one of the same name). "
            "Give command (argv array) for a local server or url for a remote one. "
            "Takes effect the next time each agent starts a session. Built-in and-code-* servers cannot be replaced.",
            {
                "name": {"type": "string", "description": "Letters, digits, - and _ only."},
                "command": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": 'Local server argv, e.g. ["npx", "-y", "pkg"].',
                },
                "url": {"type": "string", "description": "Remote server http(s) URL."},
                "env": {"type": "object", "description": "Environment variables for a local server."},
                "headers": {"type": "object", "description": "HTTP headers for a remote server."},
                "agents": AGENTS,
            },
            ["name"],
        ),
    ),
    "mcp_server_remove": (
        "mcpRemove",
        _tool(
            "mcp_server_remove",
            "Remove a user-added MCP server from the agents' configs. Built-in and-code-* servers cannot be removed.",
            {"name": {"type": "string"}, "agents": AGENTS},
            ["name"],
        ),
    ),
}

TOOLS = [definition for _, definition in TOOL_TABLE.values()]
HANDLERS = {name: (lambda args, op=op: _call(op, args)) for name, (op, _) in TOOL_TABLE.items()}


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
                        "serverInfo": {"name": "and-code-settings", "version": "1.0.0"},
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
