/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

/**
 * Line numbers for the editor without counting the whole text again on every key press or
 * every scroll frame (that made big files lag: each call walked the text from the start).
 *
 * The total number of '\n' is kept up to date from each edit, and every reader (cursor, gutter)
 * has its own [Anchor]: a known (offset, line) pair that only moves by the distance to the next
 * question, so typing and scrolling cost what changed or what is on screen, not the file size.
 *
 * Call [beforeChange] and [afterChange] from a TextWatcher with the TextWatcher arguments.
 */
class NewlineIndex {

    /** A remembered position: [line] (1-based) is the logical line at [offset]. */
    class Anchor internal constructor() {
        internal var offset = 0
        internal var line = 1
    }

    private val anchors = ArrayList<Anchor>(2)
    private var removedNewlines = 0

    /** Number of '\n' in the text. */
    var newlineCount = 0
        private set

    /** Logical lines in the text (newlines + 1). */
    val lineCount: Int
        get() = newlineCount + 1

    fun newAnchor(): Anchor = Anchor().also { anchors += it }

    /** Starts over for [text] (for a new document). */
    fun reset(text: CharSequence) {
        newlineCount = countNewlines(text, 0, text.length)
        for (anchor in anchors) {
            anchor.offset = 0
            anchor.line = 1
        }
    }

    /** TextWatcher.beforeTextChanged: [count] chars at [start] are about to be replaced. */
    fun beforeChange(text: CharSequence, start: Int, count: Int) {
        val safeStart = start.coerceIn(0, text.length)
        val end = (start + count).coerceIn(safeStart, text.length)
        removedNewlines = countNewlines(text, safeStart, end)
        // Anchors after the edit start move back to it while the old text is still there, so
        // they stay right whatever the edit does.
        for (anchor in anchors) {
            if (anchor.offset > safeStart) {
                val offset = anchor.offset.coerceAtMost(text.length)
                anchor.line -= countNewlines(text, safeStart, offset)
                anchor.offset = safeStart
            }
        }
    }

    /** TextWatcher.onTextChanged: [count] new chars are now at [start]. */
    fun afterChange(text: CharSequence, start: Int, count: Int) {
        val safeStart = start.coerceIn(0, text.length)
        val end = (start + count).coerceIn(safeStart, text.length)
        newlineCount += countNewlines(text, safeStart, end) - removedNewlines
        if (newlineCount < 0) newlineCount = countNewlines(text, 0, text.length)
        removedNewlines = 0
    }

    /** The logical line (1-based) at [offset], moving [anchor] there. */
    fun lineAt(text: CharSequence, offset: Int, anchor: Anchor): Int {
        val target = offset.coerceIn(0, text.length)
        if (anchor.offset > text.length) {
            anchor.offset = 0
            anchor.line = 1
        }
        // From the start is shorter than from the anchor: count from there instead.
        if (target < anchor.offset - target) {
            anchor.offset = 0
            anchor.line = 1
        }
        if (target >= anchor.offset) {
            anchor.line += countNewlines(text, anchor.offset, target)
        } else {
            anchor.line -= countNewlines(text, target, anchor.offset)
        }
        anchor.offset = target
        return anchor.line
    }

    /** Offset where the line containing [offset] starts. */
    fun lineStart(text: CharSequence, offset: Int): Int {
        var i = offset.coerceIn(0, text.length)
        while (i > 0 && text[i - 1] != '\n') i--
        return i
    }

    companion object {
        fun countNewlines(text: CharSequence, start: Int, end: Int): Int {
            var count = 0
            if (text is String) {
                var i = text.indexOf('\n', start)
                while (i in 0 until end) {
                    count++
                    i = text.indexOf('\n', i + 1)
                }
                return count
            }
            for (i in start until end) {
                if (text[i] == '\n') count++
            }
            return count
        }
    }
}
