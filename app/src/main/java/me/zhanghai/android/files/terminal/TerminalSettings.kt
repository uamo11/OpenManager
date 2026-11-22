/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import android.content.Context
import android.os.Environment
import androidx.core.content.edit
import androidx.preference.PreferenceManager

object TerminalSettings {
    private const val PREF_START_PATH = "terminal_start_path"
    private const val PREF_FONT_SIZE = "terminal_font_size"
    private const val PREF_KEEP_BLACK_THEME = "terminal_keep_black_theme"
    private const val PREF_ROOT_SESSION = "terminal_root_session"
    private const val PREF_INSTANT_TYPING = "terminal_instant_typing"
    private const val PREF_PREDICTIVE_ECHO = "terminal_predictive_echo"
    private const val PREF_EXTRA_KEYS_MODE = "terminal_extra_keys_mode"

    const val MIN_FONT_SIZE_SP = 8
    const val MAX_FONT_SIZE_SP = 36
    const val DEFAULT_FONT_SIZE_SP = 14

    @Suppress("DEPRECATION")
    private val DEFAULT_START_PATH: String
        get() = try {
            Environment.getExternalStorageDirectory().absolutePath
        } catch (e: Exception) {
            "/storage/emulated/0"
        }

    fun getStartPath(context: Context): String {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        return sp.getString(PREF_START_PATH, null) ?: DEFAULT_START_PATH
    }

    fun setStartPath(context: Context, path: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(PREF_START_PATH, path)
        }
    }

    fun getFontSize(context: Context): Int {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        val size = sp.getInt(PREF_FONT_SIZE, DEFAULT_FONT_SIZE_SP)
        return size.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
    }

    fun setFontSize(context: Context, size: Int) {
        val clamped = size.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putInt(PREF_FONT_SIZE, clamped)
        }
    }

    fun isKeepBlackTheme(context: Context): Boolean {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        return sp.getBoolean(PREF_KEEP_BLACK_THEME, true)
    }

    fun setKeepBlackTheme(context: Context, keepBlack: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(PREF_KEEP_BLACK_THEME, keepBlack)
        }
    }

    fun isRootSession(context: Context): Boolean {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        return sp.getBoolean(PREF_ROOT_SESSION, false)
    }

    fun setRootSession(context: Context, root: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(PREF_ROOT_SESSION, root)
        }
    }

    /**
     * Commit every key immediately instead of letting the keyboard hold a composing word (which is
     * what makes text appear late with Samsung/Gboard in `ssh` started from the local shell).
     */
    fun isInstantTyping(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_INSTANT_TYPING, true)

    fun setInstantTyping(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit { putBoolean(PREF_INSTANT_TYPING, enabled) }
    }

    /** Mosh-style predictive echo for SSH/Telnet profiles. */
    fun isPredictiveEcho(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_PREDICTIVE_ECHO, true)

    fun setPredictiveEcho(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit { putBoolean(PREF_PREDICTIVE_ECHO, enabled) }
    }

    fun getExtraKeysMode(context: Context): TerminalExtraKeysView.DeckMode {
        val name = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_EXTRA_KEYS_MODE, null)
        return TerminalExtraKeysView.DeckMode.entries.firstOrNull { it.name == name }
            ?: TerminalExtraKeysView.DeckMode.FULL
    }

    fun setExtraKeysMode(context: Context, mode: TerminalExtraKeysView.DeckMode) {
        PreferenceManager.getDefaultSharedPreferences(context).edit { putString(PREF_EXTRA_KEYS_MODE, mode.name) }
    }
}
