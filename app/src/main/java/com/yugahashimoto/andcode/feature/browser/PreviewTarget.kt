package com.yugahashimoto.andcode.feature.browser

import java.io.File

/** What a chat preview is showing: a loopback dev server or a static site in the workspace. */
sealed interface PreviewTarget {
    /** Stable identity, used as a Compose key and to deduplicate the reopen list. */
    val key: String

    /** Short human-readable label for bars and pickers. */
    val label: String

    data class Url(val url: String) : PreviewTarget {
        override val key: String get() = "url:$url"
        override val label: String get() = url.substringAfter("://")
    }

    data class Static(val rootDir: String, val entry: String) : PreviewTarget {
        override val key: String get() = "static:$rootDir/$entry"
        override val label: String get() = entry
    }
}

/** The three places a preview can be: gone, full card, or collapsed to a floating button. */
sealed interface PreviewUiState {
    data object None : PreviewUiState

    data class Active(val target: PreviewTarget) : PreviewUiState

    data class Collapsed(val target: PreviewTarget) : PreviewUiState
}

private val PREVIEW_ENTRY_EXCLUDED_DIRS =
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
 * Finds the entry page to serve for a static preview: the shallowest `index.html` in the workspace,
 * skipping vendored and generated directories. Returns a path relative to [rootDir].
 */
fun findPreviewEntry(rootDir: File): String? {
    if (!rootDir.isDirectory) return null
    return rootDir
        .walkTopDown()
        .maxDepth(PREVIEW_ENTRY_MAX_DEPTH)
        .onEnter { directory -> directory.name !in PREVIEW_ENTRY_EXCLUDED_DIRS && !directory.name.startsWith(".") }
        .filter { it.isFile && it.name.equals("index.html", ignoreCase = true) }
        .minByOrNull { it.relativeTo(rootDir).invariantSeparatorsPath.count { char -> char == '/' } }
        ?.relativeTo(rootDir)
        ?.invariantSeparatorsPath
}

private const val PREVIEW_ENTRY_MAX_DEPTH = 6
