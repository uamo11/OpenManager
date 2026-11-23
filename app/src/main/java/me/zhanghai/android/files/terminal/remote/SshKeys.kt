/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.remote

import me.zhanghai.android.files.provider.sftp.client.SecurityProviderHelper
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.util.encoders.Base64
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom

/** SSH key generation and the helpers to put a public key on a server. */
object SshKeys {

    data class KeyPair(val privateKeyOpenSsh: String, val publicKeyLine: String)

    /**
     * New Ed25519 key in the standard unencrypted OpenSSH format (the one `ssh-keygen -t ed25519`
     * writes), so it also works with OpenSSH, PuTTYgen or Termux.
     */
    fun generateEd25519(comment: String, random: SecureRandom = SecureRandom()): KeyPair {
        val private = Ed25519PrivateKeyParameters(random)
        val seed = private.encoded
        val public = private.generatePublicKey().encoded
        val publicBlob = sshBuffer {
            string("ssh-ed25519")
            bytes(public)
        }
        val check = random.nextInt()
        var privateSection = sshBuffer {
            int(check)
            int(check)
            string("ssh-ed25519")
            bytes(public)
            bytes(seed + public)
            string(comment)
        }
        // Pad to the cipher block size ("none" uses 8) with 1, 2, 3...
        var pad = 1
        while (privateSection.size % 8 != 0) privateSection += pad++.toByte()
        val body = "openssh-key-v1".toByteArray() + byteArrayOf(0) + sshBuffer {
            string("none")
            string("none")
            string("")
            int(1)
            bytes(publicBlob)
            bytes(privateSection)
        }
        val armored = String(Base64.encode(body)).chunked(70).joinToString("\n")
        return KeyPair(
            privateKeyOpenSsh = "-----BEGIN OPENSSH PRIVATE KEY-----\n$armored\n-----END OPENSSH PRIVATE KEY-----\n",
            publicKeyLine = "ssh-ed25519 ${String(Base64.encode(publicBlob))} $comment"
        )
    }

    /** `ssh-xxx AAAA... comment` for any private key sshj can read (OpenSSH, PEM, PuTTY). */
    fun publicKeyLine(privateKey: String, passphrase: String?, comment: String): String {
        SecurityProviderHelper.ensureInitialized()
        val format = KeyProviderUtil.detectKeyFileFormat(privateKey, false)
        val provider = Factory.Named.Util.create(DefaultConfig().fileKeyProviderFactories, format.toString())
            as? FileKeyProvider ?: throw IllegalArgumentException("Unsupported key format $format")
        provider.init(privateKey, null, passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) })
        val public = provider.public
        val blob = Buffer.PlainBuffer().putPublicKey(public).compactData
        return "${KeyType.fromKey(public)} ${String(Base64.encode(blob))} $comment".trim()
    }

    /**
     * Shell command that adds the key to ~/.ssh/authorized_keys once (idempotent) with the
     * permissions sshd requires. The key line only contains base64 and a comment we sanitize.
     */
    fun installCommand(publicKeyLine: String): String {
        val safe = publicKeyLine.replace("'", "")
        return "umask 077; mkdir -p ~/.ssh && touch ~/.ssh/authorized_keys && " +
            "(grep -qxF '$safe' ~/.ssh/authorized_keys || echo '$safe' >> ~/.ssh/authorized_keys) && " +
            "chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys && echo 'OpenManager: key installed'"
    }

    /** Comments end up in a shell command and authorized_keys: keep them to a safe charset. */
    fun sanitizeComment(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9@._-]"), "_").take(48).ifEmpty { "openmanager" }

    private class SshBufferBuilder {
        private val bytesOut = ByteArrayOutputStream()
        private val out = DataOutputStream(bytesOut)
        fun int(value: Int) = out.writeInt(value)
        fun bytes(value: ByteArray) {
            out.writeInt(value.size)
            out.write(value)
        }
        fun string(value: String) = bytes(value.toByteArray(Charsets.UTF_8))
        fun build(): ByteArray = bytesOut.toByteArray()
    }

    private fun sshBuffer(block: SshBufferBuilder.() -> Unit): ByteArray = SshBufferBuilder().apply(block).build()
}
