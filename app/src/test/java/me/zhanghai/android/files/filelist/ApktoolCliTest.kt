/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApktoolCliTest {

    @Test
    fun testApktoolEngineSafeOpcodes() {
        val opcodes = ApktoolEngine.getSafeOpcodes(34)
        assertNotNull("Safe opcodes must not be null", opcodes)
        assertEquals("Apktool version must be 2.9.3", "2.9.3", ApktoolEngine.VERSION)
    }

    @Test
    fun testApktoolCliVersionAndHelpInvocation() {
        // Test that help and version can be invoked without crashing
        ApktoolCli.main(arrayOf("-v"))
        ApktoolCli.main(arrayOf("--version"))
        ApktoolCli.main(arrayOf("-h"))
        ApktoolCli.main(arrayOf("--help"))
    }

    @Test
    fun testApktoolEngineDecompileAndRecompile() {
        val tempDir = File.createTempFile("apktool_test_", "").apply {
            delete()
            mkdirs()
        }

        try {
            // 1. Create a dummy DEX file from Smali
            val smaliFile = File(tempDir, "TestClass.smali")
            smaliFile.writeText("""
                .class public Lcom/example/demo/TestClass;
                .super Ljava/lang/Object;

                .method public constructor <init>()V
                    .registers 1
                    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                    return-void
                .end method

                .method public static getValue()I
                    .registers 1
                    const/16 v0, 42
                    return v0
                .end method
            """.trimIndent())

            val dexFile = File(tempDir, "classes.dex")
            val smaliOpts = SmaliOptions().apply {
                apiLevel = 28
                outputDexFile = dexFile.absolutePath
            }
            assertTrue("Smali assemble should succeed", Smali.assemble(smaliOpts, listOf(smaliFile.absolutePath)))

            // 2. Package into a test APK
            val testApk = File(tempDir, "test_input.apk")
            FileOutputStream(testApk).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    // Add AndroidManifest.xml
                    zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                    zos.write("<manifest package=\"com.example.demo\"/>".toByteArray(StandardCharsets.UTF_8))
                    zos.closeEntry()

                    // Add classes.dex
                    zos.putNextEntry(ZipEntry("classes.dex"))
                    zos.write(dexFile.readBytes())
                    zos.closeEntry()

                    // Add a sample asset
                    zos.putNextEntry(ZipEntry("assets/sample.txt"))
                    zos.write("Hello Apktool".toByteArray(StandardCharsets.UTF_8))
                    zos.closeEntry()
                }
            }

            assertTrue("Test APK must exist", testApk.exists() && testApk.length() > 0)

            // 3. Decompile using ApktoolEngine
            val outDir = File(tempDir, "decompiled_project")
            var logMessageCount = 0
            val listener = object : ApktoolEngine.ApktoolListener {
                override fun onProgress(message: String, detail: String, percentage: Int) {}
                override fun onLog(message: String) {
                    logMessageCount++
                }
            }

            ApktoolEngine.decompileApk(
                inputApk = testApk,
                outputDir = outDir,
                options = ApktoolEngine.DecompileOptions(forceOverwrite = true),
                listener = listener
            )

            assertTrue("Decompiled directory must exist", outDir.exists())
            assertTrue("apktool.yml must exist", File(outDir, "apktool.yml").exists())
            assertTrue("AndroidManifest.xml must exist", File(outDir, "AndroidManifest.xml").exists())
            assertTrue("assets/sample.txt must exist", File(outDir, "assets/sample.txt").exists())

            val smaliOutputDir = File(outDir, "smali")
            assertTrue("smali directory must exist", smaliOutputDir.exists())
            val decompiledSmali = File(smaliOutputDir, "com/example/demo/TestClass.smali")
            assertTrue("Decompiled smali file must exist", decompiledSmali.exists())
            assertTrue("Smali content must contain TestClass", decompiledSmali.readText().contains("TestClass"))

            // 4. Recompile project using ApktoolEngine
            val rebuiltApk = File(tempDir, "rebuilt_output.apk")
            ApktoolEngine.recompileApk(
                projectDir = outDir,
                outputApk = rebuiltApk,
                options = ApktoolEngine.RecompileOptions(forceOverwrite = true),
                listener = listener
            )

            assertTrue("Rebuilt APK must exist", rebuiltApk.exists())
            assertTrue("Rebuilt APK must have positive size", rebuiltApk.length() > 0)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
