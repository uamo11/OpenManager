/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.hex

import java.io.Closeable
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * The bytes of the file as they are on disk. The editor never loads a large file whole: it reads
 * the pages it shows, so a file of any size opens at once and uses a few megabytes.
 */
interface HexSource : Closeable {
    val size: Long

    /** Copies up to [length] bytes at [position] into [dst]; returns how many were copied. */
    fun read(position: Long, dst: ByteArray, offset: Int, length: Int): Int

    override fun close() {}
}

class ByteArrayHexSource(private val data: ByteArray) : HexSource {
    override val size: Long get() = data.size.toLong()

    override fun read(position: Long, dst: ByteArray, offset: Int, length: Int): Int {
        if (position < 0 || position >= data.size || length <= 0) return 0
        val count = minOf(length.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), dst, offset, count)
        return count
    }
}

/**
 * Reads the file through a seekable channel, keeping the last [maxPages] pages of [PAGE_SIZE] bytes
 * (2 MB) in memory. [readAt] fills the buffer from a position (returns -1 at the end of the file).
 */
class ChannelHexSource(
    override val size: Long,
    private val readAt: (position: Long, buffer: ByteBuffer) -> Int,
    private val closeChannel: () -> Unit,
    private val maxPages: Int = 32
) : HexSource {

    private val pages = object : LinkedHashMap<Long, ByteArray>(maxPages, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean =
            size > maxPages
    }

    @Synchronized
    override fun read(position: Long, dst: ByteArray, offset: Int, length: Int): Int {
        if (position < 0 || position >= size || length <= 0) return 0
        val total = minOf(length.toLong(), size - position).toInt()
        var copied = 0
        while (copied < total) {
            val pos = position + copied
            val pageIndex = pos / PAGE_SIZE
            val page = page(pageIndex)
            val inPage = (pos - pageIndex * PAGE_SIZE).toInt()
            if (inPage >= page.size) break
            val count = minOf(total - copied, page.size - inPage)
            System.arraycopy(page, inPage, dst, offset + copied, count)
            copied += count
        }
        return copied
    }

    private fun page(index: Long): ByteArray {
        pages[index]?.let { return it }
        val start = index * PAGE_SIZE
        val length = minOf(PAGE_SIZE.toLong(), size - start).toInt()
        val bytes = ByteArray(length)
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            if (readAt(start + buffer.position(), buffer) < 0) break
        }
        if (buffer.position() < length) {
            throw IOException("Short read at $start: ${buffer.position()} of $length bytes")
        }
        pages[index] = bytes
        return bytes
    }

    @Synchronized
    override fun close() {
        pages.clear()
        closeChannel()
    }

    companion object {
        const val PAGE_SIZE = 64 * 1024

        /** A channel of the app's file providers (local, root, SFTP, SMB…). */
        fun of(channel: java8.nio.channels.SeekableByteChannel, size: Long) = ChannelHexSource(
            size,
            { position, buffer -> channel.position(position); channel.read(buffer) },
            { channel.close() }
        )

        /** A local file, such as the rewritten copy waiting in the cache. */
        fun of(file: java.io.File): ChannelHexSource {
            val raf = RandomAccessFile(file, "r")
            val channel = raf.channel
            return ChannelHexSource(raf.length(), { position, buffer -> channel.read(buffer, position) }, { raf.close() })
        }
    }
}
