package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoAttachmentHelperTest {
    @Test
    fun `detects video mime types`() {
        assertTrue(VideoAttachmentHelper.isVideoMime("video/mp4"))
        assertTrue(VideoAttachmentHelper.isVideoMime("video/quicktime"))
        assertTrue(VideoAttachmentHelper.isVideoMime("VIDEO/MP4"))
        assertFalse(VideoAttachmentHelper.isVideoMime("image/jpeg"))
        assertFalse(VideoAttachmentHelper.isVideoMime("application/pdf"))
        assertFalse(VideoAttachmentHelper.isVideoMime("application/octet-stream"))
    }

    @Test
    fun `detects image mime types`() {
        assertTrue(VideoAttachmentHelper.isImageMime("image/jpeg"))
        assertTrue(VideoAttachmentHelper.isImageMime("IMAGE/PNG"))
        assertFalse(VideoAttachmentHelper.isImageMime("video/mp4"))
        assertFalse(VideoAttachmentHelper.isImageMime("application/pdf"))
    }

    @Test
    fun `frame timestamps cover beginning middle and end`() {
        val timestamps = VideoAttachmentHelper.frameTimestampsUs(9_000_000L)
        assertEquals(listOf(0L, 4_500_000L, 8_100_000L), timestamps)
    }

    @Test
    fun `unknown duration yields single zero timestamp`() {
        assertEquals(listOf(0L), VideoAttachmentHelper.frameTimestampsUs(0L))
        assertEquals(listOf(0L), VideoAttachmentHelper.frameTimestampsUs(-1L))
    }

    @Test
    fun `scaled dimensions preserve aspect ratio and never upscale`() {
        assertEquals(640 to 480, VideoAttachmentHelper.scaledDimensions(640, 480))
        assertEquals(1280 to 720, VideoAttachmentHelper.scaledDimensions(1920, 1080))
        assertEquals(720 to 1280, VideoAttachmentHelper.scaledDimensions(1080, 1920))
        assertEquals(0 to 0, VideoAttachmentHelper.scaledDimensions(0, 100))
    }

    @Test
    fun `frame filename is sanitized`() {
        assertEquals(
            "Screenrecorder-2026-09-09-04-26-11-78.frame-1.jpg",
            VideoAttachmentHelper.frameFilename("Screenrecorder-2026-09-09-04-26-11-78.mp4", 1),
        )
        assertEquals("___.frame-0.jpg", VideoAttachmentHelper.frameFilename("!!!", 0))
        assertEquals("video.frame-0.jpg", VideoAttachmentHelper.frameFilename("", 0))
    }
}
