/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEditorFileSynchronizationTest {

    private class SyncTracker(
        var lastLoadedModifiedTime: Long = 0L,
        var lastLoadedFileSize: Long = 0L,
        var isSelfSaving: Boolean = false
    ) {
        fun hasExternalChange(currentModTime: Long, currentSize: Long): Boolean {
            if (isSelfSaving) return false
            if (lastLoadedModifiedTime <= 0L) return false
            if (currentModTime <= 0L) return false

            return currentModTime > lastLoadedModifiedTime ||
                (currentModTime != lastLoadedModifiedTime && currentSize != lastLoadedFileSize)
        }

        fun acknowledgeExternalChange(currentModTime: Long, currentSize: Long) {
            if (currentModTime > 0L) {
                lastLoadedModifiedTime = currentModTime
                lastLoadedFileSize = currentSize
            }
        }

        fun onSaveSuccess(newModTime: Long, newSize: Long) {
            lastLoadedModifiedTime = newModTime
            lastLoadedFileSize = newSize
            isSelfSaving = false
        }
    }

    @Test
    fun testNoChangeDetectedWhenFileUnmodified() {
        val tracker = SyncTracker(
            lastLoadedModifiedTime = 1700000000000L,
            lastLoadedFileSize = 1024L
        )

        // Querying with the same timestamp and size must NOT trigger external change
        assertFalse(tracker.hasExternalChange(1700000000000L, 1024L))
    }

    @Test
    fun testExternalChangeDetectedOnNewerTimestamp() {
        val tracker = SyncTracker(
            lastLoadedModifiedTime = 1700000000000L,
            lastLoadedFileSize = 1024L
        )

        // File modified 5 seconds later
        val laterModTime = 1700000005000L
        assertTrue(
            "Should detect external modification when timestamp is newer",
            tracker.hasExternalChange(laterModTime, 1024L)
        )
    }

    @Test
    fun testExternalChangeDetectedOnSizeChangeWithDifferentTimestamp() {
        val tracker = SyncTracker(
            lastLoadedModifiedTime = 1700000000000L,
            lastLoadedFileSize = 1024L
        )

        // File size changed to 2048 with different timestamp
        assertTrue(
            "Should detect external modification when size changed",
            tracker.hasExternalChange(1700000001000L, 2048L)
        )
    }

    @Test
    fun testSelfSavingDoesNotTriggerExternalChange() {
        val tracker = SyncTracker(
            lastLoadedModifiedTime = 1700000000000L,
            lastLoadedFileSize = 1024L,
            isSelfSaving = true
        )

        // Even if disk timestamp is newer while saving, isSelfSaving suppresses notification
        assertFalse(
            "Self saving must suppress external change notification",
            tracker.hasExternalChange(1700000002000L, 1200L)
        )

        // Once saved, tracker updates to new time and clears isSelfSaving
        tracker.onSaveSuccess(1700000002000L, 1200L)
        assertFalse(
            "After save completes, state should be clean",
            tracker.hasExternalChange(1700000002000L, 1200L)
        )
    }

    @Test
    fun testAcknowledgeExternalChangePreventsRepeatedDialogs() {
        val tracker = SyncTracker(
            lastLoadedModifiedTime = 1700000000000L,
            lastLoadedFileSize = 1024L
        )

        val externalModTime = 1700000010000L
        val externalSize = 1500L
        assertTrue(tracker.hasExternalChange(externalModTime, externalSize))

        // User chooses "Conservar mis cambios" / Cancel -> acknowledge
        tracker.acknowledgeExternalChange(externalModTime, externalSize)

        // Subsequent queries with that timestamp must now return false
        assertFalse(
            "After acknowledging, it must not badger the user repeatedly",
            tracker.hasExternalChange(externalModTime, externalSize)
        )

        // If changed AGAIN later, it triggers again
        val nextModTime = 1700000020000L
        assertTrue(
            "Subsequent change after acknowledgement must trigger again",
            tracker.hasExternalChange(nextModTime, externalSize)
        )
    }

    @Test
    fun testDialogModeSelectionLogic() {
        fun getDialogActionText(hasUnsavedChanges: Boolean): Pair<String, String> {
            val title = "Archivo modificado externamente"
            val negativeBtn = if (hasUnsavedChanges) "Conservar mis cambios" else "Cancelar"
            return title to negativeBtn
        }

        val cleanMode = getDialogActionText(hasUnsavedChanges = false)
        assertEquals("Archivo modificado externamente", cleanMode.first)
        assertEquals("Cancelar", cleanMode.second)

        val dirtyMode = getDialogActionText(hasUnsavedChanges = true)
        assertEquals("Archivo modificado externamente", dirtyMode.first)
        assertEquals("Conservar mis cambios", dirtyMode.second)
    }
}
