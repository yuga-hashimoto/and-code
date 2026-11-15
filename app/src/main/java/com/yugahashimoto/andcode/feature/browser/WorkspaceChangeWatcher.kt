package com.yugahashimoto.andcode.feature.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private const val DEFAULT_INTERVAL_MILLIS = 1500L
private const val DEBOUNCE_MILLIS = 500L
private const val MAX_FILES = 4000

private val WEB_EXTENSIONS =
    setOf(
        "html",
        "htm",
        "css",
        "js",
        "mjs",
        "cjs",
        "jsx",
        "ts",
        "tsx",
        "vue",
        "svelte",
        "json",
        "svg",
        "png",
        "jpg",
        "jpeg",
        "gif",
        "webp",
        "avif",
        "ico",
        "woff",
        "woff2",
        "ttf",
        "otf",
        "wasm",
        "map",
        "xml",
        "txt",
    )

private val WATCH_EXCLUDED_DIRS =
    setOf(
        "node_modules",
        ".git",
        "build",
        "dist",
        ".gradle",
        ".idea",
        ".next",
        ".nuxt",
        "target",
        "__pycache__",
        ".venv",
        "venv",
        "coverage",
        "out",
    )

/**
 * Reloads a live preview when the agent writes to web-facing files under [rootDir].
 *
 * Polling rather than a recursive FileObserver: it survives new directories without re-registering
 * and is bounded by the extension filter and [MAX_FILES], so a large repository costs a short walk
 * rather than a watch on every inode. Cancellation (leaving the preview, backgrounding the app)
 * stops the loop through [delay].
 */
internal suspend fun watchWorkspaceChanges(
    rootDir: File,
    intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    onChanged: () -> Unit,
) {
    var previous = withContext(Dispatchers.IO) { workspaceFingerprint(rootDir) }
    while (true) {
        delay(intervalMillis)
        val next = withContext(Dispatchers.IO) { workspaceFingerprint(rootDir) }
        if (next == previous) continue
        // Let a burst of writes settle so one agent step causes one reload, not one per file.
        delay(DEBOUNCE_MILLIS)
        previous = withContext(Dispatchers.IO) { workspaceFingerprint(rootDir) }
        onChanged()
    }
}

/** Cheap content fingerprint of the web files under [rootDir]; changes when any of them change. */
internal fun workspaceFingerprint(rootDir: File): Long {
    var hash = 1125899906842597L
    var count = 0
    val files =
        rootDir
            .walkTopDown()
            .onEnter { directory -> directory.name !in WATCH_EXCLUDED_DIRS && !directory.name.startsWith(".") }
            .filter { file -> file.isFile && file.extension.lowercase() in WEB_EXTENSIONS }
    for (file in files) {
        if (count++ >= MAX_FILES) break
        hash = hash * 31 + file.relativeTo(rootDir).invariantSeparatorsPath.hashCode()
        hash = hash * 31 + file.length()
        hash = hash * 31 + file.lastModified()
    }
    return hash
}
