/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist.textsearch

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileTextSearchEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testSearchTextSimpleAndCaseInsensitive() = runBlocking {
        val root = tempFolder.newFolder("text_search_test")
        val fileA = File(root, "fileA.txt").apply { writeText("val fooBar = 1\nval FOOBAR = 2\n") }
        val fileB = File(root, "fileB.kt").apply { writeText("fun test() {\n    println(fooBar)\n}\n") }

        // Case-insensitive search
        val resultCI = FileTextSearchEngine.searchText(
            rootDir = root,
            query = "foobar",
            caseSensitive = false
        )
        assertEquals(2, resultCI.fileMatches.size)
        assertEquals(3, resultCI.totalMatches)

        // Case-sensitive search
        val resultCS = FileTextSearchEngine.searchText(
            rootDir = root,
            query = "FOOBAR",
            caseSensitive = true
        )
        assertEquals(1, resultCS.fileMatches.size)
        assertEquals(1, resultCS.totalMatches)
        assertEquals(fileA.name, resultCS.fileMatches[0].fileName)
    }

    @Test
    fun testSearchWholeWord() = runBlocking {
        val root = tempFolder.newFolder("whole_word_test")
        File(root, "test.txt").apply { writeText("foo foobar barfoo foo") }

        val result = FileTextSearchEngine.searchText(
            rootDir = root,
            query = "foo",
            wholeWord = true
        )
        assertEquals(1, result.fileMatches.size)
        assertEquals(2, result.totalMatches)
    }

    @Test
    fun testSearchRegex() = runBlocking {
        val root = tempFolder.newFolder("regex_test")
        File(root, "data.json").apply { writeText("""{"key_001": 10, "key_002": 20, "other": 30}""") }

        val result = FileTextSearchEngine.searchText(
            rootDir = root,
            query = "key_\\d+",
            isRegex = true
        )
        assertEquals(1, result.fileMatches.size)
        assertEquals(2, result.totalMatches)
    }

    @Test
    fun testReplaceSelectedFilesOnly() = runBlocking {
        val root = tempFolder.newFolder("replace_selected_test")
        val file1 = File(root, "target1.txt").apply { writeText("replace_me and replace_me again") }
        val file2 = File(root, "target2.txt").apply { writeText("replace_me here too") }

        val searchResult = FileTextSearchEngine.searchText(root, "replace_me")
        assertEquals(2, searchResult.fileMatches.size)

        // Mark only file1 as selected ("algunas palabras")
        for (fm in searchResult.fileMatches) {
            fm.isFileSelected = (fm.file == file1)
        }

        val replaceResult = FileTextSearchEngine.replaceTextInFiles(
            fileMatches = searchResult.fileMatches,
            query = "replace_me",
            replacement = "done",
            selectedFilesOnly = true
        )

        assertEquals(1, replaceResult.filesModified)
        assertEquals(2, replaceResult.totalReplacements)
        assertEquals("done and done again", file1.readText())
        assertEquals("replace_me here too", file2.readText()) // file2 is untouched
    }

    @Test
    fun testReplaceAllFilesWithSpecialCharacters() = runBlocking {
        val root = tempFolder.newFolder("replace_all_test")
        val file1 = File(root, "a.xml").apply { writeText("<price>OLD_VALUE</price>") }
        val file2 = File(root, "b.xml").apply { writeText("<cost>OLD_VALUE</cost>") }

        val searchResult = FileTextSearchEngine.searchText(root, "OLD_VALUE")
        assertEquals(2, searchResult.fileMatches.size)

        // Replace all ("todas las palabras") with special symbols: $99.99 and backslashes \
        val replaceResult = FileTextSearchEngine.replaceTextInFiles(
            fileMatches = searchResult.fileMatches,
            query = "OLD_VALUE",
            replacement = "\$99.99\\usd",
            selectedFilesOnly = false
        )

        assertEquals(2, replaceResult.filesModified)
        assertEquals(2, replaceResult.totalReplacements)
        assertEquals("<price>\$99.99\\usd</price>", file1.readText())
        assertEquals("<cost>\$99.99\\usd</cost>", file2.readText())
    }
}
