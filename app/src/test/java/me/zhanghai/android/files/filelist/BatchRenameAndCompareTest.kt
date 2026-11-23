/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import me.zhanghai.android.files.filelist.BatchRenameEngine.LetterCase
import me.zhanghai.android.files.filelist.BatchRenameEngine.Problem
import me.zhanghai.android.files.filelist.BatchRenameEngine.Rule
import me.zhanghai.android.files.filelist.FileCompareEngine.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.random.Random

class BatchRenameAndCompareTest {

    private fun newNames(old: List<String>, rule: Rule, others: Set<String> = emptySet()) =
        BatchRenameEngine.plan(old, rule, otherNamesInFolder = others).map { it.newName }

    @Test
    fun templateCounterAndExtension() {
        val old = listOf("IMG_001.jpg", "IMG_002.jpg", "notes.txt")
        assertEquals(
            listOf("Viaje 01.jpg", "Viaje 02.jpg", "Viaje 03.txt"),
            newNames(old, Rule(template = "Viaje {n}", padding = 2))
        )
        // Default rule changes nothing.
        assertEquals(old, newNames(old, Rule()))
        // {ext} in the template takes over the extension.
        assertEquals(listOf("IMG_001_jpg", "IMG_002_jpg", "notes_txt"), newNames(old, Rule(template = "{name}_{ext}")))
        assertEquals(listOf("5-IMG_001.jpg", "15-IMG_002.jpg", "25-notes.txt"), newNames(old, Rule(template = "{n}-{name}", start = 5, step = 10)))
    }

    @Test
    fun findReplaceRegexAndCase() {
        val old = listOf("IMG_001.JPG", "img_002.jpg")
        assertEquals(listOf("photo_001.JPG", "photo_002.jpg"), newNames(old, Rule(find = "img", replace = "photo", ignoreCase = true)))
        assertEquals(listOf("IMG_001.JPG", "photo_002.jpg"), newNames(old, Rule(find = "img", replace = "photo")))
        assertEquals(listOf("001-IMG.JPG", "002-img.jpg"), newNames(old, Rule(find = "(\\w+)_(\\d+)", replace = "$2-$1", regex = true)))
        assertEquals(listOf("img_001.JPG", "img_002.jpg"), newNames(old, Rule(letterCase = LetterCase.LOWER)))
        assertEquals(listOf("Mi Foto.png"), newNames(listOf("mI fOTO.png"), Rule(letterCase = LetterCase.TITLE)))
        // Hidden files keep their whole name as the base.
        assertEquals(listOf(".BASHRC"), newNames(listOf(".bashrc"), Rule(letterCase = LetterCase.UPPER)))
    }

    @Test
    fun conflictsAreReportedBeforeRenaming() {
        val dup = BatchRenameEngine.plan(listOf("a.txt", "b.txt"), Rule(template = "same"))
        assertTrue(dup.all { it.problem == Problem.DUPLICATE })
        val exists = BatchRenameEngine.plan(listOf("a.txt"), Rule(template = "keep"), otherNamesInFolder = setOf("KEEP.txt"))
        assertEquals(Problem.EXISTS, exists.single().problem)
        // Swapping names inside the selection is fine: both are being renamed.
        val swap = BatchRenameEngine.plan(listOf("1.jpg", "2.jpg"), Rule(template = "{n}", start = 2))
        assertEquals(listOf("2.jpg", "3.jpg"), swap.map { it.newName })
        assertTrue(swap.all { it.problem == null })
        assertEquals(Problem.INVALID_CHARACTER, BatchRenameEngine.plan(listOf("a.txt"), Rule(template = "x/y")).single().problem)
        assertEquals(Problem.EMPTY, BatchRenameEngine.plan(listOf("a"), Rule(find = "a", replace = "")).single().problem)
        assertTrue(BatchRenameEngine.plan(listOf("a.txt"), Rule(find = "(", regex = true)).all { it.problem == Problem.BAD_REGEX })
    }

