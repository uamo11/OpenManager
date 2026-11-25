/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.hex

import java.io.InputStream
import java.nio.charset.Charset

/**
 * The document of the HEX editor: a piece table over the file on disk. Edits never copy the file;
 * they only change the list of pieces (runs of the original file, of typed bytes or of one repeated
 * byte), so overwriting, inserting, deleting, filling and undoing cost the same in a 2 GB file as in
 * a 2 KB one.
 */
class HexBuffer(val source: HexSource) {

    constructor(data: ByteArray) : this(ByteArrayHexSource(data))

    private class Piece(val kind: Int, val start: Long, val length: Long, val fill: Byte = 0) {
        fun sub(from: Long, count: Long) = Piece(kind, if (kind == FILL) 0L else start + from, count, fill)
    }

    private class Edit(val before: List<Piece>, val after: List<Piece>, val offset: Long)

    /** Bytes typed or pasted by the user; pieces of kind [ADDED] point into it. */
    private var added = ByteArray(256)
    private var addedSize = 0

    /** Pieces with their logical starts, swapped as a whole so a search on another thread reads a consistent copy. */
    private class State(val pieces: List<Piece>) {
        val starts = LongArray(pieces.size)
        val size: Long

        init {
            var total = 0L
            for (i in pieces.indices) {
                starts[i] = total
                total += pieces[i].length
            }
            size = total
        }

