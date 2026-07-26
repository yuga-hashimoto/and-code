package com.opencode.android.runtime.local

import java.io.File
import java.util.concurrent.TimeUnit

class LocalRuntimeProcessLauncher(
    private val runtimeDirectory: File,
    private val portProbe: (Int) -> Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val procRoot: File = File("/proc"),
    private val processSignal: (Long) -> Unit = { pid ->
        android.os.Process.killProcess(pid.toInt())
    },
    private val githubToken: () -> String? = { null },
    private val beforeStart: (LocalRuntimeInstaller.InstalledRuntime) -> Unit = {},
) {
    @Volatile
    private var process: Process? = null

    @Volatile
    private var startedAtMillis: Long? = null

    @Synchronized
    fun start(runtime: LocalRuntimeInstaller.InstalledRuntime): Process {
        val port = runtime.metadata.port
        process?.let { current ->
            if (current.isAlive && portProbe(port)) return current
            terminate(current)
            process = null
        }
        beforeStart(runtime)
        val rootfs = runtime.rootfs
        val suite = runtime.commandSuite
        val logs = File(runtimeDirectory, "logs").apply { mkdirs() }
        val logFile = File(logs, "opencode-local.log")
        val workspace = File(runtimeDirectory, "workspace").apply { mkdirs() }
        val prootTmp = File(runtimeDirectory, "proot-tmp").apply { mkdirs() }

        val command =
            buildList {
                add(suite.proot.absolutePath)
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
                add("${workspace.absolutePath}:/workspace")
                add("-w")
                add("/workspace")
                add("/usr/local/bin/opencode")
                add("serve")
                add("--hostname")
                add("127.0.0.1")
                add("--port")
                add(port.toString())
            }

        val builder =
            ProcessBuilder(command)
                .directory(runtimeDirectory)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        builder.environment().apply {
            clear()
            putAll(localRuntimeEnvironment(suite.environment(), prootTmp, githubToken()))
        }
        val started = builder.start()
        process = started
        startedAtMillis = nowMillis()
        return try {
            waitUntilReady(started, port, logFile)
            started
        } catch (error: Throwable) {
            process = null
            startedAtMillis = null
            throw error
        }
    }

    @Synchronized
    fun stop() {
        process?.let(::terminate) ?: terminateResidualManagedProcesses()
        process = null
        startedAtMillis = null
    }

    fun isRunning(): Boolean = process?.isAlive == true

    fun isHealthy(port: Int): Boolean = process?.isAlive == true && portProbe(port)

    @Synchronized
    fun metrics(): LocalRuntimeProcessMetrics? {
        val current = process?.takeIf(Process::isAlive) ?: return null
        val pid = processId(current)
        val rssBytes =
            pid?.let { rootPid ->
                totalResidentSetBytes(
                    rootPid = rootPid,
                    statusReader = { processId ->
                        runCatching { File("/proc/$processId/status").readText() }.getOrNull()
                    },
                    childrenReader = ::readDirectChildPids,
                )
            }
        return LocalRuntimeProcessMetrics(
            pid = pid,
            rssBytes = rssBytes,
            uptimeMillis = (nowMillis() - (startedAtMillis ?: nowMillis())).coerceAtLeast(0L),
        )
    }

    private fun terminate(current: Process) {
        val roots =
            linkedSetOf<Long>().apply {
                processId(current)?.let(::add)
                addAll(findManagedRuntimeRootPids(runtimeDirectory, procRoot))
            }
        val terminationOrder =
            roots
                .flatMap { rootPid ->
                    processTreePostOrder(rootPid) { pid -> readDirectChildPids(pid, procRoot) }
                }
                .distinct()

        if (current.isAlive) {
            current.destroy()
            current.waitFor(750, TimeUnit.MILLISECONDS)
        }
        terminationOrder.forEach { pid ->
            runCatching { processSignal(pid) }
        }
        if (current.isAlive && !current.waitFor(2, TimeUnit.SECONDS)) {
            current.destroyForcibly()
            current.waitFor(1, TimeUnit.SECONDS)
        }
    }

    private fun terminateResidualManagedProcesses() {
        findManagedRuntimeRootPids(runtimeDirectory, procRoot)
            .flatMap { rootPid ->
                processTreePostOrder(rootPid) { pid -> readDirectChildPids(pid, procRoot) }
            }
            .distinct()
            .forEach { pid -> runCatching { processSignal(pid) } }
    }

    private fun waitUntilReady(
        process: Process,
        port: Int,
        logFile: File,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                error("Local OpenCode exited during startup: ${tail(logFile)}")
            }
            if (portProbe(port)) return
            Thread.sleep(250)
        }
        process.destroyForcibly()
        process.waitFor(2, TimeUnit.SECONDS)
        runCatching { process.outputStream.close() }
        error("Local OpenCode did not become ready on port $port: ${tail(logFile)}")
    }

    private fun tail(file: File): String =
        runCatching {
            file.readLines().takeLast(20).joinToString("\n")
        }.getOrDefault("No runtime log was produced")
}