    private fun apply(a: List<String>, b: List<String>, ops: List<Kind>): List<String> {
        val out = ArrayList<String>()
        var i = 0
        var j = 0
        for (op in ops) when (op) {
            Kind.SAME -> { assertEquals(a[i], b[j]); out += a[i]; i++; j++ }
            Kind.REMOVED -> i++
            Kind.ADDED -> { out += b[j]; j++ }
            Kind.GAP -> Unit
        }
        assertEquals(a.size, i)
        return out
    }

    private fun lcs(a: List<String>, b: List<String>): Int {
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        return dp[0][0]
    }

    @Test
    fun lineDiffIsCorrectAndMinimal() {
        val random = Random(7)
        repeat(300) {
            val a = List(random.nextInt(0, 25)) { "l${random.nextInt(6)}" }
            val b = List(random.nextInt(0, 25)) { "l${random.nextInt(6)}" }
            val ops = FileCompareEngine.diff(a, b, maxEdits = 1000)
            assertNotNull(ops)
            assertEquals(b, apply(a, b, ops!!))
            // Myers finds a shortest edit script: as many kept lines as the longest common subsequence.
            assertEquals(lcs(a, b), ops.count { it == Kind.SAME })
        }
    }

    @Test
    fun textResultCountsAndFoldsUnchangedLines() {
        val a = (1..100).joinToString("\n") { "line $it" } + "\n"
        val b = a.replace("line 50\n", "line fifty\n").replace("line 90\n", "") + "extra\n"
        val result = FileCompareEngine.compareText(a.toByteArray(), b.toByteArray()) as FileCompareEngine.Result.Text
        assertEquals(2, result.added)
        assertEquals(2, result.removed)
        assertFalse(result.identical)
        assertTrue(result.rows.any { it.kind == Kind.GAP })
        assertTrue(result.rows.size < 40)
        val removed = result.rows.first { it.kind == Kind.REMOVED }
        assertEquals("line 50", removed.text)
        assertEquals(50, removed.oldNo)

        val crlf = FileCompareEngine.compareText("a\nb\n".toByteArray(), "a\r\nb\r\n".toByteArray()) as FileCompareEngine.Result.Text
        assertTrue(crlf.onlyLineEndings)
        val same = FileCompareEngine.compareText("a\n".toByteArray(), "a\n".toByteArray()) as FileCompareEngine.Result.Text
        assertTrue(same.identical)
        assertTrue(same.rows.isEmpty())
    }

    @Test
    fun hugeDifferencesAreSummarised() {
        val a = List(3000) { "a$it" }
        val b = List(3000) { "b$it" }
        assertNull(FileCompareEngine.diff(a, b))
        // A small change inside a big file is cheap thanks to prefix/suffix trimming.
        val big = List(200_000) { "row $it" }
        val changed = big.toMutableList().also { it[123_456] = "changed" }
        val ops = FileCompareEngine.diff(big, changed)!!
        assertEquals(1, ops.count { it == Kind.ADDED })
    }

    @Test
    fun binaryComparison() {
        val a = ByteArray(200_000) { (it % 251).toByte() }
        val b = a.copyOf().also { it[150_000] = 9; it[150_001] = 9 }
        val r = FileCompareEngine.compareBinary({ ByteArrayInputStream(a) }, { ByteArrayInputStream(b) })
        assertFalse(r.identical)
        assertEquals(150_000L, r.firstDifference)
        assertEquals(2L, r.differingBytes)
        val shorter = FileCompareEngine.compareBinary({ ByteArrayInputStream(a) }, { ByteArrayInputStream(a.copyOf(1000)) })
        assertEquals(1000L, shorter.firstDifference)
        assertEquals(1000L, shorter.sizeB)
        val same = FileCompareEngine.compareBinary({ ByteArrayInputStream(a) }, { ByteArrayInputStream(a.copyOf()) })
        assertTrue(same.identical)
        assertEquals(same.sha256A, same.sha256B)
        assertTrue(FileCompareEngine.looksBinary(byteArrayOf(1, 0, 2)))
        assertFalse(FileCompareEngine.looksBinary("hola".toByteArray()))
    }
}
