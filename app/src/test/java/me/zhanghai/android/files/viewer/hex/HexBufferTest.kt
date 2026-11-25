/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.hex

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HexBufferTest {

    @Test
    fun testEmptyBufferBounds() {
        val buffer = HexBuffer(ByteArray(0))
        assertEquals(0L, buffer.size)
        assertEquals(-1L, buffer.searchHex(byteArrayOf(0x01, 0x02), 0, forward = true))
        assertEquals(-1L, buffer.searchHex(byteArrayOf(0x01, 0x02), 0, forward = false))
        assertEquals(-1L, buffer.searchText("test", 0, forward = true, ignoreCase = false))
        assertEquals(-1L, buffer.searchText("test", 0, forward = false, ignoreCase = true))
        assertEquals(0, buffer.replaceAllHex(byteArrayOf(0x01), byteArrayOf(0x02)))
    }

    @Test
    fun testPatternLargerThanBuffer() {
        val buffer = HexBuffer(byteArrayOf(0x41, 0x42))
        val pattern = byteArrayOf(0x41, 0x42, 0x43, 0x44)
        assertEquals(-1L, buffer.searchHex(pattern, 0, forward = true))
        assertEquals(-1L, buffer.searchHex(pattern, 0, forward = false))
        assertEquals(-1L, buffer.searchText("ABCDEF", 0, forward = true, ignoreCase = true))
        assertEquals(-1L, buffer.searchText("ABCDEF", 0, forward = false, ignoreCase = true))
    }

    @Test
    fun testSearchHexForwardAndBackward() {
        val buffer = HexBuffer("Hello World Hello".toByteArray())
        val pattern = "Hello".toByteArray()
        assertEquals(0L, buffer.searchHex(pattern, 0, forward = true))
        assertEquals(12L, buffer.searchHex(pattern, 5, forward = true))
        assertEquals(12L, buffer.searchHex(pattern, 16, forward = false))
        assertEquals(0L, buffer.searchHex(pattern, 10, forward = false))
    }

    @Test
    fun testSearchTextCaseInsensitive() {
        val buffer = HexBuffer("OpenManager MT Suite".toByteArray())
        assertEquals(12L, buffer.searchText("mt suite", 0, forward = true, ignoreCase = true))
        assertEquals(-1L, buffer.searchText("mt suite", 0, forward = true, ignoreCase = false))
        assertEquals(-1L, buffer.searchText("notfound", 0, forward = true, ignoreCase = true))
    }

    @Test
    fun testModifyAndUndoRedo() {
        val buffer = HexBuffer(byteArrayOf(1, 2, 3, 4, 5))
        assertFalse(buffer.isModified)
        assertFalse(buffer.canUndo)

        buffer.modifyByte(2, 99.toByte())
        assertTrue(buffer.isModified)
        assertTrue(buffer.canUndo)
        assertTrue(buffer.isModifiedAt(2))
        assertFalse(buffer.isModifiedAt(1))
        assertEquals(99.toByte(), buffer.toByteArray()[2])

        assertEquals(2L, buffer.undo())
        assertEquals(3.toByte(), buffer.toByteArray()[2])
        assertFalse(buffer.isModified)
        assertTrue(buffer.canRedo)

        buffer.redo()
        assertEquals(99.toByte(), buffer.toByteArray()[2])
        assertTrue(buffer.isModified)
    }

    @Test
    fun testReplaceAllIsOneEdit() {
        val buffer = HexBuffer("foo bar foo baz foo".toByteArray())
        assertEquals(3, buffer.replaceAllHex("foo".toByteArray(), "OM".toByteArray()))
        assertEquals("OM bar OM baz OM", String(buffer.toByteArray()))
        buffer.undo()
        assertEquals("foo bar foo baz foo", String(buffer.toByteArray()))
        assertFalse(buffer.canUndo)
    }

    @Test
    fun testInsertDeleteFill() {
        val buffer = HexBuffer("abcdef".toByteArray())
        buffer.insertBytes(3, "XY".toByteArray())
        assertEquals("abcXYdef", String(buffer.toByteArray()))
        buffer.deleteBytes(1, 2)
        assertEquals("aXYdef", String(buffer.toByteArray()))
        buffer.fillRange(4, 100, '-'.code.toByte())
        assertEquals("aXYd--", String(buffer.toByteArray()))
        buffer.insertFill(0, 3, '0'.code.toByte())
        assertEquals("000aXYd--", String(buffer.toByteArray()))
        assertFalse(buffer.canPatchInPlace)
        repeat(4) { buffer.undo() }
        assertEquals("abcdef", String(buffer.toByteArray()))
        assertFalse(buffer.isModified)
    }

    /** A 3 GB file that is never allocated: offsets past Int.MAX_VALUE, edits and search work. */
    @Test
    fun testHugeSourceIsPaged() {
        val size = 3L * 1024 * 1024 * 1024
        val reads = intArrayOf(0)
        val source = ChannelHexSource(size, { position, buf ->
            reads[0]++
            var p = position
            while (buf.hasRemaining()) buf.put(patternByte(p++))
            (p - position).toInt()
        }, {})
        val buffer = HexBuffer(source)
        assertEquals(size, buffer.size)
        val far = 2_500_000_000L
        assertEquals(patternByte(far), buffer.getByte(far))

        buffer.modifyBytes(far, "OPENMANAGER".toByteArray())
        assertTrue(buffer.canPatchInPlace)
        assertEquals("OPENMANAGER", String(buffer.getBytes(far, 11)))
        // The match is found from a start a little before it, across a page boundary.
        assertEquals(far, buffer.searchText("openmanager", far - 100_000, forward = true, ignoreCase = true))
        assertEquals(far, buffer.searchText("OPENMANAGER", far + 5_000_000, forward = false, ignoreCase = false))
        assertTrue("only touched pages are read", reads[0] < 200)

        val patches = mutableListOf<Pair<Long, String>>()
        buffer.patchInPlace { position, bytes, length -> patches += position to String(bytes, 0, length) }
        assertEquals(listOf(far to "OPENMANAGER"), patches)
    }

    @Test
    fun testChannelSourceReadsAcrossPages() {
        val data = ByteArray(ChannelHexSource.PAGE_SIZE * 3 + 17) { (it * 7).toByte() }
        val source = ChannelHexSource(data.size.toLong(), { position, buf ->
            val n = minOf(buf.remaining(), data.size - position.toInt())
            buf.put(data, position.toInt(), n)
            n
        }, {}, maxPages = 2)
        val out = ByteArray(ChannelHexSource.PAGE_SIZE + 100)
        val start = ChannelHexSource.PAGE_SIZE - 50L
        assertEquals(out.size, source.read(start, out, 0, out.size))
        assertArrayEquals(data.copyOfRange(start.toInt(), start.toInt() + out.size), out)
        val tail = ByteArray(100)
        assertEquals(17, source.read(ChannelHexSource.PAGE_SIZE * 3L, tail, 0, 100))
    }

    @Test
    fun testStreamEqualsDocument() {
        val buffer = HexBuffer(ByteArray(5000) { it.toByte() })
        buffer.insertBytes(10, ByteArray(3000) { 1 })
        buffer.deleteBytes(4000, 50)
        val streamed = buffer.openInputStream().readBytes()
        assertArrayEquals(buffer.toByteArray(), streamed)
        assertEquals(7950, streamed.size)
    }

    private fun patternByte(p: Long): Byte = ((p * 31) xor (p ushr 7)).toByte()
}
