/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.value.ValueType
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java8.nio.file.Paths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.filelist.AntiSplitEngine
import me.zhanghai.android.files.filelist.ApkSignerHelper
import me.zhanghai.android.files.provider.root.DeadManProtectionManager
import me.zhanghai.android.files.terminal.tracer.ScriptTracerPreferences
import me.zhanghai.android.files.theme.custom.OpenManagerPalette
import me.zhanghai.android.files.ui.bindToLifecycle
import me.zhanghai.android.files.ui.dismissSafely
import me.zhanghai.android.files.ui.isUiAlive
import me.zhanghai.android.files.update.UpdateChecker
import me.zhanghai.android.files.util.createInstallPackageIntent

object TerminalExpansionManager {
    const val OM_TERMEX_PACKAGE = "com.y.om.termex"
    const val TERMEX_PACKAGE = "bin.mt.termex"
    private val SUPPORTED_PACKAGES = listOf(OM_TERMEX_PACKAGE, TERMEX_PACKAGE)
    private const val ATTR_HAS_CODE = 0x0101000c

    // Official self-hosted release asset (optional) and Termux upstream bootstrap archive
    private const val PREBUILT_TERMEX_URL_TEMPLATE =
        "https://github.com/uamo11/OpenManager/releases/latest/download/OpenManager-TermEx-%s.apk"
    private const val TERMUX_BOOTSTRAP_URL_TEMPLATE =
        "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-%s.zip"

    private val CORE_CLI_TOOLS = listOf(
        "coreutils", "grep", "gawk", "sed", "find", "tar",
        "gzip", "bzip2", "xz", "unzip", "curl", "diff", "patch", "nano"
    )
    private val SYSTEM_LIBS = setOf("libc.so", "libdl.so", "libm.so", "liblog.so")
    private val SO_DEP_SCAN_REGEX = Regex("(lib[a-zA-Z0-9_+.-]+?\\.so(?:\\.[0-9][0-9.]*)?)\\u0000")
    private val SO_VERSION_NORM_REGEX = Regex("^(lib[a-zA-Z0-9_+.-]+?)\\.so\\.([0-9][0-9.]*)$")

    private const val EXTENDED_SYSTEM_PATH =
        "/system/bin:/system/xbin:/vendor/bin:/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/sbin:/data/adb/ksu/bin:/data/adb/ap/bin:/data/adb/magisk"

    data class LaunchConfig(
        val executable: String,
        val cwd: String,
        val args: Array<String>,
        val env: Array<String>,
        val isTermex: Boolean,
        val isRoot: Boolean
    )

    fun resolveDeviceArchPair(): Pair<String, String> {
        val abi = Build.SUPPORTED_ABIS.firstOrNull()?.lowercase(Locale.US) ?: "arm64-v8a"
        return when {
            abi.contains("arm64") || abi.contains("aarch64") -> "arm64-v8a" to "aarch64"
            abi.contains("armeabi") || abi == "arm" -> "armeabi-v7a" to "arm"
            abi.contains("x86_64") -> "x86_64" to "x86_64"
            abi.contains("x86") || abi.contains("i686") -> "x86" to "i686"
            else -> "arm64-v8a" to "aarch64"
        }
    }

