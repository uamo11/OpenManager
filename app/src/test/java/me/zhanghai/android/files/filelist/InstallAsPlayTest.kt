/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** MP Manager users' idea: root/Shizuku installs recorded as from Google Play. */
class InstallAsPlayTest {

    private val staged = listOf("0_base.apk" to 10L)

    @Test
    fun theSessionNamesPlayOnlyWhenAsked() {
        val play = ApkInstallHelper.buildMultiApkPmSessionScript("/data/local/tmp/x", staged, 35, asPlay = true)
        assertTrue(play.contains("pm install-create -r -d -t -g --bypass-low-target-sdk-block -i com.android.vending"))
        val plain = ApkInstallHelper.buildMultiApkPmSessionScript("/data/local/tmp/x", staged, 35)
        assertFalse(plain.contains("com.android.vending"))
    }

    @Test
    fun singleApkInstallsUseTheSameSwitch() {
        val src = (File("src/main").takeIf { it.isDirectory } ?: File("app/src/main"))
            .resolve("java/me/zhanghai/android/files/filelist/ApkInstallHelper.kt").readText()
        // Root and Shizuku single-APK commands both carry the flag.
        assertTrue(Regex("""pm install -r -d -t \$grantPermFlag\$bypassLowTargetFlag\$\{playInstallerFlag\(\)\}""")
            .findAll(src).count() == 2)
        assertTrue(src.contains("asPlay = installAsPlay()"))
    }
}
