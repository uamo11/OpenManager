package me.zhanghai.android.files.terminal

import me.zhanghai.android.files.terminal.TerminalUiHelper.ModifierState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalHudAndExtraKeysLogicTest {

    @Test
    fun testModifierStateSingleTapAndStickyLock() {
        assertEquals(ModifierState.ONE_SHOT, TerminalUiHelper.onTap(ModifierState.OFF))
        assertEquals(ModifierState.OFF, TerminalUiHelper.onTap(ModifierState.ONE_SHOT))
        assertEquals(ModifierState.OFF, TerminalUiHelper.onTap(ModifierState.LOCKED))

        assertEquals(ModifierState.LOCKED, TerminalUiHelper.onLongPress(ModifierState.OFF))
        assertEquals(ModifierState.LOCKED, TerminalUiHelper.onLongPress(ModifierState.ONE_SHOT))

        assertEquals(Pair(false, ModifierState.OFF), TerminalUiHelper.consume(ModifierState.OFF))
        assertEquals(Pair(true, ModifierState.OFF), TerminalUiHelper.consume(ModifierState.ONE_SHOT))
        assertEquals(Pair(true, ModifierState.LOCKED), TerminalUiHelper.consume(ModifierState.LOCKED))
    }

    @Test
    fun testApplyModifiersToLettersSymbolsAndAlt() {
        assertEquals("a", TerminalUiHelper.applyModifiersToKey("a", ctrl = false, alt = false))
        assertEquals("\u0003", TerminalUiHelper.applyModifiersToKey("c", ctrl = true, alt = false))
        assertEquals("\u0003", TerminalUiHelper.applyModifiersToKey("C", ctrl = true, alt = false))
        assertEquals("\u001a", TerminalUiHelper.applyModifiersToKey("z", ctrl = true, alt = false))
        assertEquals("\u001b", TerminalUiHelper.applyModifiersToKey("[", ctrl = true, alt = false))

        assertEquals("\u001bx", TerminalUiHelper.applyModifiersToKey("x", ctrl = false, alt = true))
        assertEquals("\u001b/", TerminalUiHelper.applyModifiersToKey("/", ctrl = false, alt = true))
        assertEquals("\u001b\u0003", TerminalUiHelper.applyModifiersToKey("c", ctrl = true, alt = true))
    }

    @Test
    fun testComputeSessionBadgeStatesAndThemes() {
        val systemDark = TerminalUiHelper.computeSessionBadge(
            isRunning = true,
            isRoot = false,
            isTermex = false,
            exitStatus = null,
            isLightChrome = false
        )
        val systemLight = TerminalUiHelper.computeSessionBadge(
            isRunning = true,
            isRoot = false,
            isTermex = false,
            exitStatus = null,
            isLightChrome = true
        )
        val rootDark = TerminalUiHelper.computeSessionBadge(
            isRunning = true,
            isRoot = true,
            isTermex = false,
            exitStatus = null,
            isLightChrome = false
        )
        val termexDark = TerminalUiHelper.computeSessionBadge(
            isRunning = true,
            isRoot = false,
            isTermex = true,
            exitStatus = null,
            isLightChrome = false
        )
        val doneOk = TerminalUiHelper.computeSessionBadge(
            isRunning = false,
            isRoot = false,
            isTermex = false,
            exitStatus = 0,
            isLightChrome = false
        )
        val doneErr = TerminalUiHelper.computeSessionBadge(
            isRunning = false,
            isRoot = false,
            isTermex = false,
            exitStatus = 1,
            isLightChrome = false
        )

        assertTrue(systemDark.label.contains("SH", ignoreCase = true))
        assertTrue(rootDark.label.contains("ROOT", ignoreCase = true))
        assertTrue(termexDark.label.contains("BASH", ignoreCase = true))

        assertNotEquals(systemDark.dotColor, rootDark.dotColor)
        assertNotEquals(systemDark.dotColor, termexDark.dotColor)
        assertNotEquals(doneOk.dotColor, doneErr.dotColor)
        assertFalse(doneOk.label == systemDark.label && doneOk.dotColor == systemDark.dotColor)

        assertNotEquals(systemDark.bgColor, systemLight.bgColor)
        assertNotEquals(systemDark.textColor, systemLight.textColor)
        assertNotEquals(0, systemLight.textColor)
        assertNotEquals(0, systemDark.textColor)
    }

    @Test
    fun testFormatBreadcrumbPathAndElapsedTime() {
        assertEquals("~/sdcard", TerminalUiHelper.formatBreadcrumbPath("/storage/emulated/0"))
        assertEquals("~/Music", TerminalUiHelper.formatBreadcrumbPath("/storage/emulated/0/Music"))
        assertEquals("~/sdcard", TerminalUiHelper.formatBreadcrumbPath("/storage/emulated/0/a.sh"))
        assertEquals("[app]/files", TerminalUiHelper.formatBreadcrumbPath("/data/user/0/com.y.om/files"))
        assertTrue(TerminalUiHelper.formatBreadcrumbPath(null).isNotBlank())

        assertEquals("00:00", TerminalUiHelper.formatElapsedSeconds(0))
        assertEquals("00:00", TerminalUiHelper.formatElapsedSeconds(-5))
        assertEquals("01:05", TerminalUiHelper.formatElapsedSeconds(65))
        assertEquals("1:01:01", TerminalUiHelper.formatElapsedSeconds(3661))
    }
}
