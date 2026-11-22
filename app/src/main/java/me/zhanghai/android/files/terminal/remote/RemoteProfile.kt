/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.remote

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class RemoteProtocol(val defaultPort: Int, val label: String) {
    SSH(22, "SSH"),
    TELNET(23, "Telnet");

    companion object {
        fun parse(value: String?): RemoteProtocol =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SSH
    }
}

enum class RemoteAuthType {
    PASSWORD,
    PRIVATE_KEY;

    companion object {
        fun parse(value: String?): RemoteAuthType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: PASSWORD
    }
}

/** A one-tap command shown above the extra keys while connected (e.g. "tmux" → `tmux new -A -s main`). */
data class RemoteShortcut(val label: String, val command: String) {
    fun toJson(): JSONObject = JSONObject().put("label", label).put("command", command)

    companion object {
        fun fromJson(json: JSONObject): RemoteShortcut? {
            val command = json.optString("command").trim()
            if (command.isEmpty()) return null
            val label = json.optString("label").trim().ifEmpty { command }
            return RemoteShortcut(label, command)
        }
    }
}

/**
 * A saved SSH/Telnet connection. Secrets ([password], [privateKey], [keyPassphrase]) are only kept in
 * memory here; [RemoteProfileStore] encrypts them with the Android Keystore before writing them.
 */
