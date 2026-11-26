package com.yugahashimoto.andcode.runtime.local

import java.io.File
import java.io.InputStream

/** Copies a Play-delivered archive into private storage without activating a corrupt payload. */
object BundledRuntimeArchive {
    fun copyVerified(
        openAsset: () -> InputStream,
        destination: File,
        expectedSha256: String,
    ) {
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, destination.name + ".bundled-partial")
        try {
            openAsset().use { source -> partial.outputStream().use { target -> source.copyTo(target) } }
            RuntimeArchive.verifySha256(partial, expectedSha256)
            check(partial.renameTo(destination)) { "Could not activate bundled runtime archive" }
        } finally {
            partial.delete()
        }
    }
}
