/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import java.io.InputStream
import java.security.MessageDigest

/**
 * Compares two files: a line diff (Myers' algorithm, the one `diff` and git use) for text, and
 * size, first differing byte and SHA-256 for binaries. Pure and stream-based, for any file system.
 */
object FileCompareEngine {

    /** Larger text files are compared as binaries (keeps memory bounded on a phone). */
    const val MAX_TEXT_BYTES = 8L * 1024 * 1024
    /** Above this many changed lines the diff is not worth showing line by line. */
    const val MAX_EDITS = 2000
    const val CONTEXT_LINES = 3

    enum class Kind { SAME, ADDED, REMOVED, GAP }

    data class Row(val kind: Kind, val oldNo: Int, val newNo: Int, val text: String)

    sealed class Result {
        data class Text(
            val rows: List<Row>,
            val added: Int,
            val removed: Int,
            /** Same lines, different bytes: only the line endings (CRLF/LF) or a final newline differ. */
            val onlyLineEndings: Boolean,
            val identical: Boolean
        ) : Result()

        data class TooDifferent(val oldLines: Int, val newLines: Int) : Result()

        data class Binary(
            val sizeA: Long,
            val sizeB: Long,
            val identical: Boolean,
            /** Offset of the first byte that differs, or the end of the shorter file. */
            val firstDifference: Long?,
            /** Differing bytes within the common length. */
            val differingBytes: Long,
            val sha256A: String,
            val sha256B: String
        ) : Result()
    }

    fun looksBinary(head: ByteArray, length: Int = head.size): Boolean {
        for (i in 0 until minOf(length, 8000)) if (head[i] == 0.toByte()) return true
        return false
    }

    fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = text.split('\n').map { it.removeSuffix("\r") }
        return if (text.endsWith('\n')) lines.dropLast(1) else lines
    }

    fun compareText(bytesA: ByteArray, bytesB: ByteArray): Result {
        val linesA = splitLines(String(bytesA, Charsets.UTF_8))
        val linesB = splitLines(String(bytesB, Charsets.UTF_8))
        val ops = diff(linesA, linesB, MAX_EDITS) ?: return Result.TooDifferent(linesA.size, linesB.size)
        val added = ops.count { it == Kind.ADDED }
        val removed = ops.count { it == Kind.REMOVED }
        val identicalBytes = bytesA.contentEquals(bytesB)
        return Result.Text(
            rows = rows(linesA, linesB, ops, CONTEXT_LINES),
            added = added,
            removed = removed,
            onlyLineEndings = added == 0 && removed == 0 && !identicalBytes,
            identical = identicalBytes
        )
    }

    /**
     * Edit script turning [a] into [b] as SAME/REMOVED/ADDED steps, or null if more than [maxEdits]
     * lines changed. Common prefix and suffix are skipped first, so a small change in a big file
     * costs almost nothing.
     */
    fun diff(a: List<String>, b: List<String>, maxEdits: Int = MAX_EDITS): List<Kind>? {
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix &&
            a[a.size - 1 - suffix] == b[b.size - 1 - suffix]
        ) suffix++
        val ids = HashMap<String, Int>()
        val x = IntArray(a.size - prefix - suffix) { ids.getOrPut(a[prefix + it]) { ids.size } }
        val y = IntArray(b.size - prefix - suffix) { ids.getOrPut(b[prefix + it]) { ids.size } }
        val middle = myers(x, y, maxEdits) ?: return null
        return List(prefix) { Kind.SAME } + middle + List(suffix) { Kind.SAME }
    }

    private fun myers(a: IntArray, b: IntArray, maxEdits: Int): List<Kind>? {
        val n = a.size
        val m = b.size
        if (n == 0) return if (m > maxEdits) null else List(m) { Kind.ADDED }
        if (m == 0) return if (n > maxEdits) null else List(n) { Kind.REMOVED }
        val max = n + m
        val offset = max + 1
        val v = IntArray(2 * max + 3)
        val trace = ArrayList<IntArray>()
        var found = -1
        loop@ for (d in 0..minOf(max, maxEdits)) {
            var k = -d
            while (k <= d) {
                var xx = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
                    v[offset + k + 1]
                } else {
                    v[offset + k - 1] + 1
                }
                var yy = xx - k
                while (xx < n && yy < m && a[xx] == b[yy]) { xx++; yy++ }
                v[offset + k] = xx
                if (xx >= n && yy >= m) {
                    trace += v.copyOfRange(offset - d, offset + d + 1)
                    found = d
                    break@loop
                }
                k += 2
            }
            trace += v.copyOfRange(offset - d, offset + d + 1)
        }
        if (found < 0) return null

        val out = ArrayList<Kind>(n + m)
        var xx = n
        var yy = m
        for (d in found downTo 1) {
            val prev = trace[d - 1]
            fun at(k: Int) = prev[k + d - 1]
            val k = xx - yy
            val prevK = if (k == -d || (k != d && at(k - 1) < at(k + 1))) k + 1 else k - 1
            val prevX = at(prevK)
            val prevY = prevX - prevK
            while (xx > prevX && yy > prevY) { out += Kind.SAME; xx--; yy-- }
            if (prevK == k + 1) { out += Kind.ADDED; yy-- } else { out += Kind.REMOVED; xx-- }
        }
        while (xx > 0 && yy > 0) { out += Kind.SAME; xx--; yy-- }
        out.reverse()
        return out
    }

    /** Rows to display: changes with [context] lines around them, long equal runs folded. */
    fun rows(a: List<String>, b: List<String>, ops: List<Kind>, context: Int): List<Row> {
        val full = ArrayList<Row>(ops.size)
        var i = 0
        var j = 0
        for (op in ops) {
            when (op) {
                Kind.SAME -> { full += Row(Kind.SAME, i + 1, j + 1, a[i]); i++; j++ }
                Kind.REMOVED -> { full += Row(Kind.REMOVED, i + 1, 0, a[i]); i++ }
                Kind.ADDED -> { full += Row(Kind.ADDED, 0, j + 1, b[j]); j++ }
                Kind.GAP -> Unit
            }
        }
        if (full.none { it.kind != Kind.SAME }) return emptyList()
        val keep = BooleanArray(full.size)
        full.forEachIndexed { index, row ->
            if (row.kind != Kind.SAME) {
                for (c in maxOf(0, index - context)..minOf(full.size - 1, index + context)) keep[c] = true
            }
        }
        val out = ArrayList<Row>()
        var skipped = 0
        full.forEachIndexed { index, row ->
            if (keep[index]) {
                if (skipped > 0) { out += Row(Kind.GAP, skipped, 0, ""); skipped = 0 }
                out += row
            } else {
                skipped++
            }
        }
        if (skipped > 0) out += Row(Kind.GAP, skipped, 0, "")
        return out
    }

    fun compareBinary(a: () -> InputStream, b: () -> InputStream): Result.Binary {
        val digestA = MessageDigest.getInstance("SHA-256")
        val digestB = MessageDigest.getInstance("SHA-256")
        var sizeA = 0L
        var sizeB = 0L
        var first: Long? = null
        var differing = 0L
        a().buffered(1 shl 16).use { inA ->
            b().buffered(1 shl 16).use { inB ->
                val bufA = ByteArray(1 shl 16)
                val bufB = ByteArray(1 shl 16)
                var readA = 0
                var readB = 0
                var endA = false
                var endB = false
                while (!endA || !endB) {
                    if (!endA) {
                        readA = inA.readFully(bufA)
                        if (readA <= 0) { endA = true; readA = 0 } else digestA.update(bufA, 0, readA)
                    } else readA = 0
                    if (!endB) {
                        readB = inB.readFully(bufB)
                        if (readB <= 0) { endB = true; readB = 0 } else digestB.update(bufB, 0, readB)
                    } else readB = 0
                    val common = minOf(readA, readB)
                    for (i in 0 until common) {
                        if (bufA[i] != bufB[i]) {
                            if (first == null) first = sizeA + i
                            differing++
                        }
                    }
                    if (first == null && readA != readB) first = sizeA + common
                    sizeA += readA
                    sizeB += readB
                }
            }
        }
        val hexA = digestA.digest().joinToString("") { "%02x".format(it) }
        val hexB = digestB.digest().joinToString("") { "%02x".format(it) }
        return Result.Binary(sizeA, sizeB, sizeA == sizeB && first == null, first, differing, hexA, hexB)
    }

    /** Reads until the buffer is full or the stream ends, so both files advance in step. */
    private fun InputStream.readFully(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return if (total == 0) -1 else total
    }
}
