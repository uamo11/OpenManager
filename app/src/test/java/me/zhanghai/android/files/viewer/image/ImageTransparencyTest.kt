/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class ImageTransparencyTest {

    private fun png(colorType: Int, vararg chunks: String): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        data.writeInt(13); data.writeBytes("IHDR")
        data.writeInt(8); data.writeInt(8); data.writeByte(8); data.writeByte(colorType)
        data.writeByte(0); data.writeByte(0); data.writeByte(0); data.writeInt(0)
        for (chunk in chunks) {
            data.writeInt(2); data.writeBytes(chunk); data.writeShort(0); data.writeInt(0)
        }
        return out.toByteArray()
    }

    @Test
    fun pngAlphaColorTypes() {
        assertTrue(ImageDisplayPlan.hasTransparency(png(6, "IDAT")))
        assertTrue(ImageDisplayPlan.hasTransparency(png(4, "IDAT")))
        assertFalse(ImageDisplayPlan.hasTransparency(png(2, "IDAT")))
    }

    @Test
    fun pngTransparencyChunkBeforeData() {
        assertTrue(ImageDisplayPlan.hasTransparency(png(3, "PLTE", "tRNS", "IDAT")))
        assertFalse(ImageDisplayPlan.hasTransparency(png(3, "PLTE", "IDAT", "tRNS")))
    }

    @Test
    fun webpAlphaFlagAndOthers() {
        val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8X\u000a\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
        assertTrue(ImageDisplayPlan.hasTransparency(webp + byteArrayOf(0x10, 0, 0, 0)))
        assertFalse(ImageDisplayPlan.hasTransparency(webp + byteArrayOf(0, 0, 0, 0)))
        assertFalse(ImageDisplayPlan.hasTransparency(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertFalse(ImageDisplayPlan.hasTransparency(ByteArray(0)))
    }
}
