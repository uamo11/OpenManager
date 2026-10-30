/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import me.zhanghai.android.files.viewer.dex.JadxDecompilerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ApktoolPathAndCancellationTest {

    @Test
    fun testApktoolPathHierarchy() {
        val testPkg = "com.test.company"

        val baseDir = ApktoolPaths.getApktoolBaseDir()
        assertTrue("Base dir must contain ApkTool", baseDir.path.contains("ApkTool"))

        val pkgDir = ApktoolPaths.getPackageDir(testPkg)
        assertTrue("Package dir must contain packageName", pkgDir.path.contains(testPkg))

        val javaDecompDir = ApktoolPaths.getJavaDecompileDir(testPkg)
        val expectedJavaSuffix = File(testPkg, "decomp_java").path
        assertTrue(
            "Java decompile path must end with $expectedJavaSuffix, got: ${javaDecompDir.path}",
            javaDecompDir.path.endsWith(expectedJavaSuffix)
        )

        val srcDecompDir = ApktoolPaths.getApktoolDecompileDir(testPkg)
        val expectedSrcSuffix = File(testPkg, "decomp_src").path
        assertTrue(
            "Apktool src decompile path must end with $expectedSrcSuffix, got: ${srcDecompDir.path}",
            srcDecompDir.path.endsWith(expectedSrcSuffix)
        )

        val recompiledApk = ApktoolPaths.getRecompiledApkFile(testPkg)
        val expectedApkName = "${testPkg}_recompiled.apk"
        assertEquals("Recompiled APK name must match", expectedApkName, recompiledApk.name)
    }

    @Test
    fun testDecompileToDirectoryWithCancellation() {
        val tempDir = File.createTempFile("apktool_cancel_test", "").apply {
            delete()
            mkdirs()
        }

        try {
            val smaliFile = File(tempDir, "Sample.smali")
            val dexFile = File(tempDir, "classes.dex")

            val smaliCode = """
                .class public Lcom/test/company/Sample;
                .super Ljava/lang/Object;

                .method public constructor <init>()V
                    .registers 1
                    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                    return-void
                .end method

                .method public hello()Ljava/lang/String;
                    .registers 2
                    const-string v0, "Hello World"
                    return-object v0
                .end method
            """.trimIndent()
            smaliFile.writeText(smaliCode)

            val options = SmaliOptions().apply {
                apiLevel = 28
                outputDexFile = dexFile.absolutePath
            }
            assertTrue("Smali assemble must succeed", Smali.assemble(options, listOf(smaliFile.absolutePath)))

            val outDir = File(tempDir, "decomp_java")

            // Test successful directory extraction
            var progressNotified = false
            JadxDecompilerEngine.decompileToDirectory(dexFile, outDir, isCancelled = { false }) { cur, total, cls ->
                progressNotified = true
                assertTrue(cur > 0)
                assertTrue(total > 0)
                assertTrue(cls.contains("Sample"))
            }

            assertTrue("Progress callback should have been invoked", progressNotified)
            val extractedJava = File(outDir, "com/test/company/Sample.java")
            assertTrue("Extracted .java file must exist", extractedJava.exists())
            val sourceText = extractedJava.readText()
            assertTrue("Java source must contain class Sample", sourceText.contains("class Sample"))
            assertTrue("Java source must contain hello method", sourceText.contains("hello()"))

            // Test cancellation aborts immediately
            val cancelSignal = AtomicBoolean(true)
            var threwCancellation = false
            val cancelledDir = File(tempDir, "cancelled_run")
            try {
                JadxDecompilerEngine.decompileToDirectory(dexFile, cancelledDir, isCancelled = { cancelSignal.get() })
            } catch (ie: InterruptedException) {
                threwCancellation = true
            }

            assertTrue("Must throw InterruptedException when cancelled", threwCancellation)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testApktoolTaskStateTransitions() {
        val initial = ApktoolExecutionService.TaskState(
            taskType = ApktoolExecutionService.TASK_TYPE_DECOMPILE_JAVA,
            title = "Descompilar a Java (JADX)",
            packageName = "com.test.company",
            inputPath = "/test/app.apk",
            outputPath = "/test/decomp_java",
            status = "Iniciando...",
            detail = "",
            progress = 0,
            isIndeterminate = true,
            isRunning = true,
            isCancelled = false,
            isCompleted = false
        )

        assertTrue(initial.isRunning)
        assertFalse(initial.isCompleted)
        assertFalse(initial.isCancelled)

        // Progress update
        val inProgress = initial.copy(
            status = "Descompilando clases Java (45%)",
            detail = "45/100: MainActivity.java",
            progress = 45,
            isIndeterminate = false
        )
        assertEquals(45, inProgress.progress)
        assertFalse(inProgress.isIndeterminate)

        // Cancelled transition
        val cancelled = inProgress.copy(
            isRunning = false,
            isCancelled = true,
            status = "Operación cancelada"
        )
        assertFalse(cancelled.isRunning)
        assertTrue(cancelled.isCancelled)

        // Completed transition
        val completed = inProgress.copy(
            isRunning = false,
            isCompleted = true,
            progress = 100,
            status = "Operación completada exitosamente"
        )
        assertFalse(completed.isRunning)
        assertTrue(completed.isCompleted)
        assertEquals(100, completed.progress)
    }
}
