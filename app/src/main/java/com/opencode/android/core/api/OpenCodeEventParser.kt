package com.opencode.android.core.api

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject

class OpenCodeEventParser(
    private val gson: Gson = Gson()
) {
    fun parse(json: String): OpenCodeEvent {
        val envelope = runCatching { gson.fromJson(json, JsonObject::class.java) }.getOrNull()
            ?: return OpenCodeEvent.Unknown("invalid", json)
        // `/global/event` wraps each instance event as {directory, project, workspace, payload},
        // while the per-instance `/event` stream emits the event object directly.
        val root = envelope.objectOrNull("payload") ?: envelope
        val type = root.get("type")?.takeIf { it.isJsonPrimitive }?.asString
            ?: return OpenCodeEvent.Unknown("missing-type", json)
        val properties = root.objectOrNull("properties") ?: JsonObject()

        return runCatching {
            when (type) {
                "server.connected" -> OpenCodeEvent.ServerConnected
                "message.updated" -> {
                    val info = gson.fromJson(
                        requireNotNull(properties.objectOrNull("info")),
                        OpenCodeMessageInfo::class.java
                    )
                    OpenCodeEvent.MessageUpdated(requireNotNull(info))
                }
                "message.part.updated" -> {
                    val part = gson.fromJson(
                        requireNotNull(properties.objectOrNull("part")),
                        OpenCodePart::class.java
                    )
                    OpenCodeEvent.MessagePartUpdated(requireNotNull(part))
                }
                "message.part.delta" -> OpenCodeEvent.MessagePartDelta(
                    sessionId = properties.get("sessionID").asString,
                    messageId = properties.get("messageID").asString,
                    partId = properties.get("partID").asString,
                    field = properties.get("field").asString,
                    delta = properties.get("delta").asString
                )
                "permission.asked" -> OpenCodeEvent.PermissionAsked(
                    PermissionRequest(
                        id = properties.get("id").asString,
                        sessionId = properties.get("sessionID").asString,
                        permission = properties.get("permission").asString,
                        patterns = properties.getAsJsonArray("patterns")
                            ?.mapNotNull { element -> element.takeIf { it.isJsonPrimitive }?.asString }
                            .orEmpty(),
                        metadata = properties.objectOrNull("metadata")
                            ?.entrySet()
                            ?.associate { (key, value) -> key to value }
                            .orEmpty()
                    )
                )
                "permission.replied" -> OpenCodeEvent.PermissionReplied(
                    sessionId = properties.get("sessionID").asString,
                    requestId = properties.get("requestID").asString
                )
                "session.idle" -> OpenCodeEvent.SessionIdle(properties.get("sessionID").asString)
                "session.status" -> OpenCodeEvent.SessionStatusChanged(
                    sessionId = properties.get("sessionID").asString,
                    status = requireNotNull(properties.objectOrNull("status")).get("type").asString
                )
                "session.error" -> OpenCodeEvent.SessionError(
                    sessionId = properties.get("sessionID")?.takeUnless { it.isJsonNull }?.asString,
                    message = describeError(properties.get("error"))
                )
                else -> OpenCodeEvent.Unknown(type, json)
            }
        }.getOrElse { OpenCodeEvent.Unknown(type, json) }
    }

    private fun JsonObject.objectOrNull(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    /**
     * OpenCode reports session failures as a named error object, e.g.
     * `{"name":"ProviderAuthError","data":{"message":"..."}}`. Prefer the human-readable message
     * over dumping the raw JSON into the chat.
     */
    private fun describeError(element: JsonElement?): String? {
        if (element == null || element.isJsonNull) return null
        if (element.isJsonPrimitive) return element.asString
        val error = element.takeIf { it.isJsonObject }?.asJsonObject ?: return element.toString()
        val message = error.objectOrNull("data")
            ?.get("message")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.takeIf { it.isNotBlank() }
        val name = error.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        return when {
            message != null && name != null -> "$name: $message"
            message != null -> message
            name != null -> name
            else -> error.toString()
        }
    }
}
