/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

class PayloadManifestTest {

    private class Proto {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) {
            var x = v
            while (x and 0x7FL.inv() != 0L) {
                out.write(((x and 0x7F) or 0x80).toInt())
                x = x ushr 7
            }
            out.write(x.toInt())
        }
        fun int(field: Int, v: Long) = apply { varint((field shl 3).toLong()); varint(v) }
        fun bytes(field: Int, b: ByteArray) = apply { varint(((field shl 3) or 2).toLong()); varint(b.size.toLong()); out.write(b) }
        fun string(field: Int, s: String) = bytes(field, s.toByteArray())
        fun build(): ByteArray = out.toByteArray()
    }

    private fun op(type: Long) = Proto().int(1, type).int(2, 0).int(3, 10).build()

    private fun partition(name: String, size: Long, types: List<Long>, old: Boolean = false): ByteArray {
        val p = Proto().string(1, name)
        if (old) p.bytes(6, Proto().int(1, size).build())
        p.bytes(7, Proto().int(1, size).bytes(2, ByteArray(32)).build())
        types.forEach { p.bytes(8, op(it)) }
        return p.build()
    }

    private fun payload(manifest: ByteArray, version: Long = 2): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            write("CrAU".toByteArray())
            writeLong(version)
            writeLong(manifest.size.toLong())
            if (version >= 2) writeInt(0)
            write(manifest)
            write(ByteArray(1000)) // partition data, never read
        }
        return out.toByteArray()
    }

    @Test
    fun listsPartitionsOfAFullOta() {
        val manifest = Proto()
            .int(3, 4096)
            .bytes(13, partition("boot", 100_663_296, listOf(8, 8, 8)))
            .bytes(13, partition("system", 4_000_000_000, listOf(8, 0, 6, 14, 14, 14)))
            .int(12, 0)
            .build()
        val parsed = PayloadManifest.read(ByteArrayInputStream(payload(manifest)))
        assertEquals(2L, parsed.version)
        assertFalse(parsed.isIncremental)
        assertEquals(listOf("boot", "system"), parsed.partitions.map { it.name })
        assertEquals(100_663_296L, parsed.partitions[0].size)
        assertEquals(4_000_000_000L, parsed.partitions[1].size)
        assertEquals(listOf("xz"), parsed.partitions[0].kinds)
        assertEquals("zstd", parsed.partitions[1].kinds.first())
        assertEquals(6, parsed.partitions[1].operations)
        assertEquals(4_100_663_296L, parsed.totalSize)
    }

    @Test
    fun incrementalOtaAndVersionOne() {
        val manifest = Proto().bytes(13, partition("vendor", 1000, listOf(5, 4), old = true)).build()
        val parsed = PayloadManifest.read(ByteArrayInputStream(payload(manifest, version = 1)))
        assertTrue(parsed.isIncremental)
        assertEquals(setOf("diff", "copy"), parsed.partitions[0].kinds.toSet())
    }

    @Test(expected = IOException::class)
    fun rejectsOtherFiles() {
        PayloadManifest.read(ByteArrayInputStream("PK\u0003\u0004 not a payload".toByteArray()))
    }
}
