/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.saf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The system picker shows dot-files when its "Show hidden files" is on: OM must list them. */
class StorageDocumentsProviderTest {

    private val source = (File("src/main").takeIf { it.isDirectory } ?: File("app/src/main"))
        .resolve("java/me/zhanghai/android/files/provider/saf/StorageDocumentsProvider.kt").readText()

    @Test
    fun childrenIncludeDotFiles() {
        val children = source.substringAfter("override fun queryChildDocuments(")
            .substringBefore("override fun querySearchDocuments(")
        assertFalse(children.contains("startsWith(\".\")"))
        assertTrue(children.contains("listFiles()?.forEach"))
    }
}
