/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import me.zhanghai.android.files.filelist.ScriptRunnerHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
