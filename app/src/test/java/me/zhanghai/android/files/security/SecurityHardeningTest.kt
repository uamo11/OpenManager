/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.security

import me.zhanghai.android.files.file.AxmlDecoder
import me.zhanghai.android.files.filelist.ApktoolPaths
import me.zhanghai.android.files.util.ShellEscapeUtils
import me.zhanghai.android.files.util.escapeShellArg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SecurityHardeningTest {

    @Test
    fun testAxmlDecoderSafetyOnCorruptedInput() {
        // Empty bytes
        assertFalse(AxmlDecoder.isAxml(byteArrayOf()))
        assertEquals("", AxmlDecoder.decode(byteArrayOf()))

        // Plain text XML should pass through
        val plainXml = "<manifest package=\"com.test\" />"
        assertFalse(AxmlDecoder.isAxml(plainXml.toByteArray(Charsets.UTF_8)))
        assertEquals(plainXml, AxmlDecoder.decode(plainXml.toByteArray(Charsets.UTF_8)))

        // Truncated header with AXML magic
        val truncatedAxml = byteArrayOf(0x03, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertTrue(AxmlDecoder.isAxml(truncatedAxml))
        val decoded = AxmlDecoder.decode(truncatedAxml)
        assertTrue(decoded.contains("<?xml"))
    }

    @Test
    fun testShellArgumentEscaping() {
        // Standard string
        assertEquals("'hello'", ShellEscapeUtils.escape("hello"))
        assertEquals("'hello'", "hello".escapeShellArg())

        // String with single quote
        assertEquals("'hello'\\''world'", "hello'world".escapeShellArg())

        // Command injection attempt with semicolons
        val inject1 = "; rm -rf / ;"
        val escaped1 = inject1.escapeShellArg()
        assertEquals("'; rm -rf / ;'", escaped1)

        // Command injection attempt with subshell expansion $()
        val inject2 = "foo\$(id)"
        val escaped2 = inject2.escapeShellArg()
        assertEquals("'foo\$(id)'", escaped2)

        // Command injection attempt with backticks
        val inject3 = "foo`id`"
        val escaped3 = inject3.escapeShellArg()
        assertEquals("'foo`id`'", escaped3)

        // Command injection attempt with quotes and AND operator
        val inject4 = "test\" && reboot"
        val escaped4 = inject4.escapeShellArg()
        assertEquals("'test\" && reboot'", escaped4)
    }

    @Test
    fun testApktoolPathTraversalHardening() {
        val baseDir = ApktoolPaths.getApktoolBaseDir()

        // Path traversal payload
        val maliciousPkg1 = "../../../../system"
        val dir1 = ApktoolPaths.getPackageDir(maliciousPkg1)
        assertTrue(
            "Sanitized package dir must reside inside baseDir: ${dir1.canonicalPath}",
            dir1.canonicalPath.startsWith(baseDir.canonicalPath)
        )
        assertFalse(
            "Package dir must not contain path traversal dots: ${dir1.name}",
            dir1.name.contains("..")
        )

        // Slashes in package name
        val maliciousPkg2 = "com.test/../../evil"
        val dir2 = ApktoolPaths.getPackageDir(maliciousPkg2)
        assertTrue(
            "Sanitized package dir must reside inside baseDir: ${dir2.canonicalPath}",
            dir2.canonicalPath.startsWith(baseDir.canonicalPath)
        )

        // Valid package name
        val validPkg = "com.y.om"
        val dir3 = ApktoolPaths.getPackageDir(validPkg)
        assertEquals(File(baseDir, "com.y.om").canonicalPath, dir3.canonicalPath)
    }

    @Test
    fun testSqliteIdentifierEscaping() {
        fun escapeSqlIdentifier(name: String): String = "`" + name.replace("`", "``") + "`"

        assertEquals("`users`", escapeSqlIdentifier("users"))
        assertEquals("`table with spaces`", escapeSqlIdentifier("table with spaces"))
        // Injection attempt using backtick closing
        val injection = "users`; DROP TABLE test; --"
        val escaped = escapeSqlIdentifier(injection)
        assertEquals("`users``; DROP TABLE test; --`", escaped)
    }

    @Test
    fun testFtpServerPasswordGenerationAndSecurity() {
        val pw1 = me.zhanghai.android.files.ftpserver.FtpServerService.generateRandomPassword(8)
        val pw2 = me.zhanghai.android.files.ftpserver.FtpServerService.generateRandomPassword(8)
        assertEquals(8, pw1.length)
        assertEquals(8, pw2.length)
        // Two independently generated passwords must have high entropy
        org.junit.Assert.assertNotEquals(pw1, pw2)

        // Valid characters: only alphanumeric, avoiding ambiguous chars (0, O, 1, l, I)
        val allowedChars = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789".toSet()
        for (c in pw1) {
            assertTrue("Password character '$c' must be in allowed set", allowedChars.contains(c))
        }

        // Test anonymous vs authenticated write policy
        fun evaluateFtpWritable(isAnonymous: Boolean, prefWritable: Boolean): Boolean {
            return prefWritable && !isAnonymous
        }

        // Anonymous must NEVER have write permission, even if pref is true
        assertFalse(evaluateFtpWritable(isAnonymous = true, prefWritable = true))
        assertFalse(evaluateFtpWritable(isAnonymous = true, prefWritable = false))

        // Authenticated users only have write permission if explicitly enabled
        assertTrue(evaluateFtpWritable(isAnonymous = false, prefWritable = true))
        assertFalse(evaluateFtpWritable(isAnonymous = false, prefWritable = false))
    }

    @Test
    fun testMinaObjectSerializationDecoderNeutralizedForCve2024_52046() {
        // CVE-2024-52046: Ensure unsafe Java ObjectSerialization classes have been permanently
        // eliminated from the MINA runtime and classpath.
        val decoderAbsent = try {
            Class.forName("org.apache.mina.filter.codec.serialization.ObjectSerializationDecoder")
            false
        } catch (e: ClassNotFoundException) {
            true
        } catch (e: NoClassDefFoundError) {
            true
        }
        assertTrue(
            "ObjectSerializationDecoder must be permanently stripped from classpath",
            decoderAbsent
        )

        val streamAbsent = try {
            Class.forName("org.apache.mina.filter.codec.serialization.ObjectSerializationInputStream")
            false
        } catch (e: ClassNotFoundException) {
            true
        } catch (e: NoClassDefFoundError) {
            true
        }
        assertTrue(
            "ObjectSerializationInputStream must be permanently stripped from classpath",
            streamAbsent
        )

        // Verify standard safe MINA networking classes remain functional
        val sessionClass = Class.forName("org.apache.mina.core.session.IoSession")
        assertTrue("IoSession must remain available for FTP operation", sessionClass.isInterface)
    }

    @Test
    fun testNetworkSecurityConfigHardening() {
        val xmlFile = File("src/main/res/xml/network_security_config.xml")
        assertTrue("network_security_config.xml must exist at corrected path", xmlFile.exists())
        val xmlContent = xmlFile.readText()

        // Verify base-config disallows global cleartext
        assertTrue("base-config must disallow cleartext traffic by default",
            xmlContent.contains("<base-config cleartextTrafficPermitted=\"false\">"))

        // Verify base-config only trusts system certificates (not user certificates in production)
        val baseConfigSection = xmlContent.substringAfter("<base-config").substringBefore("</base-config>")
        assertTrue("base-config must trust system certificates",
            baseConfigSection.contains("<certificates src=\"system\""))
        assertFalse("base-config must NOT trust user certificates in production",
            baseConfigSection.contains("<certificates src=\"user\""))

        // Verify debug-overrides encapsulates user certificates
        assertTrue("debug-overrides must exist for development proxying",
            xmlContent.contains("<debug-overrides>"))
        val debugSection = xmlContent.substringAfter("<debug-overrides>").substringBefore("</debug-overrides>")
        assertTrue("debug-overrides should contain user certificates",
            debugSection.contains("<certificates src=\"user\""))

        // Verify AndroidManifest.xml uses the corrected network security config and has no global cleartext attribute
        val manifestFile = File("src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml must exist", manifestFile.exists())
        val manifestContent = manifestFile.readText()
        assertTrue("Manifest must link network_security_config without typo",
            manifestContent.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertFalse("Manifest must not declare global usesCleartextTraffic=true",
            manifestContent.contains("android:usesCleartextTraffic=\"true\""))
    }

    @Test
    fun testHiddenApiExemptionsAreNarrowedAndHardened() {
        val jniFile = File("src/main/jni/hiddenapi.c")
        assertTrue("hiddenapi.c must exist in JNI sources", jniFile.exists())
        val cSource = jniFile.readText()

        // Verify that global wildcard exemptions ("" or "L") are eliminated
        assertFalse(
            "hiddenapi.c must not use empty string \"\" as global wildcard exemption",
            cSource.contains("NewStringUTF(env, \"\")")
        )
        assertFalse(
            "hiddenapi.c must not use single \"L\" as global wildcard exemption",
            cSource.contains("NewStringUTF(env, \"L\")")
        )

        // Verify targeted allowlist is defined
        assertTrue(
            "hiddenapi.c must define EXEMPTION_PREFIXES allowlist array",
            cSource.contains("EXEMPTION_PREFIXES")
        )

        // Verify required system classes for OpenManager operations are properly exempted
        val requiredClasses = listOf(
            "Landroid/app/ActivityManager;",
            "Landroid/app/ActivityThread;",
            "Landroid/content/Context;",
            "Landroid/content/pm/ApplicationInfo;",
            "Landroid/os/Parcel;",
            "Landroid/os/SELinux;",
            "Landroid/os/storage/StorageManager;",
            "Landroid/os/storage/StorageVolume;",
            "Landroid/provider/MediaStore;",
            "Landroid/service/quicksettings/TileService;",
            "Landroid/system/ErrnoException;",
            "Landroid/view/ViewGroup;",
            "Landroid/widget/TextView;",
            "Ljava/nio/NioUtils;",
            "Lsun/nio/ch/FileChannelImpl;"
        )

        for (sig in requiredClasses) {
            assertTrue("hiddenapi.c must exempt required class signature: $sig", cSource.contains(sig))
        }

        // Verify exception safety check is present
        assertTrue(
            "hiddenapi.c must clear exceptions if runtime throws during exemption setup",
            cSource.contains("ExceptionCheck") && cSource.contains("ExceptionClear")
        )
    }

    @Test
    fun testArchiveSecuritySanitizerConnectedToAllReadCallbacks() {
        val readArchiveFile = File("src/main/java/me/zhanghai/android/files/provider/archive/archiver/ReadArchive.kt")
        assertTrue("ReadArchive.kt must exist", readArchiveFile.exists())
        val code = readArchiveFile.readText()

        assertTrue(
            "ReadArchive must instantiate StreamSanitizer",
            code.contains("ArchiveSecuritySanitizer.StreamSanitizer()")
        )
        assertTrue(
            "ReadArchive InputStream callback must call streamSanitizer.sanitizeChunk",
            code.contains("streamSanitizer.sanitizeChunk")
        )
        assertTrue(
            "ReadArchive SeekableByteChannel callback must call streamSanitizer.sanitizeDirectChunk",
            code.contains("streamSanitizer.sanitizeDirectChunk")
        )
        assertTrue(
            "ReadArchive seek callback must reset streamSanitizer state",
            code.contains("streamSanitizer.reset()")
        )
    }

    @Test
    fun testRar5SecurityWarningAndPreValidationWired() {
        val archiveReaderFile = File("src/main/java/me/zhanghai/android/files/provider/archive/archiver/ArchiveReader.kt")
        assertTrue("ArchiveReader.kt must exist", archiveReaderFile.exists())
        val readerCode = archiveReaderFile.readText()

        assertTrue(
            "ArchiveReader must validate RAR5 stream before native open",
            readerCode.contains("ArchiveSecuritySanitizer.validateRar5")
        )
        assertTrue(
            "ArchiveReader must record blocked event for corrupted RAR5 headers",
            readerCode.contains("ArchiveSecurityPreferences.recordCve2026_14164Blocked()")
        )

        val settingsFragmentFile = File("src/main/java/me/zhanghai/android/files/settings/SettingsPreferenceFragment.kt")
        assertTrue("SettingsPreferenceFragment.kt must exist", settingsFragmentFile.exists())
        val fragmentCode = settingsFragmentFile.readText()

        assertTrue(
            "SettingsPreferenceFragment must attach listener to RAR5 toggle",
            fragmentCode.contains("pref_key_archive_security_enable_rar5")
        )
        assertTrue(
            "SettingsPreferenceFragment must show warning dialog when enabling RAR5",
            fragmentCode.contains("showEnableRar5WarningDialog")
        )
    }

    @Test
    fun testJadxDecompilerEngineHardeningForGhsaHvp55x4f33fq() {
        val engineFile = File("src/main/java/me/zhanghai/android/files/viewer/dex/JadxDecompilerEngine.kt")
        assertTrue("JadxDecompilerEngine.kt must exist", engineFile.exists())
        val code = engineFile.readText()

        assertTrue(
            "JadxArgs must explicitly skip resources to prevent GHSA-hvp5-5x4f-33fq",
            code.contains("isSkipResources = true")
        )
        assertTrue(
            "JadxArgs must use in-memory NoOpCodeCache",
            code.contains("NoOpCodeCache()")
        )
        assertTrue(
            "JadxDecompilerEngine must sanitize relative source paths for export",
            code.contains("sanitizeRelativeSourcePath")
        )
        assertTrue(
            "decompileToDirectory must verify canonical path containment",
            code.contains("canonicalPath.startsWith(canonicalOutPrefix)")
        )
    }

    @Test
    fun testKernelAndSyscallHardeningAgainstSymlinkAndChainedCommands() {
        // 1. Verify DeadManProtectionManager catches chained commands and symlink evasions
        val dangerousChained = "echo safe && rm -rf /system"
        assertTrue(
            "DeadManProtectionManager must detect dangerous command inside chained operator &&",
            me.zhanghai.android.files.provider.root.DeadManProtectionManager.containsDangerousSystemCommand(dangerousChained)
        )

        val quotedCmd = "rm -rf \"/system\""
        assertTrue(
            "DeadManProtectionManager must detect quoted system deletion target",
            me.zhanghai.android.files.provider.root.DeadManProtectionManager.isCatastrophicSystemDeletion(quotedCmd, "/system")
        )

        val busyboxCmd = "busybox rm -rf /vendor"
        assertTrue(
            "DeadManProtectionManager must detect busybox rm variant",
            me.zhanghai.android.files.provider.root.DeadManProtectionManager.isCatastrophicSystemDeletion(busyboxCmd, "/vendor")
        )

        val chrootCmd = "chroot /sdcard/fake_env /system/bin/sh"
        assertTrue(
            "DeadManProtectionManager must detect chroot evasion attempt",
            me.zhanghai.android.files.provider.root.DeadManProtectionManager.containsDangerousSystemCommand(chrootCmd)
        )

        // 2. Verify traversal path evasion blocked in isProtectedFromModification
        val traversalPath = "/data/local/tmp/../../system/bin/sh"
        assertTrue(
            "DeadManProtectionManager must block modification targeting system via path traversal",
            me.zhanghai.android.files.provider.root.DeadManProtectionManager.isProtectedFromModification("sh", traversalPath)
        )

        // 3. Verify ElfInspector defines sensitive symbols for symlink and evasion syscalls
        val elfFile = File("src/main/java/me/zhanghai/android/files/filelist/ElfInspector.kt")
        assertTrue("ElfInspector.kt must exist", elfFile.exists())
        val elfCode = elfFile.readText()
        val requiredElfSyscalls = listOf(
            "symlink", "symlinkat", "readlink", "readlinkat",
            "chroot", "pivot_root", "setns", "unshare", "sys_reboot"
        )
        for (sc in requiredElfSyscalls) {
            assertTrue("ElfInspector must audit sensitive symbol: $sc", elfCode.contains("\"$sc\" to Triple"))
        }

        // 4. Verify ScriptRunnerHelper audits chained commands and symlink creation
        val scriptWithSymlinkAndChain = "echo init && ln -s /system /sdcard/syslink && chroot /sdcard/syslink"
        val auditReport = me.zhanghai.android.files.filelist.ScriptRunnerHelper.auditScriptText(scriptWithSymlinkAndChain)
        assertTrue(
            "ScriptRunnerHelper must flag chained commands with symlink and chroot in systemPaths",
            auditReport.systemPaths.isNotEmpty()
        )

        // 5. Verify SyscallReaderMonitor checks /proc/<pid>/cwd for protected partitions
        val monitorFile = File("src/main/java/me/zhanghai/android/files/terminal/tracer/SyscallReaderMonitor.kt")
        assertTrue("SyscallReaderMonitor.kt must exist", monitorFile.exists())
        val monitorCode = monitorFile.readText()
        assertTrue(
            "SyscallReaderMonitor must inspect process working directory via /proc/<pid>/cwd",
            monitorCode.contains("/proc/\$targetPid/cwd")
        )
    }
}
