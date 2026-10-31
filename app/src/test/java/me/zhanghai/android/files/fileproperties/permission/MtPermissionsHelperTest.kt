/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties.permission

import me.zhanghai.android.files.provider.common.PosixFileModeBit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MtPermissionsHelperTest {

    @Test
    fun testOctal777ConversionAndDisplay() {
        val octal777 = "777".toInt(8) // 511
        val bits = MtPermissionsHelper.modeFromInt(octal777)

        assertTrue(PosixFileModeBit.OWNER_READ in bits)
        assertTrue(PosixFileModeBit.OWNER_WRITE in bits)
        assertTrue(PosixFileModeBit.OWNER_EXECUTE in bits)
        assertTrue(PosixFileModeBit.GROUP_READ in bits)
        assertTrue(PosixFileModeBit.GROUP_WRITE in bits)
        assertTrue(PosixFileModeBit.GROUP_EXECUTE in bits)
        assertTrue(PosixFileModeBit.OTHERS_READ in bits)
        assertTrue(PosixFileModeBit.OTHERS_WRITE in bits)
        assertTrue(PosixFileModeBit.OTHERS_EXECUTE in bits)

        assertEquals(octal777, MtPermissionsHelper.modeToInt(bits))
        assertEquals("777", MtPermissionsHelper.formatOctal(octal777))
        assertEquals("rwxrwxrwx", MtPermissionsHelper.formatSymbolic(bits))
        assertEquals("777 (rwxrwxrwx)", MtPermissionsHelper.formatDisplay(bits))
    }

    @Test
    fun testCommonModes644And755AndSpecialBits() {
        val mode644 = MtPermissionsHelper.modeFromInt("644".toInt(8))
        assertEquals("644 (rw-r--r--)", MtPermissionsHelper.formatDisplay(mode644))

        val mode755 = MtPermissionsHelper.modeFromInt("755".toInt(8))
        assertEquals("755 (rwxr-xr-x)", MtPermissionsHelper.formatDisplay(mode755))

        val mode4755 = MtPermissionsHelper.modeFromInt("4755".toInt(8))
        assertEquals("4755 (rwsr-xr-x)", MtPermissionsHelper.formatDisplay(mode4755))

        val mode1777 = MtPermissionsHelper.modeFromInt("1777".toInt(8))
        assertEquals("1777 (rwxrwxrwt)", MtPermissionsHelper.formatDisplay(mode1777))
    }

    @Test
    fun testProgressiveOctalTypingWithoutTruncation() {
        // Simulates typing '7', '7', '7' sequentially in octalEdit
        val step1 = MtPermissionsHelper.parseOctalInput("7")
        assertEquals(7, step1)
        assertEquals("------rwx", MtPermissionsHelper.formatSymbolic(MtPermissionsHelper.modeFromInt(step1!!)))

        val step2 = MtPermissionsHelper.parseOctalInput("77")
        assertEquals(63, step2)
        assertEquals("---rwxrwx", MtPermissionsHelper.formatSymbolic(MtPermissionsHelper.modeFromInt(step2!!)))

        val step3 = MtPermissionsHelper.parseOctalInput("777")
        assertEquals(511, step3)
        assertEquals("rwxrwxrwx", MtPermissionsHelper.formatSymbolic(MtPermissionsHelper.modeFromInt(step3!!)))

        // Invalid octal inputs
        assertNull(MtPermissionsHelper.parseOctalInput(""))
        assertNull(MtPermissionsHelper.parseOctalInput("888"))
        assertNull(MtPermissionsHelper.parseOctalInput("797"))
        assertNull(MtPermissionsHelper.parseOctalInput("77777"))
    }
}
