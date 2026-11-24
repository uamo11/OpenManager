/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.remote

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Debug builds only, for the emulator checks: while the file [FLAG_FILE] exists in the app's
 * files folder, the next write on the terminal's SSH socket fails once, like a Wi-Fi/mobile switch
 * does on a phone. No phone needed to reproduce what a dropped link does to the terminal and the
 * VPS files sharing it.
 */
object DebugNetworkFault {
    const val FLAG_FILE = "om_debug_fail_next_ssh_write"
    private const val TAG = "OMNetFault"

    fun socketFactory(context: Context): SocketFactory {
        val flag = File(context.applicationContext.filesDir, FLAG_FILE)
        return object : SocketFactory() {
            override fun createSocket(): Socket = FaultSocket(flag)

            override fun createSocket(host: String, port: Int): Socket =
                createSocket().apply { connect(InetSocketAddress(host, port)) }

            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
                createSocket().apply {
                    bind(InetSocketAddress(localHost, localPort))
                    connect(InetSocketAddress(host, port))
                }

            override fun createSocket(host: InetAddress, port: Int): Socket =
                createSocket().apply { connect(InetSocketAddress(host, port)) }

            override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
                createSocket().apply {
                    bind(InetSocketAddress(localAddress, localPort))
                    connect(InetSocketAddress(address, port))
                }
        }
    }

    private class FaultSocket(private val flag: File) : Socket() {
        private var wrapped: OutputStream? = null

        @Synchronized
        override fun getOutputStream(): OutputStream =
            wrapped ?: FaultOutputStream(super.getOutputStream(), flag).also { wrapped = it }
    }

    private class FaultOutputStream(out: OutputStream, private val flag: File) : FilterOutputStream(out) {
        private fun maybeFail() {
            if (flag.exists() && flag.delete()) {
                Log.w(TAG, "Injected network failure on the SSH socket")
                throw IOException("Injected network failure (debug)")
            }
        }

        override fun write(b: Int) {
            maybeFail()
            out.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            maybeFail()
            out.write(b, off, len)
        }

        override fun flush() {
            maybeFail()
            out.flush()
        }
    }
}
