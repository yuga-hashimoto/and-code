package com.yugahashimoto.andcode.feature.chat

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yugahashimoto.andcode.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL

data class ChatImageSource(
    val url: String,
    val mime: String,
    val filename: String? = null,
    val preview: Bitmap? = null,
)

@Composable
fun ChatImageViewerDialog(
    source: ChatImageSource,
    onDismiss: () -> Unit,
    onDownload: (ChatImageSource) -> Unit,
) {
    val context = LocalContext.current
    val loadResult by
        produceState(initialValue = false to source.preview, source.url) {
            value = true to loadChatImageBitmap(context, source)
        }
    val loaded = loadResult.first
    val bitmap = loadResult.second
    var scale by remember(source.url) { mutableFloatStateOf(1f) }
    var offset by remember(source.url) { mutableStateOf(Offset.Zero) }
    val transformState =
        rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, 5f)
            offset = if (scale == 1f) Offset.Zero else offset + panChange
        }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("chat-image-viewer")) {
            when (val image = bitmap) {
                null ->
                    if (loaded) {
                        Text(
                            stringResource(R.string.image_load_failed),
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                    }
                else ->
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = source.filename ?: stringResource(R.string.cd_image_preview),
                        contentScale = ContentScale.Fit,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offset.x,
                                    translationY = offset.y,
                                )
                                .transformable(transformState),
                    )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp).testTag("chat-image-close"),
            ) {
                Icon(Icons.Default.Close, stringResource(R.string.cd_close_image), tint = Color.White)
            }
            IconButton(
                onClick = { onDownload(source) },
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).testTag("chat-image-download"),
            ) {
                Icon(Icons.Default.Download, stringResource(R.string.cd_download_image), tint = Color.White)
            }
            if (!loaded) {
                Text(
                    text = stringResource(R.string.image_loading),
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.Center).padding(top = 72.dp),
                )
            }
        }
    }
}

suspend fun loadChatImageBitmap(
    context: Context,
    source: ChatImageSource,
): Bitmap? =
    withContext(Dispatchers.IO) {
        val bytes = loadChatImageBytes(context, source) ?: return@withContext source.preview
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext source.preview
        var sample = 1
        while (bounds.outWidth / sample > VIEWER_MAX_DIMENSION || bounds.outHeight / sample > VIEWER_MAX_DIMENSION) {
            sample *= 2
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: source.preview
    }

suspend fun loadChatImageBytes(
    context: Context,
    source: ChatImageSource,
): ByteArray? =
    withContext(Dispatchers.IO) {
        runCatching {
            when {
                source.url.startsWith("data:") -> {
                    val data = parseDataImageUri(source.url) ?: error("Invalid image data URL")
                    require(data.base64.length <= MAX_IMAGE_BASE64_CHARS)
                    Base64.decode(data.base64, Base64.DEFAULT).also { require(it.size <= MAX_IMAGE_BYTES) }
                }
                source.url.startsWith("content:") ->
                    context.contentResolver.openInputStream(Uri.parse(source.url))!!.use(::readLimited)
                source.url.startsWith("http://") || source.url.startsWith("https://") -> loadRemoteImage(source)
                else -> {
                    val workspace = File(context.filesDir, "runtime/workspace")
                    val roots =
                        listOf(
                            File(context.filesDir, "runtime/environment/antigravity-rootfs"),
                            File(context.filesDir, "runtime/environment/rootfs"),
                        )
                    val allowedRoots = listOf(workspace) + roots
                    val file =
                        imageFileCandidates(source.url, workspace, roots).firstOrNull { candidate ->
                            candidate.exists() && allowedRoots.any { root -> candidate.isInside(root) }
                        }
                    file?.inputStream()?.use(::readLimited)
                        ?: error("Image file not found")
                }
            }
        }.getOrNull()
    }

suspend fun saveChatImageToPictures(
    context: Context,
    source: ChatImageSource,
): Boolean =
    withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext false
        val bytes = loadOriginalOrPreviewBytes(context, source) ?: return@withContext false
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, safeImageFilename(source))
                put(MediaStore.Images.Media.MIME_TYPE, effectiveImageMime(source))
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AndCode")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Cannot open image destination")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }

suspend fun saveChatImageToUri(
    context: Context,
    source: ChatImageSource,
    destination: Uri,
): Boolean =
    withContext(Dispatchers.IO) {
        val bytes = loadOriginalOrPreviewBytes(context, source) ?: return@withContext false
        runCatching {
            context.contentResolver.openOutputStream(destination)?.use { it.write(bytes) }
                ?: error("Cannot open image destination")
        }.isSuccess
    }

fun safeImageFilename(source: ChatImageSource): String {
    val fallbackExtension = effectiveImageMime(source).substringAfter('/', "jpg").substringBefore('+').ifBlank { "jpg" }
    val raw = source.filename?.takeIf { it.isNotBlank() } ?: "andcode-image-${System.currentTimeMillis()}.$fallbackExtension"
    return raw.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "andcode-image.$fallbackExtension" }
}

private fun effectiveImageMime(source: ChatImageSource): String {
    if (source.mime.startsWith("image/") && source.mime != "image/*") return source.mime
    parseDataImageUri(source.url)?.mime?.let { return it }
    return when (source.url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        else -> "image/png"
    }
}

private suspend fun loadOriginalOrPreviewBytes(
    context: Context,
    source: ChatImageSource,
): ByteArray? =
    loadChatImageBytes(context, source)
        ?: source.preview?.let { bitmap ->
            ByteArrayOutputStream().use { output ->
                if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) output.toByteArray() else null
            }
        }

private fun loadRemoteImage(source: ChatImageSource): ByteArray {
    val url = URL(source.url)
    require(url.protocol == "https" && url.host.isPublicHost()) { "Private image URL is not allowed" }
    val connection = url.openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 20_000
    connection.instanceFollowRedirects = false
    try {
        require(connection.responseCode in 200..299)
        // Image hosts used by generation tools occasionally return octet-stream (or no
        // Content-Type) for a valid image. BitmapFactory remains the final format check.
        val contentType = connection.contentType.orEmpty().substringBefore(';').trim()
        require(contentType.isEmpty() || contentType == "application/octet-stream" || contentType.startsWith("image/"))
        return connection.inputStream.use(::readLimited)
    } finally {
        connection.disconnect()
    }
}

private fun String.isPublicHost(): Boolean =
    InetAddress.getAllByName(this).all { address ->
        !address.isAnyLocalAddress &&
            !address.isLoopbackAddress &&
            !address.isLinkLocalAddress &&
            !address.isSiteLocalAddress &&
            !(address is Inet6Address && (address.address.first().toInt() and 0xFE) == 0xFC)
    }

private fun File.isInside(root: File): Boolean {
    val canonicalRoot = root.canonicalFile.toPath()
    return canonicalFile.toPath().startsWith(canonicalRoot)
}

private fun readLimited(input: java.io.InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        require(total <= MAX_IMAGE_BYTES) { "Image is too large" }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private const val MAX_IMAGE_BYTES = 25 * 1024 * 1024
private const val MAX_IMAGE_BASE64_CHARS = 34_952_536
private const val VIEWER_MAX_DIMENSION = 4096
