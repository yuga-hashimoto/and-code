package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import java.io.File
import java.io.RandomAccessFile

data class LocalRuntimeProcessMetrics(
    val pid: Long?,
    val rssBytes: Long?,
    val uptimeMillis: Long,
    val restartCount: Int = 0,
    val lastExitCode: Int? = null,
    val lastExitAtMillis: Long? = null,
)

data class LocalRuntimeCommandResult(
    val exitCode: Int,
    val output: String,
)

data class LocalRuntimeToolDefinition(
    val id: String,
    val label: String,
    val command: String,
)

data class LocalRuntimeToolCheck(
    val id: String,
    val label: String,
    val available: Boolean,
    val detail: String,
)

data class LocalRuntimeDiagnostics(
    val status: LocalRuntimeStatus,
    val version: String?,
    val abi: String,
    val port: Int?,
    val runtimeBytes: Long,
    val freeBytes: Long,
    val process: LocalRuntimeProcessMetrics?,
    val tools: List<LocalRuntimeToolCheck>,
    val logTail: String,
    val collectedAtMillis: Long,
    val restartCount: Int = 0,
    val lastExitCode: Int? = null,
    val lastExitAtMillis: Long? = null,
)

class LocalRuntimeDiagnosticsCollector(
    private val runtimeDirectory: File,
    private val abi: String,
    private val statusProvider: () -> LocalRuntimeStatus,
    private val freeBytesProvider: () -> Long = {
        runtimeDirectory.parentFile?.usableSpace ?: runtimeDirectory.usableSpace
    },
    private val processMetricsProvider: () -> LocalRuntimeProcessMetrics?,
    private val commandExecutor: (LocalRuntimeToolDefinition) -> LocalRuntimeCommandResult,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val maxLogCharacters: Int = 12_000,
    private val messages: LocalRuntimeMessages = LocalRuntimeMessages,
) {
    init {
        require(maxLogCharacters >= 0)
    }

    fun collect(): LocalRuntimeDiagnostics {
        val status = statusProvider()
        val installed =
            status !is LocalRuntimeStatus.NotInstalled &&
                status !is LocalRuntimeStatus.UnsupportedAbi
        val tools =
            if (installed) {
                REQUIRED_TOOLS.map { definition ->
                    runCatching { commandExecutor(definition) }
                        .fold(
                            onSuccess = { result ->
                                LocalRuntimeToolCheck(
                                    id = definition.id,
                                    label = displayLabel(definition),
                                    available = result.exitCode == 0,
                                    detail =
                                        result.output.trim().ifBlank {
                                            if (result.exitCode == 0) {
                                                messages.diagnosticsToolAvailable
                                            } else {
                                                messages.diagnosticsToolExitCode(
                                                    result.exitCode,
                                                )
                                            }
                                        },
                                )
                            },
                            onFailure = { error ->
                                LocalRuntimeToolCheck(
                                    id = definition.id,
                                    label = displayLabel(definition),
                                    available = false,
                                    detail = error.message?.takeIf(String::isNotBlank) ?: messages.diagnosticsToolCheckFailed,
                                )
                            },
                        )
                }
            } else {
                REQUIRED_TOOLS.map { definition ->
                    LocalRuntimeToolCheck(
                        id = definition.id,
                        label = displayLabel(definition),
                        available = false,
                        detail = messages.diagnosticsToolNotInstalled,
                    )
                }
            }

        return LocalRuntimeDiagnostics(
            status = status,
            version = status.versionOrNull(),
            abi = abi,
            port = status.portOrNull(),
            runtimeBytes = if (runtimeDirectory.isDirectory) runtimeDirectory.recursiveFileBytes() else 0L,
            freeBytes = freeBytesProvider().coerceAtLeast(0L),
            process = processMetricsProvider(),
            tools = tools,
            logTail = readLogTail(),
            collectedAtMillis = nowMillis(),
            restartCount = processMetricsProvider()?.restartCount ?: 0,
            lastExitCode = processMetricsProvider()?.lastExitCode,
            lastExitAtMillis = processMetricsProvider()?.lastExitAtMillis,
        )
    }

    private fun displayLabel(definition: LocalRuntimeToolDefinition): String =
        if (definition.id == "ca-certificates") messages.diagnosticsCaCertificates else definition.label

    private fun readLogTail(): String {
        if (maxLogCharacters == 0) return ""
        val logsDirectory = File(runtimeDirectory, "logs")
        if (!logsDirectory.isDirectory) return ""
        val combined =
            logsDirectory
                .listFiles()
                .orEmpty()
                .asSequence()
                .filter { it.isFile && it.extension.equals("log", ignoreCase = true) }
                .sortedBy { it.name }
                .joinToString(separator = "\n") { file -> readFileTail(file) }
        return combined.takeLast(maxLogCharacters)
    }

    private fun readFileTail(file: File): String =
        runCatching {
            RandomAccessFile(file, "r").use { input ->
                val requestedBytes = maxLogCharacters.toLong() * MAX_UTF8_BYTES_PER_CHARACTER
                val start = (input.length() - requestedBytes).coerceAtLeast(0L)
                input.seek(start)
                val byteCount = (input.length() - start).toInt()
                val bytes = ByteArray(byteCount)
                input.readFully(bytes)
                bytes.toString(Charsets.UTF_8).takeLast(maxLogCharacters)
            }
        }.getOrElse { error ->
            "${file.name}: ${error.message.orEmpty()}"
        }

    companion object {
        private const val MAX_UTF8_BYTES_PER_CHARACTER = 4L

        val REQUIRED_TOOLS =
            listOf(
                LocalRuntimeToolDefinition("opencode", "OpenCode", "/usr/local/bin/opencode --version"),
                LocalRuntimeToolDefinition("git", "Git", "git --version"),
                LocalRuntimeToolDefinition("bash", "Bash", "bash --version | head -n 1"),
                LocalRuntimeToolDefinition("curl", "curl", "curl --version | head -n 1"),
                LocalRuntimeToolDefinition("ssh", "SSH", "ssh -V"),
                LocalRuntimeToolDefinition("rg", "ripgrep", "rg --version | head -n 1"),
                LocalRuntimeToolDefinition(
                    "ca-certificates",
                    "CA certificates",
                    "test -s /etc/ssl/certs/ca-certificates.crt && echo installed",
                ),
                LocalRuntimeToolDefinition("adb", "ADB", "adb version | head -n 1"),
                LocalRuntimeToolDefinition("java", "Java", "java -version 2>&1 | head -n 1"),
                LocalRuntimeToolDefinition("gradle", "Gradle", "gradle --version 2>&1 | head -n 1"),
                LocalRuntimeToolDefinition("python3", "Python", "python3 --version"),
                LocalRuntimeToolDefinition("node", "Node.js", "node --version"),
                LocalRuntimeToolDefinition("npm", "npm", "npm --version"),
                LocalRuntimeToolDefinition("go", "Go", "go version"),
                LocalRuntimeToolDefinition("make", "Make", "make --version | head -n 1"),
                LocalRuntimeToolDefinition("gcc", "GCC", "gcc --version | head -n 1"),
            )
    }
}

