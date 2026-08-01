package com.yugahashimoto.andcode.runtime.local

import android.content.Context
import java.io.File

internal const val AND_CODE_AGENT_CONTEXT_ASSET = "and-code-agent-context.md"

private const val RUNTIME_CONTEXT_PATH = "root/.config/and-code/agent-context.md"

private val AGENT_CONTEXT_PATHS =
    listOf(
        "root/.config/opencode/and-code-context.md",
        "root/.claude/CLAUDE.md",
        "root/.gemini/GEMINI.md",
    )

internal fun ensureAndCodeAgentContext(
    rootfs: File,
    context: Context,
) {
    val agentContext =
        context.assets.open(AND_CODE_AGENT_CONTEXT_ASSET).use { input ->
            input.readBytes()
        }
    installAndCodeAgentContext(rootfs, agentContext)
}

internal fun installAndCodeAgentContext(
    rootfs: File,
    agentContext: ByteArray,
) {
    val source = File(rootfs, RUNTIME_CONTEXT_PATH)
    source.parentFile?.mkdirs()
    if (!source.isFile || !source.readBytes().contentEquals(agentContext)) {
        source.writeBytes(agentContext)
    }
    AGENT_CONTEXT_PATHS.forEach { relativePath ->
        val target = File(rootfs, relativePath)
        if (!target.isFile || !target.readBytes().contentEquals(agentContext)) {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
        }
    }
}
