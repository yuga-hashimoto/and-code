package com.yugahashimoto.andcode.feature.browser

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File

/** Virtual host used to serve a static site from the workspace without opening a port. */
const val STATIC_PREVIEW_HOST = "andcode.preview"

/** Builds the virtual URL a static preview loads inside the WebView. */
fun staticPreviewUrl(entry: String): String {
    val encoded =
        entry
            .split('/')
            .filter(String::isNotBlank)
            .joinToString("/") { segment -> Uri.encode(segment) }
    return "https://$STATIC_PREVIEW_HOST/$encoded"
}

/** Serves static preview files to the WebView and lets every other host reach the network. */
class StaticAssetInterceptor(
    rootDir: File,
    entry: String = "index.html",
) {
    private val resolver = StaticAssetResolver(rootDir, entry)

    fun intercept(uri: Uri): WebResourceResponse? {
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (!uri.host.equals(STATIC_PREVIEW_HOST, ignoreCase = true)) return null
        val asset = resolver.resolve(uri.path.orEmpty()) ?: return notFound()
        return WebResourceResponse(
            asset.mimeType,
            "UTF-8",
            200,
            "OK",
            mapOf("Cache-Control" to "no-store", "Pragma" to "no-cache"),
            asset.file.inputStream(),
        )
    }

    private fun notFound(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            404,
            "Not Found",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )
}

/** Pure path resolution, kept apart from WebView types so it can be unit-tested on the JVM. */
internal class StaticAssetResolver(
    private val rootDir: File,
    private val entry: String = "index.html",
) {
    fun resolve(path: String): ResolvedAsset? {
        val root = rootDir.canonicalFile
        if (!root.isDirectory) return null
        val entrySubdir = entry.substringBeforeLast('/', missingDelimiterValue = "").trim('/')
        val entryBase =
            if (entrySubdir.isEmpty()) {
                root
            } else {
                File(root, entrySubdir).canonicalFile.takeIf { it.isDirectory && it.toPath().startsWith(root.toPath()) } ?: root
            }
        val resolved =
            resolveUnder(root, root, path)
                ?: entryBase.takeIf { it != root }?.let { resolveUnder(root, it, path) }
                ?: return null
        return ResolvedAsset(resolved, mimeTypeFor(resolved.name))
    }

    private fun resolveUnder(
        boundaryRoot: File,
        baseDir: File,
        path: String,
    ): File? {
        val decoded = path.trimStart('/').ifBlank { "index.html" }
        val direct = File(baseDir, decoded).canonicalFile
        if (!direct.toPath().startsWith(boundaryRoot.toPath())) return null
        val candidate =
            when {
                direct.isFile -> direct
                File(direct, "index.html").isFile -> File(direct, "index.html").canonicalFile
                // Single-page apps route client-side: an extensionless path that is not a file
                // falls back to the entry directory's index instead of 404-ing.
                direct.extension.isBlank() -> File(baseDir, "index.html").canonicalFile.takeIf { it.isFile }
                else -> null
            } ?: return null
        return candidate.takeIf { it.toPath().startsWith(boundaryRoot.toPath()) }
    }
}

internal data class ResolvedAsset(
    val file: File,
    val mimeType: String,
)

private fun mimeTypeFor(name: String): String =
    when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js", "mjs", "cjs" -> "application/javascript"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "wasm" -> "application/wasm"
        "xml" -> "application/xml"
        "txt" -> "text/plain"
        "map" -> "application/json"
        else -> "application/octet-stream"
    }
