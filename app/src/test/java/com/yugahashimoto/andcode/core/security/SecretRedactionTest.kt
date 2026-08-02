package com.yugahashimoto.andcode.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactionTest {
    @Test
    fun `redacts an Anthropic style api key`() {
        val message = "auth failed for sk-ant-api03-abcdefghijklmnop"
        val redacted = SecretRedaction.redact(message)
        assertFalse(redacted.contains("sk-ant-api03-abcdefghijklmnop"))
        assertTrue(redacted.contains("<redacted>"))
    }

    @Test
    fun `redacts a github token`() {
        val redacted = SecretRedaction.redact("token ghp_1234567890abcdefghijklmnopqrstuv leaked in output")
        assertFalse(redacted.contains("ghp_1234567890abcdefghijklmnopqrstuv"))
    }

    @Test
    fun `redacts a bearer authorization header`() {
        val redacted = SecretRedaction.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig")
        assertFalse(redacted.contains("eyJhbGciOiJIUzI1NiJ9"))
    }

    @Test
    fun `redacts a password field`() {
        val redacted = SecretRedaction.redact("connecting with password=Sup3rSecret!")
        assertFalse(redacted.contains("Sup3rSecret!"))
    }

    @Test
    fun `leaves ordinary text untouched`() {
        assertEquals("Runtime session failed", SecretRedaction.redact("Runtime session failed"))
    }

    @Test
    fun `strips oauth query parameters from urls before logging`() {
        val url = "https://claude.ai/oauth/authorize?code=abc123&state=xyz789"
        assertEquals("https://claude.ai/oauth/authorize", SecretRedaction.redactUrlQuery(url))
    }

    @Test
    fun `url without a query string is returned unchanged`() {
        val url = "https://claude.ai/oauth/authorize"
        assertEquals(url, SecretRedaction.redactUrlQuery(url))
    }
}
