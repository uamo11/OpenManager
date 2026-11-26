/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.usb

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.interfaces.RSAPublicKey

/**
 * Talks ADB to another phone plugged in with an OTG cable: connect (the other phone asks "Allow
 * USB debugging?" the first time), run commands, push files, install APKs, reboot. Blocking:
 * call it off the main thread.
 */
class AdbClient(
    private val pipe: UsbPipe,
    private val keys: KeyPair,
    private val keyName: String
) : Closeable {

    var banner: String = ""
        private set

    private var nextLocalId = 1
    private val headerBuffer = ByteArray(AdbProtocol.HEADER_SIZE)
    private val payloadBuffer = ByteArray(AdbProtocol.MAX_PAYLOAD + 512)
    private var maxPayload = 4096

    private fun send(message: AdbProtocol.Message) {
        pipe.write(AdbProtocol.header(message))
        if (message.payload.isNotEmpty()) pipe.write(message.payload)
    }

    private fun receive(timeoutMs: Int = 10_000): AdbProtocol.Message {
        val n = pipe.read(headerBuffer, timeoutMs)
        val header = AdbProtocol.parseHeader(headerBuffer.copyOf(n)) ?: throw IOException("Bad ADB header")
        val length = header[3]
        val payload = ByteArray(length)
        var read = 0
        while (read < length) {
            val chunk = pipe.read(payloadBuffer, timeoutMs)
            val take = minOf(chunk, length - read)
            System.arraycopy(payloadBuffer, 0, payload, read, take)
            read += take
        }
        return AdbProtocol.Message(header[0], header[1], header[2], payload)
    }

    /**
     * Connects and authenticates. When the other phone does not know our key yet, it shows the
     * "Allow USB debugging?" dialog: [onWaitingForApproval] is called and we wait a minute.
     */
    fun connect(onWaitingForApproval: () -> Unit): String {
        send(AdbProtocol.connect())
        var signed = false
        var sentKey = false
        while (true) {
            val message = receive(if (sentKey) 60_000 else 10_000)
            when (message.command) {
                AdbProtocol.CNXN -> {
                    maxPayload = message.arg1.coerceIn(1024, AdbProtocol.MAX_PAYLOAD)
                    banner = message.payloadText.trimEnd('\u0000')
                    return banner
                }
                AdbProtocol.AUTH -> if (message.arg0 == AdbProtocol.AUTH_TOKEN) {
                    if (!signed) {
                        signed = true
                        send(AdbProtocol.Message(AdbProtocol.AUTH, AdbProtocol.AUTH_SIGNATURE, 0,
                            AdbProtocol.signToken(keys, message.payload)))
                    } else if (!sentKey) {
                        sentKey = true
                        send(AdbProtocol.Message(AdbProtocol.AUTH, AdbProtocol.AUTH_RSA_PUBLIC_KEY, 0,
                            AdbProtocol.publicKeyPayload(keys.public as RSAPublicKey, keyName)))
                        onWaitingForApproval()
                    }
                }
            }
        }
    }

    /** Opens a service ("shell:…", "reboot:bootloader", "sync:") and returns the stream ids. */
    private fun openStream(destination: String): IntArray {
        val localId = nextLocalId++
        send(AdbProtocol.open(localId, destination))
        while (true) {
            val message = receive()
            when (message.command) {
                AdbProtocol.OKAY -> if (message.arg1 == localId) return intArrayOf(localId, message.arg0)
                AdbProtocol.CLSE -> if (message.arg1 == localId) throw IOException("Refused: $destination")
            }
        }
    }

    /** Runs [destination] and returns all it printed (e.g. "shell:getprop ro.product.model"). */
    fun exec(destination: String, timeoutMs: Int = 60_000): String {
        val (localId, remoteId) = openStream(destination).let { it[0] to it[1] }
        val out = ByteArrayOutputStream()
        try {
            while (true) {
                val message = receive(timeoutMs)
                if (message.arg1 != localId) continue
                when (message.command) {
                    AdbProtocol.WRTE -> {
                        out.write(message.payload)
                        send(AdbProtocol.okay(localId, remoteId))
                    }
                    AdbProtocol.CLSE -> {
                        send(AdbProtocol.close(localId, remoteId))
                        break
                    }
                }
            }
        } catch (e: IOException) {
            // reboot: the other phone goes away without closing the stream.
            if (!destination.startsWith("reboot")) throw e
        }
        return out.toString("UTF-8")
    }

    fun shell(command: String): String = exec("shell:$command")

    /** Copies [size] bytes to [remotePath] on the other phone (ADB sync protocol). */
    fun push(input: InputStream, size: Long, remotePath: String, progress: (Long) -> Unit = {}) {
        val (localId, remoteId) = openStream("sync:").let { it[0] to it[1] }
        fun writeAndWait(data: ByteArray) {
            send(AdbProtocol.write(localId, remoteId, data))
            while (true) {
                val message = receive()
                if (message.arg1 != localId) continue
                if (message.command == AdbProtocol.OKAY) return
                if (message.command == AdbProtocol.CLSE) throw IOException("Copy refused")
                if (message.command == AdbProtocol.WRTE) {
                    send(AdbProtocol.okay(localId, remoteId))
                    throw IOException("Copy failed: " + message.payloadText.drop(8))
                }
            }
        }
        val target = "$remotePath,${"0644".toInt(8)}".toByteArray()
        writeAndWait(syncRequest("SEND", target.size) + target)
        val chunk = ByteArray(minOf(64 * 1024, maxPayload - 8))
        var sent = 0L
        while (true) {
            val n = input.read(chunk)
            if (n <= 0) break
            writeAndWait(syncRequest("DATA", n) + chunk.copyOf(n))
            sent += n
            progress(sent)
        }
        writeAndWait(syncRequest("DONE", (System.currentTimeMillis() / 1000).toInt()))
        // The other phone answers OKAY or FAIL + reason.
        while (true) {
            val message = receive(60_000)
            if (message.arg1 != localId) continue
            if (message.command == AdbProtocol.WRTE) {
                send(AdbProtocol.okay(localId, remoteId))
                val status = String(message.payload, 0, 4, Charsets.ISO_8859_1)
                send(AdbProtocol.write(localId, remoteId, syncRequest("QUIT", 0)))
                if (status != "OKAY") throw IOException("Copy failed: " + message.payloadText.drop(8))
                return
            }
            if (message.command == AdbProtocol.CLSE) return
        }
    }

    /** Pushes an APK to /data/local/tmp and installs it with the package manager. */
    fun install(input: InputStream, size: Long, progress: (Long) -> Unit = {}): String {
        val remote = "/data/local/tmp/openmanager_install.apk"
        push(input, size, remote, progress)
        val result = shell("pm install -r -t \"$remote\"; rm -f \"$remote\"")
        return result.trim()
    }

    fun reboot(target: String = "") = exec("reboot:$target")

    override fun close() = pipe.close()

    companion object {
        fun syncRequest(id: String, value: Int): ByteArray =
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .put(id.toByteArray(Charsets.ISO_8859_1)).putInt(value).array()
    }
}
