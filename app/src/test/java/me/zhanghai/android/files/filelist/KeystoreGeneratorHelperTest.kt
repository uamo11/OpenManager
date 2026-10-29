/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class KeystoreGeneratorHelperTest {

    @Test
    fun testGenerateJksKeystore() {
        val tempDir = Files.createTempDirectory("keystore_test").toFile()
        try {
            val config = KeystoreGeneratorHelper.Config(
                directory = tempDir,
                fileName = "my-release-key.jks",
                alias = "openmanager",
                storePassword = "password123",
                keyPassword = "password123",
                keySize = 2048,
                validityYears = 25,
                generateJks = true,
                generateBks = false,
                generateAospKeys = false,
                commonName = "Test Developer",
                organizationName = "OpenManager Team",
                country = "ES"
            )

            val result = KeystoreGeneratorHelper.generateKeystore(config)

            assertNotNull(result.jksFile)
            assertTrue(result.jksFile!!.exists())
            assertTrue(result.jksFile!!.length() > 0)
            assertEquals("openmanager", result.alias)
            assertTrue(result.sha256Fingerprint.isNotEmpty())
            assertTrue(result.sha1Fingerprint.isNotEmpty())
            assertTrue(result.md5Fingerprint.isNotEmpty())

            // Verify with ApkSignerHelper.loadCustomKeystore
            val (privateKey, cert) = ApkSignerHelper.loadCustomKeystore(
                result.jksFile!!,
                "password123",
                "openmanager",
                "password123"
            )
            assertNotNull(privateKey)
            assertNotNull(cert)
            assertEquals("RSA", privateKey.algorithm)
            assertTrue(cert.subjectX500Principal.name.contains("Test Developer"))
            assertTrue(cert.subjectX500Principal.name.contains("OpenManager Team"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testGenerateAospPairKeys() {
        val tempDir = Files.createTempDirectory("aosp_keys_test").toFile()
        try {
            val config = KeystoreGeneratorHelper.Config(
                directory = tempDir,
                fileName = "testkey",
                alias = "android",
                storePassword = "password123",
                keyPassword = "password123",
                generateJks = false,
                generateBks = false,
                generateAospKeys = true,
                commonName = "Android",
                organizationName = "Google",
                country = "US"
            )

            val result = KeystoreGeneratorHelper.generateKeystore(config)

            assertNotNull(result.pk8File)
            assertNotNull(result.pemFile)
            assertTrue(result.pk8File!!.exists() && result.pk8File!!.length() > 0)
            assertTrue(result.pemFile!!.exists() && result.pemFile!!.length() > 0)

            val pemContent = result.pemFile!!.readText()
            assertTrue(pemContent.startsWith("-----BEGIN CERTIFICATE-----"))
            assertTrue(pemContent.contains("-----END CERTIFICATE-----"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testInspectKeystore() {
        val tempDir = Files.createTempDirectory("inspect_ks_test").toFile()
        try {
            val config = KeystoreGeneratorHelper.Config(
                directory = tempDir,
                fileName = "sample-key.jks",
                alias = "sample_alias",
                storePassword = "sample_pass",
                keyPassword = "sample_pass",
                commonName = "Sample CN",
                country = "US"
            )

            val result = KeystoreGeneratorHelper.generateKeystore(config)
            assertNotNull(result.jksFile)

            val inspectResults = KeystoreGeneratorHelper.inspectKeystore(result.jksFile!!, "sample_pass")
            assertEquals(1, inspectResults.size)

            val entry = inspectResults[0]
            assertEquals("sample_alias", entry.alias)
            assertEquals("RSA", entry.algorithm)
            assertTrue(entry.isKeyEntry)
            assertTrue(entry.subject.contains("Sample CN"))
            assertEquals(result.sha256Fingerprint, entry.sha256)
            assertEquals(result.sha1Fingerprint, entry.sha1)
            assertEquals(result.md5Fingerprint, entry.md5)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testFormatFingerprint() {
        val testBytes = byteArrayOf(0x12, 0x34, 0xAB.toByte(), 0xCD.toByte())
        val formatted = KeystoreGeneratorHelper.formatFingerprint(testBytes, "SHA-256")
        assertNotNull(formatted)
        assertTrue(formatted.contains(":"))
    }
}
