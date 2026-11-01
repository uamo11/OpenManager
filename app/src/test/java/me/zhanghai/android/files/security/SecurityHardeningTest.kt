/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.security

import me.zhanghai.android.files.file.AxmlDecoder
import me.zhanghai.android.files.filelist.ApktoolPaths
import me.zhanghai.android.files.util.ShellEscapeUtils
import me.zhanghai.android.files.util.escapeShellArg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SecurityHardeningTest {

    @Test
    fun testAxmlDecoderSafetyOnCorruptedInput() {
        // Empty bytes
        assertFalse(AxmlDecoder.isAxml(byteArrayOf()))
        assertEquals("", AxmlDecoder.decode(byteArrayOf()))

        // Plain text XML should pass through
        val plainXml = "<manifest package=\"com.test\" />"
        assertFalse(AxmlDecoder.isAxml(plainXml.toByteArray(Charsets.UTF_8)))
        assertEquals(plainXml, AxmlDecoder.decode(plainXml.toByteArray(Charsets.UTF_8)))

        // Truncated header with AXML magic
        val truncatedAxml = byteArrayOf(0x03, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertTrue(AxmlDecoder.isAxml(truncatedAxml))
        val decoded = AxmlDecoder.decode(truncatedAxml)
        assertTrue(decoded.contains("<?xml"))
    }

    @Test
    fun testShellArgumentEscaping() {
        // Standard string
        assertEquals("'hello'", ShellEscapeUtils.escape("hello"))
        assertEquals("'hello'", "hello".escapeShellArg())

        // String with single quote
        assertEquals("'hello'\\''world'", "hello'world".escapeShellArg())

        // Command injection attempt with semicolons
        val inject1 = "; rm -rf / ;"
        val escaped1 = inject1.escapeShellArg()
        assertEquals("'; rm -rf / ;'", escaped1)

        // Command injection attempt with subshell expansion $()
        val inject2 = "foo\$(id)"
        val escaped2 = inject2.escapeShellArg()
        assertEquals("'foo\$(id)'", escaped2)

        // Command injection attempt with backticks
        val inject3 = "foo`id`"
        val escaped3 = inject3.escapeShellArg()
        assertEquals("'foo`id`'", escaped3)

        // Command injection attempt with quotes and AND operator
        val inject4 = "test\" && reboot"
        val escaped4 = inject4.escapeShellArg()
        assertEquals("'test\" && reboot'", escaped4)
    }

    @Test
    fun testApktoolPathTraversalHardening() {
        val baseDir = ApktoolPaths.getApktoolBaseDir()

        // Path traversal payload
        val maliciousPkg1 = "../../../../system"
        val dir1 = ApktoolPaths.getPackageDir(maliciousPkg1)
        assertTrue(
            "Sanitized package dir must reside inside baseDir: ${dir1.canonicalPath}",
            dir1.canonicalPath.startsWith(baseDir.canonicalPath)
        )
        assertFalse(
            "Package dir must not contain path traversal dots: ${dir1.name}",
            dir1.name.contains("..")
        )

        // Slashes in package name
        val maliciousPkg2 = "com.test/../../evil"
        val dir2 = ApktoolPaths.getPackageDir(maliciousPkg2)
        assertTrue(
            "Sanitized package dir must reside inside baseDir: ${dir2.canonicalPath}",
            dir2.canonicalPath.startsWith(baseDir.canonicalPath)
        )

        // Valid package name
        val validPkg = "com.y.om"
        val dir3 = ApktoolPaths.getPackageDir(validPkg)
        assertEquals(File(baseDir, "com.y.om").canonicalPath, dir3.canonicalPath)
    }

    @Test
    fun testSqliteIdentifierEscaping() {
        fun escapeSqlIdentifier(name: String): String = "`" + name.replace("`", "``") + "`"

        assertEquals("`users`", escapeSqlIdentifier("users"))
        assertEquals("`table with spaces`", escapeSqlIdentifier("table with spaces"))
        // Injection attempt using backtick closing
        val injection = "users`; DROP TABLE test; --"
        val escaped = escapeSqlIdentifier(injection)
        assertEquals("`users``; DROP TABLE test; --`", escaped)
    }
}
