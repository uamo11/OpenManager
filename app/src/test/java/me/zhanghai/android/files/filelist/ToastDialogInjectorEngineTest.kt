/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Matcher

class ToastDialogInjectorEngineTest {

    @Test
    fun testEscapeSmaliString() {
        val plain = "Hello World!"
        assertEquals("Hello World!", ToastDialogInjectorEngine.escapeSmaliString(plain))

        val quotes = "Hello \"World\" & \\"
        assertEquals("Hello \\\"World\\\" & \\\\", ToastDialogInjectorEngine.escapeSmaliString(quotes))

        val newlines = "Line 1\nLine 2\tTabbed"
        assertEquals("Line 1\\nLine 2\\tTabbed", ToastDialogInjectorEngine.escapeSmaliString(newlines))

        val spanish = "Configuración rápida: ¡Éxito!"
        val escaped = ToastDialogInjectorEngine.escapeSmaliString(spanish)
        assertFalse(escaped.contains("\""))
        assertTrue(escaped.contains("Configuraci\\u00f3n"))
    }

    @Test
    fun testToastPatternRegex() {
        val smaliSnippet = """
            .method public showMsg()V
                .registers 2
                const-string v0, "Test"
                const/4 v1, 0x1
                invoke-static {p0, v0, v1}, Landroid/widget/Toast;->makeText(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;
                move-result-object v0
                invoke-virtual {v0}, Landroid/widget/Toast;->show()V
                return-void
            .end method
        """.trimIndent()

        val matcher = ToastDialogInjectorEngine.TOAST_PATTERN.matcher(smaliSnippet)
        assertTrue(matcher.find())
        val matchedLine = matcher.group()
        assertTrue(matchedLine.contains("Landroid/widget/Toast;->show()V"))

        val sb = StringBuffer()
        matcher.reset()
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement("    nop # Neutralized Toast.show"))
        }
        matcher.appendTail(sb)

        val replaced = sb.toString()
        assertFalse(replaced.contains("Landroid/widget/Toast;->show()V"))
        assertTrue(replaced.contains("nop # Neutralized Toast.show"))
    }

    @Test
    fun testDialogPatternRegex() {
        val smaliWithMoveResult = """
            invoke-virtual {v0}, Landroid/app/AlertDialog${'$'}Builder;->show()Landroid/app/AlertDialog;
            move-result-object v1
            return-void
        """.trimIndent()

        val matcher1 = ToastDialogInjectorEngine.DIALOG_PATTERN.matcher(smaliWithMoveResult)
        assertTrue(matcher1.find())
        assertEquals("v1", matcher1.group(1))

        val smaliVoidDialog = """
            invoke-virtual {p1}, Landroid/app/Dialog;->show()V
            return-void
        """.trimIndent()

        val matcher2 = ToastDialogInjectorEngine.DIALOG_PATTERN.matcher(smaliVoidDialog)
        assertTrue(matcher2.find())
        assertEquals(null, matcher2.group(1))

        val smaliAndroidx = """
            invoke-virtual {v2}, Landroidx/appcompat/app/AlertDialog${'$'}Builder;->show()Landroidx/appcompat/app/AlertDialog;
            move-result-object p0
            return-void
        """.trimIndent()

        val matcher3 = ToastDialogInjectorEngine.DIALOG_PATTERN.matcher(smaliAndroidx)
        assertTrue(matcher3.find())
        assertEquals("p0", matcher3.group(1))
    }

    @Test
    fun testIsDialogClass() {
        assertTrue(ToastDialogInjectorEngine.isDialogClass("Landroid/app/AlertDialog;"))
        assertTrue(ToastDialogInjectorEngine.isDialogClass("Landroid/app/AlertDialog\$Builder;"))
        assertTrue(ToastDialogInjectorEngine.isDialogClass("Landroid/app/Dialog;"))
        assertTrue(ToastDialogInjectorEngine.isDialogClass("Landroidx/appcompat/app/AlertDialog;"))
        assertTrue(ToastDialogInjectorEngine.isDialogClass("Landroidx/appcompat/app/AlertDialog\$Builder;"))
        assertTrue(ToastDialogInjectorEngine.isDialogClass("Landroid/support/v7/app/AlertDialog;"))
        assertTrue(ToastDialogInjectorEngine.isDialogClass("Landroid/support/v7/app/AlertDialog\$Builder;"))
        assertTrue(ToastDialogInjectorEngine.isDialogClass("Lcom/google/android/material/dialog/MaterialAlertDialogBuilder;"))

        assertFalse(ToastDialogInjectorEngine.isDialogClass("Landroid/widget/Toast;"))
        assertFalse(ToastDialogInjectorEngine.isDialogClass("Landroid/widget/PopupWindow;"))
    }

    @Test
    fun testInjectMethodCallIntoClassWithExistingOnCreate() {
        val smaliInput = """
            .class public Lcom/example/MainActivity;
            .super Landroid/app/Activity;
            .source "MainActivity.java"

            .method protected onCreate(Landroid/os/Bundle;)V
                .registers 2
                .param p1, "savedInstanceState"
                .prologue
                .line 20
                invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V
                return-void
            .end method
        """.trimIndent()

        val helperSmali = """
            .method private showOpenManagerToast()V
                .registers 1
                return-void
            .end method
        """.trimIndent()

        val modified = ToastDialogInjectorEngine.injectMethodCallIntoClass(
            smaliInput,
            "Lcom/example/MainActivity;",
            "showOpenManagerToast",
            helperSmali
        )

        assertTrue(modified.contains("invoke-direct {p0}, Lcom/example/MainActivity;->showOpenManagerToast()V"))
        assertTrue(modified.contains(".method private showOpenManagerToast()V"))
        // Check order: registers, prologue, then invocation
        val indexOfPrologue = modified.indexOf(".prologue")
        val indexOfInvoke = modified.indexOf("invoke-direct {p0}, Lcom/example/MainActivity;->showOpenManagerToast()V")
        assertTrue(indexOfInvoke > indexOfPrologue)
    }

    @Test
    fun testInjectMethodCallIntoClassWithoutOnCreate() {
        val smaliInput = """
            .class public Lcom/example/SubActivity;
            .super Landroidx/appcompat/app/AppCompatActivity;
            .source "SubActivity.java"

            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Landroidx/appcompat/app/AppCompatActivity;-><init>()V
                return-void
            .end method
        """.trimIndent()

        val helperSmali = """
            .method private showOpenManagerDialog()V
                .registers 1
                return-void
            .end method
        """.trimIndent()

        val modified = ToastDialogInjectorEngine.injectMethodCallIntoClass(
            smaliInput,
            "Lcom/example/SubActivity;",
            "showOpenManagerDialog",
            helperSmali
        )

        assertTrue(modified.contains(".method protected onCreate(Landroid/os/Bundle;)V"))
        assertTrue(modified.contains("invoke-super {p0, p1}, Landroidx/appcompat/app/AppCompatActivity;->onCreate(Landroid/os/Bundle;)V"))
        assertTrue(modified.contains("invoke-direct {p0}, Lcom/example/SubActivity;->showOpenManagerDialog()V"))
        assertTrue(modified.contains(".method private showOpenManagerDialog()V"))
    }

    @Test
    fun testFindActivitiesOnRealApkIfAvailable() {
        val sampleApk = File("build_apk/OpenManager-debug.apk/app-debug.apk")
        if (!sampleApk.exists()) {
            return
        }

        val activities = ToastDialogInjectorEngine.findActivities(sampleApk)
        assertNotNull(activities)
        assertTrue(activities.isNotEmpty())
        // FileListActivity is the launcher activity and should be first
        assertEquals("me.zhanghai.android.files.filelist.FileListActivity", activities.first())
        assertTrue(activities.any { it.contains("SettingsActivity") || it.contains("AboutActivity") || it.contains("FileListActivity") })
    }
}
