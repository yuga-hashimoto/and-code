package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AntigravityUpdateTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `the installer records the release it wrote`() {
        val rootfs = temporaryFolder.newFolder("rootfs")

        AntigravityInstaller.writeInstalledVersion(rootfs, "1.1.6")

        assertEquals("1.1.6", AntigravityInstaller.installedVersion(rootfs))
    }

    @Test
    fun `a sandbox without a marker reports no recorded version`() {
        assertNull(AntigravityInstaller.installedVersion(temporaryFolder.newFolder("bare-rootfs")))
    }

    @Test
    fun `an older recorded release offers the version this app carries`() {
        val state =
            AntigravityControllerState(
                installed = true,
                version = "1.1.6",
                bundledVersion = "1.1.7",
            )

        assertTrue(state.updateAvailable)
    }

    @Test
    fun `the pinned release offers no update`() {
        val state =
            AntigravityControllerState(
                installed = true,
                version = "1.1.7",
                bundledVersion = "1.1.7",
            )

        assertFalse(state.updateAvailable)
    }

    @Test
    fun `an agent that is not installed is never offered an update`() {
        assertFalse(AntigravityControllerState(installed = false, version = "1.1.6").updateAvailable)
    }

    @Test
    fun `an update reports both sides of the version change`() {
        assertEquals(
            AntigravityUpdateResult.Updated("1.1.6", "1.1.7"),
            antigravityUpdateResult(before = "1.1.6", after = "1.1.7"),
        )
        assertEquals(
            AntigravityUpdateResult.AlreadyLatest("1.1.7"),
            antigravityUpdateResult(before = "1.1.7", after = "1.1.7"),
        )
        assertEquals(
            AntigravityUpdateResult.AlreadyLatest("1.1.7"),
            antigravityUpdateResult(before = null, after = "1.1.7"),
        )
    }
}
