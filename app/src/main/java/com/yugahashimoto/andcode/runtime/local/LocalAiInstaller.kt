package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Installs the embedded llama-server payload into the rootfs.
 *
 * The payload is a small tarball (`llama-oc`) built for Android arm64 with the OpenCL backend
 * enabled (llama-server plus its shared libraries: libggml.so, libggml-opencl.so, etc.). It is
 * bundled with the app as an asset; [install] unpacks it at `/llama-oc` inside the runtime rootfs
 * so every proot guest process can reach it without extra binds.
 */
class LocalAiInstaller(
    private val unpackAsset: (File) -> Unit,
    private val markerFileName: String = ".llama-oc-installed",
) {
    /**
     * Installs the payload into [rootfs] unless a previous install is present.
     * Returns true when the payload was unpacked during this call.
     */
    fun ensureInstalled(rootfs: File): Boolean {
        val target = File(rootfs, "llama-oc")
        val marker = File(rootfs, markerFileName)
        if (marker.isFile && target.isDirectory) return false
        target.deleteRecursively()
        unpackAsset(target)
        marker.writeText("installed")
        return true
    }

    fun isInstalled(rootfs: File): Boolean =
        File(rootfs, markerFileName).isFile && File(rootfs, "llama-oc").isDirectory

    /** Host path of the llama-server binary inside [rootfs], or null when not installed. */
    fun llamaServer(rootfs: File): File? {
        val binary = File(rootfs, "llama-oc/llama-server")
        return binary.takeIf { it.isFile && it.canExecute() }
    }
}
