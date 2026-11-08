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

/** A command dropped by the in-guest agent into `.and-code/browser-command.json`. */
sealed interface BrowserCommand {
    /** Opens the full-screen guest browser. */
    data class Open(val url: String) : BrowserCommand

    /** Embeds the page in the conversation as a pinned preview. */
    data class Preview(val url: String) : BrowserCommand

    /**
     * Hands the page the user is already on to them and waits until they hand it back.
     *
     * It never carries a URL: acting on a URL here would reload the sign-in the user just
     * completed. [statusFile] is where the app records the outcome for the agent to poll.
     */
    data class Handoff(val statusFile: File) : BrowserCommand
}

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

/**
 * Resolves the active guest workspace to the same host folder its PRoot process sees, or null when
 * the workspace is remote or the runtime is not local.
 */
internal fun activeWorkspaceHostDirectory(
    workspacePath: String?,
    agent: LocalAgent?,
    runtimeDirectory: File,
    deviceStorage: DeviceStorage.Mounts,
): File? {
    if (workspacePath == null || agent == null) return null
    val rootfs =
        File(runtimeDirectory, "environment/antigravity-rootfs")
            .takeIf { agent == LocalAgent.ANTIGRAVITY && it.isDirectory }
            ?: File(runtimeDirectory, "environment/rootfs")
    return WorkspaceFolders.hostDirectory(rootfs, File(runtimeDirectory, "workspace"), workspacePath, deviceStorage)
}

/**
 * Reads and consumes the agent's command.
 *
 * [allowRemotePreview] is the agent-control opt-in: the inline preview is a live WebView inside the
 * conversation, so by default it stays on loopback. An explicit agent `preview` of a public page is
 * allowed only when the user has turned that opt-in on; automatic link previews never reach here
 * with a public URL.
 */
internal fun consumeBrowserCommand(
    workspacePath: String?,
    agent: LocalAgent?,
    runtimeDirectory: File,
    deviceStorage: DeviceStorage.Mounts,
    allowRemotePreview: Boolean = false,
): BrowserCommand? {
    // A remote workspace can share a path with a local one, but its commands are not on this phone.
    if (workspacePath == null || agent == null) return null
    return runCatching {
        // Misses are the common case (polled once a second), so they return instead of throwing:
        // `runCatching` is inline and a non-local return leaves no exception behind.
        val workspace =
            activeWorkspaceHostDirectory(workspacePath, agent, runtimeDirectory, deviceStorage)
                ?: return null
        val commandFile = File(workspace, BROWSER_COMMAND_FILE)
        if (!commandFile.isFile) return null
        val command = JSONObject(commandFile.readText())
        val url = command.optString("url")
        // An `open`/`preview` must name a real web page, never a javascript:/file: URL that would
        // let the agent reach past the browser. A `handoff` must not name one at all: it acts on the
        // page the user is already on, so acting on a URL here would silently mean something else.
        val parsed =
            when (command.optString("action")) {
                "open" -> BrowserCommand.Open(url).takeIf { isWebUrl(url) }
                "preview" ->
                    BrowserCommand.Preview(url).takeIf {
                        // Loopback pages are the feature's normal case; a public page is only ever
                        // embedded after the opt-in, and only over https so it cannot be tampered
                        // with on the way in.
                        isLoopbackPreviewUrl(url) || (allowRemotePreview && isHttpsUrl(url))
                    }
                "handoff" -> BrowserCommand.Handoff(File(workspace, BROWSER_STATUS_FILE)).takeIf { url.isBlank() }
                else -> null
            }
        // The agent writes this file directly. Deleting before parsing can discard a partial write;
        // only consume a complete, valid command, and do not carry it out if deletion fails.
        parsed
            ?.takeIf { commandFile.delete() }
            ?.also { consumed ->
                // Written before the banner is shown, on the watcher's IO thread, so the agent never
                // sees a stale "awaiting_user" from a previous handoff.
                if (consumed is BrowserCommand.Handoff) {
                    writeBrowserStatus(consumed.statusFile, BROWSER_STATUS_AWAITING_USER)
                }
            }
    }.getOrNull()
}

private fun isWebUrl(url: String): Boolean {
    val uri = URI(url)
    return uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
}

private fun isHttpsUrl(url: String): Boolean {
    val uri = URI(url)
    return uri.scheme?.lowercase() == "https" && !uri.host.isNullOrBlank()
}
