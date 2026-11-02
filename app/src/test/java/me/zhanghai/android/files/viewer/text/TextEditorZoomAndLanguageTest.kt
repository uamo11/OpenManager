/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import me.zhanghai.android.files.filelist.ScriptRunnerHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class TextEditorZoomAndLanguageTest {

    @Test
    fun testNonExecutableDocumentExclusion() {
        // Text and documents should NEVER be treated as executables or trigger execution dialogs
        assertTrue(ScriptRunnerHelper.isNonExecutableDocument("notes.txt"))
        assertTrue(ScriptRunnerHelper.isNonExecutableDocument("error.log"))
        assertTrue(ScriptRunnerHelper.isNonExecutableDocument("README.md"))
        assertTrue(ScriptRunnerHelper.isNonExecutableDocument("config.json"))
        assertTrue(ScriptRunnerHelper.isNonExecutableDocument("strings.xml"))
        assertTrue(ScriptRunnerHelper.isNonExecutableDocument("app.properties"))
        assertTrue(ScriptRunnerHelper.isNonExecutableDocument("build.gradle"))
        assertTrue(ScriptRunnerHelper.isNonExecutableDocument("source.java"))
        assertTrue(ScriptRunnerHelper.isNonExecutableDocument("MyClass.smali"))

        // Shell and binary scripts MUST not be in non-executable set
        assertFalse(ScriptRunnerHelper.isNonExecutableDocument("script.sh"))
        assertFalse(ScriptRunnerHelper.isNonExecutableDocument("test.bash"))
        assertFalse(ScriptRunnerHelper.isNonExecutableDocument("binary.bin"))
        assertFalse(ScriptRunnerHelper.isNonExecutableDocument("installer.run"))
    }

    @Test
    fun testLanguageDetection() {
        assertEquals(SyntaxLanguage.PLAIN_TEXT, SyntaxLanguage.fromFileName("notes.txt"))
        assertEquals(SyntaxLanguage.GRADLE, SyntaxLanguage.fromFileName("build.gradle"))
        assertEquals(SyntaxLanguage.GRADLE, SyntaxLanguage.fromFileName("settings.gradle"))
        assertEquals(SyntaxLanguage.GRADLE, SyntaxLanguage.fromFileName("App.groovy"))
        assertEquals(SyntaxLanguage.DART, SyntaxLanguage.fromFileName("main.dart"))
        assertEquals(SyntaxLanguage.CSHARP, SyntaxLanguage.fromFileName("Program.cs"))
        assertEquals(SyntaxLanguage.RUBY, SyntaxLanguage.fromFileName("script.rb"))
        assertEquals(SyntaxLanguage.RUBY, SyntaxLanguage.fromFileName("Gemfile"))
        assertEquals(SyntaxLanguage.SWIFT, SyntaxLanguage.fromFileName("ViewController.swift"))
        assertEquals(SyntaxLanguage.BATCH, SyntaxLanguage.fromFileName("run.bat"))
        assertEquals(SyntaxLanguage.BATCH, SyntaxLanguage.fromFileName("build.cmd"))
        assertEquals(SyntaxLanguage.CMAKE, SyntaxLanguage.fromFileName("CMakeLists.txt"))
        assertEquals(SyntaxLanguage.CMAKE, SyntaxLanguage.fromFileName("toolchain.cmake"))
        assertEquals(SyntaxLanguage.SMALI, SyntaxLanguage.fromFileName("MainActivity.smali"))

        // MT Manager added languages
        assertEquals(SyntaxLanguage.POWERSHELL, SyntaxLanguage.fromFileName("deploy.ps1"))
        assertEquals(SyntaxLanguage.DIFF, SyntaxLanguage.fromFileName("changes.patch"))
        assertEquals(SyntaxLanguage.DIFF, SyntaxLanguage.fromFileName("changes.diff"))
        assertEquals(SyntaxLanguage.CSV, SyntaxLanguage.fromFileName("data.csv"))
        assertEquals(SyntaxLanguage.ASM, SyntaxLanguage.fromFileName("kernel.asm"))
        assertEquals(SyntaxLanguage.ASM, SyntaxLanguage.fromFileName("start.s"))
        assertEquals(SyntaxLanguage.VISUAL_BASIC, SyntaxLanguage.fromFileName("macro.vbs"))
        assertEquals(SyntaxLanguage.ZIG, SyntaxLanguage.fromFileName("main.zig"))
        assertEquals(SyntaxLanguage.NIX, SyntaxLanguage.fromFileName("default.nix"))
        assertEquals(SyntaxLanguage.LATEX, SyntaxLanguage.fromFileName("paper.tex"))
        assertEquals(SyntaxLanguage.LISP, SyntaxLanguage.fromFileName("init.el"))
    }

    @Test
    fun testEditorThemes() {
        assertEquals(5, EditorTheme.entries.size)
        for (theme in EditorTheme.entries) {
            assertTrue(theme.id.isNotEmpty())
            assertTrue(theme.displayName.isNotEmpty())
            assertNotEquals(0, theme.backgroundColor)
            assertNotEquals(0, theme.gutterColor)
            assertNotEquals(0, theme.textColor)
            assertNotEquals(0, theme.keywordColor)
            assertEquals(theme, EditorTheme.fromId(theme.id))
        }
        // Fallback to default
        assertEquals(EditorTheme.ONE_DARK, EditorTheme.fromId("unknown_theme"))
    }

    @Test
    fun testThemeBasedSyntaxTokenization() {
        val code = "val x: Int = 100 // test comment"
        val oneDarkTokens = FastSyntaxScanner.scan(code, SyntaxLanguage.KOTLIN, EditorTheme.ONE_DARK)
        val draculaTokens = FastSyntaxScanner.scan(code, SyntaxLanguage.KOTLIN, EditorTheme.DRACULA)

        assertTrue(oneDarkTokens.isNotEmpty())
        assertTrue(draculaTokens.isNotEmpty())
        assertEquals(oneDarkTokens.size, draculaTokens.size)

        // The keyword 'val' color should match the specific theme's keyword color
        assertEquals(EditorTheme.ONE_DARK.keywordColor, oneDarkTokens[0].color)
        assertEquals(EditorTheme.DRACULA.keywordColor, draculaTokens[0].color)
        assertNotEquals(oneDarkTokens[0].color, draculaTokens[0].color)
    }

    @Test
    fun testPowerShellTokenization() {
        val ps = """
            # PowerShell test
            function Get-Info {
                param([string]${'$'}name)
                if (${'$'}name -eq "OpenManager") {
                    return "Ready"
                }
            }
        """.trimIndent()

        val tokens = FastSyntaxScanner.scan(ps, SyntaxLanguage.POWERSHELL)
        assertTrue(tokens.isNotEmpty())

        val commentTokens = tokens.filter { it.color == FastSyntaxScanner.COLOR_COMMENT }
        assertEquals(1, commentTokens.size)

        val keywordTokens = tokens.filter { it.color == FastSyntaxScanner.COLOR_KEYWORD }
        val keywordStrs = keywordTokens.map { ps.substring(it.start, it.end) }
        assertTrue(keywordStrs.contains("function"))
        assertTrue(keywordStrs.contains("param"))
        assertTrue(keywordStrs.contains("if"))
        assertTrue(keywordStrs.contains("return"))
    }

    @Test
    fun testCursorNavigationHistoryLogic() {
        val backStack = ArrayDeque<Int>()
        val forwardStack = ArrayDeque<Int>()
        var currentOffset = 0

        fun jumpTo(offset: Int) {
            backStack.addLast(currentOffset)
            forwardStack.clear()
            currentOffset = offset
        }

        fun goBack(): Int? {
            if (backStack.isEmpty()) return null
            forwardStack.addLast(currentOffset)
            currentOffset = backStack.removeLast()
            return currentOffset
        }

        fun goForward(): Int? {
            if (forwardStack.isEmpty()) return null
            backStack.addLast(currentOffset)
            currentOffset = forwardStack.removeLast()
            return currentOffset
        }

        jumpTo(500)
        assertEquals(500, currentOffset)
        assertEquals(1, backStack.size)

        jumpTo(1200)
        assertEquals(1200, currentOffset)
        assertEquals(2, backStack.size)

        val back1 = goBack()
        assertEquals(500, back1)
        assertEquals(1, forwardStack.size)

        val back2 = goBack()
        assertEquals(0, back2)
        assertEquals(2, forwardStack.size)

        val forward1 = goForward()
        assertEquals(500, forward1)
        assertEquals(1, forwardStack.size)
    }

    @Test
    fun testAccessoryBarAutoWrapLogic() {
        val pairs = mapOf(
            "{" to "}",
            "(" to ")",
            "[" to "]",
            "\"" to "\"",
            "'" to "'",
            "<" to ">"
        )

        fun wrapOrPair(selectedText: String?, sym: String): String {
            val close = pairs[sym]
            return if (!selectedText.isNullOrEmpty() && close != null) {
                "$sym$selectedText$close"
            } else if (close != null) {
                "$sym$close"
            } else {
                sym
            }
        }

        // Selection wrapping
        assertEquals("\"selected\"", wrapOrPair("selected", "\""))
        assertEquals("{block}", wrapOrPair("block", "{"))
        assertEquals("[array]", wrapOrPair("array", "["))
        assertEquals("<tag>", wrapOrPair("tag", "<"))

        // Pair insertion when no selection
        assertEquals("{}", wrapOrPair("", "{"))
        assertEquals("()", wrapOrPair(null, "("))
        assertEquals("\"\"", wrapOrPair("", "\""))

        // Non-paired symbol
        assertEquals("=", wrapOrPair("foo", "="))
    }

    @Test
    fun testGradleSyntaxScanning() {
        val gradleCode = """
            plugins {
                id 'com.android.application'
            }
            android {
                compileSdk 34
            }
            dependencies {
                implementation "androidx.core:core-ktx:1.12.0"
            }
        """.trimIndent()

        val tokens = FastSyntaxScanner.scan(gradleCode, SyntaxLanguage.GRADLE)
        assertTrue(tokens.isNotEmpty())

        val keywords = tokens.filter { it.color == FastSyntaxScanner.COLOR_KEYWORD }
        val keywordStrings = keywords.map { gradleCode.substring(it.start, it.end) }
        assertTrue(keywordStrings.contains("plugins"))
        assertTrue(keywordStrings.contains("android"))
        assertTrue(keywordStrings.contains("dependencies"))
        assertTrue(keywordStrings.contains("implementation"))

        // Verify valid ascending non-overlapping intervals
        for (i in 1 until tokens.size) {
            assertTrue(tokens[i].start >= tokens[i - 1].end)
        }
    }

    @Test
    fun testDartSyntaxScanning() {
        val dartCode = """
            import 'package:flutter/material.dart';

            // Entry point
            void main() {
                runApp(const MyApp());
            }

            class MyApp extends StatelessWidget {
                final String title = "Test";
                Widget build(BuildContext context) {
                    return Container();
                }
            }
        """.trimIndent()

        val tokens = FastSyntaxScanner.scan(dartCode, SyntaxLanguage.DART)
        assertTrue(tokens.isNotEmpty())

        val keywords = tokens.filter { it.color == FastSyntaxScanner.COLOR_KEYWORD }
        val keywordStrings = keywords.map { dartCode.substring(it.start, it.end) }
        assertTrue(keywordStrings.contains("import"))
        assertTrue(keywordStrings.contains("void"))
        assertTrue(keywordStrings.contains("class"))
        assertTrue(keywordStrings.contains("extends"))
        assertTrue(keywordStrings.contains("final"))
        assertTrue(keywordStrings.contains("return"))

        val types = tokens.filter { it.color == FastSyntaxScanner.COLOR_TYPE }
        val typeStrings = types.map { dartCode.substring(it.start, it.end) }
        assertTrue(typeStrings.contains("Widget"))
        assertTrue(typeStrings.contains("String"))
        assertTrue(typeStrings.contains("StatelessWidget"))
        assertTrue(typeStrings.contains("BuildContext"))
    }

    @Test
    fun testSmaliInstructionTokenization() {
        val smaliCode = """
            .method public test()V
                invoke-virtual {p0}, Ljava/lang/Object;->toString()Ljava/lang/String;
                move-result-object v0
                if-nez v0, :cond_0
                const-string v1, "success"
                return-object v1
                :cond_0
                return-void
            .end method
        """.trimIndent()

        val tokens = FastSyntaxScanner.scan(smaliCode, SyntaxLanguage.SMALI)
        assertTrue(tokens.isNotEmpty())

        val instructions = tokens.filter { it.color == FastSyntaxScanner.COLOR_KEYWORD }
        val instructionStrings = instructions.map { smaliCode.substring(it.start, it.end) }
        assertTrue(instructionStrings.contains("invoke-virtual"))
        assertTrue(instructionStrings.contains("move-result-object"))
        assertTrue(instructionStrings.contains("if-nez"))
        assertTrue(instructionStrings.contains("const-string"))
        assertTrue(instructionStrings.contains("return-object"))
        assertTrue(instructionStrings.contains("return-void"))
    }

    @Test
    fun testZoomClampingAndScaleLogic() {
        assertEquals(8f, TextEditorSettings.MIN_FONT_SIZE)
        assertEquals(38f, TextEditorSettings.MAX_FONT_SIZE)
        assertEquals(14f, TextEditorSettings.DEFAULT_FONT_SIZE)

        // Test clamping logic used in onScale
        val initialSize = 14f
        val zoomedIn = (initialSize * 1.5f).coerceIn(
            TextEditorSettings.MIN_FONT_SIZE,
            TextEditorSettings.MAX_FONT_SIZE
        )
        assertEquals(21f, zoomedIn, 0.01f)

        val zoomMinClamped = (initialSize * 0.1f).coerceIn(
            TextEditorSettings.MIN_FONT_SIZE,
            TextEditorSettings.MAX_FONT_SIZE
        )
        assertEquals(8f, zoomMinClamped, 0.01f)

        val zoomMaxClamped = (initialSize * 4.0f).coerceIn(
            TextEditorSettings.MIN_FONT_SIZE,
            TextEditorSettings.MAX_FONT_SIZE
        )
        assertEquals(38f, zoomMaxClamped, 0.01f)
    }
}
