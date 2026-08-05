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
const val ROUTE_SETTINGS_LEGAL = "settings-legal"
const val ROUTE_SETTINGS_LEGAL_DOCUMENT = "settings-legal-document"
const val SETTINGS_LEGAL_DOCUMENT_ARG_ID = "docId"
const val SETTINGS_LEGAL_DOCUMENT_ROUTE_PATTERN = "$ROUTE_SETTINGS_LEGAL_DOCUMENT/{$SETTINGS_LEGAL_DOCUMENT_ARG_ID}"

fun settingsLegalDocumentRoute(docId: String): String = "$ROUTE_SETTINGS_LEGAL_DOCUMENT/$docId"

const val ROUTE_WORKSPACES = "workspaces"
const val WORKSPACE_DETAIL_ROUTE = "workspace-detail"
const val LOCAL_RUNTIME_MANAGEMENT_ROUTE = "local-runtime-management"
const val ROUTE_SCHEDULES = "schedules"
const val ROUTE_SCHEDULE_DETAIL = "schedule-detail"
const val SCHEDULE_DETAIL_ROUTE_PATTERN = "$ROUTE_SCHEDULE_DETAIL/{scheduleId}"
const val ROUTE_SCHEDULE_EDIT = "schedule-edit"

/**
 * The editor doubles as the "new schedule" screen, so [SCHEDULE_EDIT_ARG_ID] has to be optional.
 * Navigation only treats query parameters as optional - as a path segment it would be required and
 * navigating to the bare [ROUTE_SCHEDULE_EDIT] would not match any destination.
 */
const val SCHEDULE_EDIT_ARG_ID = "scheduleId"
const val SCHEDULE_EDIT_ROUTE_PATTERN = "$ROUTE_SCHEDULE_EDIT?$SCHEDULE_EDIT_ARG_ID={$SCHEDULE_EDIT_ARG_ID}"
const val ROUTE_CODE_VIEWER = "code-viewer"
const val ROUTE_TERMINAL = "terminal"
const val ROUTE_GUEST_BROWSER = "guest-browser"
const val GUEST_BROWSER_ARG_URL = "url"
const val GUEST_BROWSER_ROUTE_PATTERN = "$ROUTE_GUEST_BROWSER?$GUEST_BROWSER_ARG_URL={$GUEST_BROWSER_ARG_URL}"

fun guestBrowserRoute(url: String): String = "$ROUTE_GUEST_BROWSER?$GUEST_BROWSER_ARG_URL=${encodeRouteArg(url)}"

const val CODE_VIEWER_ROUTE_PATTERN = "$ROUTE_CODE_VIEWER/{runtimeId}/{workspacePath}/{filePath}"

fun scheduleDetailRoute(scheduleId: String): String = "$ROUTE_SCHEDULE_DETAIL/${encodeRouteArg(scheduleId)}"

fun scheduleEditRoute(scheduleId: String): String = "$ROUTE_SCHEDULE_EDIT?$SCHEDULE_EDIT_ARG_ID=${encodeRouteArg(scheduleId)}"

fun codeViewerRoute(
    runtimeId: String,
    workspacePath: String,
    filePath: String,
): String = "$ROUTE_CODE_VIEWER/${encodeRouteArg(runtimeId)}/${encodeRouteArg(workspacePath)}/${encodeRouteArg(filePath)}"

fun decodeRouteArg(value: String): String = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)

private fun encodeRouteArg(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

val DRAWER_ROOT_ROUTES = setOf(ROUTE_CHAT, ROUTE_SETTINGS, ROUTE_SCHEDULES)
