package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class DebianRootfsInstaller(
    private val runtimeDirectory: File,
    private val abi: String,
    private val downloader: VerifiedRuntimeDownloader,
    private val httpClient: OkHttpClient,
    private val commandSuite: EmbeddedCommandSuite.Paths? = null,
) {
    suspend fun installInto(
        destination: File,
        onProgress: (Float) -> Unit = {},
    ): File =
        withContext(Dispatchers.IO) {
            val asset = DebianRootfsManifest.assetFor(abi)
            val token = accessToken()
            val archive = File(runtimeDirectory, "cache/debian-bookworm-slim-$abi.tar.gz").apply { parentFile?.mkdirs() }
            if (archive.length() != asset.sizeBytes || runCatching { RuntimeArchive.verifySha256(archive, asset.sha256) }.isFailure) {
                downloader.download(
                    url = asset.blobUrl,
                    destination = archive,
                    expectedSha256 = asset.sha256,
                    expectedSizeBytes = asset.sizeBytes,
                    headers = mapOf("Authorization" to "Bearer $token"),
                    onProgress = { onProgress((it ?: 0f) * 0.75f) },
                )
            } else {
                onProgress(0.75f)
            }
            val extracted = File(destination.parentFile, "${destination.name}.new-${System.nanoTime()}").apply { mkdirs() }
            try {
                archive.inputStream().use { RuntimeArchive.extractTarGz(it, extracted) }
                configure(extracted)
                ensureGlibcLoader(extracted)
                installPtyUtility(extracted)
                installDevelopmentTools(extracted)
                destination.deleteRecursively()
                require(extracted.renameTo(destination)) { "Unable to activate Debian Antigravity rootfs" }
                onProgress(1f)
                archive.delete()
                destination
            } finally {
                extracted.deleteRecursively()
            }
        }

    private fun accessToken(): String {
        val request = Request.Builder().url(DebianRootfsManifest.tokenUrl).get().build()
        httpClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Debian registry token request failed with HTTP ${response.code}" }
            val body = requireNotNull(response.body).string()
            return Json.parseToJsonElement(body).jsonObject["token"]?.jsonPrimitive?.content
                ?: error("Debian registry token response did not contain a token")
        }
    }

    private fun configure(rootfs: File) {
        listOf("root", "tmp", "workspace", "dev", "proc", "sys", "system").forEach { File(rootfs, it).mkdirs() }
        File(rootfs, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
        File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(rootfs, "root/.config/antigravity").mkdirs()
        File(rootfs, "root/.gemini").mkdirs()
        AntigravityGuestSettings.write(rootfs)
    }

    private suspend fun installPtyUtility(rootfs: File) {
        val asset = DebianRootfsManifest.bsdutilsFor(abi)
        val packageFile =
            File(runtimeDirectory, "cache/${asset.name}-$abi.deb").apply {
                parentFile?.mkdirs()
            }
        if (packageFile.length() != asset.sizeBytes || runCatching { RuntimeArchive.verifySha256(packageFile, asset.sha256) }.isFailure) {
            downloader.download(
                url = asset.url,
                destination = packageFile,
                expectedSha256 = asset.sha256,
                expectedSizeBytes = asset.sizeBytes,
            )
        }
        packageFile.inputStream().use { RuntimeArchive.extractDebianPackage(it, rootfs) }
        require(File(rootfs, "usr/bin/script").isFile) { "Debian PTY utility was not installed" }
        packageFile.delete()
    }

    /**
     * Drops the `/etc/alternatives` entries the extractor materialized as plain copies.
     *
     * [RuntimeArchive] deliberately replaces symlinks with copies of their targets, which is fine
     * for ordinary files but not for this directory: `update-alternatives` requires each entry to
     * be a real symlink and aborts with "cannot stat file '/etc/alternatives/pager': Invalid
     * argument" when it finds a regular file, failing `less`'s postinst and with it the whole apt
     * run. Removing them lets dpkg install its own links inside the guest, where symlinks work.
     */
    private fun resetAlternatives(rootfs: File) {
        File(rootfs, "etc/alternatives").listFiles()?.forEach { entry ->
            if (entry.isFile && entry.name != "README") entry.delete()
        }
    }

    private fun installDevelopmentTools(rootfs: File) {
        val suite = commandSuite ?: return
        val prootTmp = File(runtimeDirectory, "proot-tmp").apply { mkdirs() }
        resetAlternatives(rootfs)
        File(rootfs, "etc/apt/sources.list").apply {
            parentFile?.mkdirs()
            writeText(
                "deb http://deb.debian.org/debian bookworm main contrib non-free non-free-firmware\n" +
                    "deb http://deb.debian.org/debian bookworm-updates main contrib non-free non-free-firmware\n" +
                    "deb http://security.debian.org/debian-security bookworm-security main contrib non-free non-free-firmware\n",
            )
        }
        val command =
            listOf(
                suite.proot.absolutePath,
                "--kill-on-exit",
                "--link2symlink",
                "-0",
                "-r",
                rootfs.absolutePath,
                "-b",
                "/dev",
                "-b",
                "/proc",
                "-b",
                "/sys",
                "-w",
                "/root",
                // Debian 12 has a merged /usr: this is the real interpreter, and `/bin/sh` only
                // reaches it through the `/bin` -> `usr/bin` link. Naming it directly keeps the
                // install working even against a rootfs extracted by an older build.
                "/usr/bin/sh",
                "-c",
                GUEST_ENV +
                    "apt-get update -qq && " +
                    "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends " +
                    "git curl wget jq tree file less nano openssh-client ripgrep ca-certificates " +
                    "python3 python3-pil && " +
                    "apt-get clean && rm -rf /var/lib/apt/lists/*",
            )
        val installLog =
            File(runtimeDirectory, "logs/debian-tool-install.log").apply {
                parentFile?.mkdirs()
                delete()
            }
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(installLog))
                .apply {
                    environment().putAll(suite.environment())
                    environment()["PROOT_TMP_DIR"] = prootTmp.absolutePath
                }
                .start()
        val completed = process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)
        if (!completed) {
            process.destroyForcibly()
            error("Debian development tool installation timed out. $PACKAGE_INSTALL_RETRY_HINT")
        }
        require(process.exitValue() == 0) {
            // Same shape as LocalRuntimeInstaller: hint before the log tail, which is the only part
            // long enough to push it out of view.
            "Unable to install Debian development tools. $PACKAGE_INSTALL_RETRY_HINT\n\n" +
                "Last log lines:\n${installLog.readText().takeLast(4000)}"
        }
        installGitHubCli(rootfs, suite, prootTmp)
    }

    private fun installGitHubCli(
        rootfs: File,
        suite: EmbeddedCommandSuite.Paths,
        prootTmp: File,
    ) {
        val arch = if (abi == "arm64-v8a") "arm64" else "amd64"
        val command =
            listOf(
                suite.proot.absolutePath,
                "--kill-on-exit",
                "--link2symlink",
                "-0",
                "-r",
                rootfs.absolutePath,
                "-b",
                "/dev",
                "-b",
                "/proc",
                "-b",
                "/sys",
                "-w",
                "/root",
                // See installDevelopmentTools: the real interpreter lives under the merged /usr.
                "/usr/bin/sh",
                "-c",
                GUEST_ENV +
                    "curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg " +
                    "-o /usr/share/keyrings/githubcli-archive-keyring.gpg && " +
                    "echo \"deb [arch=$arch signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] " +
                    "https://cli.github.com/packages stable main\" > /etc/apt/sources.list.d/github-cli.list && " +
                    "apt-get update -qq && " +
                    "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends gh && " +
                    "apt-get clean && rm -rf /var/lib/apt/lists/*",
            )
        val installLog =
            File(runtimeDirectory, "logs/debian-gh-install.log").apply {
                parentFile?.mkdirs()
                delete()
            }
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(installLog))
                .apply {
                    environment().putAll(suite.environment())
                    environment()["PROOT_TMP_DIR"] = prootTmp.absolutePath
                }
                .start()
        val completed = process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)
        if (!completed) {
            process.destroyForcibly()
            return
        }
        if (process.exitValue() != 0) {
            android.util.Log.w("DebianRootfsInstaller", "gh install failed (non-fatal): ${installLog.readText().takeLast(500)}")
        }
    }

    private fun ensureGlibcLoader(rootfs: File) {
        val loaderName = if (abi == "arm64-v8a") "ld-linux-aarch64.so.1" else "ld-linux-x86-64.so.2"
        val source =
            listOf(
                File(rootfs, "lib/aarch64-linux-gnu/$loaderName"),
                File(rootfs, "lib/x86_64-linux-gnu/$loaderName"),
            ).firstOrNull { it.isFile }
                ?: error("Debian glibc loader is missing: $loaderName")
        val loader = File(rootfs, "lib/$loaderName")
        if (!loader.isFile) {
            loader.parentFile?.mkdirs()
            source.copyTo(loader, overwrite = true)
            loader.setExecutable(true, false)
        }
    }

    private companion object {
        /**
         * Guest-side environment for every `proot ... /usr/bin/sh -c` run here.
         *
         * PRoot hands the child the host process's environment, so without this the guest inherits
         * `TMPDIR` and `HOME` pointing at Android paths that do not exist inside the rootfs. That is
         * not cosmetic: ca-certificates' postinst died with "mktemp: failed to create file via
         * template '/data/user/0/.../command-suite/tmp/ca-certificates.tmp.XXXXXX': No such file or
         * directory", which failed the whole apt run.
         */
        const val GUEST_ENV =
            "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
                "TMPDIR=/tmp HOME=/root && "
    }
}
