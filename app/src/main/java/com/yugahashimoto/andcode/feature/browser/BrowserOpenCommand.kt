package com.yugahashimoto.andcode.feature.browser

import com.yugahashimoto.andcode.core.storage.DeviceStorage
import com.yugahashimoto.andcode.feature.workspace.WorkspaceFolders
import com.yugahashimoto.andcode.runtime.LocalAgent
import org.json.JSONObject
import java.io.File
import java.net.URI

/** A command dropped by the in-guest agent into `.and-code/browser-command.json`. */
sealed interface BrowserCommand {
    /** Opens the full-screen guest browser. */
    data class Open(val url: String) : BrowserCommand

    /** Embeds the page in the conversation as a pinned preview. */
    data class Preview(val url: String) : BrowserCommand
}

/** Resolves the active guest workspace to the same host folder its PRoot process sees. */
internal fun consumeBrowserOpenCommand(
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
                ?: return null
        val commandFile = File(workspace, ".and-code/browser-command.json")
        if (!commandFile.isFile) return null
        val command = JSONObject(commandFile.readText())
        val url = command.optString("url")
        val uri = URI(url)
        val validUrl = uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
        val parsed =
            when (command.optString("action")) {
                "open" -> url.takeIf { validUrl }?.let { BrowserCommand.Open(it) }
                // The inline preview is a live WebView inside the conversation, so it stays on
                // loopback; a rejected preview leaves the file for the agent to replace.
                "preview" ->
                    url.takeIf { validUrl && isLoopbackPreviewUrl(it) }?.let { BrowserCommand.Preview(it) }
                else -> null
            }
        // The agent writes this file directly. Deleting before parsing can discard a partial write;
        // only consume a complete, valid command, and do not reopen it if deletion fails.
        parsed?.takeIf { commandFile.delete() }
    }.getOrNull()
}
