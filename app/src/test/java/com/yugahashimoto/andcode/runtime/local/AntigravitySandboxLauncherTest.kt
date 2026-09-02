package com.yugahashimoto.andcode.runtime.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AntigravitySandboxLauncherTest {
    @Test
    fun `pty command sizes the terminal before starting agy`() {
        val command = AntigravitySandboxLauncher.ptyShellCommand(emptyList())
        assertTrue(command.startsWith("stty rows ${AntigravitySandboxLauncher.PTY_ROWS} cols ${AntigravitySandboxLauncher.PTY_COLUMNS}"))
        assertTrue(command.endsWith("exec '/usr/local/bin/agy'"))
    }

    @Test
    fun `pty width keeps the official oauth url on a single line`() {
        // The captured agy 1.1.7 sign-in URL is 521 characters and the CLI indents it by one column.
        assertTrue(AntigravitySandboxLauncher.PTY_COLUMNS > 600)
    }

    @Test
    fun `pty arguments are shell quoted`() {
        val command = AntigravitySandboxLauncher.ptyShellCommand(listOf("--print", "it's a test; rm -rf /"))
        assertTrue(command.endsWith("'/usr/local/bin/agy' '--print' 'it'\\''s a test; rm -rf /'"))
    }

    @Test
    fun `guest settings are valid json with the alt screen disabled`() {
        val parsed = Json.parseToJsonElement(AntigravityGuestSettings.content).jsonObject
        assertEquals("never", parsed["altScreenMode"]?.jsonPrimitive?.content)
        assertEquals("always-proceed", parsed["toolPermission"]?.jsonPrimitive?.content)
    }

    @Test
    fun `repair heals a legacy request-review settings file`() {
        val rootfs = Files.createTempDirectory("antigravity-guest-settings").toFile()
        try {
            val settings = File(rootfs, "root/.gemini/antigravity-cli/settings.json")
            settings.parentFile?.mkdirs()
            settings.writeText("""{"toolPermission":"request-review"}""")

            val runtime = mockRuntime(rootfs)
            AntigravityGuestSettings.repair(runtime)

            val healed = Json.parseToJsonElement(settings.readText()).jsonObject
            assertEquals("always-proceed", healed["toolPermission"]?.jsonPrimitive?.content)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `repair leaves an up to date settings file untouched`() {
        val rootfs = Files.createTempDirectory("antigravity-guest-settings").toFile()
        try {
            val settings = File(rootfs, "root/.gemini/antigravity-cli/settings.json")
            settings.parentFile?.mkdirs()
            settings.writeText("""{"toolPermission":"always-proceed","custom":"keep"}""")

            AntigravityGuestSettings.repair(mockRuntime(rootfs))

            val healed = Json.parseToJsonElement(settings.readText()).jsonObject
            assertEquals("always-proceed", healed["toolPermission"]?.jsonPrimitive?.content)
            assertEquals("keep", healed["custom"]?.jsonPrimitive?.content)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    private fun mockRuntime(rootfs: File): LocalRuntimeInstaller.InstalledRuntime =
        LocalRuntimeInstaller.InstalledRuntime(
            metadata = LocalRuntimeMetadata(version = "test", port = 0, installedAt = 0),
            commandSuite =
                EmbeddedCommandSuite.Paths(
                    home = rootfs,
                    tmp = rootfs,
                    nativeLibraryDirectory = rootfs,
                    proot = rootfs,
                    loader = rootfs,
                    loader32 = rootfs,
                ),
            rootfs = rootfs,
            openCode = null,
            antigravityRootfs = rootfs,
        )
}
