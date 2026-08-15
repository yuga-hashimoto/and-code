#!/usr/bin/env python3
"""AndCode guest schedule MCP server (stdlib only).

Bridges the agent to the schedule settings of the AndCode Android app:

- Agents run inside the app's embedded Linux guest, where the schedule store is
  unreachable: it lives in the app's private encrypted preferences.
- This server routes every ``schedule_*`` call through a request/response file
  bridge under the workspace (``/workspace/.and-code/schedule-bridge``). The app
  polls the ``pending/`` directory, executes the operation against its real
  schedule repository and alarm manager, and drops the reply into ``responses/``.

The tool surface mirrors what the schedules screen offers: list, inspect, create,
edit, delete, toggle, run-now, and run history.

Speaks MCP over stdio (newline-delimited JSON-RPC) with no third-party deps so
it runs on the bare runtime rootfs python3.
"""

import json
import os
import sys
import time
import uuid
from pathlib import Path

BRIDGE_ROOT = Path("/workspace/.and-code/schedule-bridge")
PENDING_DIR = BRIDGE_ROOT / "pending"
RESPONSES_DIR = BRIDGE_ROOT / "responses"

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
        {
            "op": op,
            "args": args or {},
            "createdAtMs": int(time.time() * 1000),
        },
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


def tool_list(args: dict) -> str:
    return _call("list", args)


def tool_get(args: dict) -> str:
    return _call("get", args)


def tool_runs(args: dict) -> str:
    return _call("runs", args)


def tool_create(args: dict) -> str:
    return _call("create", args)


def tool_update(args: dict) -> str:
    return _call("update", args)


def tool_delete(args: dict) -> str:
    return _call("delete", args)


def tool_set_enabled(args: dict) -> str:
    return _call("setEnabled", args)


def tool_run_now(args: dict) -> str:
    return _call("runNow", args)


SCHEDULE_PROPERTIES = {
    "name": {"type": "string", "description": "Display name; when blank the prompt's first line is used."},
    "runtimeId": {
        "type": "string",
        "description": "Runtime target (agent) the schedule runs on, e.g. 'local' or a Claude/Antigravity id.",
    },
    "workspacePath": {"type": "string", "description": "Working directory for the session, e.g. /workspace/foo."},
    "providerId": {"type": "string"},
    "modelId": {"type": "string"},
    "agentId": {"type": "string", "description": "OpenCode sub-agent name (build, plan, ...)."},
    "prompt": {"type": "string", "description": "Prompt the schedule sends to the agent."},
    "cron": {
        "type": "string",
        "description": "Five-field cron (minute hour day-of-month month day-of-week), e.g. '0 9 * * *'. Exactly one of cron/oneTimeAt.",
    },
    "oneTimeAt": {"type": "integer", "description": "Epoch millis for a single run. Exactly one of cron/oneTimeAt."},
    "enabled": {"type": "boolean"},
    "autoAcceptPermissions": {
        "type": "boolean",
        "description": "Per-schedule override for auto-approving tool permission requests.",
    },
}

TOOLS = [
    {
        "name": "schedule_list",
        "description": "List all schedules with their run settings and next fire time.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "schedule_get",
        "description": "Return one schedule with its run history.",
        "inputSchema": {
            "type": "object",
            "properties": {"scheduleId": {"type": "string"}},
            "required": ["scheduleId"],
        },
    },
    {
        "name": "schedule_runs",
        "description": "Return run history, optionally filtered to one schedule.",
        "inputSchema": {
            "type": "object",
            "properties": {"scheduleId": {"type": "string"}},
        },
    },
    {
        "name": "schedule_create",
        "description": "Create a schedule. Provide exactly one of cron (recurring) or oneTimeAt (single run).",
        "inputSchema": {
            "type": "object",
            "properties": SCHEDULE_PROPERTIES,
            "required": ["runtimeId", "prompt"],
        },
    },
    {
        "name": "schedule_update",
        "description": (
            "Update fields of an existing schedule. Omitting a field keeps its current value; "
            "explicit null clears providerId/modelId/agentId but keeps workspacePath."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {"scheduleId": {"type": "string"}, **SCHEDULE_PROPERTIES},
            "required": ["scheduleId"],
        },
    },
    {
        "name": "schedule_delete",
        "description": "Delete a schedule and its run history.",
        "inputSchema": {
            "type": "object",
            "properties": {"scheduleId": {"type": "string"}},
            "required": ["scheduleId"],
        },
    },
    {
        "name": "schedule_set_enabled",
        "description": "Enable or disable a schedule without changing its timing.",
        "inputSchema": {
            "type": "object",
            "properties": {"scheduleId": {"type": "string"}, "enabled": {"type": "boolean"}},
            "required": ["scheduleId", "enabled"],
        },
    },
    {
        "name": "schedule_run_now",
        "description": "Run a schedule immediately, outside of its cron timing.",
        "inputSchema": {
            "type": "object",
            "properties": {"scheduleId": {"type": "string"}},
            "required": ["scheduleId"],
        },
    },
]

HANDLERS = {
    "schedule_list": tool_list,
    "schedule_get": tool_get,
    "schedule_runs": tool_runs,
    "schedule_create": tool_create,
    "schedule_update": tool_update,
    "schedule_delete": tool_delete,
    "schedule_set_enabled": tool_set_enabled,
    "schedule_run_now": tool_run_now,
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
                        "serverInfo": {"name": "and-code-schedule", "version": "1.0.0"},
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
