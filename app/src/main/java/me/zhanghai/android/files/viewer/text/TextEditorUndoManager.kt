/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min

class TextEditorUndoManager(
    private val editText: EditText,
    private val onUndoStatusChanged: ((canUndo: Boolean, canRedo: Boolean) -> Unit)? = null
) : TextWatcher {

    private class EditItem(
        var start: Int,
        var beforeText: CharSequence,
        var afterText: CharSequence
    )

    private sealed interface UndoEntry {
        class Single(val item: EditItem) : UndoEntry
        class Batch(val steps: List<EditItem>) : UndoEntry
    }

    private val undoStack = ArrayDeque<UndoEntry>()
    private val redoStack = ArrayDeque<UndoEntry>()

    var isEnabled = true

    var isUndoingOrRedoing = false
        private set

    private var batchDepth = 0
    private val currentBatchSteps = mutableListOf<EditItem>()

    private var tempBeforeText: CharSequence = ""
    private var tempStart = 0
    private var tempCount = 0

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun beginBatchEdit() {
        batchDepth++
    }

    fun endBatchEdit() {
        if (batchDepth > 0) {
            batchDepth--
            if (batchDepth == 0 && currentBatchSteps.isNotEmpty()) {
                undoStack.addLast(UndoEntry.Batch(currentBatchSteps.toList()))
                if (undoStack.size > MAX_HISTORY) {
                    undoStack.removeFirst()
                }
                currentBatchSteps.clear()
                redoStack.clear()
                notifyStatusChanged()
            }
        }
    }

    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
        if (!isEnabled || isUndoingOrRedoing) return
        tempStart = start
        tempCount = count
        tempBeforeText = if (start + count <= s.length) {
            s.subSequence(start, start + count).toString()
        } else {
            ""
        }
    }

    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
        if (!isEnabled || isUndoingOrRedoing) return
        val afterText = if (start + count <= s.length) {
            s.subSequence(start, start + count).toString()
        } else {
            ""
        }
        if (tempBeforeText == afterText) return

        if (batchDepth > 0) {
            currentBatchSteps.add(EditItem(start, tempBeforeText, afterText))
            return
        }

        val last = (undoStack.peekLast() as? UndoEntry.Single)?.item
        var merged = false
        if (last != null) {
            // Typing consecutive single characters
            if (last.beforeText.isEmpty() && tempBeforeText.isEmpty() &&
                afterText.length == 1 && !Character.isWhitespace(afterText[0]) &&
                last.start + last.afterText.length == start
            ) {
                last.afterText = last.afterText.toString() + afterText
                merged = true
            }
            // Deleting consecutive single characters with Backspace
            else if (last.afterText.isEmpty() && afterText.isEmpty() &&
                tempBeforeText.length == 1 &&
                last.start == start + 1
            ) {
                last.start = start
                last.beforeText = tempBeforeText.toString() + last.beforeText.toString()
                merged = true
            }
        }

        if (!merged) {
            undoStack.addLast(UndoEntry.Single(EditItem(start, tempBeforeText, afterText)))
            if (undoStack.size > MAX_HISTORY) {
                undoStack.removeFirst()
            }
        }

        redoStack.clear()
        notifyStatusChanged()
    }

    override fun afterTextChanged(s: Editable) {}

    private fun applyUndoStep(editable: Editable, step: EditItem): Int {
        val replaceStart = min(step.start, editable.length)
        val replaceEnd = min(step.start + step.afterText.length, editable.length)
        editable.replace(replaceStart, replaceEnd, step.beforeText)
        return (step.start + step.beforeText.length).coerceIn(0, editable.length)
    }

    private fun applyRedoStep(editable: Editable, step: EditItem): Int {
        val replaceStart = min(step.start, editable.length)
        val replaceEnd = min(step.start + step.beforeText.length, editable.length)
        editable.replace(replaceStart, replaceEnd, step.afterText)
        return (step.start + step.afterText.length).coerceIn(0, editable.length)
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val entry = undoStack.removeLast()
        isUndoingOrRedoing = true
        try {
            val editable = editText.text ?: return
            var newCursor = editText.selectionStart
            when (entry) {
                is UndoEntry.Single -> newCursor = applyUndoStep(editable, entry.item)
                is UndoEntry.Batch -> {
                    for (step in entry.steps.asReversed()) {
                        newCursor = applyUndoStep(editable, step)
                    }
                }
            }
            editText.setSelection(newCursor.coerceIn(0, editable.length))
            redoStack.addLast(entry)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isUndoingOrRedoing = false
        }
        notifyStatusChanged()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val entry = redoStack.removeLast()
        isUndoingOrRedoing = true
        try {
            val editable = editText.text ?: return
            var newCursor = editText.selectionStart
            when (entry) {
                is UndoEntry.Single -> newCursor = applyRedoStep(editable, entry.item)
                is UndoEntry.Batch -> {
                    for (step in entry.steps) {
                        newCursor = applyRedoStep(editable, step)
                    }
                }
            }
            editText.setSelection(newCursor.coerceIn(0, editable.length))
            undoStack.addLast(entry)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isUndoingOrRedoing = false
        }
        notifyStatusChanged()
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        currentBatchSteps.clear()
        batchDepth = 0
        notifyStatusChanged()
    }

    private fun notifyStatusChanged() {
        onUndoStatusChanged?.invoke(canUndo, canRedo)
    }

    companion object {
        private const val MAX_HISTORY = 200
    }
}
