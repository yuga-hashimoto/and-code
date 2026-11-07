package com.yugahashimoto.andcode.feature.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.yugahashimoto.andcode.core.storage.DeviceStorage
import com.yugahashimoto.andcode.core.util.pollWhileActive
import com.yugahashimoto.andcode.runtime.LocalAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

private const val POLL_INTERVAL_MILLIS = 1000L

/**
 * Watches the active workspace for a browser command written by the in-guest agent
 * (`.and-code/browser-command.json`, e.g. `{"action":"open","url":"http://127.0.0.1:8080/"}` or
 * `{"action":"preview","url":"http://127.0.0.1:5173/"}`) and hands it to the caller: an `open`
 * command fills the guest browser, a `preview` command pins the page inside the conversation.
 *
 * Only polls while [foreground] is `true`: opening a browser nobody is looking at achieves nothing,
 * and a composition outlives the activity being stopped, so without the gate this read the disk
 * every second for as long as the process was alive. A command written while the app was away is
 * opened as soon as it returns.
 *
 * [foreground] keys the effect, so it must be a reference that stays the same across recompositions
 * (the app's own `StateFlow` is); a flow built inline would restart the watcher, and its immediate
 * first poll, on every recomposition.
 */
@Composable
fun GuestBrowserCommandWatcher(
    workspacePath: String?,
    agent: LocalAgent?,
    runtimeDirectory: File,
    foreground: Flow<Boolean>,
    onCommand: (BrowserCommand) -> Unit,
) {
    val currentOnCommand by rememberUpdatedState(onCommand)
    LaunchedEffect(workspacePath, agent, runtimeDirectory, foreground) {
        if (workspacePath == null || agent == null) return@LaunchedEffect
        watchBrowserCommands(
            active = foreground,
            poll = {
                withContext(Dispatchers.IO) {
                    consumeBrowserOpenCommand(workspacePath, agent, runtimeDirectory, DeviceStorage.mounts())
                }
            },
            onCommand = { command -> currentOnCommand(command) },
        )
    }
}

/**
 * The watcher's loop, apart from Compose so the gating can be tested with a virtual clock.
 *
 * [poll] suspends while it reads the command file, so a gate that closes in that instant can drop
 * the command it has just consumed. That is a window of milliseconds at the moment the app is
 * leaving the screen, when nobody is left to watch the browser open.
 */
internal suspend fun watchBrowserCommands(
    active: Flow<Boolean>,
    poll: suspend () -> BrowserCommand?,
    onCommand: (BrowserCommand) -> Unit,
    intervalMillis: Long = POLL_INTERVAL_MILLIS,
) {
    active.pollWhileActive(intervalMillis) { poll()?.let(onCommand) }
}
