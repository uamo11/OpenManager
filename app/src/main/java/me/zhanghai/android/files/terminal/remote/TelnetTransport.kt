/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.remote

import android.content.Context
import com.termux.terminal.RemoteTransport
import me.zhanghai.android.files.R
import org.apache.commons.net.telnet.EchoOptionHandler
import org.apache.commons.net.telnet.SuppressGAOptionHandler
import org.apache.commons.net.telnet.TelnetClient
import org.apache.commons.net.telnet.TelnetOption
import org.apache.commons.net.telnet.TerminalTypeOptionHandler
import org.apache.commons.net.telnet.WindowSizeOptionHandler
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Telnet session for routers, embedded boards and legacy hosts.
 *
 * Telnet has no encryption, so the transport never volunteers secrets: the saved user name and
 * password are typed only after the server shows a matching `login:` / `Password:` prompt, at most
 * once each and only during the first seconds of the connection.
 */
class TelnetTransport(
    context: Context,
    override val profile: RemoteProfile
) : ProfileTransport {
    private val appContext = context.applicationContext
    private val host = profile.host.trim().removePrefix("[").removeSuffix("]")
    private val telnet = TelnetClient(SshTransport.TERMINAL_TYPE)

    @Volatile
    private var closed = false

    private var output: OutputStream? = null
    private var input: InputStream? = null

    override fun getDisplayName(): String = profile.endpoint

    override fun connect(
        columns: Int,
        rows: Int,
        cellWidthPixels: Int,
        cellHeightPixels: Int,
        sink: RemoteTransport.StatusSink
    ) {
        sink.print(SshTransport.status(appContext.getString(R.string.om_remote_status_connecting, profile.endpoint)))
        telnet.addOptionHandler(TerminalTypeOptionHandler(SshTransport.TERMINAL_TYPE, false, false, true, false))
        telnet.addOptionHandler(EchoOptionHandler(false, false, false, true))
        telnet.addOptionHandler(SuppressGAOptionHandler(true, true, true, true))
        telnet.addOptionHandler(
            WindowSizeOptionHandler(columns.clampWindow(), rows.clampWindow(), true, false, true, false)
        )
        telnet.connectTimeout = CONNECT_TIMEOUT_MS
        if (closed) throw IOException(appContext.getString(R.string.om_remote_error_cancelled))
        try {
            telnet.connect(host, profile.port)
        } catch (e: IOException) {
            throw IOException(
                when (e) {
                    is UnknownHostException -> appContext.getString(R.string.om_remote_error_unknown_host, host)
                    is SocketTimeoutException ->
                        appContext.getString(R.string.om_remote_error_timeout, profile.endpoint)
                    is ConnectException -> appContext.getString(R.string.om_remote_error_refused, profile.endpoint)
                    else -> e.message ?: e.javaClass.simpleName
                },
                e
            )
        }
        runCatching { telnet.tcpNoDelay = true }
        runCatching { telnet.keepAlive = true }
        val out = SynchronizedOutputStream(telnet.outputStream)
        output = out
        input = AutoLoginInputStream(
            telnet.inputStream, out, profile.username.trim(), profile.password, profile.startupInput(),
            System.currentTimeMillis() + AUTO_LOGIN_WINDOW_MS
        )
        sink.print(
            SshTransport.status(appContext.getString(R.string.om_remote_status_connected, profile.endpoint)) +
                "\u001b[2;33m» " + appContext.getString(R.string.om_remote_status_telnet_plaintext) +
                "\u001b[0m\r\n"
        )
    }

    override fun getInputStream(): InputStream = input ?: throw IOException("Not connected")

    override fun getOutputStream(): OutputStream = output ?: throw IOException("Not connected")

    // Typed by AutoLoginInputStream once the shell prompt appears; sending it on connect would
    // feed the commands to the login prompt.
    override fun getStartupInput(): ByteArray? = null

    override fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        if (!telnet.isConnected || !telnet.getLocalOptionState(TelnetOption.WINDOW_SIZE)) return
        val width = columns.clampWindow()
        val height = rows.clampWindow()
        runCatching {
            telnet.sendSubnegotiation(
                intArrayOf(TelnetOption.WINDOW_SIZE, width shr 8, width and 0xFF, height shr 8, height and 0xFF)
            )
        }
    }

    override fun getExitStatus(): Int = 0

    override fun close() {
        closed = true
        runCatching { if (telnet.isConnected) telnet.disconnect() }
    }

    private fun Int.clampWindow(): Int = coerceIn(1, 0xFFFE)

    private class SynchronizedOutputStream(private val delegate: OutputStream) : OutputStream() {
        @Synchronized
        override fun write(b: Int) = delegate.write(b)

        @Synchronized
        override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)

        @Synchronized
        override fun flush() = delegate.flush()

        override fun close() = delegate.close()
    }

    /** Passes data through untouched while answering the first login/password prompts. */
    private class AutoLoginInputStream(
        delegate: InputStream,
        private val out: OutputStream,
        private val username: String,
        private val password: String?,
        private val startup: ByteArray?,
        private val deadlineMillis: Long
    ) : FilterInputStream(delegate) {
        private val tail = StringBuilder()
        private var userSent = username.isEmpty()
        private var passwordSent = password.isNullOrEmpty()
        private var startupSent = startup == null

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = super.read(b, off, len)
            if (read > 0 && !(userSent && passwordSent && startupSent)) {
                if (System.currentTimeMillis() > deadlineMillis) {
                    userSent = true
                    passwordSent = true
                    startupSent = true
                } else {
                    inspect(String(b, off, read, Charsets.UTF_8))
                }
            }
            return read
        }

        private fun inspect(chunk: String) {
            tail.append(chunk)
            if (tail.length > 160) tail.delete(0, tail.length - 160)
            val prompt = LoginPrompts.detect(tail.toString()) ?: return
            // Each prompt is answered once; start matching afresh after it.
            tail.setLength(0)
            when (prompt) {
                LoginPrompts.Prompt.USER -> if (!userSent) {
                    userSent = true
                    send(username)
                }
                LoginPrompts.Prompt.PASSWORD -> if (!passwordSent) {
                    passwordSent = true
                    userSent = true
                    send(password!!)
                }
                LoginPrompts.Prompt.SHELL -> if (!startupSent) {
                    // Credentials typed by the user count too: the first shell prompt ends login.
                    userSent = true
                    passwordSent = true
                    startupSent = true
                    runCatching {
                        out.write(startup!!)
                        out.flush()
                    }
                }
            }
        }

        private fun send(text: String) {
            runCatching {
                out.write("$text\r".toByteArray(Charsets.UTF_8))
                out.flush()
            }
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val AUTO_LOGIN_WINDOW_MS = 60_000L
    }
}

/** Recognizes the usual Telnet login prompts at the end of the received text. */
object LoginPrompts {
    enum class Prompt { USER, PASSWORD, SHELL }

    private val PASSWORD = Regex("(?i)(password|passwd|contraseña|passcode)\\s*:\\s*$")
    private val USER = Regex("(?i)(login|username|user name|user)\\s*:\\s*$")
    private val SHELL = Regex("[\\$#%>]\\s*$")
    private val ANSI = Regex("\u001b\\[[0-9;?]*[A-Za-z]")

    fun detect(text: String): Prompt? {
        val clean = ANSI.replace(text, "").trimEnd(' ', '\u0000')
        return when {
            PASSWORD.containsMatchIn(clean) -> Prompt.PASSWORD
            USER.containsMatchIn(clean) -> Prompt.USER
            SHELL.containsMatchIn(clean) -> Prompt.SHELL
            else -> null
        }
    }
}
