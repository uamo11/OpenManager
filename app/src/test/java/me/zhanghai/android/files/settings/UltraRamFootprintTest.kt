/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import me.zhanghai.android.files.provider.sftp.client.SecurityProviderHelper
import me.zhanghai.android.files.util.SampledImageResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.Security

/**
 * Guards the Ultra RAM footprint fixes found with tools/perf-probe.sh (heap dumps + malloc debug).
 */
class UltraRamFootprintTest {
    private fun resolveMainFile(relPath: String): File {
        val f1 = File("src/main/$relPath")
        return if (f1.exists()) f1 else File("app/src/main/$relPath")
    }

    @Test
    fun bouncyCastleRegistersOnDemandExactlyOnce() {
        SecurityProviderHelper.ensureInitialized()
        SecurityProviderHelper.ensureInitialized()
        assertNotNull(Security.getProvider("BC"))
        assertEquals(1, Security.getProviders().count { it.name == "BC" })
        // BKS keystores (keystore generator / APK signer) must work after on-demand registration.
        val ks = KeyStore.getInstance("BKS", "BC")
        ks.load(null, "pass".toCharArray())
        assertEquals(0, ks.size())
    }

    @Test
    fun bouncyCastleIsNotRegisteredEagerlyInUltraRamMode() {
        val sftpProvider = resolveMainFile(
            "java/me/zhanghai/android/files/provider/sftp/SftpFileSystemProvider.kt"
        ).readText()
        assertFalse(
            "Installing the SFTP provider at boot must not register BouncyCastle",
            sftpProvider.substringBefore("override fun getScheme").contains("SecurityProviderHelper.init()")
        )
        assertTrue(sftpProvider.contains("SecurityProviderHelper.ensureInitialized()"))

        val appInit = resolveMainFile("java/me/zhanghai/android/files/app/AppInitializers.kt").readText()
        val perfInit = appInit.substringAfter("private fun initializePerformanceMode()")
            .substringBefore("private fun ")
        assertTrue(
            "Performance mode OFF keeps the previous eager registration",
            perfInit.substringAfter("if (!enabled) {").contains("SecurityProviderHelper.init()")
        )

        for (path in listOf(
            "java/me/zhanghai/android/files/provider/sftp/client/Client.kt",
            "java/me/zhanghai/android/files/provider/sftp/client/Authentication.kt",
            "java/me/zhanghai/android/files/filelist/KeystoreGeneratorHelper.kt",
            "java/me/zhanghai/android/files/filelist/ApkSignerHelper.kt"
        )) {
            assertTrue(
                "$path must register BouncyCastle before using it",
                resolveMainFile(path).readText().contains("SecurityProviderHelper.ensureInitialized()")
            )
        }
    }

    @Test
    fun fileRowsUseLiteImageViewsUnlessShaped() {
        val list = resolveMainFile("res/layout/file_item_list.xml").readText()
        assertFalse(list.contains("files.ui.DisabledAlphaImageView"))
        assertEquals(5, Regex("files\\.ui\\.LiteDisabledAlphaImageView").findAll(list).count())

        val grid = resolveMainFile("res/layout/file_item_grid.xml").readText()
        val shapeableTags = Regex("<me\\.zhanghai\\.android\\.files\\.ui\\.DisabledAlphaImageView[^>]*>")
            .findAll(grid).map { it.value }.toList()
        assertEquals("Only the shaped thumbnail needs ShapeableImageView", 1, shapeableTags.size)
        assertTrue(shapeableTags.single().contains("shapeAppearance"))
    }

    @Test
    fun localeDisplayNamesAreBuiltLazily() {
        val src = resolveMainFile("java/me/zhanghai/android/files/settings/LocalePreference.kt")
            .readText()
        assertTrue(src.contains("override fun getEntries()"))
        assertTrue(src.contains("pendingEntries"))
        assertFalse(
            "Eager entries would make ICU load every supported locale",
            src.contains("summaryProvider = SimpleSummaryProvider.getInstance()")
        )
        assertTrue(
            "pendingEntries must be declared before init {} or its initializer resets it",
            src.indexOf("private var pendingEntries") < src.indexOf("\n    init {")
        )
    }

    @Test
    fun charsetListsAreNotBuiltOnScreenOpen() {
        val pref = resolveMainFile("java/me/zhanghai/android/files/settings/CharsetPreference.kt")
            .readText()
        val initBlock = pref.substringAfter("\n    init {").substringBefore("private fun ensureCharsetsLoaded")
        assertFalse(initBlock.contains("availableCharsets"))
        assertTrue(pref.contains("override fun getEntries()"))
        assertTrue(pref.contains("override fun getEntryValues()"))
        assertTrue(pref.contains("override fun findIndexOfValue"))

        val editor = resolveMainFile("java/me/zhanghai/android/files/viewer/text/TextEditorFragment.kt")
            .readText()
        val menuInflate = editor.substringAfter("fun inflate(menu: Menu, inflater: MenuInflater)")
            .substringBefore("return MenuBinding(")
        assertFalse("Editor menu must not enumerate charsets on open", menuInflate.contains("in Charset.availableCharsets()"))
        assertTrue(editor.contains("R.id.action_encoding -> {"))
        assertTrue(editor.contains("private fun ensureEncodingSubMenuPopulated()"))
    }

    @Test
    fun sampledDecodingNeverGoesBelowTargetSize() {
        assertEquals(1, SampledImageResources.computeInSampleSize(512, 512, 0))
        assertEquals(1, SampledImageResources.computeInSampleSize(100, 100, 84))
        // 512px logo in a 32dp view at 2.625x (84px): 128px keeps it crisp.
        assertEquals(4, SampledImageResources.computeInSampleSize(512, 512, 84))
        assertEquals(16, SampledImageResources.computeInSampleSize(1024, 1024, 63))
        for (size in listOf(48, 63, 84, 126, 168, 300)) {
            val sample = SampledImageResources.computeInSampleSize(1024, 1024, size)
            assertTrue(1024 / sample >= size)
            assertTrue(1024 / (sample * 2) < size)
        }
        assertTrue(SampledImageResources.isRasterPath("res/drawable-nodpi/ic_shizuku_logo.png"))
        assertFalse(SampledImageResources.isRasterPath("res/drawable/ic_shizuku.xml"))
        assertFalse(SampledImageResources.isRasterPath(null))
    }

    @Test
    fun superuserCardDoesNotInflateFullSizeLogos() {
        val layout = resolveMainFile("res/layout/preference_superuser_flag.xml").readText()
        assertFalse(layout.contains("android:src=\"@drawable/ic_shizuku_logo\""))
        assertFalse(layout.contains("android:src=\"@drawable/ic_shield_locked\""))
        assertFalse(layout.contains("android:src=\"@drawable/ic_circle_error_red\""))
        val pref = resolveMainFile("java/me/zhanghai/android/files/settings/SuperuserStatusPreference.kt")
            .readText()
        assertFalse(pref.contains(".setImageResource("))
        assertTrue(pref.contains("ivShizukuMini?.setImageResourceAtViewSize(R.drawable.ic_shizuku_logo)"))
    }
}
