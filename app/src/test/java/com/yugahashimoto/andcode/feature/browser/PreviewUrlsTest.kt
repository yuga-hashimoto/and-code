package com.yugahashimoto.andcode.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewUrlsTest {
    @Test
    fun `finds loopback urls and ignores the rest`() {
        val text = "Open http://127.0.0.1:5173/ and http://localhost:3000, not https://example.com."

        assertEquals(listOf("http://127.0.0.1:5173/", "http://localhost:3000"), loopbackUrlsIn(text))
    }

    @Test
    fun `keeps order and de-duplicates`() {
        val text = "http://localhost:3000 http://localhost:3000 http://127.0.0.1:8080"

        assertEquals(listOf("http://localhost:3000", "http://127.0.0.1:8080"), loopbackUrlsIn(text))
    }

    @Test
    fun `trims trailing prose punctuation`() {
        assertEquals(listOf("http://127.0.0.1:8080/"), loopbackUrlsIn("see (http://127.0.0.1:8080/)."))
    }

    @Test
    fun `finds ipv6 loopback urls`() {
        assertEquals(listOf("http://[::1]:8080/"), loopbackUrlsIn("go to http://[::1]:8080/ now"))
    }

    @Test
    fun `rejects private lan and non-http schemes`() {
        assertEquals(
            emptyList<String>(),
            loopbackUrlsIn("http://192.168.1.5:8000 file:///sdcard/x javascript:alert(1)"),
        )
    }
}
