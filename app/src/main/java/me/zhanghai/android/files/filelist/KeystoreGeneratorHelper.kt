/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.util.encoders.Base64

object KeystoreGeneratorHelper {

    data class Config(
        val directory: File,
        val fileName: String,
        val alias: String,
        val storePassword: String,
        val keyPassword: String,
        val keySize: Int = 2048,
        val validityYears: Int = 25,
        val generateJks: Boolean = true,
        val generateBks: Boolean = false,
        val generateAospKeys: Boolean = false,
        val commonName: String = "Android",
        val organizationUnit: String = "",
        val organizationName: String = "",
        val localityName: String = "",
        val stateName: String = "",
        val country: String = "US"
    )

    data class Result(
        val jksFile: File?,
        val bksFile: File?,
        val pk8File: File?,
        val pemFile: File?,
        val alias: String,
        val storePassword: String,
        val keyPassword: String,
        val sha256Fingerprint: String,
        val sha1Fingerprint: String,
        val md5Fingerprint: String,
        val certificate: X509Certificate
    )

    data class KeystoreCertificateInfo(
        val alias: String,
        val isKeyEntry: Boolean,
        val algorithm: String,
        val subject: String,
        val issuer: String,
        val notBefore: Date,
        val notAfter: Date,
        val serialNumber: String,
        val sha256: String,
        val sha1: String,
        val md5: String
    )

    /**
     * Generates custom signing keys and certificates according to [config].
     */
    fun generateKeystore(config: Config): Result {
        if (!config.directory.exists()) {
            config.directory.mkdirs()
        }

        // 1. Generate RSA KeyPair
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(config.keySize, SecureRandom())
        val keyPair = kpg.generateKeyPair()
        val privateKey = keyPair.private
        val publicKey = keyPair.public

        // 2. Build Distinguished Name (X.500)
        val nameBuilder = X500NameBuilder(BCStyle.INSTANCE)
        val cn = if (config.commonName.isNotBlank()) config.commonName.trim() else "Android"
        nameBuilder.addRDN(BCStyle.CN, cn)
        if (config.organizationUnit.isNotBlank()) {
            nameBuilder.addRDN(BCStyle.OU, config.organizationUnit.trim())
        }
        if (config.organizationName.isNotBlank()) {
            nameBuilder.addRDN(BCStyle.O, config.organizationName.trim())
        }
        if (config.localityName.isNotBlank()) {
            nameBuilder.addRDN(BCStyle.L, config.localityName.trim())
        }
        if (config.stateName.isNotBlank()) {
            nameBuilder.addRDN(BCStyle.ST, config.stateName.trim())
        }
        if (config.country.isNotBlank()) {
            nameBuilder.addRDN(BCStyle.C, config.country.trim().take(2).uppercase())
        }
        val x500Name = nameBuilder.build()

        // 3. Validity & Serial
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 86400000L) // Yesterday to avoid timezone issues
        val notAfter = Date(now + config.validityYears.toLong() * 365L * 24L * 3600000L)
        val serialNumber = BigInteger.valueOf(now)

        // 4. Build X.509 v3 Certificate
        val certBuilder = JcaX509v3CertificateBuilder(
            x500Name,
            serialNumber,
            notBefore,
            notAfter,
            x500Name,
            publicKey
        )

        try {
            val extUtils = JcaX509ExtensionUtils()
            certBuilder.addExtension(
                Extension.subjectKeyIdentifier,
                false,
                extUtils.createSubjectKeyIdentifier(publicKey)
            )
        } catch (_: Throwable) {}

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
        val certHolder = certBuilder.build(signer)
        val cert = JcaX509CertificateConverter().getCertificate(certHolder)

        // 5. Calculate Fingerprints
        val sha256 = formatFingerprint(cert.encoded, "SHA-256")
        val sha1 = formatFingerprint(cert.encoded, "SHA-1")
        val md5 = formatFingerprint(cert.encoded, "MD5")

        val rawFileName = config.fileName.trim().ifEmpty { "my-release-key.jks" }
        val baseName = rawFileName
            .removeSuffix(".jks")
            .removeSuffix(".keystore")
            .removeSuffix(".bks")
            .removeSuffix(".p12")

        var jksFile: File? = null
        var bksFile: File? = null
        var pk8File: File? = null
        var pemFile: File? = null

        // 6. Write JKS / PKCS12 KeyStore
        if (config.generateJks) {
            val finalName = if (rawFileName.endsWith(".jks", ignoreCase = true) || rawFileName.endsWith(".keystore", ignoreCase = true)) {
                rawFileName
            } else {
                "$baseName.jks"
            }
            jksFile = File(config.directory, finalName)
            writePkcs12Keystore(jksFile, config.alias, privateKey, config.keyPassword, cert, config.storePassword)
        }

