/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The side bar fits without scrolling: tools in a grid, About and Exit pinned at the bottom. */
class DrawerLayoutTest {

    private val main = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
    private val layout = File(main, "res/layout/file_list_activity.xml").readText()
    private val activity =
        File(main, "java/me/zhanghai/android/files/filelist/FileListActivity.kt").readText()

    @Test
    fun toolsAreAGridOfFourAcross() {
        val grid = layout.substringAfter("@+id/drawerToolsGrid").substringBefore("</GridLayout>")
        assertTrue(grid.contains("android:columnCount=\"4\""))
        for (id in listOf(
            "drawerItemApktool", "drawerItemInstalledApps", "drawerItemRunningApps",
            "drawerItemTextEditor", "drawerItemTerminal", "drawerItemConnections",
            "drawerItemRecycleBin"
        )) {
            assertTrue("$id is a tile of the tools grid", grid.contains("@+id/$id\""))
        }
    }

    @Test
    fun aboutAndExitArePinnedBelowTheScrollingList() {
        val scroll = layout.substringAfter("@+id/drawerScrollView").substringBefore("</ScrollView>")
        val footer = layout.substringAfter("@+id/drawerFooter\"")
        for (id in listOf("drawerItemAbout", "drawerItemExit")) {
            assertFalse("$id no longer scrolls away", scroll.contains("@+id/$id\""))
            assertTrue("$id is in the pinned footer", footer.contains("@+id/$id\""))
        }
        // The footer, not the list, keeps clear of the navigation bar.
        assertTrue(activity.contains("R.id.drawerFooter)?.updatePadding("))
    }

    @Test
    fun settingsIsOnlyTheHeaderGear() {
        assertFalse(layout.contains("@+id/drawerItemSettings"))
        assertTrue(layout.contains("@+id/btnDrawerSettings"))
        assertFalse(activity.contains("R.id.drawerItemSettings"))
    }
}
