/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.remote

import android.content.Context
import com.termux.terminal.RemoteTransport
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.sftp.client.SecurityProviderHelper
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthMethod
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.AuthPublickey
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.PublicKey

/** Interactive SSH shell over sshj with strict host key checking. */
class SshTransport(
    context: Context,
    override val profile: RemoteProfile
) : ProfileTransport {
    private val appContext = context.applicationContext
    private val host = profile.host.trim().removePrefix("[").removeSuffix("]")

    @Volatile
    private var client: SSHClient? = null

    @Volatile
    private var session: Session? = null

    @Volatile
    private var shell: Session.Shell? = null

    @Volatile
    private var closed = false

    // What the connection actually negotiated, shown in the connection sheet.
    @Volatile private var negotiated: net.schmizz.sshj.transport.NegotiatedAlgorithms? = null
    @Volatile private var hostKeyType: String? = null
    @Volatile private var hostKeyFingerprint: String? = null
    @Volatile private var hostKeyTrustedBefore = false
    @Volatile private var serverVersion: String? = null
    @Volatile private var connectedAtMs = 0L
    @Volatile private var authMethodUsed: String? = null

    override val isSecure: Boolean
        get() = shell != null && hostKeyFingerprint != null

    override fun connectionDetails(context: Context): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()
        rows += context.getString(R.string.om_conn_protocol) to "SSH-2"
        rows += context.getString(R.string.om_conn_server) to profile.endpoint
        serverVersion?.let { rows += context.getString(R.string.om_conn_server_software) to it }
        authMethodUsed?.let { rows += context.getString(R.string.om_conn_auth) to it }
        hostKeyType?.let { rows += context.getString(R.string.om_conn_host_key) to it }
        hostKeyFingerprint?.let {
            rows += context.getString(R.string.om_conn_fingerprint) to it
            rows += context.getString(R.string.om_conn_identity) to context.getString(
                if (hostKeyTrustedBefore) R.string.om_conn_identity_known else R.string.om_conn_identity_new
            )
        }
        negotiated?.let { algs ->
            rows += context.getString(R.string.om_conn_kex) to algs.keyExchangeAlgorithm
            rows += context.getString(R.string.om_conn_cipher) to
                distinct(algs.client2ServerCipherAlgorithm, algs.server2ClientCipherAlgorithm)
            rows += context.getString(R.string.om_conn_mac) to
                distinct(algs.client2ServerMACAlgorithm, algs.server2ClientMACAlgorithm)
            rows += context.getString(R.string.om_conn_compression) to
                distinct(algs.client2ServerCompressionAlgorithm, algs.server2ClientCompressionAlgorithm)
        }
        if (profile.keepAliveSeconds > 0) {
            rows += context.getString(R.string.om_conn_keepalive) to "${profile.keepAliveSeconds} s"
        }
        if (connectedAtMs > 0) {
            val seconds = (System.currentTimeMillis() - connectedAtMs) / 1000
            rows += context.getString(R.string.om_conn_uptime) to
                String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60)
        }
        return rows
    }

    private fun distinct(a: String?, b: String?): String =
        if (a == b || b == null) a.orEmpty() else "$a / $b"

    override fun getDisplayName(): String = profile.endpoint

    override fun connect(
        columns: Int,
        rows: Int,
        cellWidthPixels: Int,
        cellHeightPixels: Int,
        sink: RemoteTransport.StatusSink
    ) {
        SecurityProviderHelper.ensureInitialized()
        sink.print(status(appContext.getString(R.string.om_remote_status_connecting, profile.endpoint)))

        val config = DefaultConfig().apply {
            // Detects dead links (Wi-Fi/mobile switch) instead of hanging forever.
            keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
        }
        val sshClient = SSHClient(config)
        client = sshClient
        if (closed) throw IOException(appContext.getString(R.string.om_remote_error_cancelled))
        sshClient.connectTimeout = CONNECT_TIMEOUT_MS
        sshClient.addHostKeyVerifier(StrictHostKeyVerifier(sink))
        sshClient.transport.addAlgorithmsVerifier { algorithms ->
            negotiated = algorithms
            true
        }
        try {
            sshClient.connect(host, profile.port)
        } catch (e: IOException) {
            throw IOException(describeConnectError(e), e)
        }
        serverVersion = runCatching { sshClient.transport.serverVersion }.getOrNull()
        // Each keystroke must leave immediately instead of waiting for Nagle's algorithm.
        runCatching { sshClient.socket?.tcpNoDelay = true }
        if (profile.keepAliveSeconds > 0) {
            sshClient.connection.keepAlive.keepAliveInterval = profile.keepAliveSeconds
        }

        authenticate(sshClient, sink)

        val sshSession = sshClient.startSession()
        session = sshSession
        sshSession.allocatePTY(
            TERMINAL_TYPE, columns, rows, columns * cellWidthPixels, rows * cellHeightPixels, emptyMap()
        )
        shell = sshSession.startShell()
        connectedAtMs = System.currentTimeMillis()
        sink.print(status(appContext.getString(R.string.om_remote_status_connected, profile.endpoint)))
    }

    private fun authenticate(sshClient: SSHClient, sink: RemoteTransport.StatusSink) {
        val methods = mutableListOf<AuthMethod>()
        if (profile.authType == RemoteAuthType.PRIVATE_KEY && !profile.privateKey.isNullOrBlank()) {
            methods += AuthPublickey(createKeyProvider(profile.privateKey))
        }
        // Password is also offered after a key, as many servers allow either.
        val passwordFinder = InteractivePasswordFinder(
            saved = profile.password,
            title = appContext.getString(R.string.om_remote_password_title, profile.endpoint),
            prompt = appContext.getString(R.string.om_remote_password_hint)
        )
        if (profile.authType == RemoteAuthType.PASSWORD || profile.hasSavedPassword) {
            methods += AuthPassword(passwordFinder)
            methods += AuthKeyboardInteractive(PasswordResponseProvider(passwordFinder))
        }
        authMethodUsed = appContext.getString(
            if (profile.authType == RemoteAuthType.PRIVATE_KEY) R.string.om_remote_auth_key else R.string.om_remote_auth_password
        )
        try {
            sshClient.auth(profile.username.trim(), methods)
        } catch (e: UserAuthException) {
            if (passwordFinder.cancelled) {
                throw IOException(appContext.getString(R.string.om_remote_error_cancelled), e)
            }
            throw IOException(appContext.getString(R.string.om_remote_error_auth, profile.username), e)
        }
        passwordFinder.rememberedPassword?.let { password ->
            RemoteProfileStore.get(appContext, profile.id)?.let {
                RemoteProfileStore.save(appContext, it.copy(password = password))
            }
            sink.print(status(appContext.getString(R.string.om_remote_status_password_saved)))
        }
    }

    private fun createKeyProvider(privateKey: String): FileKeyProvider {
        val format = KeyProviderUtil.detectKeyFileFormat(privateKey, false)
        val keyProvider = Factory.Named.Util.create(KEY_PROVIDER_FACTORIES, format.toString())
            ?: throw IOException(appContext.getString(R.string.om_remote_error_key_format))
        val passphraseFinder = InteractivePasswordFinder(
            saved = profile.keyPassphrase,
            title = appContext.getString(R.string.om_remote_passphrase_title),
            prompt = appContext.getString(R.string.om_remote_passphrase_hint),
            offerRemember = false
        )
        keyProvider.init(privateKey, null, passphraseFinder)
        return keyProvider
    }

    override fun getInputStream(): InputStream =
        shell?.inputStream ?: throw IOException("Shell not open")

    override fun getOutputStream(): OutputStream =
        shell?.outputStream ?: throw IOException("Shell not open")

    override fun getStartupInput(): ByteArray? = profile.startupInput()

    override fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        runCatching {
            shell?.changeWindowDimensions(
                columns, rows, columns * cellWidthPixels, rows * cellHeightPixels
            )
        }
    }

    override fun getExitStatus(): Int {
        val currentShell = shell ?: return 0
        // The exit-status request can arrive just after EOF.
        runCatching { currentShell.join(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
        return (currentShell as? SessionChannel)?.exitStatus ?: 0
    }

    override fun close() {
        closed = true
        runCatching { shell?.close() }
        runCatching { session?.close() }
        runCatching { client?.disconnect() }
    }

    private fun describeConnectError(e: IOException): String = when (e) {
        is UnknownHostException -> appContext.getString(R.string.om_remote_error_unknown_host, host)
        is SocketTimeoutException -> appContext.getString(R.string.om_remote_error_timeout, profile.endpoint)
        is ConnectException -> appContext.getString(R.string.om_remote_error_refused, profile.endpoint)
        else -> e.message ?: e.javaClass.simpleName
    }

    private inner class StrictHostKeyVerifier(
        private val sink: RemoteTransport.StatusSink
    ) : HostKeyVerifier {
        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val keyType = KeyType.fromKey(key).toString()
            val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
            val fingerprint = KnownHostsStore.sha256Fingerprint(blob)
            hostKeyType = keyType
            hostKeyFingerprint = fingerprint
            return when (val verdict = KnownHostsStore.check(appContext, host, port, keyType, fingerprint)) {
                KnownHostsStore.Verdict.Trusted -> {
                    hostKeyTrustedBefore = true
                    true
                }
                KnownHostsStore.Verdict.Unknown, is KnownHostsStore.Verdict.Changed -> {
                    val previous = (verdict as? KnownHostsStore.Verdict.Changed)?.previousFingerprint
                    if (previous != null) {
                        sink.print(
                            "\r\n\u001b[1;31m" +
                                appContext.getString(R.string.om_remote_status_hostkey_changed) +
                                "\u001b[0m\r\n"
                        )
                    }
                    when (RemotePrompter.confirmHostKey(profile.endpoint, keyType, fingerprint, previous)) {
                        RemotePrompter.HostKeyDecision.ACCEPT_AND_SAVE -> {
                            KnownHostsStore.trust(appContext, host, port, keyType, fingerprint)
                            true
                        }
                        RemotePrompter.HostKeyDecision.ACCEPT_ONCE -> true
                        RemotePrompter.HostKeyDecision.REJECT -> false
                    }
                }
            }
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
            KnownHostsStore.knownKeyTypes(appContext, host, port)
    }

    /**
     * Uses the saved secret first, then asks the user (up to [MAX_ATTEMPTS] times). The answer is
     * kept for this connection so password and keyboard-interactive share one prompt.
     */
    private class InteractivePasswordFinder(
        private val saved: String?,
        private val title: String,
        private val prompt: String,
        private val offerRemember: Boolean = true
    ) : PasswordFinder {
        private var attempts = 0
        private var lastAnswer: String? = null
        private var usedSaved = false

        @Volatile
        var cancelled = false
            private set

        var rememberedPassword: String? = null
            private set

        @Synchronized
        override fun reqPassword(resource: Resource<*>?): CharArray {
            if (!saved.isNullOrEmpty() && !usedSaved) {
                usedSaved = true
                return saved.toCharArray()
            }
            lastAnswer?.let { return it.toCharArray() }
            // sshj cannot take a null secret; an empty one fails cleanly and shouldRetry() stops.
            if (cancelled) return CharArray(0)
            attempts++
            val answer = RemotePrompter.askPassword(title, prompt, offerRemember)
            if (answer == null) {
                cancelled = true
                return CharArray(0)
            }
            lastAnswer = answer.password
            rememberedPassword = if (answer.remember) answer.password else null
            return answer.password.toCharArray()
        }

        @Synchronized
        override fun shouldRetry(resource: Resource<*>?): Boolean {
            // The last answer was wrong: forget it and ask again.
            lastAnswer = null
            rememberedPassword = null
            return !cancelled && attempts < MAX_ATTEMPTS
        }
    }

    companion object {
        const val TERMINAL_TYPE = "xterm-256color"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val MAX_ATTEMPTS = 3
        private val KEY_PROVIDER_FACTORIES by lazy { DefaultConfig().fileKeyProviderFactories }

        fun status(text: String): String = "\u001b[2;36m» $text\u001b[0m\r\n"
    }
}
