/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.usb

import android.util.Base64
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher

/**
 * The ADB wire protocol (system/core/adb/protocol.txt), what a computer speaks to a phone over
 * USB: 24-byte little-endian headers, connection, RSA authentication and streams. Pure code, no
 * library: the USB part is Android's own host API.
 */
object AdbProtocol {
    const val CNXN = 0x4e584e43
    const val AUTH = 0x48545541
    const val OPEN = 0x4e45504f
    const val OKAY = 0x59414b4f
    const val CLSE = 0x45534c43
    const val WRTE = 0x45545257

    const val VERSION = 0x01000001
    const val MAX_PAYLOAD = 256 * 1024

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSA_PUBLIC_KEY = 3

    const val HEADER_SIZE = 24

    class Message(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray = ByteArray(0)) {
        val payloadText: String get() = String(payload, Charsets.UTF_8)

        override fun toString(): String = "${name(command)}($arg0, $arg1, ${payload.size} bytes)"
    }

    fun name(command: Int): String =
        String(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(command).array(), Charsets.ISO_8859_1)

    /** Old devices check the payload sum; newer ones ignore it, so it is always sent. */
    fun checksum(payload: ByteArray): Int {
        var sum = 0
        for (b in payload) sum += b.toInt() and 0xFF
        return sum
    }

    fun header(message: Message): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(message.command)
            .putInt(message.arg0)
            .putInt(message.arg1)
            .putInt(message.payload.size)
            .putInt(checksum(message.payload))
            .putInt(message.command.inv())
            .array()

    /** Command, args and payload length of a header; null if its magic does not match. */
    fun parseHeader(bytes: ByteArray): IntArray? {
        if (bytes.size < HEADER_SIZE) return null
        val b = ByteBuffer.wrap(bytes, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val command = b.int
        val arg0 = b.int
        val arg1 = b.int
        val length = b.int
        b.int // checksum
        val magic = b.int
        if (magic != command.inv() || length < 0 || length > MAX_PAYLOAD * 4) return null
        return intArrayOf(command, arg0, arg1, length)
    }

    fun connect(): Message = Message(CNXN, VERSION, MAX_PAYLOAD, "host::OpenManager\u0000".toByteArray())

    fun open(localId: Int, destination: String): Message =
        Message(OPEN, localId, 0, (destination + "\u0000").toByteArray())

    fun okay(localId: Int, remoteId: Int): Message = Message(OKAY, localId, remoteId)

    fun close(localId: Int, remoteId: Int): Message = Message(CLSE, localId, remoteId)

    fun write(localId: Int, remoteId: Int, data: ByteArray): Message = Message(WRTE, localId, remoteId, data)

    /** DigestInfo prefix for SHA-1: the device's token is signed as if it were a SHA-1 digest. */
    private val SHA1_DIGEST_INFO = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    )

    fun signToken(keys: KeyPair, token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keys.private)
        return cipher.doFinal(SHA1_DIGEST_INFO + token)
    }

    /**
     * The public key in Android's own format (mincrypt RSAPublicKey: word count, n0inv, modulus,
     * R² mod n, exponent; little-endian 32-bit words), base64, then " name" and a NUL.
     */
    /** Android's Base64 in the app; the JVM one in unit tests. */
    var base64: (ByteArray) -> String = { Base64.encodeToString(it, Base64.NO_WRAP) }

    fun publicKeyPayload(key: RSAPublicKey, name: String): ByteArray {
        val encoded = base64(publicKeyStruct(key))
        return ("$encoded $name\u0000").toByteArray()
    }

    /** The raw mincrypt struct (separate from the base64 so it can be checked in tests). */
    fun publicKeyStruct(key: RSAPublicKey): ByteArray {
        val words = 2048 / 32
        val n = key.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toInt()
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
        val buffer = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(words)
        buffer.putInt(n0inv)
        putWords(buffer, n, words)
        putWords(buffer, rr, words)
        buffer.putInt(key.publicExponent.toInt())
        return buffer.array()
    }

    private fun putWords(buffer: ByteBuffer, value: BigInteger, words: Int) {
        val mask = BigInteger.valueOf(0xFFFFFFFFL)
        var v = value
        repeat(words) {
            buffer.putInt(v.and(mask).toInt())
            v = v.shiftRight(32)
        }
    }
}
