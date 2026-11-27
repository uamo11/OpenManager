/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties

import me.zhanghai.android.files.fileproperties.PayloadManifest.Operation
import me.zhanghai.android.files.fileproperties.PayloadManifest.Partition
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/** User report: OM listed an OTA's partitions but could not extract them. */
class PayloadExtractorTest {

    private fun manifest(partitions: List<Partition>) =
        PayloadManifest(version = 2, blockSize = 4, isIncremental = false, partitions = partitions, dataStart = 10)

    @Test
    fun rawZeroAndPackedBlocksLandWhereTheySay() {
        val boot = Partition(
            "boot", size = 12, operations = 3, kinds = listOf("raw"),
            operationList = listOf(
                Operation(PayloadExtractor.TYPE_REPLACE, 0, 4, longArrayOf(0, 1)),
                Operation(PayloadExtractor.TYPE_ZERO, 0, 0, longArrayOf(1, 1)),
                Operation(PayloadExtractor.TYPE_REPLACE_XZ, 4, 2, longArrayOf(2, 1))
            )
        )
        // 10 header bytes, then "ABCD" (raw) and two "packed" bytes.
        val payload = ByteArray(10) + "ABCD".toByteArray() + byteArrayOf(7, 7)
        val dir = Files.createTempDirectory("payload").toFile()
        try {
            var progress = 0L
            val out = PayloadExtractor.extract(
                ByteArrayInputStream(payload), manifest(listOf(boot)), setOf("boot"), dir,
                decompress = { type, blob, expected ->
                    assertEquals(PayloadExtractor.TYPE_REPLACE_XZ, type)
                    assertEquals(2, blob.size)
                    assertEquals(4L, expected)
                    "WXYZ".toByteArray()
                },
                onProgress = { done, _ -> progress = done }
            )
            assertEquals(listOf(File(dir, "boot.img")), out)
            assertArrayEquals("ABCD".toByteArray() + ByteArray(4) + "WXYZ".toByteArray(), out[0].readBytes())
            assertEquals(6L, progress)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun onlyTheChosenPartitionsAreWritten() {
        val a = Partition("a", 4, 1, listOf("raw"), listOf(Operation(0, 0, 4, longArrayOf(0, 1))))
        val b = Partition("b", 4, 1, listOf("raw"), listOf(Operation(0, 4, 4, longArrayOf(0, 1))))
        val payload = ByteArray(10) + "AAAABBBB".toByteArray()
        val dir = Files.createTempDirectory("payload").toFile()
        try {
            PayloadExtractor.extract(ByteArrayInputStream(payload), manifest(listOf(a, b)), setOf("b"), dir)
            assertEquals(setOf("b.img"), dir.list()!!.toSet())
            assertArrayEquals("BBBB".toByteArray(), File(dir, "b.img").readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun incrementalOtasAreRefusedClearly() {
        val diff = Partition("system", 4, 1, listOf("diff"), listOf(Operation(9, 0, 4, longArrayOf(0, 1))))
        assertTrue(PayloadExtractor.extractable(manifest(listOf(diff))).isEmpty())
        val dir = Files.createTempDirectory("payload").toFile()
        try {
            PayloadExtractor.extract(ByteArrayInputStream(ByteArray(20)), manifest(listOf(diff)), setOf("system"), dir)
            fail("an incremental partition cannot be extracted")
        } catch (e: PayloadExtractor.UnsupportedPayloadException) {
            assertTrue(e.message!!.contains("incremental"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theManifestKeepsEveryOperation() {
        // PartitionUpdate { name "boot"; new_partition_info { size 8 }; operations { type 8 offset 3 length 5 dst (2,2) } }
        fun varint(v: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream(); var x = v
            while (true) { if (x and 0x7F.inv().toLong() == 0L) { out.write(x.toInt()); break }; out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
            return out.toByteArray()
        }
        fun field(n: Int, wire: Int) = varint(((n shl 3) or wire).toLong())
        fun bytesField(n: Int, b: ByteArray) = field(n, 2) + varint(b.size.toLong()) + b
        fun intField(n: Int, v: Long) = field(n, 0) + varint(v)
        val extent = intField(1, 2) + intField(2, 2)
        val op = intField(1, 8) + intField(2, 3) + intField(3, 5) + bytesField(6, extent)
        val partition = bytesField(1, "boot".toByteArray()) + bytesField(7, intField(1, 8)) + bytesField(8, op)
        val manifestBytes = intField(3, 4096) + bytesField(13, partition)
        val m = PayloadManifest.parseManifest(2, manifestBytes)
        val o = m.partitions.single().operationList.single()
        assertEquals(8, o.type)
        assertEquals(3L, o.dataOffset)
        assertEquals(5L, o.dataLength)
        assertArrayEquals(longArrayOf(2, 2), o.dstExtents)
    }
}
