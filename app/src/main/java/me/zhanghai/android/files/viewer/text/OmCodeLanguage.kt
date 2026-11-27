/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.SymbolPairMatch
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * Our languages for the sora editor: [FastSyntaxScanner] colors the text on the editor's own
 * background thread (never on the UI thread), and new lines get the indentation of the code.
 */
class OmCodeLanguage(
    private val language: SyntaxLanguage,
    private val theme: EditorTheme,
    private val highlighting: Boolean,
    private val autoClosePairs: Boolean
) : EmptyLanguage() {

    private val analyzer = Analyzer()

    override fun getAnalyzeManager(): AnalyzeManager = analyzer

    override fun getSymbolPairs(): SymbolPairMatch =
        if (autoClosePairs) PAIRS else EmptyLanguage.EMPTY_SYMBOL_PAIRS

    override fun useTab(): Boolean = false

    /** One more level after a line that opens a block: `{`, `(`, `[`, or `:` (Python, YAML). */
    override fun getIndentAdvance(content: ContentReference, line: Int, column: Int): Int {
        val text = content.getLine(line)
        var end = column.coerceAtMost(text.length)
        while (end > 0 && text[end - 1].isWhitespace()) end--
        if (end == 0) return 0
        return when (text[end - 1]) {
            '{', '(', '[', ':' -> INDENT
            else -> 0
        }
    }

    override fun destroy() {
        analyzer.destroy()
    }

    private inner class Analyzer : SimpleAnalyzeManager<Unit>() {
        override fun analyze(
            text: StringBuilder,
            delegate: SimpleAnalyzeManager<Unit>.Delegate<Unit>
        ): Styles? {
            val builder = MappedSpans.Builder(1024)
            if (!highlighting || language == SyntaxLanguage.PLAIN_TEXT ||
                !FastSyntaxScanner.isWithinHighlightLimit(text.length, isLive = true)
            ) {
                // Plain text on every line, at no memory per line. A span map holding only line 0
                // made the renderer fail on each other line: thousands of caught exceptions while
                // scrolling a large file (seen in the E2E logs).
                debugLog(0, text.length)
                return Styles(PlainSpans(lineCountOf(text)))
            }
            val tokens = FastSyntaxScanner.scan(text, language, theme)
            if (delegate.isCancelled) return null
            val sorted = if (isSorted(tokens)) tokens else tokens.sortedBy { it.start }

            var line = 0
            var lineStart = 0
            var pos = 0
            // Lines as the editor counts them: a lone '\r' also ends a line ('\r\n' is one break).
            fun advance(to: Int) {
                val target = to.coerceAtMost(text.length)
                while (pos < target) {
                    val c = text[pos]
                    if (c == '\n' || (c == '\r' && (pos + 1 >= text.length || text[pos + 1] != '\n'))) {
                        line++
                        lineStart = pos + 1
                    }
                    pos++
                }
            }
            builder.addIfNeeded(0, 0, EditorColorScheme.TEXT_NORMAL.toLong())
            for ((index, token) in sorted.withIndex()) {
                if (index and 1023 == 0 && delegate.isCancelled) return null
                // Overlapping or empty tokens are skipped: spans must move forward.
                if (token.start < pos || token.end <= token.start || token.end > text.length) continue
                advance(token.start)
                builder.addIfNeeded(line, token.start - lineStart, styleOf(token))
                advance(token.end)
                builder.addIfNeeded(line, token.end - lineStart, EditorColorScheme.TEXT_NORMAL.toLong())
            }
            advance(text.length)
            builder.determine(line)
            debugLog(sorted.size, text.length)
            return Styles(builder.build())
        }
    }

    /** Lines as the editor counts them: '\n', '\r\n' and a lone '\r' each end one. */
    private fun lineCountOf(text: CharSequence): Int {
        var lines = 1
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '\n') {
                lines++
            } else if (c == '\r') {
                lines++
                if (i + 1 < n && text[i + 1] == '\n') i++
            }
            i++
        }
        return lines
    }

    private fun debugLog(tokens: Int, chars: Int) {
        if (me.zhanghai.android.files.BuildConfig.DEBUG) {
            android.util.Log.d("OMEditor", "analyzed lang=$language tokens=$tokens chars=$chars")
        }
    }

    // Only used on the analysis thread.
    private val styleCache = HashMap<Long, Long>()

    private fun styleOf(token: FastSyntaxScanner.SyntaxToken): Long {
        val key = (token.color.toLong() shl 1) or (if (token.isItalic) 1L else 0L)
        return styleCache.getOrPut(key) {
            val id = OmEditorColorScheme.colorIdFor(theme, token.color)
            if (token.isItalic) TextStyle.makeStyle(id, 0, false, true, false) else TextStyle.makeStyle(id)
        }
    }

    private fun isSorted(tokens: List<FastSyntaxScanner.SyntaxToken>): Boolean {
        for (i in 1 until tokens.size) {
            if (tokens[i].start < tokens[i - 1].start) return false
        }
        return true
    }

    companion object {
        const val INDENT = 4
        private val PAIRS = SymbolPairMatch.DefaultSymbolPairs()
    }
}

/** Every line in the normal text style, without a span list per line (huge or plain files). */
private class PlainSpans(private val lines: Int) : io.github.rosemoe.sora.lang.styling.Spans {
    override fun adjustOnInsert(
        start: io.github.rosemoe.sora.text.CharPosition, end: io.github.rosemoe.sora.text.CharPosition
    ) = Unit

    override fun adjustOnDelete(
        start: io.github.rosemoe.sora.text.CharPosition, end: io.github.rosemoe.sora.text.CharPosition
    ) = Unit

    override fun read(): io.github.rosemoe.sora.lang.styling.Spans.Reader =
        io.github.rosemoe.sora.lang.styling.EmptyReader.getInstance()

    override fun supportsModify(): Boolean = false

    override fun modify(): io.github.rosemoe.sora.lang.styling.Spans.Modifier =
        throw UnsupportedOperationException("Plain text has no spans to modify")

    override fun getLineCount(): Int = lines
}
