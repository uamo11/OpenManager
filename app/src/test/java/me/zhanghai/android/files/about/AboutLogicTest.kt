/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutLogicTest {

    @Test
    fun testExtraScrollToAcknowledgementsKey() {
        assertEquals(
            "me.zhanghai.android.files.about.extra.SCROLL_TO_ACKNOWLEDGEMENTS",
            AboutActivity.EXTRA_SCROLL_TO_ACKNOWLEDGEMENTS
        )
    }

    @Test
    fun testAuthorAndRepositoryAttribution() {
        val authorName = "Uamo11"
        val authorWebsite = "https://uam.lol"
        val authorGitHub = "https://github.com/uamo11"
        val repoUrl = "https://github.com/uamo11/OpenManager"

        assertEquals("Uamo11", authorName)
        assertTrue(authorWebsite.contains("uam.lol"))
        assertTrue(authorGitHub.contains("github.com/uamo11"))
        assertTrue(repoUrl.contains("uamo11/OpenManager"))
    }

    @Test
    fun testBaseProjectAcknowledgements() {
        val baseAuthor = "Hai Zhang (Shangai)"
        val baseAuthorGitHub = "https://github.com/zhanghai"
        val baseRepo = "https://github.com/zhanghai/MaterialFiles"

        assertTrue(baseAuthor.contains("Hai Zhang"))
        assertTrue(baseAuthor.contains("Shangai"))
        assertTrue(baseAuthorGitHub.contains("github.com/zhanghai"))
        assertTrue(baseRepo.contains("zhanghai/MaterialFiles"))
    }
}