internal fun readDirectChildPids(
    parentPid: Long,
    procRoot: File = File("/proc"),
): List<Long> {
    val standardChildren = File(procRoot, "$parentPid/task/$parentPid/children")
    if (standardChildren.isFile) {
        return runCatching {
            standardChildren.readText()
                .trim()
                .split(Regex("\\s+"))
                .mapNotNull(String::toLongOrNull)
                .sorted()
        }.getOrDefault(emptyList())
    }

    return procRoot.listFiles()
        .orEmpty()
        .asSequence()
        .filter(File::isDirectory)
        .mapNotNull { directory ->
            val pid = directory.name.toLongOrNull() ?: return@mapNotNull null
            val statusText =
                runCatching {
                    File(directory, "status").readText()
                }.getOrNull() ?: return@mapNotNull null
            pid.takeIf { parseParentProcessId(statusText) == parentPid }
        }
        .sorted()
        .toList()
}

internal fun parseParentProcessId(statusText: String): Long? =
    statusText.lineSequence()
        .firstOrNull { it.startsWith("PPid:") }
        ?.substringAfter("PPid:")
        ?.trim()
        ?.substringBefore(' ')
        ?.toLongOrNull()

internal fun totalResidentSetBytes(
    rootPid: Long,
    statusReader: (Long) -> String?,
    childrenReader: (Long) -> List<Long>,
): Long? {
    val pending = ArrayDeque<Long>().apply { add(rootPid) }
    val visited = mutableSetOf<Long>()
    var total = 0L
    var found = false
    while (pending.isNotEmpty()) {
        val pid = pending.removeFirst()
        if (!visited.add(pid)) continue
        statusReader(pid)?.let(::parseResidentSetBytes)?.let { rssBytes ->
            total += rssBytes
            found = true
        }
        childrenReader(pid).forEach(pending::addLast)
    }
    return total.takeIf { found }
}

internal fun parseResidentSetBytes(statusText: String): Long? {
    val valueKb =
        statusText.lineSequence()
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.substringAfter("VmRSS:")
            ?.trim()
            ?.substringBefore(' ')
            ?.toLongOrNull()
    return valueKb?.times(1024L)
}

private fun File.recursiveFileBytes(): Long =
    walkTopDown()
        .filter(File::isFile)
        .sumOf(File::length)

private fun LocalRuntimeStatus.versionOrNull(): String? =
    when (this) {
        is LocalRuntimeStatus.Ready -> version
        is LocalRuntimeStatus.Starting -> version
        is LocalRuntimeStatus.Updating -> currentVersion
        is LocalRuntimeStatus.Stopped -> version
        else -> null
    }

private fun LocalRuntimeStatus.portOrNull(): Int? =
    when (this) {
        is LocalRuntimeStatus.Ready -> port
        is LocalRuntimeStatus.Starting -> port
        is LocalRuntimeStatus.Updating -> null
        is LocalRuntimeStatus.Stopped -> port
        else -> null
    }
