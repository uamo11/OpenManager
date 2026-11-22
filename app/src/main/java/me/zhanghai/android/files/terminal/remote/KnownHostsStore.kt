/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.remote

import android.content.Context
import android.util.Base64
import java.security.MessageDigest

/**
 * OpenSSH-style trust-on-first-use store: `host:port keyType` → SHA-256 fingerprint.
 *
 * A changed key is never accepted silently; the user must explicitly replace it.
 */
object KnownHostsStore {
    private const val PREFS_NAME = "remote_terminal_known_hosts"

    sealed class Verdict {
        object Trusted : Verdict()
        object Unknown : Verdict()
        data class Changed(val previousFingerprint: String) : Verdict()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun entryKey(host: String, port: Int, keyType: String): String =
        "${host.trim().lowercase()}:$port $keyType"

    fun check(context: Context, host: String, port: Int, keyType: String, fingerprint: String): Verdict {
        val stored = prefs(context).getString(entryKey(host, port, keyType), null)
        return evaluate(stored, fingerprint)
    }

    fun evaluate(stored: String?, fingerprint: String): Verdict = when (stored) {
        null -> Verdict.Unknown
        fingerprint -> Verdict.Trusted
        else -> Verdict.Changed(stored)
    }

    /** Key types already trusted for this endpoint, so the key exchange can prefer them. */
    fun knownKeyTypes(context: Context, host: String, port: Int): List<String> {
        val prefix = "${host.trim().lowercase()}:$port "
        return prefs(context).all.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
    }

    fun trust(context: Context, host: String, port: Int, keyType: String, fingerprint: String) {
        prefs(context).edit().putString(entryKey(host, port, keyType), fingerprint).apply()
    }

    fun forget(context: Context, host: String, port: Int) {
        val prefix = "${host.trim().lowercase()}:$port "
        val prefs = prefs(context)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
    }

    /** `SHA256:<base64 without padding>`, the format printed by `ssh-keygen -lf`. */
    fun sha256Fingerprint(publicKeyBlob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBlob)
        return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
