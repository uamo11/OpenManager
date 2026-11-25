/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import android.graphics.Color
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/** The editor's colors from one of our [EditorTheme]s. */
class OmEditorColorScheme(val theme: EditorTheme) : EditorColorScheme(isDarkTheme(theme)) {

    init {
        applyTheme()
    }

    // The base class calls applyDefault() from its constructor, before our fields exist.
    override fun applyDefault() {
        super.applyDefault()
        @Suppress("SENSELESS_COMPARISON")
        if (theme != null) applyTheme()
    }

    private fun applyTheme() {
        val accent = theme.gutterCurrentLineColor
        setColor(WHOLE_BACKGROUND, theme.backgroundColor)
        setColor(TEXT_NORMAL, theme.textColor)
        setColor(LINE_NUMBER_BACKGROUND, theme.gutterColor)
        setColor(LINE_NUMBER, theme.gutterTextColor)
        setColor(LINE_NUMBER_CURRENT, accent)
        setColor(LINE_NUMBER_PANEL, theme.gutterColor)
        setColor(LINE_NUMBER_PANEL_TEXT, theme.textColor)
        setColor(LINE_DIVIDER, theme.gutterDividerColor)
        setColor(CURRENT_LINE, theme.currentLineColor)
        setColor(SELECTION_INSERT, accent)
        setColor(SELECTION_HANDLE, accent)
        setColor(SELECTED_TEXT_BACKGROUND, withAlpha(accent, 0x55))
        setColor(MATCHED_TEXT_BACKGROUND, withAlpha(theme.typeColor, 0x66))
        setColor(UNDERLINE, accent)
        setColor(BLOCK_LINE, withAlpha(theme.gutterTextColor, 0x40))
        setColor(BLOCK_LINE_CURRENT, withAlpha(accent, 0x99))
        setColor(SIDE_BLOCK_LINE, withAlpha(accent, 0x99))
        setColor(SCROLL_BAR_THUMB, withAlpha(theme.gutterTextColor, 0x88))
        setColor(SCROLL_BAR_THUMB_PRESSED, accent)
        setColor(SCROLL_BAR_TRACK, 0)
        setColor(NON_PRINTABLE_CHAR, withAlpha(theme.gutterTextColor, 0x88))
        setColor(HIGHLIGHTED_DELIMITERS_FOREGROUND, accent)
        setColor(HIGHLIGHTED_DELIMITERS_BACKGROUND, withAlpha(accent, 0x33))
        setColor(HIGHLIGHTED_DELIMITERS_UNDERLINE, accent)
        setColor(TEXT_ACTION_WINDOW_BACKGROUND, theme.gutterColor)
        setColor(TEXT_ACTION_WINDOW_ICON_COLOR, theme.textColor)
        setColor(COMPLETION_WND_BACKGROUND, theme.gutterColor)
        setColor(COMPLETION_WND_CORNER, theme.gutterDividerColor)
        setColor(COMPLETION_WND_TEXT_PRIMARY, theme.textColor)
        setColor(COMPLETION_WND_TEXT_SECONDARY, theme.gutterTextColor)
        // Token colors from the scanner (see colorIdFor) and the built-in ones, for any other use.
        for ((index, color) in tokenColors(theme).withIndex()) setColor(TOKEN_BASE + index, color)
        setColor(KEYWORD, theme.keywordColor)
        setColor(COMMENT, theme.commentColor)
        setColor(OPERATOR, theme.operatorColor)
        setColor(LITERAL, theme.stringColor)
        setColor(ANNOTATION, theme.annotationColor)
        setColor(HTML_TAG, theme.tagColor)
        setColor(ATTRIBUTE_NAME, theme.attributeColor)
        setColor(ATTRIBUTE_VALUE, theme.stringColor)
        setColor(IDENTIFIER_NAME, theme.typeColor)
        setColor(IDENTIFIER_VAR, theme.textColor)
        setColor(FUNCTION_NAME, theme.textColor)
    }

    companion object {
        /** Our token colors live at their own ids, after the editor's built-in ones. */
        private const val TOKEN_BASE = 300

        private fun tokenColors(theme: EditorTheme) = intArrayOf(
            theme.keywordColor, theme.typeColor, theme.stringColor, theme.numberColor,
            theme.commentColor, theme.annotationColor, theme.tagColor, theme.attributeColor,
            theme.operatorColor
        )

        /** Color id for a token the scanner painted [color] with [theme]. */
        fun colorIdFor(theme: EditorTheme, color: Int): Int {
            val index = tokenColors(theme).indexOf(color)
            return if (index >= 0) TOKEN_BASE + index else EditorColorScheme.TEXT_NORMAL
        }

        private fun isDarkTheme(theme: EditorTheme): Boolean {
            val c = theme.backgroundColor
            val luminance = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
            return luminance < 128
        }

        private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
    }
}
