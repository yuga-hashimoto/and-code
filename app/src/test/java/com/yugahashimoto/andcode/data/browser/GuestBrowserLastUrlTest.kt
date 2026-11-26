package com.yugahashimoto.andcode.data.browser

import com.yugahashimoto.andcode.data.security.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuestBrowserLastUrlTest {
    private val preferences = InMemorySharedPreferences()

    @Test
    fun `nothing is resumed before a page was visited`() {
        assertNull(GuestBrowserLastUrl(preferences).load())
    }

    @Test
    fun `the last web page is kept and resumed by a new instance`() {
        GuestBrowserLastUrl(preferences).save("https://x.com/home")

        assertEquals("https://x.com/home", GuestBrowserLastUrl(preferences).load())
    }

    @Test
    fun `a non-web page leaves the previous page in place`() {
        val store = GuestBrowserLastUrl(preferences)
        store.save("https://example.com/")

        store.save("about:blank")

        assertEquals("https://example.com/", store.load())
    }

    @Test
    fun `a stored non-web value is never resumed`() {
        preferences.edit().putString(GuestBrowserLastUrl.KEY_URL, "file:///etc/passwd").commit()

        assertNull(GuestBrowserLastUrl(preferences).load())
    }

    @Test
    fun `loopback dev servers are resumed too`() {
        val store = GuestBrowserLastUrl(preferences)
        store.save("http://127.0.0.1:5173/")

        assertEquals("http://127.0.0.1:5173/", GuestBrowserLastUrl(preferences).load())
    }
}
