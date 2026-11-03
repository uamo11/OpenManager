/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectionalActionLogicTest {

    private fun getDirectionalCopyLabel(isPanelA: Boolean, isArchive: Boolean): String {
        val verb = if (isArchive) "Extraer" else "Copiar"
        return if (isPanelA) "$verb >>" else "<< $verb"
    }

    private fun getDirectionalMoveLabel(isPanelA: Boolean): String {
        return if (isPanelA) "Mover >>" else "<< Mover"
    }

    private fun getSelectAllText(selectedCount: Int, totalSelectable: Int): String {
        return if (selectedCount > 0 && selectedCount >= totalSelectable && totalSelectable > 0) {
            "Desmarcar"
        } else if (selectedCount > 0) {
            "Todo ($selectedCount)"
        } else {
            "Todo"
        }
    }

    @Test
    fun testLeftPanelDirectionalLabels() {
        // Panel A (Left) sends files to Panel B (Right) -> arrows point right (>>)
        assertEquals("Copiar >>", getDirectionalCopyLabel(isPanelA = true, isArchive = false))
        assertEquals("Extraer >>", getDirectionalCopyLabel(isPanelA = true, isArchive = true))
        assertEquals("Mover >>", getDirectionalMoveLabel(isPanelA = true))
    }

    @Test
    fun testRightPanelDirectionalLabels() {
        // Panel B (Right) sends files to Panel A (Left) -> arrows point left (<<)
        assertEquals("<< Copiar", getDirectionalCopyLabel(isPanelA = false, isArchive = false))
        assertEquals("<< Extraer", getDirectionalCopyLabel(isPanelA = false, isArchive = true))
        assertEquals("<< Mover", getDirectionalMoveLabel(isPanelA = false))
    }

    @Test
    fun testSelectAllButtonText() {
        // When some files selected
        assertEquals("Todo (3)", getSelectAllText(selectedCount = 3, totalSelectable = 10))

        // When all files selected
        assertEquals("Desmarcar", getSelectAllText(selectedCount = 10, totalSelectable = 10))

        // When no files selected
        assertEquals("Todo", getSelectAllText(selectedCount = 0, totalSelectable = 10))
    }
}
