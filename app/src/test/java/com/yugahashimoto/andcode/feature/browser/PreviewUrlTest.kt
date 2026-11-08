package com.yugahashimoto.andcode.feature.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewUrlTest {
    @Test
    fun `loopback hosts are previewable`() {
        assertTrue(isLoopbackPreviewUrl("http://127.0.0.1:5173/"))
        assertTrue(isLoopbackPreviewUrl("http://localhost:3000"))
        assertTrue(isLoopbackPreviewUrl("https://localhost"))
        assertTrue(isLoopbackPreviewUrl("http://[::1]:8000/"))
    }

    @Test
    fun `pages that are not on loopback are rejected`() {
        assertFalse(isLoopbackPreviewUrl("https://example.com/"))
        assertFalse(isLoopbackPreviewUrl("http://192.168.1.10:8000/"))
        assertFalse(isLoopbackPreviewUrl("http://127.0.0.1.evil.com/"))
        assertFalse(isLoopbackPreviewUrl("http://localhost.evil.com/"))
    }

    @Test
    fun `non-http schemes and malformed input are rejected`() {
        assertFalse(isLoopbackPreviewUrl("javascript:alert(1)"))
        assertFalse(isLoopbackPreviewUrl("file:///etc/passwd"))
        assertFalse(isLoopbackPreviewUrl("not a url"))
        assertFalse(isLoopbackPreviewUrl(""))
    }
}
