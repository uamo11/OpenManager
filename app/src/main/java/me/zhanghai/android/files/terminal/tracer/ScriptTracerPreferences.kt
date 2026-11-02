/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.tracer

import android.content.Context
import java.security.MessageDigest

object ScriptTracerPreferences {
    private const val PREFS_NAME = "om_script_tracer_prefs"
    private const val PREFIX_TRACER_ENABLED = "tracer_enabled_"
    private const val PREFIX_DELETION_POLICY = "deletion_policy_"

    enum class DeletionPolicy {
        ASK,         // Prompt user (Allow or Block)
        BLOCK_ALL,   // Block deletions automatically
        ALLOW_ALL    // Allow deletions automatically
    }

    private fun getScriptKey(scriptPath: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val bytes = md.digest(scriptPath.toByteArray())
            bytes.joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            scriptPath.replace("[^a-zA-Z0-9_]".toRegex(), "_")
        }
    }

    fun isTracerEnabled(context: Context, scriptPath: String?): Boolean {
        if (scriptPath.isNullOrEmpty()) return true
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = PREFIX_TRACER_ENABLED + getScriptKey(scriptPath)
        return sp.getBoolean(key, true)
    }

    fun setTracerEnabled(context: Context, scriptPath: String?, enabled: Boolean) {
        if (scriptPath.isNullOrEmpty()) return
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = PREFIX_TRACER_ENABLED + getScriptKey(scriptPath)
        sp.edit().putBoolean(key, enabled).apply()
    }

    fun getDeletionPolicy(context: Context, scriptPath: String?): DeletionPolicy {
        if (scriptPath.isNullOrEmpty()) return DeletionPolicy.ASK
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = PREFIX_DELETION_POLICY + getScriptKey(scriptPath)
        val name = sp.getString(key, DeletionPolicy.ASK.name) ?: DeletionPolicy.ASK.name
        return try {
            DeletionPolicy.valueOf(name)
        } catch (_: Exception) {
            DeletionPolicy.ASK
        }
    }

    fun setDeletionPolicy(context: Context, scriptPath: String?, policy: DeletionPolicy) {
        if (scriptPath.isNullOrEmpty()) return
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = PREFIX_DELETION_POLICY + getScriptKey(scriptPath)
        sp.edit().putString(key, policy.name).apply()
    }
}
