package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayBundledUpdateEngineTest {
    private val release =
        LocalRuntimeRelease(
            version = "1.19.6",
            releaseNotes = "",
            asset = LocalRuntimeReleaseAsset("opencode-linux-arm64-musl.tar.gz", "https://example.com/archive", "a".repeat(64), 100),
        )

    @Test
    fun `offers only the version delivered with the app`() =
        runBlocking {
            val engine = PlayBundledUpdateEngine(FakeEngine(), "arm64-v8a") { release }
            val available = engine.check("1.19.5", "arm64-v8a") as LocalRuntimeUpdateCheck.Available
            assertEquals(release, available.release)
            assertTrue(engine.check("1.19.6", "arm64-v8a") is LocalRuntimeUpdateCheck.UpToDate)
            assertTrue(engine.check("1.20.0", "arm64-v8a") is LocalRuntimeUpdateCheck.UpToDate)
        }

    @Test
    fun `rejects a release that was not delivered with the app`() =
        runBlocking {
            val delegate = FakeEngine()
            val engine = PlayBundledUpdateEngine(delegate, "arm64-v8a") { release }
            val forged = release.copy(asset = release.asset.copy(sha256 = "b".repeat(64)))
            val result = runCatching { engine.prepare(forged) { _, _ -> } }
            assertTrue(result.isFailure)
            assertEquals(0, delegate.prepareCalls)
        }

    private class FakeEngine : LocalRuntimeUpdateEngine {
        var prepareCalls = 0

        override suspend fun check(
            currentVersion: String,
            abi: String,
        ): LocalRuntimeUpdateCheck = error("unexpected network check")

        override suspend fun prepare(
            release: LocalRuntimeRelease,
            onProgress: (Float?, String) -> Unit,
        ): PreparedRuntimeUpdate {
            prepareCalls++
            error("not required for this test")
        }

        override suspend fun activate(prepared: PreparedRuntimeUpdate): LocalRuntimeMetadata = error("unused")

        override suspend fun commit() = Unit

        override suspend fun recover(): LocalRuntimeMetadata? = null

        override suspend fun rollback(): LocalRuntimeMetadata = error("unused")

        override suspend fun rollbackVersion(): String? = null
    }
}
