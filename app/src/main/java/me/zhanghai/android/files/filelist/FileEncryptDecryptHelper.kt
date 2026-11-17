/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Activity
import android.content.res.ColorStateList
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.deleteIfExists
import me.zhanghai.android.files.provider.common.moveTo
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.newOutputStream
import me.zhanghai.android.files.theme.custom.OpenManagerPalette
import me.zhanghai.android.files.ui.bindToLifecycle
import me.zhanghai.android.files.ui.dismissSafely
import me.zhanghai.android.files.ui.isUiAlive
import me.zhanghai.android.files.ui.runWithProgressDialog
import me.zhanghai.android.files.ui.showSafeDialog
import java.io.InputStream
import java.security.SecureRandom
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object FileEncryptDecryptHelper {

    private val MAGIC_HEADER = "OMENC1".toByteArray(Charsets.UTF_8)
    private const val ITERATIONS = 65536
    private const val KEY_LENGTH = 256

    fun isEncryptedFile(file: FileItem): Boolean {
        return file.name.endsWith(".enc", ignoreCase = true)
    }

    fun showEncryptDialog(activity: Activity, file: FileItem, onDone: () -> Unit) {
        if (!activity.isUiAlive) return
        val context = activity
        val palette = OpenManagerPalette.from(context)
        val pad = (16 * context.resources.displayMetrics.density).toInt()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        val etPassword = EditText(context).apply {
            setHint(R.string.om_enc_password_hint)
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textSecondary)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val etConfirm = EditText(context).apply {
            setHint(R.string.om_enc_confirm_password_hint)
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textSecondary)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val cbDeleteOriginal = CheckBox(context).apply {
            setText(R.string.om_enc_delete_original)
            setTextColor(palette.textPrimary)
            buttonTintList = ColorStateList.valueOf(palette.accentColor)
            isChecked = false
        }

        layout.addView(etPassword)
        layout.addView(etConfirm)
        layout.addView(cbDeleteOriginal)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.om_tool_encrypt)
            .setMessage(context.getString(R.string.om_enc_aes_msg_format, file.name))
            .setView(layout)
            .setPositiveButton(R.string.om_enc_btn) { _, _ ->
                val pwd = etPassword.text.toString()
                val confirm = etConfirm.text.toString()
                if (pwd.isEmpty()) {
                    Toast.makeText(context, R.string.om_enc_empty_password, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (pwd != confirm) {
                    Toast.makeText(context, R.string.om_enc_password_mismatch, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                encrypt(activity, file.path, pwd, cbDeleteOriginal.isChecked, onDone)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        if (activity is LifecycleOwner) {
            dialog.bindToLifecycle(activity)
        }
        if (activity.isUiAlive) {
            dialog.show()
        }
    }

    fun showDecryptDialog(activity: Activity, file: FileItem, onDone: () -> Unit) {
        if (!activity.isUiAlive) return
        val context = activity
        val palette = OpenManagerPalette.from(context)
        val pad = (16 * context.resources.displayMetrics.density).toInt()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        val etPassword = EditText(context).apply {
            setHint(R.string.om_enc_password_hint)
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textSecondary)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val cbDeleteEnc = CheckBox(context).apply {
            setText(R.string.om_dec_delete_encrypted)
            setTextColor(palette.textPrimary)
            buttonTintList = ColorStateList.valueOf(palette.accentColor)
            isChecked = false
        }

        layout.addView(etPassword)
        layout.addView(cbDeleteEnc)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.om_tool_decrypt)
            .setMessage(context.getString(R.string.om_dec_msg_format, file.name))
            .setView(layout)
            .setPositiveButton(R.string.om_dec_btn) { _, _ ->
                val pwd = etPassword.text.toString()
                if (pwd.isEmpty()) {
                    Toast.makeText(context, R.string.om_dec_enter_password, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                decrypt(activity, file.path, pwd, cbDeleteEnc.isChecked, onDone)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        if (activity is LifecycleOwner) {
            dialog.bindToLifecycle(activity)
        }
        if (activity.isUiAlive) {
            dialog.show()
        }
    }

    private fun runCryptoOperation(
        activity: Activity,
        progressTitle: String,
        progressMessage: String,
        errorTitleRes: Int,
        successMessage: String,
        onDone: () -> Unit,
        worker: () -> String?
    ) {
        val handleResult: (String?) -> Unit = { errorMsg ->
            if (activity.isUiAlive) {
                if (errorMsg == null) {
                    Toast.makeText(activity, successMessage, Toast.LENGTH_LONG).show()
                    onDone()
                } else if (activity is FragmentActivity) {
                    activity.showSafeDialog {
                        setTitle(errorTitleRes)
                        setMessage(errorMsg)
                        setPositiveButton(android.R.string.ok, null)
                    }
                } else {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle(errorTitleRes)
                        .setMessage(errorMsg)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }

        if (activity is FragmentActivity) {
            activity.runWithProgressDialog(
                title = progressTitle,
                initialMessage = progressMessage
            ) {
                val errorMsg = withContext(Dispatchers.IO) { worker() }
                handleResult(errorMsg)
            }
            return
        }

        val progress = MaterialAlertDialogBuilder(activity)
            .setTitle(progressTitle)
            .setMessage(progressMessage)
            .setCancelable(false)
            .create()
        if (activity is LifecycleOwner) {
            progress.bindToLifecycle(activity)
        }
        if (activity.isUiAlive) {
            progress.show()
        }

        CoroutineScope(Dispatchers.IO).launch {
            val errorMsg = worker()
            withContext(Dispatchers.Main) {
                progress.dismissSafely()
                handleResult(errorMsg)
            }
        }
    }

    private fun moveTempToTarget(tempPath: Path, targetPath: Path) {
        try {
            tempPath.moveTo(targetPath, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            tempPath.copyTo(targetPath, StandardCopyOption.REPLACE_EXISTING)
            runCatching { tempPath.deleteIfExists() }
        }
    }

    private fun encrypt(
        activity: Activity,
        sourcePath: Path,
        password: String,
        deleteOriginal: Boolean,
        onDone: () -> Unit
    ) {
        val fileName = sourcePath.fileName.toString()
        val targetName = "$fileName.enc"
        val parentDir = sourcePath.parent ?: Paths.get("/storage/emulated/0/Download")
        val targetPath = parentDir.resolve(targetName)
        val tempPath = parentDir.resolve(".${targetName}.om_crypto_tmp_${System.currentTimeMillis()}")

        runCryptoOperation(
            activity = activity,
            progressTitle = activity.getString(R.string.om_enc_progress_title),
            progressMessage = activity.getString(R.string.om_enc_progress_msg_format, fileName),
            errorTitleRes = R.string.om_enc_error_title,
            successMessage = activity.getString(R.string.om_enc_success_format, targetName),
            onDone = onDone
        ) {
            val pwdChars = password.toCharArray()
            var keyBytes: ByteArray? = null
            try {
                val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }

                val keySpec = PBEKeySpec(pwdChars, salt, ITERATIONS, KEY_LENGTH)
                val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                keyBytes = factory.generateSecret(keySpec).encoded
                keySpec.clearPassword()
                val secretKey = SecretKeySpec(keyBytes, "AES")

                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))

                sourcePath.newInputStream().use { inStream ->
                    tempPath.newOutputStream().use { outStream ->
                        outStream.write(MAGIC_HEADER)
                        outStream.write(salt)
                        outStream.write(iv)

                        CipherOutputStream(outStream, cipher).use { cipherOut ->
                            val buf = ByteArray(65536)
                            var read: Int
                            while (inStream.read(buf).also { read = it } != -1) {
                                cipherOut.write(buf, 0, read)
                            }
                            cipherOut.flush()
                        }
                    }
                }

                moveTempToTarget(tempPath, targetPath)

                if (deleteOriginal) {
                    runCatching { sourcePath.deleteIfExists() }
                }
                null
            } catch (e: Exception) {
                e.printStackTrace()
                runCatching { tempPath.deleteIfExists() }
                e.localizedMessage ?: e.javaClass.simpleName
            } finally {
                pwdChars.fill('\u0000')
                keyBytes?.fill(0)
            }
        }
    }

    private fun decrypt(
        activity: Activity,
        sourcePath: Path,
        password: String,
        deleteEnc: Boolean,
        onDone: () -> Unit
    ) {
        val fileName = sourcePath.fileName.toString()
        val targetName = if (fileName.endsWith(".enc", ignoreCase = true)) {
            fileName.substringBeforeLast(".enc")
        } else {
            "${fileName}_decrypted"
        }
        val parentDir = sourcePath.parent ?: Paths.get("/storage/emulated/0/Download")
        val targetPath = parentDir.resolve(targetName)
        val tempPath = parentDir.resolve(".${targetName}.om_crypto_tmp_${System.currentTimeMillis()}")

        runCryptoOperation(
            activity = activity,
            progressTitle = activity.getString(R.string.om_dec_progress_title),
            progressMessage = activity.getString(R.string.om_dec_progress_msg_format, fileName),
            errorTitleRes = R.string.om_dec_error_title,
            successMessage = activity.getString(R.string.om_dec_success_format, targetName),
            onDone = onDone
        ) {
            val pwdChars = password.toCharArray()
            var keyBytes: ByteArray? = null
            var isWrongPassword = false
            try {
                sourcePath.newInputStream().use { inStream ->
                    val magic = ByteArray(MAGIC_HEADER.size)
                    if (!inStream.readFully(magic) || !magic.contentEquals(MAGIC_HEADER)) {
                        throw IllegalArgumentException(activity.getString(R.string.om_dec_unsupported_format))
                    }

                    val salt = ByteArray(16)
                    if (!inStream.readFully(salt)) {
                        throw IllegalArgumentException(activity.getString(R.string.om_dec_corrupt_salt))
                    }
                    val iv = ByteArray(16)
                    if (!inStream.readFully(iv)) {
                        throw IllegalArgumentException(activity.getString(R.string.om_dec_corrupt_iv))
                    }

                    val keySpec = PBEKeySpec(pwdChars, salt, ITERATIONS, KEY_LENGTH)
                    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    keyBytes = factory.generateSecret(keySpec).encoded
                    keySpec.clearPassword()
                    val secretKey = SecretKeySpec(keyBytes, "AES")

                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))

                    tempPath.newOutputStream().use { outStream ->
                        CipherInputStream(inStream, cipher).use { cipherIn ->
                            val buf = ByteArray(65536)
                            var read: Int
                            try {
                                while (cipherIn.read(buf).also { read = it } != -1) {
                                    outStream.write(buf, 0, read)
                                }
                            } catch (e: Exception) {
                                isWrongPassword = true
                                throw e
                            }
                        }
                    }
                }

                moveTempToTarget(tempPath, targetPath)

                if (deleteEnc) {
                    runCatching { sourcePath.deleteIfExists() }
                }
                null
            } catch (e: Exception) {
                e.printStackTrace()
                runCatching { tempPath.deleteIfExists() }
                if (isWrongPassword || e is BadPaddingException) {
                    activity.getString(R.string.om_dec_wrong_password)
                } else {
                    e.localizedMessage ?: e.javaClass.simpleName
                }
            } finally {
                pwdChars.fill('\u0000')
                keyBytes?.fill(0)
            }
        }
    }

    private fun InputStream.readFully(b: ByteArray): Boolean {
        var offset = 0
        while (offset < b.size) {
            val count = read(b, offset, b.size - offset)
            if (count == -1) return false
            offset += count
        }
        return true
    }
}
