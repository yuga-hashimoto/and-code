package com.yugahashimoto.andcode.feature.browser

import com.yugahashimoto.andcode.core.storage.DeviceStorage
import com.yugahashimoto.andcode.feature.workspace.WorkspaceFolders
import com.yugahashimoto.andcode.runtime.LocalAgent
import org.json.JSONObject
import java.io.File
import java.net.URI

internal const val BROWSER_COMMAND_FILE = ".and-code/browser-command.json"
internal const val BROWSER_STATUS_FILE = ".and-code/browser-status.json"

internal const val BROWSER_STATUS_AWAITING_USER = "awaiting_user"
internal const val BROWSER_STATUS_AGENT = "agent"
internal const val BROWSER_STATUS_DISMISSED = "dismissed"

/** What the agent asked the app to do with the Guest Browser. */
enum class BrowserAction { OPEN, HANDOFF }

/**
 * A validated agent command, already consumed from the workspace.
 *
 * `open` carries the web page to show. `handoff` never carries one: it asks for the page the user
 * is already on, so it cannot reload the sign-in they just completed.
 */
data class BrowserCommand(
    val action: BrowserAction,
    val url: String,
    /** Where the app records handoff progress for the agent to poll. */
    val statusFile: File,
)

/** Records the handoff state the agent polls; the file is the only cross-process channel. */
internal fun writeBrowserStatus(
    file: File,
    state: String,
) {
    runCatching {
        file.parentFile?.mkdirs()
        file.writeText(JSONObject().put("state", state).toString())
    }
}

/** Resolves the active guest workspace to the same host folder its PRoot process sees. */
internal fun consumeBrowserCommand(
    workspacePath: String?,
    agent: LocalAgent?,
    runtimeDirectory: File,
    deviceStorage: DeviceStorage.Mounts,
): BrowserCommand? {
    // A remote workspace can share a path with a local one, but its commands are not on this phone.
    if (workspacePath == null || agent == null) return null
    return runCatching {
        val rootfs =
            File(runtimeDirectory, "environment/antigravity-rootfs")
                .takeIf { agent == LocalAgent.ANTIGRAVITY && it.isDirectory }
                ?: File(runtimeDirectory, "environment/rootfs")
        val workspace =
            WorkspaceFolders.hostDirectory(rootfs, File(runtimeDirectory, "workspace"), workspacePath, deviceStorage)
                ?: error("workspace is not mounted")
        val commandFile = File(workspace, BROWSER_COMMAND_FILE)
        check(commandFile.isFile) { "no browser command" }
        val command = JSONObject(commandFile.readText())
        val action = actionOf(command.optString("action"))
        val url = command.optString("url")
        // An `open` must name a real web page, never a javascript:/file: URL that would let the
        // agent reach past the browser. A `handoff` must not name one at all: it acts on the page
        // the user is already on, so acting on a URL here would silently mean something else.
        check(action != null) { "invalid browser command" }
        check(
            when (action) {
                BrowserAction.OPEN -> isWebUrl(url)
                BrowserAction.HANDOFF -> url.isBlank()
            },
        ) { "invalid browser command" }
        // The agent writes this file directly. Deleting before parsing can discard a partial write;
        // only consume a complete, valid command, and do not carry it out if deletion fails.
        check(commandFile.delete()) { "could not consume the browser command" }
        val statusFile = File(workspace, BROWSER_STATUS_FILE)
        // Written before the banner is shown, on the watcher's IO thread, so the agent never sees
        // a stale "awaiting_user" from a previous handoff.
        if (action == BrowserAction.HANDOFF) {
            writeBrowserStatus(statusFile, BROWSER_STATUS_AWAITING_USER)
        }
        BrowserCommand(action, url, statusFile)
    }.getOrNull()
}

private fun actionOf(raw: String): BrowserAction? =
    when (raw) {
        "open" -> BrowserAction.OPEN
        "handoff" -> BrowserAction.HANDOFF
        else -> null
    }

private fun isWebUrl(url: String): Boolean {
    val uri = URI(url)
    return uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
}
