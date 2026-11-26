/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.usb

import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/**
 * Fastboot (a phone in its bootloader): plain-text commands of up to 64 bytes, answered with
 * INFO… lines and then OKAY, FAIL or DATA. Blocking: call it off the main thread.
 */
class FastbootClient(private val pipe: UsbPipe) : Closeable {

    class FastbootException(message: String) : IOException(message)

    private val response = ByteArray(256)

    /** Sends [command]; INFO lines go to [onInfo]; returns what follows OKAY (or DATA). */
    fun command(command: String, timeoutMs: Int = 30_000, onInfo: (String) -> Unit = {}): String {
        require(command.length <= 64) { "Fastboot commands are at most 64 bytes" }
        pipe.write(command.toByteArray(Charsets.US_ASCII))
        return readResponse(timeoutMs, onInfo)
    }

    private fun readResponse(timeoutMs: Int, onInfo: (String) -> Unit): String {
        while (true) {
            val n = pipe.read(response, timeoutMs)
            if (n < 4) continue
            val text = String(response, 0, n, Charsets.US_ASCII)
            when (text.substring(0, 4)) {
                "INFO", "TEXT" -> onInfo(text.substring(4))
                "OKAY" -> return text.substring(4)
                "DATA" -> return text.substring(4)
                "FAIL" -> throw FastbootException(text.substring(4).ifBlank { "FAIL" })
            }
        }
    }

    fun getvar(name: String): String = command("getvar:$name")

    /** Every variable the bootloader reports (product, unlocked, slots, max-download-size…). */
    fun allVariables(): List<String> {
        val lines = mutableListOf<String>()
        command("getvar:all", 30_000) { lines += it.trim() }
        return lines.filter { it.isNotEmpty() }
    }

    /** Sends [size] bytes and writes them to [partition]. */
    fun flash(partition: String, input: InputStream, size: Long, progress: (Long) -> Unit = {}): String {
        val max = parseSize(runCatching { getvar("max-download-size") }.getOrDefault(""))
        if (max > 0 && size > max) {
            throw FastbootException("The image ($size bytes) is larger than the bootloader accepts at once ($max)")
        }
        command("download:%08x".format(size))
        val chunk = ByteArray(1024 * 1024)
        var sent = 0L
        while (sent < size) {
            val n = input.read(chunk, 0, minOf(chunk.size.toLong(), size - sent).toInt())
            if (n <= 0) throw FastbootException("The file ended early")
            pipe.write(if (n == chunk.size) chunk else chunk.copyOf(n))
            sent += n
            progress(sent)
        }
        readResponse(60_000) {}
        return command("flash:$partition", 5 * 60_000)
    }

    fun reboot(target: String = "") = runCatching {
        command(if (target.isEmpty()) "reboot" else "reboot-$target")
    }

    override fun close() = pipe.close()

    companion object {
        fun parseSize(value: String): Long {
            val v = value.trim()
            return when {
                v.startsWith("0x", ignoreCase = true) -> v.substring(2).toLongOrNull(16) ?: -1
                else -> v.toLongOrNull() ?: -1
            }
        }
    }
}
