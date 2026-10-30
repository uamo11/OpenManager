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
}
