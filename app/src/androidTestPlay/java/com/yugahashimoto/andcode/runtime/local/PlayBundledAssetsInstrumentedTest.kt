package com.yugahashimoto.andcode.runtime.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class PlayBundledAssetsInstrumentedTest {
    @Test
    fun bundledArchivesAreAvailableUncompressedAndMatchTheManifest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manifest = LocalRuntimeManifestReader(context).read()
        for (abi in listOf("arm64-v8a", "x86_64")) {
            val architecture = manifest.architecture(abi)
            for ((name, expected) in listOf("alpine" to architecture.alpineSha256, "opencode" to architecture.openCodeSha256)) {
                val path = "play-runtime/$abi/$name.tgz"
                context.assets.openFd(path).use { assertTrue(it.length > 0) }
                val digest = MessageDigest.getInstance("SHA-256")
                context.assets.open(path).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                assertEquals(expected, digest.digest().joinToString("") { "%02x".format(it) })
            }
        }
    }
}
