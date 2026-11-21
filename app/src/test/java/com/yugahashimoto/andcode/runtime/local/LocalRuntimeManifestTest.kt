package com.yugahashimoto.andcode.runtime.local

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalRuntimeManifestTest {
    private val architecture =
        LocalRuntimeArchitecture(
            alpineUrl = "https://example.com/alpine.tar.gz",
            alpineSha256 = "a".repeat(64),
            openCodeUrl = "https://example.com/opencode.tar.gz",
            openCodeSha256 = "b".repeat(64),
        )

    @Test
    fun `valid manifest resolves architecture`() {
        val manifest =
            LocalRuntimeManifest(
                schemaVersion = 1,
                runtimeVersion = "2026.07.18.1",
                openCodeVersion = "1.18.3",
                alpineVersion = "3.24.1",
                port = 4097,
                architectures = mapOf("arm64-v8a" to architecture),
            )

        manifest.validate()

        assertEquals(architecture, manifest.architecture("arm64-v8a"))
    }

    @Test
    fun `manifest rejects insecure download URL`() {
        val invalid = architecture.copy(alpineUrl = "http://example.com/alpine.tar.gz")

        assertThrows(IllegalArgumentException::class.java) {
            invalid.validate("arm64-v8a")
        }
    }

    @Test
    fun `manifest rejects invalid hash`() {
        val invalid = architecture.copy(openCodeSha256 = "not-a-hash")

        assertThrows(IllegalArgumentException::class.java) {
            invalid.validate("arm64-v8a")
        }
    }

    private fun repositoryRoot(): File {
        var directory = File(".").canonicalFile
        repeat(8) {
            if (File(directory, "settings.gradle.kts").exists()) return directory
            directory = directory.parentFile ?: return@repeat
        }
        error("Could not locate repository root from ${File(".").canonicalPath}")
    }

    @Test
    fun `the bundled manifest pins the expected OpenCode release and URLs`() {
        val manifestFile = repositoryRoot().resolve("app/src/main/assets/local-runtime-manifest.json")
        val manifest =
            Json { ignoreUnknownKeys = true }
                .decodeFromString<LocalRuntimeManifest>(manifestFile.readText())

        manifest.validate()

        assertEquals("1.19.6", manifest.openCodeVersion)
        assertTrue(manifest.architecture("arm64-v8a").openCodeUrl.endsWith("/opencode-linux-arm64-musl.tar.gz"))
        assertTrue(manifest.architecture("x86_64").openCodeUrl.endsWith("/opencode-linux-x64-musl.tar.gz"))
        manifest.architectures.forEach { (abi, architecture) ->
            assertTrue(
                "$abi openCodeUrl must contain /v${manifest.openCodeVersion}/: ${architecture.openCodeUrl}",
                architecture.openCodeUrl.contains("/v${manifest.openCodeVersion}/"),
            )
        }
    }
}