internal fun processTreePostOrder(
    rootPid: Long,
    childrenReader: (Long) -> List<Long>,
): List<Long> {
    val visited = mutableSetOf<Long>()
    val result = mutableListOf<Long>()

    fun visit(pid: Long) {
        if (!visited.add(pid)) return
        childrenReader(pid).forEach(::visit)
        result += pid
    }

    visit(rootPid)
    return result
}

internal fun findManagedRuntimeRootPids(
    runtimeDirectory: File,
    procRoot: File = File("/proc"),
    prootMarker: String = EmbeddedCommandSuite.PROOT_LIBRARY_NAME,
): List<Long> {
    val runtimeMarker = runtimeDirectory.absolutePath
    return procRoot.listFiles()
        .orEmpty()
        .asSequence()
        .filter(File::isDirectory)
        .mapNotNull { directory ->
            val pid = directory.name.toLongOrNull() ?: return@mapNotNull null
            val commandLine =
                runCatching {
                    File(directory, "cmdline")
                        .readBytes()
                        .toString(Charsets.UTF_8)
                        .replace('\u0000', ' ')
                }.getOrNull() ?: return@mapNotNull null
            pid.takeIf {
                commandLine.contains(prootMarker) &&
                    commandLine.contains(runtimeMarker)
            }
        }
        .sorted()
        .toList()
}

internal fun processId(process: Process): Long? {
    val methodValue =
        runCatching {
            process.javaClass.methods
                .firstOrNull { it.name == "pid" && it.parameterCount == 0 }
                ?.invoke(process)
        }.getOrNull()
    when (methodValue) {
        is Long -> return methodValue
        is Int -> return methodValue.toLong()
    }

    return sequenceOf("pid", "id")
        .mapNotNull { fieldName ->
            runCatching {
                process.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }.get(process)
            }.getOrNull()
        }
        .mapNotNull { value ->
            when (value) {
                is Long -> value
                is Int -> value.toLong()
                else -> null
            }
        }
        .firstOrNull()
}

internal fun localRuntimeEnvironment(
    suiteEnvironment: Map<String, String>,
    prootTmp: File,
    githubToken: String? = null,
): Map<String, String> =
    buildMap {
        putAll(suiteEnvironment)
        put("PROOT_TMP_DIR", prootTmp.absolutePath)
        put("HOME", "/root")
        put("USER", "root")
        put("LOGNAME", "root")
        put("SHELL", "/bin/bash")
        put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin")
        put("JAVA_HOME", "/usr/lib/jvm/java-17-openjdk")
        put("TMPDIR", "/tmp")
        put("XDG_CONFIG_HOME", "/root/.config")
        put("XDG_CACHE_HOME", "/root/.cache")
        put("XDG_DATA_HOME", "/root/.local/share")
        put("XDG_STATE_HOME", "/root/.local/state")
        put("OPENCODE_CONFIG_DIR", "/root/.config/opencode")
        put("OPENCODE_DISABLE_AUTOUPDATE", "true")
        githubToken?.takeIf(String::isNotBlank)?.let {
            put("OPENCODE_GITHUB_TOKEN", it)
            put("GH_TOKEN", it)
        }
    }
