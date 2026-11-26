/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher

/** A phone on the other end of the cable, scripted: it answers each host packet. */
private class FakePipe(private val answer: FakePipe.(ByteArray) -> Unit) : UsbPipe {
    val written = mutableListOf<ByteArray>()
    private val toRead = ArrayDeque<ByteArray>()

    fun queue(bytes: ByteArray) { toRead += bytes }

    fun queue(message: AdbProtocol.Message) {
        queue(AdbProtocol.header(message))
        if (message.payload.isNotEmpty()) queue(message.payload)
    }

    override fun write(bytes: ByteArray) {
        written += bytes
        answer(bytes)
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val next = toRead.removeFirstOrNull() ?: throw java.io.IOException("nothing to read")
        System.arraycopy(next, 0, buffer, 0, next.size)
        return next.size
    }

    override fun close() {}
}

class AdbFastbootTest {

    @Before
    fun jvmBase64() {
        AdbProtocol.base64 = { java.util.Base64.getEncoder().encodeToString(it) }
    }

    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test
    fun headerRoundTripAndMagic() {
        val message = AdbProtocol.Message(AdbProtocol.WRTE, 3, 7, "hello".toByteArray())
        val header = AdbProtocol.header(message)
        val parsed = AdbProtocol.parseHeader(header)!!
        assertEquals(AdbProtocol.WRTE, parsed[0])
        assertEquals(3, parsed[1])
        assertEquals(7, parsed[2])
        assertEquals(5, parsed[3])
        header[20] = 0
        assertEquals(null, AdbProtocol.parseHeader(header))
        assertEquals("WRTE", AdbProtocol.name(AdbProtocol.WRTE))
    }

    @Test
    fun signatureIsPkcs1Sha1OfTheToken() {
        val token = ByteArray(20) { it.toByte() }
        val signature = AdbProtocol.signToken(keys, token)
        val decrypt = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, keys.public) }
        val plain = decrypt.doFinal(signature)
        assertEquals(35, plain.size)
        assertArrayEquals(token, plain.copyOfRange(15, 35))
    }

    @Test
    fun publicKeyStructMatchesMincrypt() {
        val key = keys.public as RSAPublicKey
        val struct = ByteBuffer.wrap(AdbProtocol.publicKeyStruct(key)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, struct.int)
        val n0inv = struct.int.toLong() and 0xFFFFFFFFL
        // n0inv * n[0] == -1 mod 2^32
        val n0 = key.modulus.mod(BigInteger.ONE.shiftLeft(32)).toLong()
        assertEquals(0xFFFFFFFFL, (n0inv * n0) and 0xFFFFFFFFL)
        val payload = String(AdbProtocol.publicKeyPayload(key, "OpenManager"))
        assertTrue(payload.endsWith(" OpenManager\u0000"))
    }

    @Test
    fun connectsAfterApprovalAndRunsAShellCommand() {
        var approvalAsked = false
        var authCount = 0
        lateinit var pipe: FakePipe
        pipe = FakePipe { bytes ->
            val header = AdbProtocol.parseHeader(bytes) ?: return@FakePipe
            when (header[0]) {
                AdbProtocol.CNXN, AdbProtocol.AUTH -> if (header[0] == AdbProtocol.CNXN || header[3] == 0) {
                    // payload comes next; answer once it arrives (see below)
                }
            }
        }
        // Script: token; signature refused (token again); public key accepted -> CNXN.
        pipe.queue(AdbProtocol.Message(AdbProtocol.AUTH, AdbProtocol.AUTH_TOKEN, 0, ByteArray(20) { 1 }))
        pipe.queue(AdbProtocol.Message(AdbProtocol.AUTH, AdbProtocol.AUTH_TOKEN, 0, ByteArray(20) { 2 }))
        pipe.queue(AdbProtocol.Message(AdbProtocol.CNXN, AdbProtocol.VERSION, 4096, "device::ro.product.model=Pixel;".toByteArray()))
        val client = AdbClient(pipe, keys, "OpenManager")
        val banner = client.connect { approvalAsked = true }
        assertTrue(approvalAsked)
        assertTrue(banner.startsWith("device::"))
        for (written in pipe.written) {
            val h = AdbProtocol.parseHeader(written) ?: continue
            if (h[0] == AdbProtocol.AUTH) authCount++
        }
        assertEquals(2, authCount) // signature, then public key

        // shell: OKAY(remote 9), WRTE "Pixel\n", CLSE
        pipe.queue(AdbProtocol.Message(AdbProtocol.OKAY, 9, 1))
        pipe.queue(AdbProtocol.Message(AdbProtocol.WRTE, 9, 1, "Pixel\n".toByteArray()))
        pipe.queue(AdbProtocol.Message(AdbProtocol.CLSE, 9, 1))
        assertEquals("Pixel\n", client.shell("getprop ro.product.model"))
    }

    @Test
    fun fastbootVariablesFlashAndFailure() {
        val pipe = FakePipe { }
        pipe.queue("INFOproduct: sargo".toByteArray())
        pipe.queue("INFOunlocked: yes".toByteArray())
        pipe.queue("OKAY".toByteArray())
        val client = FastbootClient(pipe)
        assertEquals(listOf("product: sargo", "unlocked: yes"), client.allVariables())

        // max-download-size, download (DATA), the bytes (OKAY), flash (OKAY)
        pipe.queue("OKAY0x10000000".toByteArray())
        pipe.queue("DATA00000004".toByteArray())
        pipe.queue("OKAY".toByteArray())
        pipe.queue("OKAY".toByteArray())
        client.flash("boot", ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), 4)
        val commands = pipe.written.map { String(it) }
        assertTrue(commands.contains("download:00000004"))
        assertTrue(commands.contains("flash:boot"))

        pipe.queue("FAILpartition does not exist".toByteArray())
        try {
            client.command("erase:nothing")
            throw AssertionError("expected a failure")
        } catch (e: FastbootClient.FastbootException) {
            assertEquals("partition does not exist", e.message)
        }
        assertEquals(0x10000000L, FastbootClient.parseSize("0x10000000"))
    }
}
