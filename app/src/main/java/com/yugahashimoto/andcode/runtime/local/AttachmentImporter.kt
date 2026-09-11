package com.yugahashimoto.andcode.runtime.local

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import com.yugahashimoto.andcode.core.api.PromptAttachment
import java.io.ByteArrayOutputStream

class AttachmentImporter(
    private val context: Context,
) {
    /**
     * Imports [uri] as a single attachment. For video this keeps only the first
     * extracted frame; use [importAll] to receive every frame.
     */
    fun import(uri: Uri): PromptAttachment = importAll(uri).first()

    /**
     * Imports [uri] as one or more model-sendable attachments.
     *
     * Video files are not sent raw: most providers reject video file parts, so
     * representative JPEG frames are extracted instead. Everything else keeps the
     * previous single-attachment behaviour.
     */
    fun importAll(uri: Uri): List<PromptAttachment> {
        val filename = sanitize(queryDisplayName(uri) ?: "attachment-${System.currentTimeMillis()}")
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        if (VideoAttachmentHelper.isVideoMime(mime)) {
            return importVideoFrames(uri, filename)
        }
        val bytes =
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open attachment input stream" }
                input.readBytes()
            }
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return listOf(
            PromptAttachment(
                filename = filename,
                mime = mime,
                url = "data:$mime;base64,$encoded",
            ),
        )
    }

    fun import(
        bitmap: Bitmap,
        filename: String = "image-${System.currentTimeMillis()}.jpg",
    ): PromptAttachment {
        val baos = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)) { "Cannot encode attachment" }
        val encoded = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        return PromptAttachment(sanitize(filename), "image/jpeg", "data:image/jpeg;base64,$encoded")
    }

    private fun importVideoFrames(
        uri: Uri,
        filename: String,
    ): List<PromptAttachment> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs =
                runCatching {
                    retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                }.getOrDefault(0L)
            val timestampsUs = VideoAttachmentHelper.frameTimestampsUs(durationMs * 1_000L)
            val frames =
                timestampsUs.mapIndexedNotNull { index, timestampUs ->
                    val frame =
                        runCatching {
                            retriever.getFrameAtTime(
                                timestampUs,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            )
                        }.getOrNull() ?: return@mapIndexedNotNull null
                    try {
                        val (scaledWidth, scaledHeight) =
                            VideoAttachmentHelper.scaledDimensions(frame.width, frame.height)
                        if (scaledWidth <= 0 || scaledHeight <= 0) return@mapIndexedNotNull null
                        val scaled =
                            if (scaledWidth == frame.width && scaledHeight == frame.height) {
                                frame
                            } else {
                                Bitmap.createScaledBitmap(frame, scaledWidth, scaledHeight, true)
                            }
                        try {
                            val baos = ByteArrayOutputStream()
                            check(
                                scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos),
                            ) { "Cannot encode video frame" }
                            val encoded = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                            PromptAttachment(
                                filename = VideoAttachmentHelper.frameFilename(filename, index),
                                mime = "image/jpeg",
                                url = "data:image/jpeg;base64,$encoded",
                            )
                        } finally {
                            if (scaled !== frame) scaled.recycle()
                        }
                    } finally {
                        frame.recycle()
                    }
                }
            require(frames.isNotEmpty()) { "Could not extract images from video" }
            return frames
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            Log.w("AttachmentImporter", "Failed to extract video frames", e)
            throw IllegalArgumentException("Could not extract images from video", e)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()

    private fun sanitize(name: String): String = name.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "attachment" }
}
