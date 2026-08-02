package com.yugahashimoto.andcode.core.security

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class ConnectionQrPayload(
    val name: String? = null,
    val url: String? = null,
    val username: String? = null,
    val password: String? = null,
    val insecure: Boolean = false,
) {
    override fun toString(): String =
        "ConnectionQrPayload(name=$name, url=$url, username=$username, " +
            "password=${if (password.isNullOrEmpty()) password else "<redacted>"}, insecure=$insecure)"

    companion object {
        private const val SCHEME_PREFIX = "opencode://connect"

        fun parse(text: String): ConnectionQrPayload? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null

            if (trimmed.startsWith(SCHEME_PREFIX)) {
                return runCatching { parseConnectUri(trimmed) }.getOrNull()
            }

            if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                return ConnectionQrPayload(url = trimmed)
            }

            return null
        }

        private fun parseConnectUri(text: String): ConnectionQrPayload? {
            val queryStart = text.indexOf('?')
            if (queryStart == -1) return ConnectionQrPayload()
            val query = text.substring(queryStart + 1)
            val params = linkedMapOf<String, String>()
            query.split('&').forEach { pair ->
                if (pair.isEmpty()) return@forEach
                val separator = pair.indexOf('=')
                val key = if (separator == -1) pair else pair.substring(0, separator)
                val rawValue = if (separator == -1) "" else pair.substring(separator + 1)
                if (key.isBlank()) return@forEach
                params[key] = decode(rawValue)
            }
            return ConnectionQrPayload(
                name = params["name"]?.takeIf { it.isNotBlank() },
                url = params["url"]?.takeIf { it.isNotBlank() },
                username = params["username"]?.takeIf { it.isNotBlank() },
                password = params["password"]?.takeIf { it.isNotBlank() },
                insecure = params["insecure"]?.equals("true", ignoreCase = true) ?: false,
            )
        }

        private fun decode(value: String): String =
            runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)

        private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

        fun format(
            name: String?,
            url: String?,
            username: String?,
            password: String?,
            insecure: Boolean = false,
        ): String {
            val params =
                buildList {
                    name?.takeIf { it.isNotBlank() }?.let { add("name" to it) }
                    url?.takeIf { it.isNotBlank() }?.let { add("url" to it) }
                    username?.takeIf { it.isNotBlank() }?.let { add("username" to it) }
                    password?.takeIf { it.isNotBlank() }?.let { add("password" to it) }
                    add("insecure" to insecure.toString())
                }
            val query = params.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
            return "$SCHEME_PREFIX?$query"
        }
    }
}