        // 7. Write BKS KeyStore
        if (config.generateBks) {
            bksFile = File(config.directory, "$baseName.bks")
            writeBksKeystore(bksFile, config.alias, privateKey, config.keyPassword, cert, config.storePassword)
        }

        // 8. Write AOSP Pair Keys (.pk8 + .pem)
        if (config.generateAospKeys) {
            pk8File = File(config.directory, "$baseName.pk8")
            pemFile = File(config.directory, "$baseName.x509.pem")
            writeAospKeys(pk8File, pemFile, privateKey, cert)
        }

        return Result(
            jksFile = jksFile,
            bksFile = bksFile,
            pk8File = pk8File,
            pemFile = pemFile,
            alias = config.alias,
            storePassword = config.storePassword,
            keyPassword = config.keyPassword,
            sha256Fingerprint = sha256,
            sha1Fingerprint = sha1,
            md5Fingerprint = md5,
            certificate = cert
        )
    }

    private fun writePkcs12Keystore(
        file: File,
        alias: String,
        privateKey: PrivateKey,
        keyPass: String,
        cert: X509Certificate,
        storePass: String
    ) {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, storePass.toCharArray())
        ks.setKeyEntry(alias, privateKey, keyPass.toCharArray(), arrayOf(cert))
        FileOutputStream(file).use { fos ->
            ks.store(fos, storePass.toCharArray())
        }
    }

    private fun writeBksKeystore(
        file: File,
        alias: String,
        privateKey: PrivateKey,
        keyPass: String,
        cert: X509Certificate,
        storePass: String
    ) {
        val ks = try {
            KeyStore.getInstance("BKS", "BC")
        } catch (_: Throwable) {
            KeyStore.getInstance("BKS")
        }
        ks.load(null, storePass.toCharArray())
        ks.setKeyEntry(alias, privateKey, keyPass.toCharArray(), arrayOf(cert))
        FileOutputStream(file).use { fos ->
            ks.store(fos, storePass.toCharArray())
        }
    }

    private fun writeAospKeys(
        pk8File: File,
        pemFile: File,
        privateKey: PrivateKey,
        cert: X509Certificate
    ) {
        FileOutputStream(pk8File).use { fos ->
            fos.write(privateKey.encoded)
        }
        FileOutputStream(pemFile).use { fos ->
            fos.write("-----BEGIN CERTIFICATE-----\n".toByteArray(Charsets.US_ASCII))
            val base64 = Base64.toBase64String(cert.encoded).chunked(64).joinToString("\n")
            fos.write(base64.toByteArray(Charsets.US_ASCII))
            fos.write("\n-----END CERTIFICATE-----\n".toByteArray(Charsets.US_ASCII))
        }
    }

    /**
     * Inspects a KeyStore file and returns certificates and alias details.
     */
    fun inspectKeystore(file: File, password: String): List<KeystoreCertificateInfo> {
        val types = listOf("PKCS12", "JKS", "BKS")
        val passChars = password.toCharArray()

        for (type in types) {
            try {
                val ks = try {
                    if (type == "BKS") {
                        try { KeyStore.getInstance("BKS", "BC") } catch (_: Throwable) { KeyStore.getInstance("BKS") }
                    } else {
                        KeyStore.getInstance(type)
                    }
                } catch (_: Throwable) {
                    continue
                }

                FileInputStream(file).use { ks.load(it, passChars) }

                val results = mutableListOf<KeystoreCertificateInfo>()
                val aliases = ks.aliases()
                while (aliases.hasMoreElements()) {
                    val alias = aliases.nextElement()
                    val cert = ks.getCertificate(alias) as? X509Certificate ?: continue
                    val isKey = ks.isKeyEntry(alias)
                    val sha256 = formatFingerprint(cert.encoded, "SHA-256")
                    val sha1 = formatFingerprint(cert.encoded, "SHA-1")
                    val md5 = formatFingerprint(cert.encoded, "MD5")

                    results.add(
                        KeystoreCertificateInfo(
                            alias = alias,
                            isKeyEntry = isKey,
                            algorithm = cert.publicKey.algorithm,
                            subject = cert.subjectX500Principal.name,
                            issuer = cert.issuerX500Principal.name,
                            notBefore = cert.notBefore,
                            notAfter = cert.notAfter,
                            serialNumber = cert.serialNumber.toString(16),
                            sha256 = sha256,
                            sha1 = sha1,
                            md5 = md5
                        )
                    )
                }
                return results
            } catch (_: Throwable) {
                // Try next type
            }
        }
        throw IllegalArgumentException("No se pudo leer el archivo KeyStore con la contraseña proporcionada.")
    }

    fun formatFingerprint(data: ByteArray, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        val digest = md.digest(data)
        return digest.joinToString(":") { "%02X".format(it) }
    }
}
