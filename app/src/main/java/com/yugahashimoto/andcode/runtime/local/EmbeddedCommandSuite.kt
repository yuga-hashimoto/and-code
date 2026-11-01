package com.yugahashimoto.andcode.runtime.local

import android.content.Context
import java.io.File

/** Resolves the Android-native PRoot launcher and its guest loader binaries packaged as jniLibs. */
class EmbeddedCommandSuite(
    private val context: Context,
    private val runtimeDirectory: File,
    private val abi: String,
) {
    data class Paths(
        val home: File,
        val tmp: File,
        val nativeLibraryDirectory: File,
        val proot: File,
        val loader: File,
        val loader32: File,
    ) {
        fun environment(): Map<String, String> =
            mapOf(
                "HOME" to home.absolutePath,
                "TMPDIR" to tmp.absolutePath,
                "PATH" to "/system/bin:/system/xbin",
                "LD_LIBRARY_PATH" to nativeLibraryDirectory.absolutePath,
                "PROOT_TMP_DIR" to tmp.absolutePath,
                "PROOT_LOADER" to loader.absolutePath,
                "PROOT_LOADER_32" to loader32.absolutePath,
            )
    }

    fun ensureInstalled(): Paths {
        require(abi in SUPPORTED_ABIS) { "Unsupported Android ABI: $abi" }
        val stateRoot = File(runtimeDirectory, "command-suite").apply { mkdirs() }
        val home = File(stateRoot, "home").apply { mkdirs() }
        val tmp = File(stateRoot, "tmp").apply { mkdirs() }
        val nativeLibraryDirectory = File(context.applicationInfo.nativeLibraryDir)
        val proot = File(nativeLibraryDirectory, PROOT_LIBRARY_NAME)
        val loader = File(nativeLibraryDirectory, "libopencode_android_proot_loader.so")
        val loader32 = File(nativeLibraryDirectory, "libopencode_android_proot_loader32.so")
        require(proot.isFile && proot.canExecute()) { "Embedded PRoot is unavailable for $abi" }
        require(loader.isFile && loader.canExecute()) { "Embedded PRoot loader is unavailable for $abi" }
        require(loader32.isFile && loader32.canExecute()) { "Embedded PRoot 32-bit loader is unavailable for $abi" }
        return Paths(home, tmp, nativeLibraryDirectory, proot, loader, loader32)
    }

    companion object {
        const val PROOT_LIBRARY_NAME = "libopencode_android_proot.so"
        private val SUPPORTED_ABIS = setOf("arm64-v8a", "x86_64")
    }
}
