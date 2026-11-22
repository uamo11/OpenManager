/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.remote

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists SSH/Telnet profiles in a private SharedPreferences file.
 *
 * Passwords, private keys and passphrases never touch disk in clear text: they are sealed with an
 * AES-256-GCM key that lives in the Android Keystore (non-exportable). If the key is lost (device
 * restore, keystore reset) the secrets simply read back as missing and the user is asked again.
 */
object RemoteProfileStore {
    private const val PREFS_NAME = "remote_terminal_profiles"
    private const val KEY_PROFILES = "profiles"
    private const val SECRET_PREFIX = "secret_"

    private val lock = Any()

    @Volatile
    private var cache: List<RemoteProfile>? = null

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun list(context: Context): List<RemoteProfile> {
        cache?.let { return it }
        synchronized(lock) {
            cache?.let { return it }
            val loaded = load(context)
            cache = loaded
            return loaded
        }
    }

    /** Most recently used first, then alphabetical. */
    fun listSorted(context: Context): List<RemoteProfile> =
        list(context).sortedWith(
            compareByDescending<RemoteProfile> { it.lastUsedMillis }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
        )

    fun get(context: Context, id: String): RemoteProfile? = list(context).firstOrNull { it.id == id }

    fun save(context: Context, profile: RemoteProfile) {
        synchronized(lock) {
            val current = list(context).toMutableList()
            val index = current.indexOfFirst { it.id == profile.id }
            if (index >= 0) current[index] = profile else current.add(profile)
            persist(context, current)
        }
    }

    fun delete(context: Context, id: String) {
        synchronized(lock) {
            val current = list(context).filterNot { it.id == id }
            prefs(context).edit().remove(SECRET_PREFIX + id).apply()
            persist(context, current)
        }
    }

    fun markUsed(context: Context, id: String) {
        val profile = get(context, id) ?: return
        save(context, profile.copy(lastUsedMillis = System.currentTimeMillis()))
    }

    private fun load(context: Context): List<RemoteProfile> {
        val prefs = prefs(context)
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            val profile = runCatching { RemoteProfile.fromPublicJson(json) }.getOrNull()
                ?: return@mapNotNull null
            val secrets = prefs.getString(SECRET_PREFIX + profile.id, null)
                ?.let { SecretCipher.decrypt(it) }
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (secrets == null) {
                profile
            } else {
                profile.copy(
                    password = secrets.optString("password").ifEmpty { null },
                    privateKey = secrets.optString("privateKey").ifEmpty { null },
                    keyPassphrase = secrets.optString("keyPassphrase").ifEmpty { null }
                )
            }
        }
    }

    private fun persist(context: Context, profiles: List<RemoteProfile>) {
        val editor = prefs(context).edit()
        val array = JSONArray()
        for (profile in profiles) {
            array.put(profile.toPublicJson())
            val secrets = JSONObject().apply {
                profile.password?.takeIf { it.isNotEmpty() }?.let { put("password", it) }
                profile.privateKey?.takeIf { it.isNotBlank() }?.let { put("privateKey", it) }
                profile.keyPassphrase?.takeIf { it.isNotEmpty() }?.let { put("keyPassphrase", it) }
            }
            val sealed = if (secrets.length() > 0) SecretCipher.encrypt(secrets.toString()) else null
            if (sealed != null) {
                editor.putString(SECRET_PREFIX + profile.id, sealed)
            } else {
                editor.remove(SECRET_PREFIX + profile.id)
            }
        }
        editor.putString(KEY_PROFILES, array.toString())
        editor.apply()
        cache = profiles.toList()
    }

    /** AES-GCM with a non-exportable Android Keystore key. */
    internal object SecretCipher {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "openmanager_remote_terminal_secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128

        private fun key(): SecretKey {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return generator.generateKey()
        }

        fun encrypt(plain: String): String? = runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + sealed, Base64.NO_WRAP)
        }.getOrNull()

        fun decrypt(encoded: String): String? = runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            if (bytes.size <= IV_LENGTH) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE, key(),
                GCMParameterSpec(TAG_BITS, bytes, 0, IV_LENGTH)
            )
            String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
        }.getOrNull()
    }
}
