package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.storage.DeviceStorage
import java.io.File

/**
 * Builds PRoot invocations for the Claude Code binary inside the shared Alpine sandbox.
 *
 * Centralised so the binary path and the bind mounts stay identical across the chat process, the
 * sign-in flow and version checks — a mismatch there is invisible until the process fails to start.
 */
object ClaudeSandboxLauncher {
    fun command(
        runtime: LocalRuntimeInstaller.InstalledRuntime,
        workspaceHostDir: File,
        workingDirectory: String,
        arguments: List<String>,
        pty: Boolean,
    ): List<String> =
        buildList {
            addAll(mounts(runtime, workspaceHostDir, workingDirectory))
            if (pty) {
                // `claude auth login` only prints its pasteable authorization URL when it believes a
                // human is watching, so the sign-in flow needs a real terminal rather than a pipe.
                add("/usr/bin/script")
                add("-qefc")
                add((listOf(ClaudeCodeInstaller.CLAUDE_BINARY) + arguments).joinToString(" "))
                add("/dev/null")
            } else {
                add(ClaudeCodeInstaller.CLAUDE_BINARY)
                addAll(arguments)
            }
        }

    /**
     * Runs [script] with the same mounts a chat session gets.
     *
     * Used for the questions Claude Code has no protocol for — the workspace's git state, the MCP
     * server list — which the sandbox's own tools can answer. Deliberately not a login shell:
     * `/etc/profile.d` narrows PATH to the OpenCode set, which hides `git` among others.
     */
    fun shellCommand(
        runtime: LocalRuntimeInstaller.InstalledRuntime,
        workspaceHostDir: File,
        workingDirectory: String,
        script: String,
    ): List<String> =
        buildList {
            addAll(mounts(runtime, workspaceHostDir, workingDirectory))
            add("/bin/sh")
            add("-c")
            add(script)
        }

    private fun mounts(
        runtime: LocalRuntimeInstaller.InstalledRuntime,
        workspaceHostDir: File,
        workingDirectory: String,
    ): List<String> =
        buildList {
            add(runtime.commandSuite.proot.absolutePath)
            add("--kill-on-exit")
            add("--link2symlink")
            add("-0")
            add("-r")
            add(runtime.rootfs.absolutePath)
            add("-b")
            add("/dev")
            add("-b")
            add("/proc")
            add("-b")
            add("/sys")
            add("-b")
            add("/system")
            add("-b")
            add("${workspaceHostDir.absolutePath}:/workspace")
            // PermissionRequest hook ↔ Android approval UI file bridge.
            val bridgeHost = File(workspaceHostDir.parentFile, ClaudePermissionBridge.HOST_DIR_NAME).apply { mkdirs() }
            add("-b")
            add("${bridgeHost.absolutePath}:${ClaudePermissionBridge.GUEST_BRIDGE_PATH}")
            // Empty until the user grants all-files access, so the sandbox is unchanged without it.
            addAll(DeviceStorage.bindArguments())
            add("-w")
            add(workingDirectory)
        }

    fun environment(
        runtime: LocalRuntimeInstaller.InstalledRuntime,
        prootTmp: File,
        githubToken: String? = null,
    ): Map<String, String> =
        localRuntimeEnvironment(runtime.commandSuite.environment(), prootTmp, githubToken) +
            mapOf(
                // The bundled ripgrep is a glibc build and cannot run on musl; the sandbox installs
                // Alpine's ripgrep instead.
                "USE_BUILTIN_RIPGREP" to "0",
                "CLAUDE_CODE_DISABLE_AUTOUPDATER" to "1",
                // PRoot presents a fake uid 0, and Claude Code refuses bypassPermissions as root
                // unless it is told it is sandboxed. The rootfs is exactly that: app-private
                // storage reachable only through the bind mounts declared above.
                "IS_SANDBOX" to "1",
                "BUN_OPTIONS" to "--preload ${ClaudeCodeInstaller.DNS_PRELOAD}",
                "TERM" to "xterm-256color",
                "CI" to "1",
                "ANDCODE_CLAUDE_BRIDGE" to ClaudePermissionBridge.GUEST_BRIDGE_PATH,
            )
}
