/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.dex

import org.junit.Assert.assertEquals
import org.junit.Test

class DexPackageTreeTest {

    // (package, class) pairs, as a DEX has them.
    private val classes = listOf(
        "com.google.android.material" to "Chip",
        "com.google.android.material" to "Slider",
        "com.google.android.gms" to "Api",
        "android.app" to "Activity",
        "android.app.job" to "JobServiceEngine",
        "android" to "R",
        "" to "Default",
        "kotlin" to "Unit"
    )
    private val tree = DexPackageTree(classes) { it.first }

    @Test
    fun theRootShowsTopFoldersAndDefaultPackageClasses() {
        assertEquals(listOf("android", "com.google.android", "kotlin"), tree.folders("").map { it.label })
        assertEquals(listOf("Default"), tree.classes("").map { it.second })
    }

    @Test
    fun chainsWithoutClassesAreJoined() {
        // com -> google -> android have no classes and one child each: shown as one folder.
        val com = tree.folders("").first { it.label == "com.google.android" }
        assertEquals("com.google.android", com.fullName)
        assertEquals(3, com.classCount)
        assertEquals(listOf("gms", "material"), tree.folders("com.google.android").map { it.label })
        // Back from it goes straight to the root.
        assertEquals("", tree.parentShown("com.google.android"))
    }

    @Test
    fun aPackageWithClassesKeepsItsLevel() {
        assertEquals(listOf("app"), tree.folders("android").map { it.label })
        assertEquals(listOf("R"), tree.classes("android").map { it.second })
        assertEquals(listOf("job"), tree.folders("android.app").map { it.label })
        assertEquals(listOf("Activity"), tree.classes("android.app").map { it.second })
        assertEquals("android", tree.parentShown("android.app"))
        assertEquals(3, tree.folders("").first { it.label == "android" }.classCount)
    }
}
