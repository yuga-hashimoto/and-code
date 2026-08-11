package com.yugahashimoto.andcode.feature.browser

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

private const val COMMAND_FILE_RELATIVE_PATH = ".and-code/browser-command.json"
private const val POLL_INTERVAL_MILLIS = 1000L

/**
 * Watches the active workspace for a browser command written by the in-guest agent
 * (`.and-code/browser-command.json`, e.g. `{"action":"open","url":"http://127.0.0.1:8080/"}`)
 * and opens the guest browser at the requested URL so the user can watch and join in.
 */
@Composable
fun GuestBrowserCommandWatcher(
    workspacePath: String?,
    onOpenUrl: (String) -> Unit,
) {
    LaunchedEffect(workspacePath) {
        val path = workspacePath ?: return@LaunchedEffect
        val commandFile = File(path, COMMAND_FILE_RELATIVE_PATH)
        while (true) {
            delay(POLL_INTERVAL_MILLIS)
            val url = withContext(Dispatchers.IO) { consumeOpenCommand(commandFile) }
            if (url != null) {
                onOpenUrl(url)
            }
        }
    }
}

private fun consumeOpenCommand(commandFile: File): String? =
    runCatching {
        if (!commandFile.exists()) {
            return null
        }
        val text = commandFile.readText()
        commandFile.delete()
        JSONObject(text).optString("url").takeIf { url ->
            url.isNotBlank() && Uri.parse(url).scheme in setOf("http", "https")
        }
    }.getOrNull()
