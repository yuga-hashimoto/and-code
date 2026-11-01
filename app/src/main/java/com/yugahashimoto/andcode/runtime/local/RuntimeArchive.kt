package com.yugahashimoto.andcode.runtime.local

import android.system.Os
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object RuntimeArchive {
    /** Extracts the data member of a Debian .deb without invoking a guest package manager. */
    fun extractDebianPackage(
        input: InputStream,
        destination: File,
    ) {
        ArArchiveInputStream(BufferedInputStream(input)).use { ar ->
            var entry = ar.nextArEntry
            while (entry != null) {
                if (entry.name == "data.tar.xz") {
                    XZCompressorInputStream(BufferedInputStream(ar)).use { xz ->
                        extractTar(xz, destination)
                    }
                    return
                }
                entry = ar.nextArEntry
            }
        }
        error("Debian package does not contain data.tar.xz")
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifySha256(
        file: File,
        expected: String,
    ) {
        val actual = sha256(file)
        require(actual.equals(expected, ignoreCase = true)) {
            "SHA-256 mismatch for ${file.name}: expected $expected, got $actual"
        }
    }

    fun extractTarGz(
        input: InputStream,
        destination: File,
    ) {
        GzipCompressorInputStream(input.buffered()).use { gzip ->
            extractTar(gzip, destination)
        }
    }

    internal fun extractTar(
        input: InputStream,
        destination: File,
    ) {
        destination.mkdirs()
        val canonicalRoot = destination.canonicalFile
        val pendingSymlinks = mutableListOf<Pair<File, String>>()
        TarArchiveInputStream(input).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val target = File(destination, entry.name).canonicalFile
                require(target.path == canonicalRoot.path || target.path.startsWith(canonicalRoot.path + File.separator)) {
                    "Archive entry escapes destination: ${entry.name}"
                }
                when {
                    entry.isDirectory -> target.mkdirs()
                    entry.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        target.delete()
                        // Android app-private filesystems can expose a symlink loop to PRoot
                        // when the link is created before its target. Resolve links after all
                        // archive entries have been materialized and use a regular copy; the
                        // rootfs only needs the target contents, not inode identity.
                        pendingSymlinks += target to entry.linkName
                    }
                    entry.isLink -> {
                        val source = File(destination, entry.linkName).canonicalFile
                        require(source.path == canonicalRoot.path || source.path.startsWith(canonicalRoot.path + File.separator)) {
                            "Archive hard link escapes destination: ${entry.linkName}"
                        }
                        target.parentFile?.mkdirs()
                        target.delete()
                        // App-private filesystems frequently reject hardlinks even though
                        // ordinary file creation is allowed. A byte-for-byte copy preserves
                        // the rootfs semantics and keeps OCI/Debian layers installable.
                        runCatching { Os.link(source.absolutePath, target.absolutePath) }
                            .onFailure {
                                source.inputStream().buffered().use { input ->
                                    target.outputStream().buffered().use { output -> input.copyTo(output) }
                                }
                            }
                    }
                    entry.isFile -> {
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { output -> tar.copyTo(output) }
                        val executable = entry.mode and 0b001_001_001 != 0
                        target.setReadable(true, false)
                        target.setWritable(true, true)
                        if (executable) target.setExecutable(true, false)
                    }
                }
                entry = tar.nextEntry
            }
        }
        // Deepest link first, so a link to a directory is copied only once the links *inside* that
        // directory have themselves been materialized. Debian's merged-/usr layout is exactly this
        // case: `/bin` links to `usr/bin`, and `usr/bin/sh` links to `dash`. Resolved in archive
        // order, `/bin` was copied while `usr/bin/sh` was still an unresolved link, so the guest
        // ended up with no `/bin/sh` at all and every `proot ... /bin/sh -c` failed with
        // "'/bin/sh' not found" - which is what broke the Antigravity rootfs install on device.
        pendingSymlinks.sortByDescending { (target, _) -> target.path.count { it == File.separatorChar } }
        repeat(pendingSymlinks.size + 1) {
            var resolved = 0
            pendingSymlinks.toList().forEach { (target, linkName) ->
                if (target.exists()) return@forEach
                val source =
                    (
                        if (linkName.startsWith("/")) {
                            File(destination, linkName.removePrefix("/"))
                        } else {
                            File(target.parentFile, linkName)
                        }
                    ).canonicalFile
                if (!source.path.startsWith(canonicalRoot.path + File.separator) && source.path != canonicalRoot.path) return@forEach
                when {
                    source.isFile -> {
                        target.parentFile?.mkdirs()
                        source.copyTo(target, overwrite = true)
                        target.setExecutable(source.canExecute(), false)
                        resolved++
                    }
                    source.isDirectory -> {
                        source.copyRecursively(target, overwrite = true)
                        // `copyTo` - and so `copyRecursively` - copies bytes only. Without this the
                        // whole of `/bin` arrived as rw-------, so even the binaries that *were*
                        // copied could not be executed.
                        source.walkTopDown().forEach { file ->
                            val copy = File(target, file.relativeTo(source).path)
                            if (file.isFile && copy.isFile) copy.setExecutable(file.canExecute(), false)
                        }
                        resolved++
                    }
                }
            }
            pendingSymlinks.removeAll { it.first.exists() }
            if (resolved == 0) return@repeat
        }
        // Some base images intentionally point at host-provided paths (for example /etc/mtab).
        // Those links are not needed inside the app sandbox and are left absent.
    }
}
