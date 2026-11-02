/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager

object TextEditorSettings {
    private const val PREF_FONT_SIZE = "text_editor_font_size"
    private const val PREF_WORD_WRAP = "text_editor_word_wrap"
    private const val PREF_SHOW_LINE_NUMBERS = "text_editor_show_line_numbers"
    private const val PREF_THEME = "text_editor_theme"
    private const val PREF_SHOW_WRAP_ARROWS = "text_editor_show_wrap_arrows"
    private const val PREF_HIGHLIGHT_CURRENT_LINE = "text_editor_highlight_current_line"
    private const val PREF_SYNTAX_HIGHLIGHTING = "text_editor_syntax_highlighting"
    private const val PREF_SHOW_SYMBOL_BAR = "text_editor_show_symbol_bar"
    private const val PREF_AUTO_INDENT = "text_editor_auto_indent"
    private const val PREF_AUTO_CLOSE_PAIRS = "text_editor_auto_close_pairs"
    private const val PREF_PINCH_TO_ZOOM = "text_editor_pinch_to_zoom"
    private const val PREF_READ_ONLY_DEFAULT = "text_editor_read_only_default"
    private const val PREF_CONFIRM_ON_EXIT = "text_editor_confirm_on_exit"

    const val DEFAULT_FONT_SIZE = 14f
    const val MIN_FONT_SIZE = 8f
    const val MAX_FONT_SIZE = 38f

    fun getFontSize(context: Context): Float {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        return sp.getFloat(PREF_FONT_SIZE, DEFAULT_FONT_SIZE).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
    }

    fun setFontSize(context: Context, size: Float) {
        val clamped = size.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putFloat(PREF_FONT_SIZE, clamped)
        }
    }

    fun isWordWrap(context: Context): Boolean {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        return sp.getBoolean(PREF_WORD_WRAP, true)
    }

    fun setWordWrap(context: Context, wrap: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(PREF_WORD_WRAP, wrap)
        }
    }

    fun isShowLineNumbers(context: Context): Boolean {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        return sp.getBoolean(PREF_SHOW_LINE_NUMBERS, true)
    }

    fun setShowLineNumbers(context: Context, show: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(PREF_SHOW_LINE_NUMBERS, show)
        }
    }

    fun getTheme(context: Context): EditorTheme {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        val id = sp.getString(PREF_THEME, EditorTheme.ONE_DARK.id) ?: EditorTheme.ONE_DARK.id
        return EditorTheme.fromId(id)
    }

    fun setTheme(context: Context, theme: EditorTheme) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(PREF_THEME, theme.id)
        }
    }

    fun isShowWrapArrows(context: Context): Boolean {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        return sp.getBoolean(PREF_SHOW_WRAP_ARROWS, true)
    }

    fun setShowWrapArrows(context: Context, show: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(PREF_SHOW_WRAP_ARROWS, show)
        }
    }

    fun isHighlightCurrentLine(context: Context): Boolean {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        return sp.getBoolean(PREF_HIGHLIGHT_CURRENT_LINE, true)
    }

    fun setHighlightCurrentLine(context: Context, highlight: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(PREF_HIGHLIGHT_CURRENT_LINE, highlight)
        }
    }

    fun isSyntaxHighlighting(context: Context): Boolean {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        return sp.getBoolean(PREF_SYNTAX_HIGHLIGHTING, true)
    }

    fun setSyntaxHighlighting(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(PREF_SYNTAX_HIGHLIGHTING, enabled)
        }
    }

    fun isShowSymbolBar(context: Context): Boolean {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        return sp.getBoolean(PREF_SHOW_SYMBOL_BAR, true)
    }

    fun setShowSymbolBar(context: Context, show: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(PREF_SHOW_SYMBOL_BAR, show)
        }
    }

    fun isAutoIndent(context: Context): Boolean {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        return sp.getBoolean(PREF_AUTO_INDENT, true)
    }

    fun setAutoIndent(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(PREF_AUTO_INDENT, enabled)
        }
    }

    fun isAutoClosePairs(context: Context): Boolean {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        return sp.getBoolean(PREF_AUTO_CLOSE_PAIRS, true)
    }

    fun setAutoClosePairs(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(PREF_AUTO_CLOSE_PAIRS, enabled)
        }
    }

    fun isPinchToZoom(context: Context): Boolean {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        return sp.getBoolean(PREF_PINCH_TO_ZOOM, true)
    }

    fun setPinchToZoom(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(PREF_PINCH_TO_ZOOM, enabled)
        }
    }

    fun isReadOnlyDefault(context: Context): Boolean {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        return sp.getBoolean(PREF_READ_ONLY_DEFAULT, false)
    }

    fun setReadOnlyDefault(context: Context, readOnly: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(PREF_READ_ONLY_DEFAULT, readOnly)
        }
    }

    fun isConfirmOnExit(context: Context): Boolean {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        return sp.getBoolean(PREF_CONFIRM_ON_EXIT, true)
    }

    fun setConfirmOnExit(context: Context, confirm: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(PREF_CONFIRM_ON_EXIT, confirm)
        }
    }
}
