package com.yugahashimoto.andcode.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlLauncherTest {
    @Test
    fun `http and https URLs are openable`() {
        assertTrue(UrlLauncher.isOpenableUrl("http://example.com"))
        assertTrue(UrlLauncher.isOpenableUrl("https://example.com/path?q=1"))
        assertTrue(UrlLauncher.isOpenableUrl("  HTTPS://EXAMPLE.COM  "))
    }

    @Test
    fun `file URL from issue 300 is refused`() {
        assertFalse(UrlLauncher.isOpenableUrl("file:///workspace/my-workspace"))
    }

    @Test
    fun `non-http schemes and bare paths are refused`() {
        assertFalse(UrlLauncher.isOpenableUrl("content://com.example/x"))
        assertFalse(UrlLauncher.isOpenableUrl("intent://example.com#Intent;end"))
        assertFalse(UrlLauncher.isOpenableUrl("javascript:alert(1)"))
        assertFalse(UrlLauncher.isOpenableUrl("data:text/plain,hello"))
        assertFalse(UrlLauncher.isOpenableUrl("/workspace/my-workspace"))
        assertFalse(UrlLauncher.isOpenableUrl("workspace/my-workspace"))
        assertFalse(UrlLauncher.isOpenableUrl(""))
        assertFalse(UrlLauncher.isOpenableUrl("   "))
        assertFalse(UrlLauncher.isOpenableUrl("http:example.com"))
        assertFalse(UrlLauncher.isOpenableUrl("1http://example.com"))
    }
}
