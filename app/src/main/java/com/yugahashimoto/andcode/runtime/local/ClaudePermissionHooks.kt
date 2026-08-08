package com.yugahashimoto.andcode.runtime.local

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * Installs AndCode's Claude Code PermissionRequest hook into the guest settings and binary path.
 */
object ClaudePermissionHooks {
    const val HOOK_GUEST_PATH = "/usr/local/bin/and-code-claude-permission-hook.sh"
    const val HOOK_MARKER = "and-code-claude-permission"
    private const val SETTINGS_RELATIVE = "root/.claude/settings.json"
    private const val HOOK_RELATIVE = "usr/local/bin/and-code-claude-permission-hook.sh"

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = true
        }

    fun settingsFragment(): String =
        """
        {
          "hooks": {
            "PermissionRequest": [
              {
                "matcher": "*",
                "hooks": [
                  {
                    "type": "command",
                    "command": "$HOOK_GUEST_PATH",
                    "timeout": 360,
                    "statusMessage": "Waiting for AndCode approval"
                  }
                ]
              }
            ]
          }
        }
        """.trimIndent()

    fun mergeSettingsJson(existing: String?): String {
        val root =
            if (existing.isNullOrBlank()) {
                buildJsonObject {}
            } else {
                runCatching { json.parseToJsonElement(existing).jsonObject }
                    .getOrDefault(buildJsonObject {})
            }
        val hooks = root["hooks"]?.jsonObject ?: buildJsonObject {}
        val permissionGroups = hooks["PermissionRequest"]?.jsonArray?.toMutableList() ?: mutableListOf()
        permissionGroups.removeAll { group ->
            group.jsonObject["hooks"]?.jsonArray?.any { handler ->
                handler.jsonObject["command"]?.jsonPrimitive?.contentOrNull?.contains(HOOK_MARKER) == true
            } == true
        }
        permissionGroups +=
            buildJsonObject {
                put("matcher", "*")
                put(
                    "hooks",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "command")
                                put("command", HOOK_GUEST_PATH)
                                put("timeout", 360)
                                put("statusMessage", "Waiting for AndCode approval")
                            },
                        )
                    },
                )
            }
        val mergedHooks =
            buildJsonObject {
                hooks.forEach { (key, value) ->
                    if (key != "PermissionRequest") put(key, value)
                }
                put("PermissionRequest", JsonArray(permissionGroups))
            }
        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                root.forEach { (key, value) ->
                    if (key != "hooks") put(key, value)
                }
                put("hooks", mergedHooks)
            },
        )
    }

    /**
     * Writes the hook script and merges settings into [rootfs].
     *
     * @return true when the guest is ready for interactive approvals.
     */
    fun installInto(
        rootfs: File,
        hookScript: String,
    ): Boolean {
        return runCatching {
            val scriptFile = File(rootfs, HOOK_RELATIVE).apply { parentFile?.mkdirs() }
            scriptFile.writeText(hookScript)
            scriptFile.setExecutable(true, false)
            val settingsFile = File(rootfs, SETTINGS_RELATIVE).apply { parentFile?.mkdirs() }
            val existing = settingsFile.takeIf { it.isFile }?.readText()
            settingsFile.writeText(mergeSettingsJson(existing))
            scriptFile.isFile && settingsFile.isFile
        }.getOrDefault(false)
    }

    fun isInstalled(rootfs: File): Boolean {
        val script = File(rootfs, HOOK_RELATIVE)
        val settings = File(rootfs, SETTINGS_RELATIVE)
        if (!script.isFile) return false
        val text = settings.takeIf { it.isFile }?.readText().orEmpty()
        return text.contains(HOOK_MARKER)
    }
}