    fun getInstalledTermexPackage(context: Context = application): String? {
        for (pkg in SUPPORTED_PACKAGES) {
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun isTermexInstalled(context: Context = application): Boolean =
        getInstalledTermexPackage(context) != null

    fun getTermexVersion(context: Context = application): String? {
        val pkg = getInstalledTermexPackage(context) ?: return null
        return try {
            val info = context.packageManager.getPackageInfo(pkg, 0)
            val name = info.versionName ?: "3.10"
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64"
            "$name $abi"
        } catch (_: Exception) {
            null
        }
    }

    fun isRootAvailable(): Boolean {
        return try {
            me.zhanghai.android.files.provider.root.LibSuFileServiceLauncher.isSuAvailable() ||
                (com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Finds bash executable either from the extension native library dir or extracted in filesDir/termex/bin.
     */
    fun getBashExecutable(context: Context = application): File? {
        val pkg = getInstalledTermexPackage(context) ?: return null
        try {
            val pkgInfo = context.packageManager.getPackageInfo(pkg, 0)
            val nativeLibDir = pkgInfo.applicationInfo?.nativeLibraryDir ?: ""
            val apkPath = pkgInfo.applicationInfo?.sourceDir ?: ""

            val termexDir = File(context.filesDir, "termex")
            val binDir = File(termexDir, "bin").apply { mkdirs() }
            File(termexDir, "lib").apply { mkdirs() }

            // 1. Check native library directory (bash, libbash.so)
            val candidates = arrayOf(
                File(nativeLibDir, "libbash.so"),
                File(nativeLibDir, "bash")
            )
            for (cand in candidates) {
                if (cand.exists() && cand.canExecute()) {
                    return cand
                }
            }

            // 2. Check private termex/bin/bash
            val privateBash = File(binDir, "bash")
            if (privateBash.exists() && privateBash.canExecute() && privateBash.length() > 0) {
                return privateBash
            }

            // 3. Extract bash or libbash.so from extension APK if available
            if (apkPath.isNotEmpty() && File(apkPath).exists()) {
                extractTermexBinary(apkPath, "bash", privateBash)
                if (privateBash.exists() && privateBash.length() > 0) {
                    privateBash.setReadable(true, false)
                    privateBash.setExecutable(true, false)
                    return privateBash
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun isBashAvailable(context: Context = application): Boolean {
        return getBashExecutable(context) != null
    }

    /**
     * Ensures standalone command-line tools (such as apktool and bash) are deployed
     * in context.filesDir/bin and marked executable.
     */
    fun ensureBinTools(context: Context): File {
        val binDir = File(context.filesDir, "bin").apply { mkdirs() }

        // 1. Record application APK path for app_process CLASSPATH lookup
        val appApkPath = context.applicationInfo?.sourceDir ?: ""
        if (appApkPath.isNotEmpty() && File(appApkPath).exists()) {
            try {
                val apkPathFile = File(binDir, ".apk_path")
                if (!apkPathFile.exists() || apkPathFile.readText() != appApkPath) {
                    apkPathFile.writeText(appApkPath)
                }
            } catch (_: Throwable) {}
        }

        // 2. Deploy or update standalone 'apktool' CLI wrapper script
        val apktoolFile = File(binDir, "apktool")
        val apktoolContent = """
            #!/system/bin/sh
            # OpenManager Standalone Apktool CLI for Android (ART Native)
            BIN_DIR="${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd)"
            APK_PATH="${'$'}(cat "${'$'}BIN_DIR/.apk_path" 2>/dev/null)"
            if [ -z "${'$'}APK_PATH" ] || [ ! -f "${'$'}APK_PATH" ]; then
                APK_PATH="${'$'}(pm path ${context.packageName} 2>/dev/null | head -n 1 | cut -d: -f2)"
            fi
            if [ -z "${'$'}APK_PATH" ] || [ ! -f "${'$'}APK_PATH" ]; then
                APK_PATH="${'$'}(pm path me.zhanghai.android.files 2>/dev/null | head -n 1 | cut -d: -f2)"
            fi
            if [ -z "${'$'}APK_PATH" ] || [ ! -f "${'$'}APK_PATH" ]; then
                echo "Error: OpenManager APK not found on device." >&2
                exit 1
            fi
            export CLASSPATH="${'$'}APK_PATH"
            exec /system/bin/app_process /system/bin me.zhanghai.android.files.filelist.ApktoolCli "${'$'}@"
        """.trimIndent() + "\n"

        try {
            if (!apktoolFile.exists() || apktoolFile.readText() != apktoolContent) {
                apktoolFile.writeText(apktoolContent)
            }
            apktoolFile.setReadable(true, false)
            apktoolFile.setExecutable(true, false)
        } catch (_: Throwable) {}

        // 3. Aggressively purge any legacy files or scripts (rm, rmdir, om_tracer, bash, etc.) from binDir.
        // Android 10+ (targetSdk 29+) SELinux strictly blocks untrusted apps from executing
        // scripts or binaries from internal storage (app_data_file) via execve (W^X violation).
        // Only apktool and .apk_path are allowed in binDir. Any other file is deleted immediately.
        binDir.listFiles()?.forEach { file ->
            if (file.name != "apktool" && file.name != ".apk_path") {
                try {
                    file.setWritable(true, false)
                    val deleted = file.delete()
                    if (!deleted || file.exists()) {
                        try {
                            file.writeBytes(ByteArray(0))
                        } catch (_: Throwable) {}
                        val trashFile = File(binDir, ".trash_${System.currentTimeMillis()}_${file.name}")
                        file.renameTo(trashFile)
                        trashFile.delete()
                    }
                } catch (_: Throwable) {}
            }
        }
        if (isRootAvailable()) {
            try {
                com.topjohnwu.superuser.Shell.cmd(
                    "rm -f '${binDir.absolutePath}/rm' '${binDir.absolutePath}/rmdir' '${binDir.absolutePath}/om_tracer' 2>/dev/null || true"
                ).exec()
            } catch (_: Throwable) {}
        }

        return binDir
    }

    private fun ensureShellInitScript(context: Context): File {
        val initFile = File(context.filesDir, "om_shell_rc.sh")
        try {
            val rcContent = """
                alias ll='ls -la'
                alias la='ls -A'
                alias l='ls -CF'
                alias cls='clear'
                alias ..='cd ..'
                alias ...='cd ../..'
                export LANG=en_US.UTF-8
                export COLORTERM=truecolor

                # Shell function interceptors for dead man & deletion policy without SELinux execve violations
                rm() {
                    if [ "${'$'}OM_DEAD_MAN_ACTIVE" = "1" ]; then
                        case "${'$'}*" in
                            *" /"*|*" /*"*|*"-rf / "*|*"-rf /"*|*"-fr / "*|*"-fr /"*|*"/system"*|*"/vendor"*|*"/product"*|*"/apex"*|*"/system_ext"*|*"/dev/block"*)
                                echo "[OM_TRACER:DEADMAN_DELETE:rm:${'$'}*]"
                                echo "[DEAD MAN PROTECTION] Deletion of protected system paths blocked: ${'$'}*" >&2
                                return 1
                                ;;
                        esac
                    fi
                    if [ "${'$'}OM_TRACER_DISABLED" != "1" ]; then
                        echo "[OM_TRACER:DELETE:rm:${'$'}*]"
                        if [ "${'$'}OM_DELETION_POLICY" = "BLOCK_ALL" ]; then
                            echo "[OM_TRACER:BLOCKED] rm ${'$'}*" >&2
                            return 1
                        fi
                    fi
                    if [ -x /system/bin/rm ]; then
                        /system/bin/rm "${'$'}@"
                    else
                        command rm "${'$'}@"
                    fi
                }

                rmdir() {
                    if [ "${'$'}OM_DEAD_MAN_ACTIVE" = "1" ]; then
                        case "${'$'}*" in
                            *"/system"*|*"/vendor"*|*"/product"*|*"/apex"*|*"/system_ext"*|*"/dev/block"*)
                                echo "[OM_TRACER:DEADMAN_DELETE:rmdir:${'$'}*]"
                                echo "[DEAD MAN PROTECTION] Deletion of protected system paths blocked: ${'$'}*" >&2
                                return 1
                                ;;
                        esac
                    fi
                    if [ "${'$'}OM_TRACER_DISABLED" != "1" ]; then
                        echo "[OM_TRACER:DELETE:rmdir:${'$'}*]"
                        if [ "${'$'}OM_DELETION_POLICY" = "BLOCK_ALL" ]; then
                            echo "[OM_TRACER:BLOCKED] rmdir ${'$'}*" >&2
                            return 1
                        fi
                    fi
                    if [ -x /system/bin/rmdir ]; then
                        /system/bin/rmdir "${'$'}@"
                    else
                        command rmdir "${'$'}@"
                    fi
                }

                unlink() {
                    if [ "${'$'}OM_DEAD_MAN_ACTIVE" = "1" ]; then
                        case "${'$'}*" in
                            *" /"*|*" /*"*|*"/system"*|*"/vendor"*|*"/product"*|*"/apex"*|*"/system_ext"*|*"/dev/block"*)
                                echo "[OM_TRACER:DEADMAN_DELETE:unlink:${'$'}*]"
                                echo "[DEAD MAN PROTECTION] Deletion of protected system paths blocked: ${'$'}*" >&2
                                return 1
                                ;;
                        esac
                    fi
                    if [ "${'$'}OM_TRACER_DISABLED" != "1" ]; then
                        echo "[OM_TRACER:DELETE:unlink:${'$'}*]"
                        if [ "${'$'}OM_DELETION_POLICY" = "BLOCK_ALL" ]; then
                            echo "[OM_TRACER:BLOCKED] unlink ${'$'}*" >&2
                            return 1
                        fi
                    fi
                    if [ -x /system/bin/unlink ]; then
                        /system/bin/unlink "${'$'}@"
                    else
                        command unlink "${'$'}@"
                    fi
                }

                if [ -n "${'$'}OM_TERMEX_NATIVE_DIR" ] && [ -d "${'$'}OM_TERMEX_NATIVE_DIR" ]; then
                    for _om_cmd in grep gawk sed find tar gzip bzip2 xz unzip curl diff patch nano; do
                        if [ -x "${'$'}OM_TERMEX_NATIVE_DIR/libom_cmd_${'$'}{_om_cmd}.so" ]; then
                            eval "${'$'}{_om_cmd}() { \"${'$'}OM_TERMEX_NATIVE_DIR/libom_cmd_${'$'}{_om_cmd}.so\" \"\${'$'}@\"; }"
                            export -f "${'$'}_om_cmd" 2>/dev/null || true
                        fi
                    done
                    if [ -x "${'$'}OM_TERMEX_NATIVE_DIR/libom_cmd_grep.so" ]; then
                        egrep() { "${'$'}OM_TERMEX_NATIVE_DIR/libom_cmd_grep.so" -E "${'$'}@"; }
                        fgrep() { "${'$'}OM_TERMEX_NATIVE_DIR/libom_cmd_grep.so" -F "${'$'}@"; }
                        export -f egrep fgrep 2>/dev/null || true
                    fi
                    if [ -x "${'$'}OM_TERMEX_NATIVE_DIR/libom_cmd_gawk.so" ]; then
                        awk() { "${'$'}OM_TERMEX_NATIVE_DIR/libom_cmd_gawk.so" "${'$'}@"; }
                        export -f awk 2>/dev/null || true
                    fi
                    if [ -x "${'$'}OM_TERMEX_NATIVE_DIR/libom_cmd_gzip.so" ]; then
                        gunzip() { "${'$'}OM_TERMEX_NATIVE_DIR/libom_cmd_gzip.so" -d "${'$'}@"; }
                        export -f gunzip 2>/dev/null || true
                    fi
                    if [ -x "${'$'}OM_TERMEX_NATIVE_DIR/libom_cmd_bzip2.so" ]; then
                        bunzip2() { "${'$'}OM_TERMEX_NATIVE_DIR/libom_cmd_bzip2.so" -d "${'$'}@"; }
                        export -f bunzip2 2>/dev/null || true
                    fi
                    if [ -x "${'$'}OM_TERMEX_NATIVE_DIR/libom_cmd_coreutils.so" ]; then
                        for _om_applet in head tail sort uniq wc cut tr base64 sha256sum md5sum realpath stat seq tee; do
                            eval "${'$'}{_om_applet}() { \"${'$'}OM_TERMEX_NATIVE_DIR/libom_cmd_coreutils.so\" --coreutils-prog=${'$'}{_om_applet} \"\${'$'}@\"; }"
                            export -f "${'$'}_om_applet" 2>/dev/null || true
                        done
                    fi
                    if [ -n "${'$'}PREFIX" ] && [ -f "${'$'}PREFIX/etc/bash.bashrc" ]; then
                        . "${'$'}PREFIX/etc/bash.bashrc" 2>/dev/null || true
                    fi
                fi

                export -f rm rmdir unlink 2>/dev/null || true
            """.trimIndent() + "\n"
            if (!initFile.exists() || initFile.readText() != rcContent) {
                initFile.writeText(rcContent)
                initFile.setReadable(true, false)
            }
        } catch (_: Throwable) {}
        return initFile
    }

    fun prepareLaunchConfig(
        context: Context,
        forceRoot: Boolean? = null,
        forceTermex: Boolean? = null,
        overrideCwd: String? = null,
        initialCommand: String? = null,
        activeScriptPath: String? = null
    ): LaunchConfig {
        val initScript = ensureShellInitScript(context)
        val tracerEnabled = ScriptTracerPreferences.isTracerEnabled(context, activeScriptPath)
        val deletionPolicy = ScriptTracerPreferences.getDeletionPolicy(context, activeScriptPath)
        val deadManActive = DeadManProtectionManager.isDeadManProtectionActive(context)
        val tracerEnv = arrayOf(
            "OM_TRACER_DISABLED=" + (if (tracerEnabled) "0" else "1"),
            "OM_DELETION_POLICY=" + deletionPolicy.name,
            "OM_ACTIVE_SCRIPT_PATH=" + (activeScriptPath ?: ""),
            "OM_DEAD_MAN_ACTIVE=" + (if (deadManActive) "1" else "0"),
            "BASH_ENV=" + initScript.absolutePath,
            "ENV=" + initScript.absolutePath
        )
        val asRoot = forceRoot ?: TerminalSettings.isRootSession(context)
        val useTermex = forceTermex ?: (isTermexInstalled(context) && !asRoot)
        val targetCwd = overrideCwd ?: TerminalSettings.getStartPath(context)
        val validCwd = if (File(targetCwd).isDirectory) targetCwd else "/storage/emulated/0"
        val escapedCwd = validCwd.replace("'", "'\\''")
        val histFile = File(context.filesDir, ".om_shell_history").absolutePath
        val binDir = ensureBinTools(context)

        val shellCmd = if (initialCommand != null) {
            ". '${initScript.absolutePath}' 2>/dev/null; cd '$escapedCwd' && ($initialCommand); _mt_status=\$?; exit \$_mt_status"
        } else null

        if (asRoot && isRootAvailable()) {
            val env = arrayOf(
                "TERM=xterm-256color",
                "COLORTERM=truecolor",
                "LANG=en_US.UTF-8",
                "HOME=$validCwd",
                "TMPDIR=${context.cacheDir.absolutePath}",
                "HISTFILE=$histFile",
                "ENV=${initScript.absolutePath}",
                "PATH=$EXTENDED_SYSTEM_PATH:${binDir.absolutePath}"
            ) + tracerEnv
            val args = if (shellCmd != null) {
                arrayOf("su", "-c", shellCmd)
            } else {
                arrayOf("su")
            }
            return LaunchConfig(
                executable = "su",
                cwd = validCwd,
                args = args,
                env = env,
                isTermex = false,
                isRoot = true
            )
        }

        val installedPkg = getInstalledTermexPackage(context)
        if (useTermex && installedPkg != null) {
            try {
                val pkgInfo = context.packageManager.getPackageInfo(installedPkg, 0)
                val nativeLibDir = pkgInfo.applicationInfo?.nativeLibraryDir ?: ""
                val apkPath = pkgInfo.applicationInfo?.sourceDir ?: ""

                val termexDir = File(context.filesDir, "termex")
                val libDir = File(termexDir, "lib")
                libDir.mkdirs()

                if (installedPkg == TERMEX_PACKAGE) {
                    // Legacy bin.mt.termex auxiliary libraries live under lib/<abi>/lib/
                    val marker = File(libDir, "libandroid-support.so")
                    if (!marker.exists() || marker.length() == 0L) {
                        extractTermexAuxLibraries(apkPath, libDir, termexDir)
                    }
                } else {
                    val bashrcMarker = File(termexDir, "etc/bash.bashrc")
                    if (!bashrcMarker.exists()) {
                        extractTermexAuxLibraries(apkPath, libDir, termexDir)
                    }
                }

                val bashBin = getBashExecutable(context)
                if (bashBin != null && bashBin.exists()) {
                    val prompt = "\$([ \$? == 0 ] && echo '\\[\\e[1;32m\\]' || echo '\\[\\e[1;31m\\]')\$([ \$UID == 0 ] && echo '#' || echo '➜') \\[\\e[1;36m\\]\\w\\[\\e[m\\] "
                    val env = arrayOf(
                        "PREFIX=${termexDir.absolutePath}",
                        "LD_LIBRARY_PATH=$nativeLibDir:${libDir.absolutePath}",
                        "OM_TERMEX_NATIVE_DIR=$nativeLibDir",
                        "PATH=$nativeLibDir:$EXTENDED_SYSTEM_PATH:${binDir.absolutePath}",
                        "SHELL=${bashBin.absolutePath}",
                        "TERM=xterm-256color",
                        "COLORTERM=truecolor",
                        "LANG=en_US.UTF-8",
                        "HOME=$validCwd",
                        "TMPDIR=${context.cacheDir.absolutePath}",
                        "HISTFILE=$histFile",
                        "ENV=${initScript.absolutePath}",
                        "PS1=$prompt"
                    ) + tracerEnv
                    val args = if (shellCmd != null) {
                        arrayOf("bash", "--norc", "-c", shellCmd)
                    } else {
                        arrayOf("bash", "--rcfile", initScript.absolutePath, "-i")
                    }
                    return LaunchConfig(
                        executable = bashBin.absolutePath,
                        cwd = validCwd,
                        args = args,
                        env = env,
                        isTermex = true,
                        isRoot = false
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Standard Android shell fallback (mksh / sh)
        val env = arrayOf(
            "PATH=$EXTENDED_SYSTEM_PATH:${binDir.absolutePath}",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=en_US.UTF-8",
            "HOME=$validCwd",
            "TMPDIR=${context.cacheDir.absolutePath}",
            "HISTFILE=$histFile",
            "ENV=${initScript.absolutePath}",
            "PS1=➜ \${PWD##*/} \$ "
        ) + tracerEnv
        val args = if (shellCmd != null) {
            arrayOf("sh", "-c", shellCmd)
        } else {
            arrayOf("sh", "-i")
        }
        return LaunchConfig(
            executable = "/system/bin/sh",
            cwd = validCwd,
            args = args,
            env = env,
            isTermex = false,
            isRoot = false
        )
    }

    private fun extractTermexAuxLibraries(apkPath: String, libDir: File, termexDir: File) {
        if (apkPath.isEmpty() || !File(apkPath).exists()) return
        try {
            ZipFile(File(apkPath)).use { zip ->
                val entries = zip.entries()
                val isArm64 = Build.SUPPORTED_ABIS.any { it.contains("arm64") }
                val targetPrefix = if (isArm64) "lib/arm64-v8a/lib/" else "lib/armeabi-v7a/lib/"

                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.startsWith(targetPrefix)) {
                        var fileName = entry.name.substring(targetPrefix.length)
                        if (fileName.contains("/")) {
                            fileName = fileName.substringAfterLast("/")
                        }
                        if (fileName.endsWith(".rename")) {
                            fileName = fileName.removeSuffix(".rename")
                        } else if (fileName.endsWith(".link")) {
                            fileName = fileName.removeSuffix(".link")
                        }
                        val outFile = File(libDir, fileName)
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(outFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        outFile.setReadable(true, false)
                        outFile.setExecutable(true, false)
                    } else if (entry.name == "assets/etc/bash.bashrc") {
                        val etcDir = File(termexDir, "etc")
                        etcDir.mkdirs()
                        val rcFile = File(etcDir, "bash.bashrc")
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(rcFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun extractTermexBinary(apkPath: String, binaryName: String, destinationFile: File) {
        try {
            ZipFile(File(apkPath)).use { zip ->
                val entries = zip.entries()
                val isArm64 = Build.SUPPORTED_ABIS.any { it.contains("arm64") }
                val targetAbi = if (isArm64) "arm64-v8a" else "armeabi-v7a"

                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    if (name.contains(targetAbi) && (name.endsWith("/$binaryName") || name.endsWith("/lib$binaryName.so"))) {
                        destinationFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(destinationFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        destinationFile.setReadable(true, false)
                        destinationFile.setExecutable(true, false)
                        return
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Scans storage for any existing Termex / Linux extension APK.
     */
    fun findLocalTermexApk(context: Context = application): File? {
        val searchDirs = listOfNotNull(
            File("/storage/emulated/0/Download"),
            File("/storage/emulated/0/Downloads"),
            File("/sdcard/Download"),
            File("/sdcard/Downloads"),
            context.getExternalFilesDir(null),
            File("/sdcard")
        )

        for (dir in searchDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val found = dir.listFiles { f ->
                f.isFile && f.extension.equals("apk", ignoreCase = true) &&
                    (f.name.contains("termex", ignoreCase = true) ||
                        f.name.contains("mterm", ignoreCase = true))
            }?.firstOrNull()
            if (found != null && found.length() > 0) {
                return found
            }
        }
        return null
    }

    /**
     * Launches system PackageInstaller via OpenManager's custom FileProvider.
     */
    fun installApkFile(activity: Activity, apkFile: File): Boolean {
        return try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Paths.get(apkFile.absolutePath).fileProviderUri
            } else {
                Uri.fromFile(apkFile)
            }
            val intent = uri.createInstallPackageIntent()
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(
                activity,
                activity.getString(R.string.om_termex_installer_error_format, e.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
            false
        }
    }

    fun normalizeSoFileName(libName: String): String {
        val match = SO_VERSION_NORM_REGEX.matchEntire(libName) ?: return libName
        return "${match.groupValues[1]}_${match.groupValues[2].replace('.', '_')}.so"
    }

    fun normalizeElfVersionedSoNames(elfBytes: ByteArray): ByteArray {
        val out = elfBytes.copyOf()
        val iso = String(out, StandardCharsets.ISO_8859_1)
        for (match in SO_DEP_SCAN_REGEX.findAll(iso)) {
            val start = match.range.first
            if (start > 0 && isIdentByte(out[start - 1])) continue
            val libName = match.groupValues[1]
            val normalized = normalizeSoFileName(libName)
            if (normalized != libName && normalized.length == libName.length) {
                val repl = normalized.toByteArray(StandardCharsets.US_ASCII)
                System.arraycopy(repl, 0, out, start, repl.size)
            }
        }
        return out
    }

    private fun isIdentByte(b: Byte): Boolean {
        val c = b.toInt() and 0xFF
        return (c in 'a'.code..'z'.code) ||
            (c in 'A'.code..'Z'.code) ||
            (c in '0'.code..'9'.code) ||
            c == '_'.code
    }

    private fun isElfBytes(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x7f.toByte() &&
            bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()

    private fun extractNeededLibs(elfBytes: ByteArray): Set<String> {
        val iso = String(elfBytes, StandardCharsets.ISO_8859_1)
        val libs = LinkedHashSet<String>()
        for (match in SO_DEP_SCAN_REGEX.findAll(iso)) {
            val start = match.range.first
            if (start > 0 && isIdentByte(elfBytes[start - 1])) continue
            val name = match.groupValues[1]
            if (name !in SYSTEM_LIBS) {
                libs.add(name)
            }
        }
        return libs
    }

    /**
     * Packages GNU Bash, coreutils and standard CLI utilities together with transitive shared
     * libraries from a Termux bootstrap ZIP into a signed, dex-less, SELinux-compatible APK.
     */
    fun buildTermexApkFromBootstrap(
        bootstrapZip: File,
        outputApk: File,
        apkAbi: String,
        onProgress: ((String) -> Unit)? = null
    ): Boolean {
        if (!bootstrapZip.exists() || bootstrapZip.length() == 0L) return false

        val apkFiles = LinkedHashMap<String, ByteArray>()
        ZipFile(bootstrapZip).use { zip ->
            val symlinks = HashMap<String, String>()
            zip.getEntry("SYMLINKS.txt")?.let { symEntry ->
                val text = zip.getInputStream(symEntry).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                for (rawLine in text.lineSequence()) {
                    val idx = rawLine.indexOf('\u2190')
                    if (idx <= 0) continue
                    val target = rawLine.substring(0, idx).trim()
                    val linkPath = rawLine.substring(idx + 1).trim().removePrefix("./").trimStart('/')
                    if (target.isNotEmpty() && linkPath.isNotEmpty()) {
                        symlinks[linkPath] = target
                    }
                }
            }

            fun resolveZipEntry(rawPath: String): ZipEntry? {
                var cur = rawPath.removePrefix("./").trimStart('/')
                val visited = HashSet<String>()
                for (hop in 0 until 6) {
                    zip.getEntry(cur)?.let { if (!it.isDirectory) return it }
                    if (!visited.add(cur)) break
                    val targetRaw = symlinks[cur] ?: break
                    val parent = cur.substringBeforeLast('/', "")
                    val combined = if (targetRaw.startsWith("/")) {
                        targetRaw.trimStart('/')
                    } else if (parent.isEmpty()) {
                        targetRaw
                    } else {
                        "$parent/$targetRaw"
                    }
                    val parts = ArrayDeque<String>()
                    for (seg in combined.split('/')) {
                        when (seg) {
                            "", "." -> {}
                            ".." -> if (parts.isNotEmpty()) parts.removeLast()
                            else -> parts.addLast(seg)
                        }
                    }
                    cur = parts.joinToString("/")
                }
                return null
            }

            fun readEntryBytes(entry: ZipEntry): ByteArray {
                return zip.getInputStream(entry).use { ins ->
                    val bos = ByteArrayOutputStream(entry.size.coerceIn(1024L, 4L * 1024 * 1024).toInt())
                    ins.copyTo(bos)
                    bos.toByteArray()
                }
            }

            onProgress?.invoke("Extrayendo GNU Bash y utilidades Linux…")
            val bashEntry = resolveZipEntry("bin/bash") ?: return false
            val bashRaw = readEntryBytes(bashEntry)
            if (!isElfBytes(bashRaw)) return false

            val libQueue = ArrayDeque<String>()
            val processedLibs = HashSet<String>()

            apkFiles["lib/$apkAbi/libbash.so"] = normalizeElfVersionedSoNames(bashRaw)
            extractNeededLibs(bashRaw).forEach { libQueue.addLast(it) }

            for (tool in CORE_CLI_TOOLS) {
                val entry = resolveZipEntry("bin/$tool") ?: continue
                val toolRaw = readEntryBytes(entry)
                if (!isElfBytes(toolRaw)) continue
                apkFiles["lib/$apkAbi/libom_cmd_$tool.so"] = normalizeElfVersionedSoNames(toolRaw)
                extractNeededLibs(toolRaw).forEach { libQueue.addLast(it) }
            }

            while (libQueue.isNotEmpty()) {
                val libName = libQueue.removeFirst()
                if (!processedLibs.add(libName)) continue
                val libEntry = resolveZipEntry("lib/$libName") ?: continue
                val libRaw = readEntryBytes(libEntry)
                if (!isElfBytes(libRaw)) continue
                extractNeededLibs(libRaw).forEach { dep ->
                    if (dep !in processedLibs) libQueue.addLast(dep)
                }
                val normalizedName = normalizeSoFileName(libName)
                val destPath = "lib/$apkAbi/$normalizedName"
                if (!apkFiles.containsKey(destPath)) {
                    apkFiles[destPath] = normalizeElfVersionedSoNames(libRaw)
                }
            }

            zip.getEntry("etc/bash.bashrc")?.let { rcEntry ->
                if (!rcEntry.isDirectory) {
                    apkFiles["assets/etc/bash.bashrc"] = readEntryBytes(rcEntry)
                }
            }
        }

        if (!apkFiles.containsKey("lib/$apkAbi/libbash.so")) return false

        onProgress?.invoke("Empaquetando y firmando complemento APK…")
        val module = ApkModule()
        val manifest = AndroidManifestBlock.empty()
        manifest.packageName = OM_TERMEX_PACKAGE
        manifest.versionCode = 3010
        manifest.versionName = "3.10"
        manifest.minSdkVersion = 23
        manifest.targetSdkVersion = 34
        manifest.setApplicationLabel("OpenManager Linux Extension")
        manifest.setExtractNativeLibs(true)
        manifest.getOrCreateApplicationElement()
            .getOrCreateAndroidAttribute("hasCode", ATTR_HAS_CODE).apply {
                valueType = ValueType.BOOLEAN
                valueAsBoolean = false
            }
        module.setManifest(manifest)

        for ((entryPath, content) in apkFiles) {
            module.add(ByteInputSource(content, entryPath))
        }

        val parent = outputApk.parentFile ?: outputApk.absoluteFile.parentFile
        parent?.let { if (!it.exists()) it.mkdirs() }
        val unsignedTmp = File.createTempFile("om_termex_unsigned_", ".apk", parent)
        return try {
            AntiSplitEngine.writeAlignedUnsignedApk(module, unsignedTmp, extractNativeLibs = true)
            module.close()
            ApkSignerHelper.signFileDirect(unsignedTmp, outputApk, ApkSignerHelper.SignScheme.V1_AND_V2)
            outputApk.exists() && outputApk.length() > 0L
        } finally {
            try { module.close() } catch (_: Exception) {}
            unsignedTmp.delete()
        }
    }

    /**
     * Shows a simple, clear MT-style dialog to install the Linux Extension (Bash + CLI tools).
     */
    fun showExpansionInstallerDialog(activity: Activity, onInstalled: (() -> Unit)? = null) {
        val density = activity.resources.displayMetrics.density
        val palette = OpenManagerPalette.from(activity)
        val (apkAbi, _) = resolveDeviceArchPair()
        val localApk = findLocalTermexApk(activity)

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padH = (24 * density).toInt()
            val padTop = (12 * density).toInt()
            val padBottom = (4 * density).toInt()
            setPadding(padH, padTop, padH, padBottom)
        }

        val tvDesc = TextView(activity).apply {
            text = activity.getString(R.string.om_termex_dialog_desc, apkAbi)
            setTextColor(palette.textSecondary)
            textSize = 14f
        }
        layout.addView(tvDesc)

        var dialogRef: AlertDialog? = null
        if (localApk != null) {
            val mb = String.format(Locale.US, "%.1f", localApk.length() / (1024.0 * 1024.0))
            val btnLocal = Button(activity).apply {
                text = activity.getString(R.string.om_termex_install_local_format, localApk.name, mb)
                setBackgroundColor(palette.accentColor)
                setTextColor(palette.onAccentColor)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (16 * density).toInt()
                layoutParams = lp
                setOnClickListener {
                    dialogRef?.dismissSafely()
                    installApkFile(activity, localApk)
                    onInstalled?.invoke()
                }
            }
            layout.addView(btnLocal)
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.om_termex_dialog_title)
            .setView(layout)
            .setPositiveButton(R.string.om_termex_download_install) { _, _ ->
                downloadAndInstallTermex(activity, onInstalled)
            }
            .setNeutralButton(R.string.om_termex_pick_apk) { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "application/vnd.android.package-archive"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    activity.startActivity(
                        Intent.createChooser(intent, activity.getString(R.string.om_termex_pick_chooser))
                    )
                } catch (_: Exception) {
                    Toast.makeText(activity, R.string.om_termex_place_apk_hint, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .apply {
                if (activity is FragmentActivity) {
                    bindToLifecycle(activity)
                }
            }
        dialogRef = dialog
        dialog.show()
    }

    /**
     * Downloads the prebuilt Linux Extension APK (or builds it locally from official Termux
     * bootstrap packages) and launches Android's PackageInstaller.
     */
    fun downloadAndInstallTermex(activity: Activity, onCompleted: (() -> Unit)? = null) {
        val density = activity.resources.displayMetrics.density
        val palette = OpenManagerPalette.from(activity)
        val (apkAbi, termuxArch) = resolveDeviceArchPair()

        val progressLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * density).toInt()
            setPadding(pad, (16 * density).toInt(), pad, pad)
        }

        val tvStatus = TextView(activity).apply {
            text = activity.getString(R.string.om_termex_connecting)
            textSize = 14f
            setTextColor(palette.textPrimary)
        }
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (12 * density).toInt()
            )
            lp.topMargin = (14 * density).toInt()
            layoutParams = lp
        }

        progressLayout.addView(tvStatus)
        progressLayout.addView(progressBar)

        val progressDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.om_termex_downloading_title)
            .setView(progressLayout)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
            .create()
            .apply {
                if (activity is FragmentActivity) {
                    bindToLifecycle(activity)
                }
            }

        progressDialog.show()

        var isCancelled = false
        progressDialog.setOnDismissListener { isCancelled = true }

        val scope = (activity as? FragmentActivity)?.lifecycleScope ?: CoroutineScope(Dispatchers.Main)
        scope.launch(Dispatchers.IO) {
            val outputApk = File(activity.cacheDir, "termex_install.apk")
            val bootstrapFile = File(activity.cacheDir, "termex_bootstrap.zip")
            var downloadSuccess = false
            var errorMsg: String? = null

            suspend fun downloadToFile(urlStr: String, targetFile: File, labelPrefix: String): Boolean {
                var conn: HttpURLConnection? = null
                return try {
                    conn = UpdateChecker.openConnectionWithRedirects(urlStr)
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        errorMsg = "HTTP $code"
                        return false
                    }
                    val contentLength = conn.contentLength.toLong()
                    val totalMb = if (contentLength > 0) {
                        String.format(Locale.US, "%.1f", contentLength / (1024.0 * 1024.0))
                    } else "?"

                    withContext(Dispatchers.Main) {
                        if (!isCancelled && !activity.isFinishing && !activity.isDestroyed) {
                            progressBar.isIndeterminate = contentLength <= 0
                            if (contentLength > 0) {
                                progressBar.max = 100
                                progressBar.progress = 0
                            }
                        }
                    }

                    var lastUiUpdate = 0L
                    conn.inputStream.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            val buffer = ByteArray(32768)
                            var bytesRead: Int
                            var downloadedBytes = 0L
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                if (isCancelled) return false
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead

                                val now = System.currentTimeMillis()
                                if (now - lastUiUpdate >= 150L) {
                                    lastUiUpdate = now
                                    val curMb = String.format(Locale.US, "%.1f", downloadedBytes / (1024.0 * 1024.0))
                                    val pct = if (contentLength > 0) ((downloadedBytes * 100) / contentLength).toInt() else 0
                                    withContext(Dispatchers.Main) {
                                        if (!isCancelled && !activity.isFinishing && !activity.isDestroyed) {
                                            if (contentLength > 0) {
                                                progressBar.progress = pct.coerceIn(0, 100)
                                                tvStatus.text = "$labelPrefix $curMb MB / $totalMb MB ($pct%)"
                                            } else {
                                                tvStatus.text = "$labelPrefix $curMb MB"
                                            }
                                        }
                                    }
                                }
                            }
                            output.flush()
                        }
                    }
                    targetFile.exists() && targetFile.length() > 0L
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    errorMsg = t.localizedMessage ?: t.javaClass.simpleName
                    false
                } finally {
                    conn?.disconnect()
                }
            }

            try {
                // 1. Try prebuilt OpenManager TermEx release APK if published
                val prebuiltUrl = String.format(Locale.US, PREBUILT_TERMEX_URL_TEMPLATE, termuxArch)
                if (downloadToFile(prebuiltUrl, outputApk, "Descargando:")) {
                    downloadSuccess = true
                }

                // 2. Fallback & primary self-hosted builder: official Termux bootstrap for device ABI
                if (!downloadSuccess && !isCancelled) {
                    outputApk.delete()
                    val bootstrapUrl = String.format(Locale.US, TERMUX_BOOTSTRAP_URL_TEMPLATE, termuxArch)
                    if (downloadToFile(bootstrapUrl, bootstrapFile, "Descargando:")) {
                        withContext(Dispatchers.Main) {
                            if (!isCancelled && !activity.isFinishing && !activity.isDestroyed) {
                                progressBar.isIndeterminate = true
                                tvStatus.text = "Empaquetando entorno Linux ($apkAbi)…"
                            }
                        }
                        downloadSuccess = buildTermexApkFromBootstrap(bootstrapFile, outputApk, apkAbi) { step ->
                            scope.launch(Dispatchers.Main) {
                                if (!isCancelled && !activity.isFinishing && !activity.isDestroyed) {
                                    tvStatus.text = step
                                }
                            }
                        }
                        if (!downloadSuccess && errorMsg == null) {
                            errorMsg = "Error al construir el paquete APK ($apkAbi)"
                        }
                    }
                }
            } finally {
                bootstrapFile.delete()
            }

            withContext(Dispatchers.Main) {
                progressDialog.dismissSafely()
                if (activity.isFinishing || activity.isDestroyed) return@withContext

                if (downloadSuccess && outputApk.exists() && outputApk.length() > 0L) {
                    installApkFile(activity, outputApk)
                    onCompleted?.invoke()
                } else if (!isCancelled) {
                    Toast.makeText(
                        activity,
                        activity.getString(
                            R.string.om_termex_download_failed_format,
                            errorMsg ?: activity.getString(R.string.om_termex_network_error)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
