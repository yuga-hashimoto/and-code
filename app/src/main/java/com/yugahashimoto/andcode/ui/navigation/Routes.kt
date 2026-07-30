package com.yugahashimoto.andcode.ui.navigation

import java.util.Base64

const val ROUTE_ONBOARDING = "onboarding"
const val ROUTE_ANDROID_SETUP = "android-setup"
const val ROUTE_REMOTE_CONNECTION = "remote-connection"
const val ROUTE_CHAT = "chat"
const val ROUTE_SETTINGS = "settings"
const val ROUTE_SETTINGS_VOICE = "settings-voice"
const val ROUTE_SETTINGS_PROVIDERS = "settings-providers"
const val ROUTE_SETTINGS_AGENTS = "settings-agents"
const val ROUTE_SETTINGS_AGENT_OPENCODE = "settings-agent-opencode"
const val ROUTE_SETTINGS_AGENT_CLAUDE = "settings-agent-claude"
const val ROUTE_SETTINGS_AGENT_ANTIGRAVITY = "settings-agent-antigravity"
const val ROUTE_SETTINGS_GITHUB = "settings-github"
const val ROUTE_SETTINGS_MCP = "settings-mcp"
const val ROUTE_SETTINGS_MCP_CLAUDE = "settings-mcp-claude"
const val ROUTE_SETTINGS_MCP_ANTIGRAVITY = "settings-mcp-antigravity"
const val ROUTE_SETTINGS_MODEL_VISIBILITY = "settings-model-visibility"
const val ROUTE_SETTINGS_SERVER_INFO = "settings-server-info"
const val ROUTE_WORKSPACES = "workspaces"
const val WORKSPACE_DETAIL_ROUTE = "workspace-detail"
const val LOCAL_RUNTIME_MANAGEMENT_ROUTE = "local-runtime-management"
const val ROUTE_CODE_VIEWER = "code-viewer"
const val ROUTE_TERMINAL = "terminal"

const val CODE_VIEWER_ROUTE_PATTERN = "$ROUTE_CODE_VIEWER/{runtimeId}/{workspacePath}/{filePath}"

fun codeViewerRoute(
    runtimeId: String,
    workspacePath: String,
    filePath: String,
): String = "$ROUTE_CODE_VIEWER/${encodeRouteArg(runtimeId)}/${encodeRouteArg(workspacePath)}/${encodeRouteArg(filePath)}"

fun decodeRouteArg(value: String): String = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)

private fun encodeRouteArg(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

val DRAWER_ROOT_ROUTES = setOf(ROUTE_CHAT, ROUTE_SETTINGS)
