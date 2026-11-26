/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * The table of contents of an Android OTA `payload.bin` (update_engine's "CrAU" format): the
 * partitions it writes, their sizes and how their data is stored. Only the header and the manifest
 * at the start are read (a few KB), never the gigabytes of partition data, so it works at once,
 * even for a payload still inside the OTA zip.
 */
data class PayloadManifest(
    val version: Long,
    val blockSize: Int,
    val isIncremental: Boolean,
    val partitions: List<Partition>
) {
    data class Partition(
        val name: String,
        val size: Long,
        val operations: Int,
        /** "xz", "bz2", "zstd", "raw", "diff"... the storage kinds of its data, most used first. */
        val kinds: List<String>
    )

    val totalSize: Long get() = partitions.sumOf { it.size }

    companion object {
        /** Larger manifests are not payloads a phone produces (they are a few hundred KB). */
        private const val MAX_MANIFEST = 64L * 1024 * 1024

        fun isPayload(header: ByteArray): Boolean =
            header.size >= 4 && header[0] == 'C'.code.toByte() && header[1] == 'r'.code.toByte() &&
                header[2] == 'A'.code.toByte() && header[3] == 'U'.code.toByte()

        @Throws(IOException::class)
        fun read(input: InputStream): PayloadManifest {
            val data = DataInputStream(input)
            val magic = ByteArray(4)
            data.readFully(magic)
            if (!isPayload(magic)) throw IOException("Not an OTA payload (no CrAU magic)")
            val version = data.readLong()
            val manifestSize = data.readLong()
            if (version >= 2) data.readInt() // metadata signature size
            if (manifestSize <= 0 || manifestSize > MAX_MANIFEST) {
                throw IOException("Bad manifest size $manifestSize")
            }
            val manifest = ByteArray(manifestSize.toInt())
            data.readFully(manifest)
            return parseManifest(version, manifest)
        }

        /** DeltaArchiveManifest (update_metadata.proto), only the fields shown. */
        fun parseManifest(version: Long, bytes: ByteArray): PayloadManifest {
            var blockSize = 4096
            var incremental = false
            val partitions = ArrayList<Partition>()
            val reader = ProtoReader(bytes, 0, bytes.size)
            while (reader.hasMore()) {
                val (field, wire) = reader.tag()
                when {
                    field == 3 && wire == 0 -> blockSize = reader.varint().toInt()
                    field == 13 && wire == 2 -> {
                        val (start, end) = reader.bytesRange()
                        val partition = parsePartition(bytes, start, end)
                        if (partition.second) incremental = true
                        partitions += partition.first
                    }
                    else -> reader.skip(wire)
                }
            }
            return PayloadManifest(version, blockSize, incremental, partitions)
        }

        /** PartitionUpdate; the second value is true when it patches an old partition. */
        private fun parsePartition(bytes: ByteArray, from: Int, to: Int): Pair<Partition, Boolean> {
            var name = "?"
            var size = 0L
            var hasOld = false
            val kinds = HashMap<String, Int>()
            var operations = 0
            val reader = ProtoReader(bytes, from, to)
            while (reader.hasMore()) {
                val (field, wire) = reader.tag()
                when {
                    field == 1 && wire == 2 -> {
                        val (s, e) = reader.bytesRange()
                        name = String(bytes, s, e - s, Charsets.UTF_8)
                    }
                    field == 6 && wire == 2 -> {
                        reader.bytesRange()
                        hasOld = true
                    }
                    field == 7 && wire == 2 -> {
                        val (s, e) = reader.bytesRange()
                        val info = ProtoReader(bytes, s, e)
                        while (info.hasMore()) {
                            val (f, w) = info.tag()
                            if (f == 1 && w == 0) size = info.varint() else info.skip(w)
                        }
                    }
                    field == 8 && wire == 2 -> {
                        val (s, e) = reader.bytesRange()
                        operations++
                        val op = ProtoReader(bytes, s, e)
                        var type = 0L
                        while (op.hasMore()) {
                            val (f, w) = op.tag()
                            if (f == 1 && w == 0) type = op.varint() else op.skip(w)
                        }
                        val kind = kindOf(type)
                        kinds[kind] = (kinds[kind] ?: 0) + 1
                    }
                    else -> reader.skip(wire)
                }
            }
            val sortedKinds = kinds.entries.sortedByDescending { it.value }.map { it.key }
            return Partition(name, size, operations, sortedKinds) to hasOld
        }

        /** InstallOperation.Type */
        private fun kindOf(type: Long): String = when (type) {
            0L -> "raw"
            1L -> "bz2"
            8L -> "xz"
            14L -> "zstd"
            6L -> "zero"
            7L -> "discard"
            4L -> "copy"
            else -> "diff"
        }
    }

    /** Minimal protobuf reader: varints, length-delimited fields and skipping. */
    private class ProtoReader(private val bytes: ByteArray, private var pos: Int, private val end: Int) {
        fun hasMore() = pos < end

        fun tag(): Pair<Int, Int> {
            val key = varint()
            return (key ushr 3).toInt() to (key and 7).toInt()
        }

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= end) throw EOFException("Truncated varint")
                val b = bytes[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IOException("Bad varint")
            }
        }

        fun bytesRange(): Pair<Int, Int> {
            val length = varint()
            if (length < 0 || pos + length > end) throw EOFException("Truncated field")
            val start = pos
            pos += length.toInt()
            return start to pos
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> bytesRange()
                5 -> pos += 4
                else -> throw IOException("Unsupported wire type $wire")
            }
            if (pos > end) throw EOFException("Truncated field")
        }
    }
}
