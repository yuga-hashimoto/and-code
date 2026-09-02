package com.yugahashimoto.andcode.runtime.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Owns `~/.gemini/antigravity-cli/settings.json` inside the guest rootfs.
 *
 * The file is built with the JSON serializer rather than a string literal, and it is repaired on
 * every sign-in rather than only at install time: a rootfs written by an older build can carry a
 * malformed file, and the official CLI then logs `settings file is malformed` and silently falls
 * back to defaults - which re-enables the alternate screen buffer and makes the sign-in transcript
 * far harder to read.
 */
object AntigravityGuestSettings {
    private const val RELATIVE_PATH = "root/.gemini/antigravity-cli/settings.json"

    /**
     * The one-shot `--print` bridge has no channel to answer per-tool review prompts, so
     * `request-review` makes agy treat every tool call - `read_file`, `list_dir`, shell - as
     * denied by the user. The session's `--mode` flag is what scopes what the agent may do;
     * this setting only has to stop the unanswerable prompts.
     */
    const val TOOL_PERMISSION = "always-proceed"

    private val json = Json { prettyPrint = true }

    val content: String =
        json.encodeToString(
            JsonObject.serializer(),
            JsonObject(
                mapOf(
                    "altScreenMode" to JsonPrimitive("never"),
                    "notifications" to JsonPrimitive(false),
                    "enableTelemetry" to JsonPrimitive(false),
                    "toolPermission" to JsonPrimitive(TOOL_PERMISSION),
                    "trustedWorkspaces" to JsonArray(listOf(JsonPrimitive("/workspace"))),
                ),
            ),
        ) + "\n"

    fun write(rootfs: File) {
        val settings = File(rootfs, RELATIVE_PATH)
        settings.parentFile?.mkdirs()
        settings.writeText(content)
    }

    fun repair(runtime: LocalRuntimeInstaller.InstalledRuntime) {
        val rootfs = runtime.antigravityRootfs ?: runtime.rootfs
        runCatching {
            val settings = File(rootfs, RELATIVE_PATH)
            if (!settings.isFile || !isValid(settings.readText())) write(rootfs)
        }
    }

    /**
     * Valid means usable by the current build, not merely parseable: installs provisioned before
     * the tool-permission fix still carry a parseable `request-review` file that denies every tool
     * call, so the value itself has to be checked for [repair] to ever heal them.
     */
    private fun isValid(raw: String): Boolean =
        runCatching {
            val parsed = Json.parseToJsonElement(raw)
            parsed is JsonObject && parsed["toolPermission"]?.let { it is JsonPrimitive && it.content == TOOL_PERMISSION } == true
        }.getOrDefault(false)
}
