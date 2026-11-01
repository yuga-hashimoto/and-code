package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * PRoot command prefix for the embedded llama-server.
 *
 * Unlike the opencode sandbox, the llama server needs the device's GPU driver and bionic runtime:
 * - `/vendor` carries `libOpenCL.so` / `libOpenCL_adreno.so` (the Adreno OpenCL ICD);
 * - `/apex` resolves `/system/bin/linker64`, which is a symlink into
 *   `/apex/com.android.runtime/bin/linker64` — without it the bionic-linked llama-server binary
 *   fails with "No such file or directory" at exec time.
 *
 * The model's parent directory is bound at `/models`, so `-m /models/<file>` works for any file
 * under it. The llama binaries themselves live inside the rootfs at `/llama-oc` (installed by
 * [LocalAiInstaller]).
 */
internal fun localAiProotCommandPrefix(
    proot: File,
    rootfs: File,
    modelDir: File,
): List<String> =
    buildList {
        add(proot.absolutePath)
        add("--kill-on-exit")
        add("--link2symlink")
        add("-0")
        add("-r")
        add(rootfs.absolutePath)
        add("-b")
        add("/dev")
        add("-b")
        add("/proc")
        add("-b")
        add("/sys")
        add("-b")
        add("/system")
        add("-b")
        add("/vendor")
        add("-b")
        add("/apex")
        add("-b")
        add("${modelDir.absolutePath}:/models")
        add("-w")
        add("/")
    }
