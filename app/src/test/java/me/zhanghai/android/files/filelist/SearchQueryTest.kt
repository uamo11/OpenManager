/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {

    @Test
    fun aPlainNameStaysTheBareTextForTheProvidersFastPath() {
        val q = SearchQuery(".png")
        assertTrue(q.isPlain)
        assertEquals(".png", q.encode())
        assertEquals(".png", q.providerText)
        assertEquals(q, SearchQuery.decode(".png"))
        assertTrue(q.matchesName("Photo.PNG"))
    }

    @Test
    fun wildcardsMatchTheWholeName() {
        val q = SearchQuery("*.png")
        assertFalse(q.isPlain)
        assertEquals("", q.providerText)
        assertTrue(q.matchesName("a.png"))
        assertTrue(q.matchesName("A.PNG"))
        assertFalse(q.matchesName("a.png.bak"))
        assertTrue(SearchQuery("img_??.jpg").matchesName("img_01.jpg"))
        assertFalse(SearchQuery("img_??.jpg").matchesName("img_1.jpg"))
        // Regex characters in a wildcard name are literal.
        assertTrue(SearchQuery("a+b(*).txt").matchesName("a+b(1).txt"))
    }

    @Test
    fun caseAndRegex() {
        assertFalse(SearchQuery("Readme", caseSensitive = true).matchesName("README.md"))
        assertTrue(SearchQuery("^img_\\d+\\.jpg$", regex = true).matchesName("img_42.jpg"))
        assertFalse(SearchQuery("^img_\\d+\\.jpg$", regex = true).matchesName("img_x.jpg"))
        assertTrue(SearchQuery("([", regex = true).isInvalid)
    }

    @Test
    fun sizesAndContents() {
        assertEquals(500L * 1024, SearchQuery.parseSize("500K"))
        assertEquals(2L * 1024 * 1024, SearchQuery.parseSize("2 MB"))
        assertEquals((1.5 * (1L shl 30)).toLong(), SearchQuery.parseSize("1,5g"))
        assertEquals(300L, SearchQuery.parseSize("300"))
        assertNull(SearchQuery.parseSize(""))
        assertNull(SearchQuery.parseSize("big"))
        val q = SearchQuery("", minSize = 100, maxSize = 200, content = "Token")
        assertTrue(q.matchesSize(150))
        assertFalse(q.matchesSize(250))
        assertTrue(q.matchesContent("a token here"))
        assertFalse(q.copy(caseSensitive = true).matchesContent("a token here"))
    }

    @Test
    fun optionsSurviveTheTripThroughTheSearchState() {
        val q = SearchQuery("*.log", recursive = false, caseSensitive = true, minSize = 10, content = "error")
        val back = SearchQuery.decode(q.encode())
        assertEquals(q, back)
        assertFalse(back.recursive)
    }
}
