/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Extracts partitions of a full OTA `payload.bin` to `<name>.img` files (as MT's payload dumper;
 * user report: OM listed them but could not extract them).
 *
 * The payload is read once, front to back, so it also works straight from inside the OTA zip:
 * the operations of the chosen partitions are sorted by where their data lies, each blob is read,
 * unpacked (raw, xz, bz2, zstd) and written at its blocks. Incremental OTAs patch an old partition
 * and cannot be extracted on their own (MT cannot either).
 */
object PayloadExtractor {

    const val TYPE_REPLACE = 0
    const val TYPE_REPLACE_BZ = 1
    const val TYPE_ZERO = 6
    const val TYPE_DISCARD = 7
    const val TYPE_REPLACE_XZ = 8
    const val TYPE_ZSTD = 14

    private val SUPPORTED = setOf(TYPE_REPLACE, TYPE_REPLACE_BZ, TYPE_ZERO, TYPE_DISCARD, TYPE_REPLACE_XZ, TYPE_ZSTD)

    /** A blob larger than this is not something a phone's OTA writes per operation. */
    private const val MAX_BLOB = 256L * 1024 * 1024

    class UnsupportedPayloadException(message: String) : IOException(message)

    /** Partitions that only use operations this extractor can do (not incremental ones). */
    fun extractable(manifest: PayloadManifest): List<PayloadManifest.Partition> =
        manifest.partitions.filter { p ->
            p.operationList.isNotEmpty() && p.operationList.all { it.type in SUPPORTED }
        }

    /**
     * Writes each partition in [names] to `[outDir]/<name>.img`. [input] is the payload from its
     * first byte. [onProgress] gets the bytes of blobs read so far and their total.
     */
    @Throws(IOException::class)
    fun extract(
        input: InputStream,
        manifest: PayloadManifest,
        names: Set<String>,
        outDir: File,
        decompress: (type: Int, blob: ByteArray, expected: Long) -> ByteArray = ::decompressWithLibarchive,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted }
    ): List<File> {
        val chosen = manifest.partitions.filter { it.name in names }
        for (p in chosen) {
            val bad = p.operationList.firstOrNull { it.type !in SUPPORTED }
            if (bad != null || p.operationList.isEmpty()) {
                throw UnsupportedPayloadException(
                    "${p.name}: incremental OTA (operation type ${bad?.type}), it patches the old partition"
                )
            }
        }
        if (!outDir.isDirectory && !outDir.mkdirs()) throw IOException("Cannot create $outDir")
        val blockSize = manifest.blockSize.toLong()
        val files = LinkedHashMap<String, RandomAccessFile>()
        val outputs = ArrayList<File>()
        try {
            for (p in chosen) {
                val file = File(outDir, p.name.replace('/', '_') + ".img")
                val raf = RandomAccessFile(file, "rw")
                raf.setLength(0)
                // Sparse where nothing is written (ZERO/DISCARD blocks read back as zeros).
                raf.setLength(p.size)
                files[p.name] = raf
                outputs += file
            }
            val ops = chosen.flatMap { p -> p.operationList.map { p.name to it } }
            val withData = ops.filter { it.second.dataLength > 0 }.sortedBy { it.second.dataOffset }
            val total = withData.sumOf { it.second.dataLength }
            var done = 0L
            skipFully(input, manifest.dataStart)
            var position = 0L
            for ((name, op) in withData) {
                if (isCancelled()) throw InterruptedIOException()
                if (op.dataOffset < position) {
                    throw IOException("Overlapping data in the payload at ${op.dataOffset}")
                }
                if (op.dataLength > MAX_BLOB) throw IOException("Blob too large: ${op.dataLength}")
                skipFully(input, op.dataOffset - position)
                val blob = ByteArray(op.dataLength.toInt())
                readFully(input, blob)
                position = op.dataOffset + op.dataLength
                val expected = extentBytes(op, blockSize)
                val data = if (op.type == TYPE_REPLACE) blob else decompress(op.type, blob, expected)
                writeExtents(files.getValue(name), op, blockSize, data)
                done += op.dataLength
                onProgress(done, total)
            }
            return outputs
        } finally {
            files.values.forEach { runCatching { it.close() } }
        }
    }

    private fun extentBytes(op: PayloadManifest.Operation, blockSize: Long): Long {
        var sum = 0L
        var i = 0
        while (i + 1 < op.dstExtents.size) {
            sum += op.dstExtents[i + 1] * blockSize
            i += 2
        }
        return sum
    }

    private fun writeExtents(raf: RandomAccessFile, op: PayloadManifest.Operation, blockSize: Long, data: ByteArray) {
        var from = 0
        var i = 0
        while (i + 1 < op.dstExtents.size && from < data.size) {
            val length = minOf((op.dstExtents[i + 1] * blockSize), (data.size - from).toLong()).toInt()
            raf.seek(op.dstExtents[i] * blockSize)
            raf.write(data, from, length)
            from += length
            i += 2
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        val scratch = ByteArray(64 * 1024)
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            // Some streams (zip entries) skip 0: read and drop instead.
            val n = input.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("Payload ended early")
            left -= n
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var off = 0
        while (off < buffer.size) {
            val n = input.read(buffer, off, buffer.size - off)
            if (n < 0) throw EOFException("Payload ended early")
            off += n
        }
    }

    /** xz, bzip2 and zstd blobs unpacked by libarchive's raw format (no extra library). */
    fun decompressWithLibarchive(type: Int, blob: ByteArray, expected: Long): ByteArray {
        val archive = me.zhanghai.android.libarchive.Archive.readNew()
        try {
            me.zhanghai.android.libarchive.Archive.readSupportFilterAll(archive)
            me.zhanghai.android.libarchive.Archive.readSupportFormatRaw(archive)
            val input = ByteBuffer.allocateDirect(blob.size)
            input.put(blob)
            input.flip()
            me.zhanghai.android.libarchive.Archive.readOpenMemory(archive, input)
            me.zhanghai.android.libarchive.Archive.readNextHeader(archive)
            val out = ByteArrayOutputStream(expected.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            val chunk = ByteBuffer.allocateDirect(256 * 1024)
            val bytes = ByteArray(chunk.capacity())
            while (true) {
                chunk.clear()
                me.zhanghai.android.libarchive.Archive.readData(archive, chunk)
                chunk.flip()
                val n = chunk.remaining()
                if (n == 0) break
                chunk.get(bytes, 0, n)
                out.write(bytes, 0, n)
            }
            // Keep the input buffer alive until libarchive is done with it.
            input.clear()
            return out.toByteArray()
        } catch (e: me.zhanghai.android.libarchive.ArchiveException) {
            throw IOException("Cannot unpack a type $type block: ${e.message}", e)
        } finally {
            me.zhanghai.android.libarchive.Archive.readFree(archive)
        }
    }
}
