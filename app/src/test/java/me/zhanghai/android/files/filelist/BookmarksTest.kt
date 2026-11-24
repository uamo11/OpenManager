/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import me.zhanghai.android.files.navigation.BookmarkDirectories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookmarksTest {

    @Test
    fun positionAwareSwipeOpensTheSideItStartedOn() {
        fun target(x: Float, aware: Boolean = true, both: Boolean = true, active: Int = 1, rtl: Boolean = false) =
            BookmarkSwipe.targetPanel(aware, both, active, x, 1080f, rtl)
        assertEquals(0, target(100f))
        assertEquals(1, target(900f, active = 0))
        // Right to left: the first panel is on the right.
        assertEquals(1, target(100f, rtl = true, active = 0))
        // Off, or a single panel on screen: always the active one.
        assertEquals(1, target(100f, aware = false))
        assertEquals(1, target(100f, both = false))
    }

    @Test
    fun newBookmarksGoAtTheEndUnlessAskedForTheTop() {
        assertEquals(listOf("a", "b", "new"), BookmarkDirectories.added(listOf("a", "b"), "new", atTop = false))
        assertEquals(listOf("new", "a", "b"), BookmarkDirectories.added(listOf("a", "b"), "new", atTop = true))
    }

    @Test
    fun bookmarksCanBeOpenedFromTheBottomBarTheMenuAndTheDrawer() {
        val res = File("src/main/res").takeIf { it.isDirectory } ?: File("app/src/main/res")
        val menu = File(res, "menu/mt_overflow_menu.xml").readText()
        assertTrue("⋮ menu has Bookmarks", menu.contains("@+id/mt_menu_bookmarks"))
        val activity = File(res, "layout/file_list_activity.xml").readText()
        assertTrue("drawer has a bookmarks section", activity.contains("@+id/layoutDrawerBookmarks"))
        // No setting may promise groups that do not exist.
        for (dir in res.listFiles { f -> f.name.startsWith("values") }.orEmpty()) {
            for (file in dir.listFiles { f -> f.name.startsWith("strings") }.orEmpty()) {
                val line = file.readLines().firstOrNull { "settings_show_bookmarks_sidebar_summary" in it } ?: continue
                assertTrue("${file.path} still mentions bookmark groups: $line",
                    listOf("group", "grupo", "分组", "групп").none { line.contains(it, ignoreCase = true) })
            }
        }
    }
}
