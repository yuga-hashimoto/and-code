package com.yugahashimoto.andcode.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionQrPayloadTest {
    @Test
    fun `parses full connect uri`() {
        val uri =
            ConnectionQrPayload.format(
                name = "Mac mini",
                url = "http://192.168.1.10:4096",
                username = "opencode",
                password = "s3cret!",
                insecure = true,
            )

        val parsed = ConnectionQrPayload.parse(uri)

        assertEquals("Mac mini", parsed?.name)
        assertEquals("http://192.168.1.10:4096", parsed?.url)
        assertEquals("opencode", parsed?.username)
        assertEquals("s3cret!", parsed?.password)
        assertTrue(parsed?.insecure == true)
    }

    @Test
    fun `format and parse round trip preserves special characters`() {
        val uri =
            ConnectionQrPayload.format(
                name = "Mac mini #1",
                url = "http://192.168.1.10:4096",
                username = "opencode",
                password = "p@ss w/ord&stuff=",
                insecure = false,
            )

        val parsed = ConnectionQrPayload.parse(uri)

        assertEquals("Mac mini #1", parsed?.name)
        assertEquals("p@ss w/ord&stuff=", parsed?.password)
        assertFalse(parsed!!.insecure)
    }

    @Test
    fun `plain http url is treated as url only`() {
        val parsed = ConnectionQrPayload.parse("http://192.168.1.10:4096")
        assertEquals("http://192.168.1.10:4096", parsed?.url)
        assertNull(parsed?.name)
        assertNull(parsed?.username)
        assertNull(parsed?.password)
        assertFalse(parsed!!.insecure)
    }

    @Test
    fun `plain https url is treated as url only`() {
        val parsed = ConnectionQrPayload.parse("https://opencode.example.com")
        assertEquals("https://opencode.example.com", parsed?.url)
    }

    @Test
    fun `missing params fall back to nulls and defaults`() {
        val parsed = ConnectionQrPayload.parse("opencode://connect?name=Mac%20mini")
        assertEquals("Mac mini", parsed?.name)
        assertNull(parsed?.url)
        assertNull(parsed?.username)
        assertNull(parsed?.password)
        assertFalse(parsed!!.insecure)
    }

    @Test
    fun `connect uri without query returns empty payload`() {
        val parsed = ConnectionQrPayload.parse("opencode://connect")
        assertEquals(ConnectionQrPayload(), parsed)
    }

    @Test
    fun `malformed input never throws and returns null for unrecognized formats`() {
        assertNull(ConnectionQrPayload.parse(""))
        assertNull(ConnectionQrPayload.parse("   "))
        assertNull(ConnectionQrPayload.parse("not a uri at all"))
        assertNull(ConnectionQrPayload.parse("ftp://example.com"))
    }

    @Test
    fun `garbled connect query never throws`() {
        val parsed = ConnectionQrPayload.parse("opencode://connect?%%%invalid=%GG")
        assertEquals(ConnectionQrPayload(), parsed)
    }

    @Test
    fun `blank parameter values are ignored`() {
        val parsed = ConnectionQrPayload.parse("opencode://connect?name=&url=http%3A%2F%2Fx&username=")
        assertNull(parsed?.name)
        assertEquals("http://x", parsed?.url)
        assertNull(parsed?.username)
    }

    @Test
    fun `toString never prints the plaintext password`() {
        val payload = ConnectionQrPayload(name = "Mac mini", url = "http://x", username = "opencode", password = "s3cret!")
        assertFalse(payload.toString().contains("s3cret!"))
        assertTrue(payload.toString().contains("<redacted>"))
    }
}
