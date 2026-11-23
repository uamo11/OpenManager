/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.remote

import android.content.Context
import android.os.Looper
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.PublicKey

/**
 * Trust-on-first-use host key check shared by every SSH-based connection (SFTP storage, ...):
 * known keys pass silently, a new key is shown to the user once, a changed key is never accepted
 * without an explicit "replace". Replaces sshj's PromiscuousVerifier, which accepted anything.
 */
class KnownHostsVerifier(
    context: Context,
    private val label: String
) : HostKeyVerifier {
    private val appContext = context.applicationContext

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val keyType = KeyType.fromKey(key).toString()
        val fingerprint = KnownHostsStore.sha256Fingerprint(Buffer.PlainBuffer().putPublicKey(key).compactData)
        val verdict = KnownHostsStore.check(appContext, hostname, port, keyType, fingerprint)
        if (verdict == KnownHostsStore.Verdict.Trusted) return true
        // Asking blocks until the user answers; that is impossible on the main thread.
        if (Looper.myLooper() == Looper.getMainLooper()) return false
        val previous = (verdict as? KnownHostsStore.Verdict.Changed)?.previousFingerprint
        return when (RemotePrompter.confirmHostKey(label, keyType, fingerprint, previous)) {
            RemotePrompter.HostKeyDecision.ACCEPT_AND_SAVE -> {
                KnownHostsStore.trust(appContext, hostname, port, keyType, fingerprint)
                true
            }
            RemotePrompter.HostKeyDecision.ACCEPT_ONCE -> true
            RemotePrompter.HostKeyDecision.REJECT -> false
        }
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
        KnownHostsStore.knownKeyTypes(appContext, hostname, port)
}
