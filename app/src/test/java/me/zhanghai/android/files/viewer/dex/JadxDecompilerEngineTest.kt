/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.dex

import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class JadxDecompilerEngineTest {

    @Test
    fun testNormalizeClassName() {
        assertEquals("com.example.MyClass", JadxDecompilerEngine.normalizeClassName("Lcom/example/MyClass;"))
        assertEquals("com.example.MyClass", JadxDecompilerEngine.normalizeClassName("com/example/MyClass"))
        assertEquals("com.example.MyClass", JadxDecompilerEngine.normalizeClassName("com.example.MyClass"))
        assertEquals("com.example.MyClass\$Inner", JadxDecompilerEngine.normalizeClassName("Lcom/example/MyClass\$Inner;"))
    }

    @Test
    fun testDecompileClassFromDex() {
        val tempDir = File.createTempFile("jadx_test", "").apply {
            delete()
            mkdirs()
        }

        try {
            val smaliFile = File(tempDir, "Calculator.smali")
            val dexFile = File(tempDir, "classes.dex")

            val smaliCode = """
                .class public Lcom/openmanager/test/Calculator;
                .super Ljava/lang/Object;

                .method public constructor <init>()V
                    .registers 1
                    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                    return-void
                .end method

                .method public add(II)I
                    .registers 4
                    add-int v0, p1, p2
                    return v0
                .end method
            """.trimIndent()

            smaliFile.writeText(smaliCode)

            val options = SmaliOptions().apply {
                apiLevel = 28
                outputDexFile = dexFile.absolutePath
            }

            val assembled = Smali.assemble(options, listOf(smaliFile.absolutePath))
            assertTrue("Smali assembly must succeed", assembled)
            assertTrue("DEX file must exist", dexFile.exists())

            // Test single class decompilation
            val javaCode = JadxDecompilerEngine.decompileClass(dexFile, "Lcom/openmanager/test/Calculator;")
            assertNotNull(javaCode)
            assertTrue("Decompiled code must contain class declaration: $javaCode", javaCode.contains("class Calculator"))
            assertTrue("Decompiled code must contain method add: $javaCode", javaCode.contains("add(int"))

            // Test export to ZIP
            val zipFile = File(tempDir, "sources.zip")
            var progressCalls = 0
            JadxDecompilerEngine.decompileToZip(dexFile, zipFile) { _, _, _ ->
                progressCalls++
            }

            assertTrue("Zip file must exist", zipFile.exists())
            assertTrue("Zip file must have size > 0", zipFile.length() > 0)
            assertTrue("Progress callback must be invoked", progressCalls > 0)

            // Verify Zip entry
            ZipFile(zipFile).use { zf ->
                val entry = zf.getEntry("com/openmanager/test/Calculator.java")
                assertNotNull("com/openmanager/test/Calculator.java entry must exist in ZIP", entry)
            }

            // Test export to directory (Apktool JADX mode)
            val outDir = File(tempDir, "decomp_java")
            var dirProgressCalls = 0
            JadxDecompilerEngine.decompileToDirectory(dexFile, outDir) { _, _, _ ->
                dirProgressCalls++
            }
            val javaFile = File(outDir, "com/openmanager/test/Calculator.java")
            assertTrue("Calculator.java must be written to directory", javaFile.exists())
            assertTrue("Directory progress callback must be invoked", dirProgressCalls > 0)
            val dirCode = javaFile.readText()
            assertTrue("Directory Java file must contain class Calculator: $dirCode", dirCode.contains("class Calculator"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testSanitizeRelativeSourcePath_normalClass() {
        assertEquals(
            "com/example/MainActivity.java",
            JadxDecompilerEngine.sanitizeRelativeSourcePath("com.example.MainActivity")
        )
        assertEquals(
            "com/example/MyClass.java",
            JadxDecompilerEngine.sanitizeRelativeSourcePath("Lcom/example/MyClass;")
        )
        assertEquals(
            "com/example/MyClass\$Inner.java",
            JadxDecompilerEngine.sanitizeRelativeSourcePath("Lcom/example/MyClass\$Inner;")
        )
    }

    @Test
    fun testSanitizeRelativeSourcePath_pathTraversalSequencesNeutralized() {
        val payload1 = "../../../../../../system/etc/hosts"
        val path1 = JadxDecompilerEngine.sanitizeRelativeSourcePath(payload1)
        org.junit.Assert.assertFalse("Path must not contain traversal dots: $path1", path1.contains(".."))
        org.junit.Assert.assertFalse("Path must not start with slash: $path1", path1.startsWith("/"))
        assertEquals("system/etc/hosts.java", path1)

        val payload2 = "com/example/../../malicious/Payload"
        val path2 = JadxDecompilerEngine.sanitizeRelativeSourcePath(payload2)
        org.junit.Assert.assertFalse("Path must not contain traversal dots: $path2", path2.contains(".."))
        assertEquals("com/example/malicious/Payload.java", path2)
    }

    @Test
    fun testSanitizeRelativeSourcePath_windowsBackslashTraversalNeutralized() {
        val payload = "..\\..\\..\\Windows\\System32\\cmd"
        val path = JadxDecompilerEngine.sanitizeRelativeSourcePath(payload)
        org.junit.Assert.assertFalse("Path must not contain backslashes: $path", path.contains("\\"))
        org.junit.Assert.assertFalse("Path must not contain traversal dots: $path", path.contains(".."))
        assertEquals("Windows/System32/cmd.java", path)
    }

    @Test
    fun testSanitizeRelativeSourcePath_illegalCharactersSanitized() {
        val payload = "com.example.Class:with*illegal?chars\"<bar>|"
        val path = JadxDecompilerEngine.sanitizeRelativeSourcePath(payload)
        org.junit.Assert.assertFalse("Path must not contain illegal chars", path.contains(":") || path.contains("*") || path.contains("?") || path.contains("\"") || path.contains("<") || path.contains(">") || path.contains("|"))
        assertEquals("com/example/Class_with_illegal_chars__bar__.java", path)
    }

    @Test
    fun testSanitizeRelativeSourcePath_pureTraversalFallsBackToSafeName() {
        val payload = "../../../.."
        val path = JadxDecompilerEngine.sanitizeRelativeSourcePath(payload)
        org.junit.Assert.assertFalse("Path must not contain traversal dots: $path", path.contains(".."))
        assertTrue("Fallback path must start with unnamed/Class_: $path", path.startsWith("unnamed/Class_"))
        assertTrue("Fallback path must end with .java: $path", path.endsWith(".java"))
    }
}
