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
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
