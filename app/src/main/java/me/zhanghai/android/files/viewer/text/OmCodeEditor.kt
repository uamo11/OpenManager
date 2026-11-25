/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.accessibility.AccessibilityNodeInfo
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * The sora editor with two fixes for big files:
 * - accessibility (TalkBack, autofill, screen readers) got a copy of the WHOLE text on every
 *   query; above [A11Y_FULL_TEXT_LIMIT] it gets the lines on screen instead;
 * - [onLayoutReady] tells when the background layout of a long text (word wrap) is done, so a
 *   jump made before it can be scrolled into view again.
 */
class OmCodeEditor : CodeEditor {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr)

    /** Called on the main thread when a background layout pass has finished. */
    var onLayoutReady: (() -> Unit)? = null

    /** Whether the editor is still laying out the text in the background (set from its workers). */
    @Volatile
    var isLayingOut = false
        private set

    override fun setLayoutBusy(busy: Boolean) {
        val finished = isLayingOut && !busy
        isLayingOut = busy
        super.setLayoutBusy(busy)
        if (finished) post { onLayoutReady?.invoke() }
    }

    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo {
        val content = text
        if (content.length <= A11Y_FULL_TEXT_LIMIT) return super.createAccessibilityNodeInfo()
        @Suppress("DEPRECATION")
        val info = AccessibilityNodeInfo.obtain(this)
        onInitializeAccessibilityNodeInfo(info)
        if (isEnabled) {
            info.isEditable = isEditable
            info.inputType = InputType.TYPE_CLASS_TEXT
            info.isMultiLine = true
            val lastLine = content.lineCount - 1
            val first = firstVisibleLine.coerceIn(0, lastLine.coerceAtLeast(0))
            val last = lastVisibleLine.coerceIn(first, lastLine.coerceAtLeast(first))
            val visible = StringBuilder()
            for (line in first..last) {
                if (line > first) visible.append('\n')
                visible.append(content.getLine(line))
                if (visible.length > A11Y_FULL_TEXT_LIMIT) break
            }
            info.text = visible
            info.isLongClickable = true
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_COPY)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE)
            if (scrollMaxY > 0) {
                info.isScrollable = true
                if (offsetY > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
                if (offsetY < scrollMaxY) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            }
        }
        return info
    }

    private companion object {
        const val A11Y_FULL_TEXT_LIMIT = 32 * 1024
    }
}
