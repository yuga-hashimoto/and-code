package com.yugahashimoto.andcode.feature.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.yugahashimoto.andcode.core.storage.DeviceStorage
import com.yugahashimoto.andcode.runtime.LocalAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private const val POLL_INTERVAL_MILLIS = 1000L

/**
 * Watches the active workspace for a browser command written by the in-guest agent
 * (`.and-code/browser-command.json`, e.g. `{"action":"open","url":"http://127.0.0.1:8080/"}`)
 * and opens the guest browser at the requested URL so the user can watch and join in.
 */
@Composable
fun GuestBrowserCommandWatcher(
    workspacePath: String?,
    agent: LocalAgent?,
    runtimeDirectory: File,
    onOpenUrl: (String) -> Unit,
) {
    val openUrl by rememberUpdatedState(onOpenUrl)
    LaunchedEffect(workspacePath, agent, runtimeDirectory) {
        if (workspacePath == null || agent == null) return@LaunchedEffect
        while (true) {
            delay(POLL_INTERVAL_MILLIS)
            val url =
                withContext(Dispatchers.IO) {
                    consumeBrowserOpenCommand(workspacePath, agent, runtimeDirectory, DeviceStorage.mounts())
                }
            if (url != null) {
                openUrl(url)
            }
        }
    }
}
