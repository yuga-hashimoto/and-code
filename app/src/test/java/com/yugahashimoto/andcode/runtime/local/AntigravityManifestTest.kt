package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AntigravityManifestTest {
    @Test fun `selects pinned official assets by ABI`() {
        assertEquals("agy_cli_linux_arm64.tar.gz", AntigravityManifest.assetFor("arm64-v8a").name)
        assertEquals("agy_cli_linux_x64.tar.gz", AntigravityManifest.assetFor("x86_64").name)
        assertEquals("36596ad8038531fd75f0e97b5ba8967a30f2fd67f8d9c61959d2e4761d7b215f", AntigravityManifest.arm64.sha256)
        assertEquals(91_596_782L, AntigravityManifest.arm64.sizeBytes)
    }

    @Test fun `asset URLs carry the pinned version and the asset name`() {
        assertEquals("1.5.13", AntigravityManifest.VERSION)
        listOf(AntigravityManifest.arm64, AntigravityManifest.x64).forEach { asset ->
            assertTrue(asset.url.contains("/${AntigravityManifest.VERSION}/"))
            assertTrue(asset.url.endsWith(asset.name))
            assertTrue(Regex("^[a-f0-9]{64}$").matches(asset.sha256))
            assertTrue(asset.sizeBytes > 0L)
        }
    }

    @Test fun `assetFor maps Android and Linux ABI aliases`() {
        assertEquals(AntigravityManifest.arm64, AntigravityManifest.assetFor("arm64-v8a"))
        assertEquals(AntigravityManifest.arm64, AntigravityManifest.assetFor("aarch64"))
        assertEquals(AntigravityManifest.x64, AntigravityManifest.assetFor("x86_64"))
        assertEquals(AntigravityManifest.x64, AntigravityManifest.assetFor("amd64"))
    }

    @Test fun `the free space gate covers the peak update footprint`() {
        assertTrue(AntigravityManifest.MIN_FREE_BYTES >= 800L * 1024L * 1024L)
    }

    @Test fun `rejects unsupported ABI`() {
        assertThrows(IllegalStateException::class.java) { AntigravityManifest.assetFor("armeabi-v7a") }
    }
}
