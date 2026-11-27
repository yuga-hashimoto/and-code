package com.yugahashimoto.andcode.runtime.local

import java.io.File
import java.io.InputStream

/** Activates only a verified Play-delivered Alpine package cache, without touching the user's cache. */
object PlayApkCache {
    fun prepare(
        openAsset: () -> InputStream,
        expectedSha256: String,
        destination: File,
    ) {
        val parent = requireNotNull(destination.parentFile)
        parent.mkdirs()
        val staging = File(parent, "${destination.name}.staging")
        val rollback = File(parent, "${destination.name}.rollback")
        val archive = File(parent, "${destination.name}.tgz")
        if (!destination.exists() && rollback.exists()) {
            check(rollback.renameTo(destination)) { "Could not restore previous Play package cache" }
        }
        staging.deleteRecursively()
        try {
            BundledRuntimeArchive.copyVerified(openAsset, archive, expectedSha256)
            archive.inputStream().use { RuntimeArchive.extractTarGz(it, staging) }
            require(staging.listFiles()?.all { it.isFile && it.parentFile == staging } == true) {
                "Invalid bundled Alpine package cache"
            }
            rollback.deleteRecursively()
            val hadPrevious = destination.exists()
            if (hadPrevious) check(destination.renameTo(rollback)) { "Could not preserve previous Play package cache" }
            if (!staging.renameTo(destination)) {
                if (hadPrevious) check(rollback.renameTo(destination)) { "Could not restore previous Play package cache" }
                error("Could not activate Play package cache")
            }
            rollback.deleteRecursively()
        } finally {
            staging.deleteRecursively()
            archive.delete()
        }
    }
}
