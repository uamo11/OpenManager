/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import me.zhanghai.android.files.provider.sftp.client.SecurityProviderHelper
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.FileSystems
import java8.nio.file.Path
import java8.nio.file.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.newOutputStream
import me.zhanghai.android.files.theme.custom.OpenManagerPalette
import me.zhanghai.android.files.ui.bindToLifecycle
import me.zhanghai.android.files.ui.dismissSafely
import me.zhanghai.android.files.ui.isUiAlive
import me.zhanghai.android.files.ui.runWithProgressDialog
import me.zhanghai.android.files.ui.showSafeDialog
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ApkSignerHelper {

    enum class SignScheme {
        V1_AND_V2,
        V1_ONLY,
        V2_ONLY
    }

    private var cachedKeyPair: Pair<PrivateKey, X509Certificate>? = null

    fun computeDefaultSignedOutputName(fileName: String): String {
        val dotIdx = fileName.lastIndexOf('.')
        if (dotIdx > 0 && dotIdx < fileName.length - 1) {
            val ext = fileName.substring(dotIdx + 1)
            val lowerExt = ext.lowercase(Locale.ROOT)
            if (lowerExt in setOf("apk", "apks", "xapk", "apkm")) {
                val stem = fileName.substring(0, dotIdx)
                return "${stem}_signed.${lowerExt}"
            }
        }
        return "${fileName}_signed.apk"
    }

    @Synchronized
    fun getTestKeyPair(): Pair<PrivateKey, X509Certificate> {
        cachedKeyPair?.let { return it }
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val now = System.currentTimeMillis()
        val startDate = Date(now - 86400000L)
        val endDate = Date(now + 30L * 365 * 86400000L)
        val serial = BigInteger.valueOf(now)
        val owner = X500Name("CN=Android,O=Android,C=US")
        val certBuilder = JcaX509v3CertificateBuilder(
            owner, serial, startDate, endDate, owner, kp.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        val certHolder = certBuilder.build(signer)
        val cert = JcaX509CertificateConverter().getCertificate(certHolder)
        val pair = Pair(kp.private, cert)
        cachedKeyPair = pair
        return pair
    }

    fun loadCustomKeystore(
        file: File,
        storePass: String,
        alias: String,
        keyPass: String
    ): Pair<PrivateKey, X509Certificate> {
        val types = mutableListOf("PKCS12", "JKS", "BKS")

        var lastException: Exception? = null
        for (ksType in types.distinct()) {
            try {
                val ks = try {
                    if (ksType == "BKS") {
                        try { run { SecurityProviderHelper.ensureInitialized(); KeyStore.getInstance("BKS", "BC") } } catch (_: Throwable) { KeyStore.getInstance("BKS") }
                    } else {
                        KeyStore.getInstance(ksType)
                    }
                } catch (_: Throwable) {
                    continue
                }
                FileInputStream(file).use { ks.load(it, storePass.toCharArray()) }
                val privateKey = ks.getKey(alias, keyPass.toCharArray()) as? PrivateKey ?: continue
                val cert = ks.getCertificate(alias) as? X509Certificate ?: continue
                return Pair(privateKey, cert)
            } catch (e: Exception) {
                lastException = e
            }
        }
        throw lastException ?: IllegalArgumentException("Private key or certificate not found for alias: $alias")
    }

    fun signFileDirect(
        inputFile: File,
        outputFile: File,
        scheme: SignScheme = SignScheme.V1_AND_V2,
        customKey: Pair<PrivateKey, X509Certificate>? = null
    ) {
        val (privateKey, cert) = customKey ?: getTestKeyPair()
        val signerConfig = ApkSigner.SignerConfig.Builder("CERT", privateKey, listOf(cert)).build()
        val v1 = scheme == SignScheme.V1_AND_V2 || scheme == SignScheme.V1_ONLY
        val v2 = scheme == SignScheme.V1_AND_V2 || scheme == SignScheme.V2_ONLY
        val v3 = scheme == SignScheme.V1_AND_V2
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(inputFile)
            .setOutputApk(outputFile)
            .setOtherSignersSignaturesPreserved(false)
            .setV1SigningEnabled(v1)
            .setV2SigningEnabled(v2)
            .setV3SigningEnabled(v3)
            .build()
            .sign()
    }

    /**
     * Signs every `.apk` split inside a `.apks`/`.xapk`/`.apkm` archive while preserving
     * non-APK metadata/OBB entries intact. Returns the number of signed split APKs.
     */
    fun signSplitBundleDirect(
        inputBundle: File,
        outputBundle: File,
        scheme: SignScheme = SignScheme.V1_AND_V2,
        customKey: Pair<PrivateKey, X509Certificate>? = null
    ): Int {
        val keyPair = customKey ?: getTestKeyPair()
        val workParent = outputBundle.parentFile ?: inputBundle.parentFile ?: File(".")
        val workDir = File(workParent, ".om_sign_bundle_${System.currentTimeMillis()}_${System.nanoTime()}")
        workDir.mkdirs()
        val tempBundleOut = File(workDir, "signed_bundle.tmp")
        var signedCount = 0

        try {
            ZipFile(inputBundle).use { zip ->
                val splitEntries = ApkInstallHelper.collectSplitApkEntries(zip)
                if (splitEntries.isEmpty()) {
                    throw IllegalArgumentException("No APK splits found inside bundle")
                }

                ZipOutputStream(FileOutputStream(tempBundleOut)).use { zos ->
                    val buffer = ByteArray(65536)
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val name = entry.name
                        if (name.contains("..") || name.startsWith("/") ||
                            name.startsWith("__MACOSX/", ignoreCase = true)
                        ) {
                            continue
                        }

                        if (entry.isDirectory) {
                            zos.putNextEntry(ZipEntry(name))
                            zos.closeEntry()
                        } else if (name.endsWith(".apk", ignoreCase = true) && entry.size != 0L) {
                            val inSplit = File(workDir, "in_split.apk")
                            val outSplit = File(workDir, "out_split.apk")
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(inSplit).use { output ->
                                    var read: Int
                                    while (input.read(buffer).also { read = it } != -1) {
                                        output.write(buffer, 0, read)
                                    }
                                }
                            }
                            signFileDirect(inSplit, outSplit, scheme, keyPair)

                            zos.putNextEntry(ZipEntry(name))
                            FileInputStream(outSplit).use { input ->
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    zos.write(buffer, 0, read)
                                }
                            }
                            zos.closeEntry()
                            inSplit.delete()
                            outSplit.delete()
                            signedCount++
                        } else {
                            zos.putNextEntry(ZipEntry(name))
                            zip.getInputStream(entry).use { input ->
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    zos.write(buffer, 0, read)
                                }
                            }
                            zos.closeEntry()
                        }
                    }
                }
            }

            if (signedCount == 0) {
                throw IllegalArgumentException("No APK splits found inside bundle")
            }
            tempBundleOut.copyTo(outputBundle, overwrite = true)
            return signedCount
        } finally {
            workDir.deleteRecursively()
        }
    }

    fun signApk(
        activity: Activity,
        apkPath: Path,
        onComplete: ((Path?) -> Unit)? = null
    ) {
        showSignDialog(activity, apkPath, onComplete)
    }

    fun showSignDialog(
        activity: Activity,
        apkPath: Path,
        onComplete: ((Path?) -> Unit)? = null
    ) {
        if (!activity.isUiAlive) return
        val context = activity
        val dialog = AppCompatDialog(context)
        dialog.supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_mt_sign_apk, null)
        dialog.setContentView(view)
        if (activity is LifecycleOwner) {
            dialog.bindToLifecycle(activity)
        }
        OpenManagerPalette.from(context).applyToDialogSurface(view, cornerRadiusDp = 14f)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val displayMetrics = context.resources.displayMetrics
            val targetWidth = (displayMetrics.widthPixels * 0.92).toInt().coerceAtMost(
                (450 * displayMetrics.density).toInt()
            )
            setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val fileName = apkPath.fileName.toString()
        val defaultTargetFileName = computeDefaultSignedOutputName(fileName)

        val editOutputName = view.findViewById<EditText>(R.id.editOutputName)
        editOutputName.setText(defaultTargetFileName)

        val rbSchemeV1 = view.findViewById<RadioButton>(R.id.rbSchemeV1)
        val rbSchemeV2 = view.findViewById<RadioButton>(R.id.rbSchemeV2)

        val rbKeyCustom = view.findViewById<RadioButton>(R.id.rbKeyCustom)
        val layoutCustomKeyOptions = view.findViewById<LinearLayout>(R.id.layoutCustomKeyOptions)

        val editKeystorePath = view.findViewById<EditText>(R.id.editKeystorePath)
        val btnBrowseKeystore = view.findViewById<Button>(R.id.btnBrowseKeystore)
        val editKeystorePass = view.findViewById<EditText>(R.id.editKeystorePass)
        val editKeyAlias = view.findViewById<EditText>(R.id.editKeyAlias)
        val editKeyPass = view.findViewById<EditText>(R.id.editKeyPass)

        val cbOverwrite = view.findViewById<CheckBox>(R.id.cbOverwrite)
        val btnCancelSign = view.findViewById<Button>(R.id.btnCancelSign)
        val btnConfirmSign = view.findViewById<Button>(R.id.btnConfirmSign)

        val rgKeystore = view.findViewById<RadioGroup>(R.id.rgKeystore)
        rgKeystore.setOnCheckedChangeListener { _, checkedId ->
            layoutCustomKeyOptions.visibility = if (checkedId == R.id.rbKeyCustom) View.VISIBLE else View.GONE
        }

        cbOverwrite.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                editOutputName.setText(fileName)
                editOutputName.isEnabled = false
            } else {
                editOutputName.setText(defaultTargetFileName)
                editOutputName.isEnabled = true
            }
        }

        btnBrowseKeystore.setOnClickListener {
            val defaultPath = "/storage/emulated/0/Download"
            if (editKeystorePath.text.isNullOrEmpty()) {
                editKeystorePath.setText("$defaultPath/my-release-key.jks")
            }
            Toast.makeText(context, "Ingresa la ruta absoluta de tu archivo .jks o .keystore", Toast.LENGTH_SHORT).show()
        }

        val btnCreateKeystore = view.findViewById<Button>(R.id.btnCreateKeystore)
        btnCreateKeystore?.setOnClickListener {
            val parentDir = try {
                apkPath.parent?.toFile()?.absolutePath ?: "/storage/emulated/0/Download"
            } catch (_: Exception) {
                "/storage/emulated/0/Download"
            }
            if (activity is FragmentActivity) {
                KeystoreGeneratorDialogFragment.show(activity, parentDir) { result ->
                    val target = result.jksFile ?: result.bksFile
                    target?.let { editKeystorePath.setText(it.absolutePath) }
                    editKeystorePass.setText(result.storePassword)
                    editKeyAlias.setText(result.alias)
                    editKeyPass.setText(result.keyPassword)
                    rbKeyCustom.isChecked = true
                }
            }
        }

        btnCancelSign.setOnClickListener {
            dialog.dismissSafely()
        }

        btnConfirmSign.setOnClickListener {
            val outputName = editOutputName.text.toString().trim()
            if (outputName.isEmpty()) {
                Toast.makeText(context, R.string.om_sign_invalid_name, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val scheme = when {
                rbSchemeV1.isChecked -> SignScheme.V1_ONLY
                rbSchemeV2.isChecked -> SignScheme.V2_ONLY
                else -> SignScheme.V1_AND_V2
            }

            val useCustomKey = rbKeyCustom.isChecked
            val customKeyPath = editKeystorePath.text.toString().trim()
            val customKeyStorePass = editKeystorePass.text.toString()
            val customKeyAlias = editKeyAlias.text.toString().trim()
            val customKeyPass = editKeyPass.text.toString()

            if (useCustomKey && (customKeyPath.isEmpty() || customKeyAlias.isEmpty())) {
                Toast.makeText(context, R.string.om_sign_missing_keystore_fields, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dialog.dismissSafely()

            signApkInternal(
                activity = activity,
                apkPath = apkPath,
                outputFileName = outputName,
                scheme = scheme,
                useCustomKey = useCustomKey,
                customKeyPath = customKeyPath,
                customKeyStorePass = customKeyStorePass,
                customKeyAlias = customKeyAlias,
                customKeyPass = customKeyPass,
                onComplete = onComplete
            )
        }

        if (activity.isUiAlive) {
            dialog.show()
        }
    }

    private fun runBackgroundWithDialog(
        activity: Activity,
        title: String,
        message: String,
        worker: () -> Unit
    ) {
        if (activity is FragmentActivity) {
            activity.runWithProgressDialog(title = title, initialMessage = message) {
                withContext(Dispatchers.IO) { worker() }
            }
            return
        }

        val progressDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .create()
        if (activity is LifecycleOwner) {
            progressDialog.bindToLifecycle(activity)
        }
        if (activity.isUiAlive) {
            progressDialog.show()
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                worker()
            } finally {
                withContext(Dispatchers.Main) {
                    progressDialog.dismissSafely()
                }
            }
        }
    }

    private fun showUiAlert(activity: Activity, title: CharSequence, message: CharSequence, onConfigure: (MaterialAlertDialogBuilder.() -> Unit)? = null) {
        if (!activity.isUiAlive) return
        if (activity is FragmentActivity) {
            activity.showSafeDialog {
                setTitle(title)
                setMessage(message)
                setPositiveButton(android.R.string.ok, null)
                onConfigure?.invoke(this)
            }
        } else {
            val d = MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .apply { onConfigure?.invoke(this) }
                .create()
            if (activity is LifecycleOwner) {
                d.bindToLifecycle(activity)
            }
            d.show()
        }
    }

    private fun signApkInternal(
        activity: Activity,
        apkPath: Path,
        outputFileName: String,
        scheme: SignScheme,
        useCustomKey: Boolean,
        customKeyPath: String,
        customKeyStorePass: String,
        customKeyAlias: String,
        customKeyPass: String,
        onComplete: ((Path?) -> Unit)?
    ) {
        val parentDir = apkPath.parent ?: Paths.get("/storage/emulated/0/Download")
        val targetPath = parentDir.resolve(outputFileName)
        val appContext = activity.applicationContext

        runBackgroundWithDialog(
            activity = activity,
            title = activity.getString(R.string.om_sign_progress_title),
            message = activity.getString(R.string.om_sign_progress_msg_format, scheme.name)
        ) {
            var success = false
            var errorMsg: String? = null
            var tempInFile: File? = null
            var shouldDeleteTempIn = false
            var tempOutFile: File? = null

            try {
                val (privateKey, cert) = if (useCustomKey) {
                    val kf = File(customKeyPath)
                    if (!kf.exists() || !kf.canRead()) {
                        throw IllegalArgumentException("No se puede leer el archivo KeyStore:\n$customKeyPath")
                    }
                    loadCustomKeystore(kf, customKeyStorePass, customKeyAlias, customKeyPass)
                } else {
                    getTestKeyPair()
                }

                val resolvedInFile = if (apkPath.fileSystem == FileSystems.getDefault()) {
                    File(apkPath.toString())
                } else {
                    shouldDeleteTempIn = true
                    val tmp = File.createTempFile("apk_in_", ".apk", appContext.cacheDir)
                    apkPath.newInputStream().use { ins ->
                        tmp.outputStream().use { outs -> ins.copyTo(outs, bufferSize = 65536) }
                    }
                    tmp
                }
                tempInFile = resolvedInFile

                val resolvedOutFile = File.createTempFile("apk_out_", ".apk", appContext.cacheDir)
                tempOutFile = resolvedOutFile

                if (ApkInstallHelper.isSplitApkArchiveFile(resolvedInFile)) {
                    signSplitBundleDirect(resolvedInFile, resolvedOutFile, scheme, privateKey to cert)
                } else {
                    signFileDirect(resolvedInFile, resolvedOutFile, scheme, privateKey to cert)
                }

                if (targetPath.fileSystem == FileSystems.getDefault()) {
                    val destFile = File(targetPath.toString())
                    resolvedOutFile.copyTo(destFile, overwrite = true)
                } else {
                    resolvedOutFile.inputStream().use { ins ->
                        targetPath.newOutputStream().use { outs -> ins.copyTo(outs, bufferSize = 65536) }
                    }
                }

                success = true
            } catch (e: Exception) {
                e.printStackTrace()
                errorMsg = e.localizedMessage ?: e.javaClass.simpleName
            } finally {
                if (shouldDeleteTempIn) {
                    tempInFile?.delete()
                }
                tempOutFile?.delete()
            }

            activity.runOnUiThread {
                if (!activity.isUiAlive) return@runOnUiThread
                if (success) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.om_sign_success_format, scheme.name, outputFileName),
                        Toast.LENGTH_LONG
                    ).show()
                    onComplete?.invoke(targetPath)
                } else {
                    showUiAlert(
                        activity = activity,
                        title = activity.getString(R.string.om_sign_error_title),
                        message = errorMsg ?: activity.getString(R.string.om_sign_unknown_error)
                    )
                    onComplete?.invoke(null)
                }
            }
        }
    }

    /**
     * Inspects and verifies signatures (V1, V2, V3) for single APKs and multi-APK split bundles.
     */
    fun showVerifySignatureDialog(activity: Activity, apkPath: Path) {
        if (!activity.isUiAlive) return
        val appContext = activity.applicationContext

        runBackgroundWithDialog(
            activity = activity,
            title = activity.getString(R.string.om_verify_progress_title),
            message = activity.getString(R.string.om_verify_progress_msg)
        ) {
            var tempFile: File? = null
            var shouldDeleteTemp = false
            var v1Ok = false
            var v2Ok = false
            var v3Ok = false
            var certSummary = activity.getString(R.string.om_verify_no_certs)
            var md5Hex = ""
            var sha1Hex = ""
            var sha256Hex = ""
            var errorMsg: String? = null

            try {
                val resolvedFile = if (apkPath.fileSystem == FileSystems.getDefault()) {
                    File(apkPath.toString())
                } else {
                    shouldDeleteTemp = true
                    val tmp = File.createTempFile("verify_", ".apk", appContext.cacheDir)
                    apkPath.newInputStream().use { ins ->
                        tmp.outputStream().use { outs -> ins.copyTo(outs, bufferSize = 65536) }
                    }
                    tmp
                }
                tempFile = resolvedFile

                var primaryCerts: List<X509Certificate> = emptyList()
                if (ApkInstallHelper.isSplitApkArchiveFile(resolvedFile)) {
                    val tempVerifyDir = File(
                        appContext.cacheDir,
                        "om_verify_splits_${System.currentTimeMillis()}_${System.nanoTime()}"
                    ).apply { mkdirs() }
                    try {
                        ZipFile(resolvedFile).use { zip ->
                            val splits = ApkInstallHelper.collectSplitApkEntries(zip)
                            if (splits.isEmpty()) {
                                throw IllegalArgumentException("No APK splits found inside bundle")
                            }
                            v1Ok = true
                            v2Ok = true
                            v3Ok = true
                            var firstCertDigest: ByteArray? = null
                            val sha256Md = MessageDigest.getInstance("SHA-256")
                            val buffer = ByteArray(65536)

                            for (split in splits) {
                                val entry = zip.getEntry(split.zipEntryName) ?: continue
                                val extracted = File(tempVerifyDir, split.sessionEntryName)
                                try {
                                    zip.getInputStream(entry).use { input ->
                                        FileOutputStream(extracted).use { output ->
                                            var read: Int
                                            while (input.read(buffer).also { read = it } != -1) {
                                                output.write(buffer, 0, read)
                                            }
                                        }
                                    }
                                    val res = ApkVerifier.Builder(extracted).build().verify()
                                    if (!res.isVerified) {
                                        val firstErr = res.errors.firstOrNull()?.toString() ?: "invalid signature"
                                        errorMsg = "Split '${split.displayName}' verification failed: $firstErr"
                                        break
                                    }
                                    v1Ok = v1Ok && res.isVerifiedUsingV1Scheme
                                    v2Ok = v2Ok && res.isVerifiedUsingV2Scheme
                                    v3Ok = v3Ok && res.isVerifiedUsingV3Scheme

                                    val splitCerts = res.signerCertificates
                                    if (splitCerts.isEmpty()) {
                                        errorMsg = "Split '${split.displayName}' has missing signer certificate"
                                        break
                                    }
                                    val digest = sha256Md.digest(splitCerts[0].encoded)
                                    if (firstCertDigest == null) {
                                        firstCertDigest = digest
                                        primaryCerts = splitCerts
                                    } else if (!firstCertDigest.contentEquals(digest)) {
                                        errorMsg = "Split '${split.displayName}' has a mismatched signer certificate"
                                        break
                                    }
                                } finally {
                                    extracted.delete()
                                }
                            }
                        }
                    } finally {
                        tempVerifyDir.deleteRecursively()
                    }
                } else {
                    val result = ApkVerifier.Builder(resolvedFile).build().verify()
                    v1Ok = result.isVerifiedUsingV1Scheme
                    v2Ok = result.isVerifiedUsingV2Scheme
                    v3Ok = result.isVerifiedUsingV3Scheme
                    primaryCerts = result.signerCertificates
                }

                if (errorMsg == null && primaryCerts.isNotEmpty()) {
                    val c = primaryCerts[0]
                    val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    val sb = StringBuilder()
                    sb.append(activity.getString(R.string.om_verify_cert_subject)).append(c.subjectX500Principal.name).append("\n")
                    sb.append(activity.getString(R.string.om_verify_cert_issuer)).append(c.issuerX500Principal.name).append("\n")
                    sb.append(activity.getString(R.string.om_verify_cert_valid_from)).append(df.format(c.notBefore)).append("\n")
                    sb.append(activity.getString(R.string.om_verify_cert_valid_until)).append(df.format(c.notAfter)).append("\n")
                    sb.append(activity.getString(R.string.om_verify_cert_serial)).append(c.serialNumber.toString(16)).append("\n")
                    certSummary = sb.toString()

                    val der = c.encoded
                    md5Hex = MessageDigest.getInstance("MD5").digest(der).joinToString(":") { "%02X".format(it) }
                    sha1Hex = MessageDigest.getInstance("SHA-1").digest(der).joinToString(":") { "%02X".format(it) }
                    sha256Hex = MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                errorMsg = e.localizedMessage ?: e.javaClass.simpleName
            } finally {
                if (shouldDeleteTemp) {
                    tempFile?.delete()
                }
            }

            activity.runOnUiThread {
                if (!activity.isUiAlive) return@runOnUiThread
                val currentErr = errorMsg
                if (currentErr != null) {
                    showUiAlert(
                        activity = activity,
                        title = activity.getString(R.string.om_verify_error_title),
                        message = currentErr
                    ) {
                        setPositiveButton(R.string.close, null)
                    }
                    return@runOnUiThread
                }

                val validText = activity.getString(R.string.om_verify_valid)
                val invalidText = activity.getString(R.string.om_verify_invalid)
                val msg = StringBuilder()
                msg.append(activity.getString(R.string.om_verify_file_label)).append(apkPath.fileName).append("\n\n")
                msg.append(activity.getString(R.string.om_verify_scheme_v1)).append(if (v1Ok) validText else invalidText).append("\n")
                msg.append(activity.getString(R.string.om_verify_scheme_v2)).append(if (v2Ok) validText else invalidText).append("\n")
                msg.append(activity.getString(R.string.om_verify_scheme_v3)).append(if (v3Ok) validText else invalidText).append("\n\n")
                msg.append(activity.getString(R.string.om_verify_cert_info_header))
                msg.append(certSummary).append("\n")
                if (sha256Hex.isNotEmpty()) {
                    msg.append("MD5:\n").append(md5Hex).append("\n\n")
                    msg.append("SHA-1:\n").append(sha1Hex).append("\n\n")
                    msg.append("SHA-256:\n").append(sha256Hex)
                }

                val fullText = msg.toString()
                showUiAlert(
                    activity = activity,
                    title = activity.getString(R.string.om_verify_dialog_title),
                    message = fullText
                ) {
                    setNeutralButton(R.string.copy) { _, _ ->
                        val cb = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.om_verify_clip_label), fullText))
                        Toast.makeText(activity, R.string.om_copied_to_clipboard, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