data class RemoteProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val protocol: RemoteProtocol = RemoteProtocol.SSH,
    val host: String = "",
    val port: Int = protocol.defaultPort,
    val username: String = "",
    val authType: RemoteAuthType = RemoteAuthType.PASSWORD,
    val password: String? = null,
    val privateKey: String? = null,
    val keyPassphrase: String? = null,
    /** Lines typed after login, e.g. `tmux new -A -s main` then `cd ~/OpenManager`. */
    val startupCommands: String = "",
    val shortcuts: List<RemoteShortcut> = emptyList(),
    val keepAliveSeconds: Int = DEFAULT_KEEP_ALIVE_SECONDS,
    /** Telnet only: the user accepted that this profile travels unencrypted. */
    val telnetRiskAccepted: Boolean = false,
    val lastUsedMillis: Long = 0L
) {
    val displayName: String
        get() = name.ifBlank { endpoint }

    val endpoint: String
        get() {
            val user = if (username.isNotBlank()) "$username@" else ""
            val hostPart = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            val portPart = if (port != protocol.defaultPort) ":$port" else ""
            return "$user$hostPart$portPart"
        }

    val hasSavedPassword: Boolean
        get() = !password.isNullOrEmpty()

    /** Bytes the terminal types right after the shell opens: one line per startup command. */
    fun startupInput(): ByteArray? {
        val lines = parseCommandLines(startupCommands)
        if (lines.isEmpty()) return null
        return lines.joinToString(separator = "") { "$it\r" }.toByteArray(Charsets.UTF_8)
    }

    /** Non-secret fields only; see [RemoteProfileStore] for the encrypted secrets. */
    fun toPublicJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("protocol", protocol.name)
        put("host", host)
        put("port", port)
        put("username", username)
        put("authType", authType.name)
        put("startupCommands", startupCommands)
        put("shortcuts", JSONArray().apply { shortcuts.forEach { put(it.toJson()) } })
        put("keepAliveSeconds", keepAliveSeconds)
        put("telnetRiskAccepted", telnetRiskAccepted)
        put("lastUsedMillis", lastUsedMillis)
    }

    /** Returns null when the profile cannot be used to connect. */
    fun validationError(): ValidationError? = when {
        host.isBlank() -> ValidationError.HOST
        !isValidHost(host.trim()) -> ValidationError.HOST
        port !in 1..65535 -> ValidationError.PORT
        protocol == RemoteProtocol.SSH && username.isBlank() -> ValidationError.USERNAME
        protocol == RemoteProtocol.SSH && authType == RemoteAuthType.PRIVATE_KEY &&
            privateKey.isNullOrBlank() -> ValidationError.PRIVATE_KEY
        else -> null
    }

    enum class ValidationError { HOST, PORT, USERNAME, PRIVATE_KEY }

    companion object {
        const val DEFAULT_KEEP_ALIVE_SECONDS = 30
        private const val MAX_SHORTCUTS = 24

        fun fromPublicJson(json: JSONObject): RemoteProfile {
            val protocol = RemoteProtocol.parse(json.optString("protocol"))
            val shortcutsJson = json.optJSONArray("shortcuts") ?: JSONArray()
            val shortcuts = (0 until shortcutsJson.length()).mapNotNull { index ->
                shortcutsJson.optJSONObject(index)?.let { RemoteShortcut.fromJson(it) }
            }.take(MAX_SHORTCUTS)
            return RemoteProfile(
                id = json.optString("id").ifEmpty { UUID.randomUUID().toString() },
                name = json.optString("name"),
                protocol = protocol,
                host = json.optString("host"),
                port = json.optInt("port", protocol.defaultPort),
                username = json.optString("username"),
                authType = RemoteAuthType.parse(json.optString("authType")),
                startupCommands = json.optString("startupCommands"),
                shortcuts = shortcuts,
                keepAliveSeconds = json.optInt("keepAliveSeconds", DEFAULT_KEEP_ALIVE_SECONDS)
                    .coerceIn(0, 600),
                telnetRiskAccepted = json.optBoolean("telnetRiskAccepted", false),
                lastUsedMillis = json.optLong("lastUsedMillis", 0L)
            )
        }

        /** Splits a multi-line field into commands, dropping blanks and `#` comments. */
        fun parseCommandLines(text: String): List<String> =
            text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()

        /**
         * Parses the shortcut editor format: one per line, either `label = command` or just `command`.
         */
        fun parseShortcuts(text: String): List<RemoteShortcut> =
            parseCommandLines(text).mapNotNull { line ->
                val separator = line.indexOf(" = ")
                if (separator > 0) {
                    val label = line.substring(0, separator).trim()
                    val command = line.substring(separator + 3).trim()
                    if (command.isEmpty()) null else RemoteShortcut(label.ifEmpty { command }, command)
                } else {
                    RemoteShortcut(shortLabel(line), line)
                }
            }.take(MAX_SHORTCUTS)

        fun formatShortcuts(shortcuts: List<RemoteShortcut>): String =
            shortcuts.joinToString("\n") {
                if (it.label == shortLabel(it.command)) it.command else "${it.label} = ${it.command}"
            }

        private fun shortLabel(command: String): String =
            if (command.length <= 18) command else command.take(17) + "…"

        private val HOST_REGEX = Regex("^[A-Za-z0-9._\\-]+$")

        fun isValidHost(host: String): Boolean {
            if (host.isEmpty() || host.length > 253) return false
            val bare = host.removePrefix("[").removeSuffix("]")
            if (bare.contains(':')) {
                // IPv6 literal.
                return bare.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }
            }
            return HOST_REGEX.matches(bare)
        }

        /** True for loopback and private/link-local networks, where Telnet exposure is limited. */
        fun isLocalNetworkHost(host: String): Boolean {
            val h = host.trim().removePrefix("[").removeSuffix("]").lowercase()
            if (h == "localhost" || h == "::1" || h.endsWith(".local") || h.endsWith(".lan")) return true
            if (h.startsWith("fe80:") || h.startsWith("fd") || h.startsWith("fc")) return h.contains(':')
            val parts = h.split('.')
            if (parts.size != 4) return false
            val octets = parts.map { it.toIntOrNull() ?: return false }
            if (octets.any { it !in 0..255 }) return false
            return octets[0] == 10 || octets[0] == 127 ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 169 && octets[1] == 254) ||
                (octets[0] == 100 && octets[1] in 64..127)
        }
    }
}
