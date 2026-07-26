package com.opencode.android.runtime.local

import java.io.File
import java.util.concurrent.TimeUnit

data class GitCloneResult(
    val exitCode: Int,
    val serverPath: String,
    val output: String,
)

/**
 * Clones a Git repository into the local runtime's shared workspace so the
 * OpenCode server can open it as a project. The clone runs inside the same
 * PRoot environment as the runtime, binding the host workspace directory to
 * /workspace and exporting the GitHub token for private repositories.
 */
class GitCloneRepository(
    private val runtimeDirectory: File,
    private val installedRuntimeProvider: () -> LocalRuntimeInstaller.InstalledRuntime?,
    private val accessCoordinator: LocalRuntimeAccessCoordinator = LocalRuntimeAccessCoordinator(),
    private val githubToken: () -> String? = { null },
    private val timeoutSeconds: Long = 300L,
) {
    fun clone(
        url: String,
        name: String,
    ): GitCloneResult =
        accessCoordinator.read {
            val sanitizedName = name.trim().trimStart('.', '/').replace(Regex("[^a-zA-Z0-9._-]"), "_")
            if (sanitizedName.isBlank() || sanitizedName.contains("..")) {
                return@read GitCloneResult(1, "", "Invalid repository name: $name")
            }
            val runtime =
                installedRuntimeProvider()
                    ?: return@read GitCloneResult(127, "", "Runtime is not installed")
            val workspace = File(runtimeDirectory, "workspace").apply { mkdirs() }
            val prootTmp = File(runtimeDirectory, "proot-tmp").apply { mkdirs() }
            val target = "/workspace/$sanitizedName"
            val outputFile =
                File.createTempFile(
                    "clone-",
                    ".log",
                    File(runtimeDirectory, "logs").apply { mkdirs() },
                )
            val sanitizedUrl = url.trim().removeSuffix("/")
            val command =
                listOf(
                    runtime.commandSuite.proot.absolutePath,
                    "--kill-on-exit",
                    "--link2symlink",
                    "-0",
                    "-r",
                    runtime.rootfs.absolutePath,
                    "-b",
                    "/dev",
                    "-b",
                    "/proc",
                    "-b",
                    "/sys",
                    "-b",
                    "/system",
                    "-b",
                    "${workspace.absolutePath}:/workspace",
                    "-w",
                    "/workspace",
                    "/bin/sh",
                    "-lc",
                    "git clone --depth 1 ${shellQuote(sanitizedUrl)} ${shellQuote(target)}",
                )
            try {
                val process =
                    ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.to(outputFile))
                        .apply {
                            environment().clear()
                            environment().putAll(
                                localRuntimeEnvironment(
                                    runtime.commandSuite.environment(),
                                    prootTmp,
                                    githubToken(),
                                ),
                            )
                        }
                        .start()
                val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    process.waitFor(2, TimeUnit.SECONDS)
                    GitCloneResult(124, target, "Clone timed out")
                } else {
                    GitCloneResult(
                        exitCode = process.exitValue(),
                        serverPath = target,
                        output = outputFile.readText().takeLast(4_000),
                    )
                }
            } finally {
                outputFile.delete()
            }
        }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