        fun pieceAt(offset: Long): Int {
            var lo = 0
            var hi = starts.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (starts[mid] <= offset) lo = mid else hi = mid - 1
            }
            return lo
        }
    }

    @Volatile
    private var state = State(emptyList())
    private val pieces: List<Piece> get() = state.pieces
    private val starts: LongArray get() = state.starts
    private var savedPieces: List<Piece>

    val size: Long get() = state.size

    private val undoStack = ArrayList<Edit>()
    private val redoStack = ArrayList<Edit>()

    init {
        setPieces(if (source.size > 0) listOf(Piece(SOURCE, 0, source.size)) else emptyList())
        savedPieces = pieces
    }

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Differs from the file on disk. Undoing back to the saved state makes it clean again. */
    val isModified: Boolean get() = pieces !== savedPieces

    private fun setPieces(newPieces: List<Piece>) {
        state = State(newPieces)
    }

    fun read(position: Long, dst: ByteArray, offset: Int, length: Int): Int {
        val st = state
        if (position < 0 || position >= st.size || length <= 0) return 0
        val total = minOf(length.toLong(), st.size - position).toInt()
        var copied = 0
        var index = st.pieceAt(position)
        while (copied < total && index < st.pieces.size) {
            val piece = st.pieces[index]
            val inPiece = position + copied - st.starts[index]
            val count = minOf((total - copied).toLong(), piece.length - inPiece).toInt()
            when (piece.kind) {
                SOURCE -> {
                    var done = 0
                    while (done < count) {
                        // A file changed or gone behind our back reads as missing bytes, not a crash.
                        val n = try {
                            source.read(piece.start + inPiece + done, dst, offset + copied + done, count - done)
                        } catch (e: java.io.IOException) {
                            -1
                        }
                        if (n <= 0) break
                        done += n
                    }
                    if (done < count) return copied + done
                }
                ADDED -> System.arraycopy(added, (piece.start + inPiece).toInt(), dst, offset + copied, count)
                else -> java.util.Arrays.fill(dst, offset + copied, offset + copied + count, piece.fill)
            }
            copied += count
            index++
        }
        return copied
    }

    fun getByte(offset: Long): Byte {
        if (offset < 0 || offset >= size) return 0
        val one = ByteArray(1)
        read(offset, one, 0, 1)
        return one[0]
    }

    fun getBytes(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset >= size || length <= 0) return ByteArray(0)
        val bytes = ByteArray(minOf(length.toLong(), size - offset).toInt())
        val n = read(offset, bytes, 0, bytes.size)
        return if (n == bytes.size) bytes else bytes.copyOf(n)
    }

    /** The byte at [offset] was typed, pasted or filled since the last save. */
    fun isModifiedAt(offset: Long): Boolean {
        val st = state
        if (offset < 0 || offset >= st.size || st.pieces.isEmpty()) return false
        return st.pieces[st.pieceAt(offset)].kind != SOURCE
    }

    // ---- editing -----------------------------------------------------------------------------

    private fun appendAdded(bytes: ByteArray): Piece {
        val needed = addedSize.toLong() + bytes.size
        require(needed <= Int.MAX_VALUE - 8) { "Too many typed bytes" }
        if (needed > added.size) {
            added = added.copyOf(maxOf(needed, added.size * 2L).coerceAtMost(Int.MAX_VALUE - 8L).toInt())
        }
        System.arraycopy(bytes, 0, added, addedSize, bytes.size)
        val piece = Piece(ADDED, addedSize.toLong(), bytes.size.toLong())
        addedSize += bytes.size
        return piece
    }

    /** The pieces that make up [from, to). */
    private fun slice(from: Long, to: Long, out: MutableList<Piece>) {
        if (from >= to) return
        var index = state.pieceAt(from)
        while (index < pieces.size && starts[index] < to) {
            val piece = pieces[index]
            val a = maxOf(from, starts[index])
            val b = minOf(to, starts[index] + piece.length)
            if (b > a) {
                out += if (a == starts[index] && b - a == piece.length) piece else piece.sub(a - starts[index], b - a)
            }
            index++
        }
    }

    private fun splice(offset: Long, deleteCount: Long, insert: Piece?) {
        val out = ArrayList<Piece>(pieces.size + 2)
        slice(0, offset, out)
        if (insert != null && insert.length > 0) out += insert
        slice(offset + deleteCount, size, out)
        commit(out, offset)
    }

    private fun commit(newPieces: List<Piece>, offset: Long) {
        undoStack += Edit(pieces, newPieces, offset)
        redoStack.clear()
        setPieces(newPieces)
    }

    /** Overwrites from [offset]; bytes past the end of the file are dropped. */
    fun modifyBytes(offset: Long, newBytes: ByteArray) {
        if (offset < 0 || offset >= size || newBytes.isEmpty()) return
        val count = minOf(newBytes.size.toLong(), size - offset).toInt()
        val applied = if (count == newBytes.size) newBytes else newBytes.copyOf(count)
        splice(offset, count.toLong(), appendAdded(applied))
    }

    fun modifyByte(offset: Long, b: Byte) = modifyBytes(offset, byteArrayOf(b))

    fun insertBytes(offset: Long, bytes: ByteArray) {
        if (offset < 0 || offset > size || bytes.isEmpty()) return
        splice(offset, 0, appendAdded(bytes))
    }

    /** Inserts [count] copies of [fillByte] without allocating them. */
    fun insertFill(offset: Long, count: Long, fillByte: Byte) {
        if (offset < 0 || offset > size || count <= 0) return
        splice(offset, 0, Piece(FILL, 0, count, fillByte))
    }

    fun deleteBytes(offset: Long, count: Long) {
        if (offset < 0 || offset >= size || count <= 0) return
        splice(offset, minOf(count, size - offset), null)
    }

    fun fillRange(startOffset: Long, endOffset: Long, fillByte: Byte) {
        if (startOffset < 0 || startOffset >= size) return
        val end = endOffset.coerceIn(startOffset, size - 1)
        val count = end - startOffset + 1
        splice(startOffset, count, Piece(FILL, 0, count, fillByte))
    }

    fun replace(offset: Long, patternLength: Int, replacement: ByteArray) {
        if (offset < 0 || offset >= size) return
        splice(offset, minOf(patternLength.toLong(), size - offset), replacement.takeIf { it.isNotEmpty() }?.let { appendAdded(it) })
    }

    /** Replaces every match (not overlapping) as one edit, so one undo restores them all. */
    fun replaceAllHex(pattern: ByteArray, replacement: ByteArray, ignoreCase: Boolean = false): Int {
        val matches = findAll(pattern, ignoreCase)
        replaceAt(matches, pattern.size, replacement)
        return matches.size
    }

    /** Offsets of every match, not overlapping. Safe to call off the main thread. */
    fun findAll(pattern: ByteArray, ignoreCase: Boolean, isActive: () -> Boolean = { true }): List<Long> {
        if (pattern.isEmpty() || size < pattern.size) return emptyList()
        val matches = ArrayList<Long>()
        scanForward(pattern, 0, ignoreCase, isActive) { matches += it; true }
        return if (isActive()) matches else emptyList()
    }

    /** Replaces [patternLength] bytes at each of [matches] (ascending) with [replacement], as one edit. */
    fun replaceAt(matches: List<Long>, patternLength: Int, replacement: ByteArray) {
        if (matches.isEmpty()) return
        val rep = if (replacement.isEmpty()) null else appendAdded(replacement)
        val out = ArrayList<Piece>(pieces.size + matches.size * 2 + 1)
        var cursor = 0L
        for (m in matches) {
            slice(cursor, m, out)
            if (rep != null) out += rep
            cursor = m + patternLength
        }
        slice(cursor, size, out)
        commit(out, matches.first())
    }

    fun undo(): Long {
        if (undoStack.isEmpty()) return -1
        val edit = undoStack.removeAt(undoStack.lastIndex)
        redoStack += edit
        setPieces(edit.before)
        return edit.offset.coerceAtMost(maxOf(size - 1, 0))
    }

    fun redo(): Long {
        if (redoStack.isEmpty()) return -1
        val edit = redoStack.removeAt(redoStack.lastIndex)
        undoStack += edit
        setPieces(edit.after)
        return edit.offset.coerceAtMost(maxOf(size - 1, 0))
    }

    fun markSaved() {
        undoStack.clear()
        redoStack.clear()
        savedPieces = pieces
    }

    // ---- reading it whole --------------------------------------------------------------------

    /** Only for small documents (tests, the in-memory path). */
    fun toByteArray(): ByteArray = getBytes(0, size.toInt())

    fun openInputStream(): InputStream = object : InputStream() {
        private var position = 0L
        override fun read(): Int {
            if (position >= size) return -1
            return getByte(position++).toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (position >= size) return -1
            val n = this@HexBuffer.read(position, b, off, len)
            if (n <= 0) return -1
            position += n
            return n
        }

        override fun available(): Int = (size - position).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * When the size did not change and every original byte is still where it was, saving only has
     * to write the edited runs into the file instead of rewriting all of it.
     */
    val canPatchInPlace: Boolean
        get() {
            val st = state
            if (st.size != source.size) return false
            for (i in st.pieces.indices) {
                if (st.pieces[i].kind == SOURCE && st.pieces[i].start != st.starts[i]) return false
            }
            return true
        }

    /**
     * Writes the edited runs at their offsets with [writeAt] (position, bytes, length);
     * [canPatchInPlace] must be true. Returns the bytes written.
     */
    fun patchInPlace(writeAt: (Long, ByteArray, Int) -> Unit): Long {
        check(canPatchInPlace)
        val st = state
        var written = 0L
        val chunk = ByteArray(CHUNK)
        for (i in st.pieces.indices) {
            val piece = st.pieces[i]
            if (piece.kind == SOURCE) continue
            var done = 0L
            while (done < piece.length) {
                val n = minOf(CHUNK.toLong(), piece.length - done).toInt()
                read(st.starts[i] + done, chunk, 0, n)
                writeAt(st.starts[i] + done, chunk, n)
                done += n
            }
            written += piece.length
        }
        return written
    }

    // ---- search ------------------------------------------------------------------------------

    fun searchHex(pattern: ByteArray, startOffset: Long, forward: Boolean, isActive: () -> Boolean = { true }): Long =
        search(pattern, startOffset, forward, ignoreCase = false, isActive = isActive)

    fun searchText(
        text: String,
        startOffset: Long,
        forward: Boolean,
        ignoreCase: Boolean,
        charset: Charset = Charsets.UTF_8,
        isActive: () -> Boolean = { true }
    ): Long {
        if (text.isEmpty()) return -1
        return search(text.toByteArray(charset), startOffset, forward, ignoreCase, isActive)
    }

    fun search(pattern: ByteArray, startOffset: Long, forward: Boolean, ignoreCase: Boolean, isActive: () -> Boolean = { true }): Long {
        if (pattern.isEmpty() || size < pattern.size) return -1
        var found = -1L
        if (forward) {
            scanForward(pattern, startOffset, ignoreCase, isActive) { found = it; false }
            return found
        }
        val p = if (ignoreCase) fold(pattern) else pattern
        val last = size - pattern.size
        val buf = ByteArray(CHUNK + pattern.size - 1)
        var end = startOffset.coerceIn(0, last)
        while (end >= 0) {
            if (!isActive()) return -1
            val from = maxOf(0L, end - CHUNK + 1)
            val count = (end - from + 1).toInt()
            read(from, buf, 0, count + pattern.size - 1)
            for (i in count - 1 downTo 0) {
                if (matches(buf, i, p, ignoreCase)) return from + i
            }
            end = from - 1
        }
        return -1
    }

    /** Calls [onMatch] for each match from [startOffset] on, not overlapping, while it returns true. */
    private fun scanForward(pattern: ByteArray, startOffset: Long, ignoreCase: Boolean, isActive: () -> Boolean, onMatch: (Long) -> Boolean) {
        val p = if (ignoreCase) fold(pattern) else pattern
        val last = size - pattern.size
        if (last < 0) return
        val buf = ByteArray(CHUNK + pattern.size - 1)
        var pos = startOffset.coerceIn(0, last)
        var nextAllowed = pos
        while (pos <= last) {
            if (!isActive()) return
            val candidates = minOf(CHUNK.toLong(), last - pos + 1).toInt()
            read(pos, buf, 0, candidates + pattern.size - 1)
            for (i in 0 until candidates) {
                val at = pos + i
                if (at < nextAllowed) continue
                if (matches(buf, i, p, ignoreCase)) {
                    if (!onMatch(at)) return
                    nextAllowed = at + pattern.size
                }
            }
            pos += candidates
        }
    }

    private fun matches(buf: ByteArray, at: Int, p: ByteArray, ignoreCase: Boolean): Boolean {
        for (j in p.indices) {
            val b = if (ignoreCase) fold(buf[at + j]) else buf[at + j]
            if (b != p[j]) return false
        }
        return true
    }

    companion object {
        private const val SOURCE = 0
        private const val ADDED = 1
        private const val FILL = 2
        const val CHUNK = 1024 * 1024

        private fun fold(b: Byte): Byte = if (b in 'A'.code.toByte()..'Z'.code.toByte()) (b + 32).toByte() else b
        private fun fold(bytes: ByteArray): ByteArray = ByteArray(bytes.size) { fold(bytes[it]) }
    }
}
