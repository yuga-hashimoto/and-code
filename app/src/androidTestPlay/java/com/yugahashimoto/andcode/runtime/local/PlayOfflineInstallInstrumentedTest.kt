package com.yugahashimoto.andcode.runtime.local

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yugahashimoto.andcode.runtime.LocalAgent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Run only after the test device's Wi-Fi and mobile data have both been disabled. */
@RunWith(AndroidJUnit4::class)
class PlayOfflineInstallInstrumentedTest {
    @Test
    fun installsAndRunsOpenCodeAndRequiredToolsWithoutNetwork() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            assertEquals("This test requires no active network", null, connectivity.activeNetwork)
            val abi = Build.SUPPORTED_ABIS.first { it == "arm64-v8a" || it == "x86_64" }
            val directory = File(context.cacheDir, "play-offline-install-test")
            directory.deleteRecursively()
            try {
                val runtime = LocalRuntimeInstaller(context, directory, abi).install(setOf(LocalAgent.OPEN_CODE))
                assertNotNull(runtime.openCode)
                assertTrue(runtime.openCode!!.isFile)
                assertTrue(File(runtime.rootfs, "usr/bin/git").isFile)
                assertTrue(File(runtime.rootfs, "usr/bin/python3").isFile)
                assertTrue(File(directory, "cache/play-apk-$abi").isDirectory)
                assertFalse(File(directory, "cache/apk").exists())
                val suite = runtime.commandSuite
                val process =
                    ProcessBuilder(
                        suite.proot.absolutePath,
                        "--kill-on-exit",
                        "--link2symlink",
                        "-0",
                        "-r",
                        runtime.rootfs.absolutePath,
                        "-b",
                        "/dev",
                        "-b",
                        "/proc",
                        "-w",
                        "/root",
                        "/bin/sh",
                        "-c",
                        "/usr/bin/git --version && /usr/local/bin/opencode --version",
                    ).redirectErrorStream(true).apply {
                        environment().putAll(suite.environment())
                        environment()["PROOT_TMP_DIR"] = File(directory, "proot-tmp").absolutePath
                    }.start()
                assertTrue("Runtime version commands timed out", process.waitFor(60, TimeUnit.SECONDS))
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(output, 0, process.exitValue())
                assertTrue(output, output.contains("git version"))
                assertTrue(output, output.contains("1.19.6"))
                assertEquals("Network must remain disconnected", null, connectivity.activeNetwork)
            } finally {
                directory.deleteRecursively()
            }
        }
}
