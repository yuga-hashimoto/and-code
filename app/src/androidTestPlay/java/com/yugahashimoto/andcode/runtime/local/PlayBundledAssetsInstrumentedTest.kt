package com.yugahashimoto.andcode.runtime.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
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
        val apkCacheHashes =
            context.assets.open("play-runtime/apk-cache-hashes.json").bufferedReader().use { JSONObject(it.readText()) }
        val notices = context.assets.open("legal/oss_licenses.md").bufferedReader().use { it.readText() }
        assertTrue(notices.contains("Bun 1.4.4 and JavaScriptCore"))
        assertTrue(notices.contains("GNU LIBRARY GENERAL PUBLIC LICENSE"))
        assertTrue(notices.contains("Version 2.1, February 1999"))
        for (abi in listOf("arm64-v8a", "x86_64")) {
            val architecture = manifest.architecture(abi)
            for (
            (name, expected) in
            listOf(
                "alpine" to architecture.alpineSha256,
                "opencode" to architecture.openCodeSha256,
                "apk-cache" to apkCacheHashes.getString(abi),
            )
            ) {
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
