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
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.app.application
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

object TerminalExpansionManager {
    const val TERMEX_PACKAGE = "bin.mt.termex"

    // Primary and fallback download URLs for Termex ARM64 APK
    private const val TERMEX_DOWNLOAD_URL =
        "https://github.com/xxnonxxp11/OpenManager/releases/download/v1.9.28/termex_v3008_arm64.apk"
    private const val TERMEX_FALLBACK_URL =
        "https://github.com/xxnonxxp11/OpenManager/releases/download/v1.9.27/termex_v3008_arm64.apk"

    private const val EXTENDED_SYSTEM_PATH =
        "/data/adb/ksu/bin:/data/adb/ap/bin:/data/adb/magisk:/system/bin:/system/xbin:/sbin:/vendor/bin:/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin"

    data class LaunchConfig(
        val executable: String,
        val cwd: String,
        val args: Array<String>,
        val env: Array<String>,
        val isTermex: Boolean,
        val isRoot: Boolean
    )

    fun isTermexInstalled(context: Context = application): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMEX_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun getTermexVersion(context: Context = application): String? {
        return try {
            val info = context.packageManager.getPackageInfo(TERMEX_PACKAGE, 0)
            val name = info.versionName ?: "v3.8"
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
     * Finds bash executable either from Termex native library dir or extracted in filesDir/termex/bin.
     */
    fun getBashExecutable(context: Context = application): File? {
        if (!isTermexInstalled(context)) return null
        try {
            val pkgInfo = context.packageManager.getPackageInfo(TERMEX_PACKAGE, 0)
            val nativeLibDir = pkgInfo.applicationInfo?.nativeLibraryDir ?: ""
            val apkPath = pkgInfo.applicationInfo?.sourceDir ?: ""

            val termexDir = File(context.filesDir, "termex")
            val binDir = File(termexDir, "bin").apply { mkdirs() }
            val libDir = File(termexDir, "lib").apply { mkdirs() }

            // 1. Check native library directory (bash, libbash.so)
            val candidates = arrayOf(
                File(nativeLibDir, "bash"),
                File(nativeLibDir, "libbash.so")
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

            // 3. Extract bash or libbash.so from Termex APK if available
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

        // 3. Deploy bash wrapper script if Termex bash is found
        val bashExecutable = getBashExecutable(context)
        if (bashExecutable != null && bashExecutable.exists()) {
            val bashWrapper = File(binDir, "bash")
            val bashContent = """
                #!/system/bin/sh
                exec "${bashExecutable.absolutePath}" "${'$'}@"
            """.trimIndent() + "\n"
            try {
                if (!bashWrapper.exists() || bashWrapper.readText() != bashContent) {
                    bashWrapper.writeText(bashContent)
                }
                bashWrapper.setReadable(true, false)
                bashWrapper.setExecutable(true, false)
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
        initialCommand: String? = null
    ): LaunchConfig {
        val asRoot = forceRoot ?: TerminalSettings.isRootSession(context)
        val useTermex = forceTermex ?: (isTermexInstalled(context) && !asRoot)
        val targetCwd = overrideCwd ?: TerminalSettings.getStartPath(context)
        val validCwd = if (File(targetCwd).isDirectory) targetCwd else "/storage/emulated/0"
        val escapedCwd = validCwd.replace("'", "'\\''")
        val histFile = File(context.filesDir, ".om_shell_history").absolutePath
        val initScript = ensureShellInitScript(context)
        val binDir = ensureBinTools(context)

        val shellCmd = if (initialCommand != null) {
            "cd '$escapedCwd' && ($initialCommand); _mt_status=\$?; exit \$_mt_status"
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
                "PATH=${binDir.absolutePath}:$EXTENDED_SYSTEM_PATH"
            )
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

        if (useTermex && isTermexInstalled(context)) {
            try {
                val pkgInfo = context.packageManager.getPackageInfo(TERMEX_PACKAGE, 0)
                val nativeLibDir = pkgInfo.applicationInfo?.nativeLibraryDir ?: ""
                val apkPath = pkgInfo.applicationInfo?.sourceDir ?: ""

                val termexDir = File(context.filesDir, "termex")
                val libDir = File(termexDir, "lib")
                libDir.mkdirs()

                // Check if auxiliary libraries need extraction
                val marker = File(libDir, "libandroid-support.so")
                if (!marker.exists() || marker.length() == 0L) {
                    extractTermexAuxLibraries(apkPath, libDir, termexDir)
                }

                val bashBin = getBashExecutable(context)
                if (bashBin != null && bashBin.exists()) {
                    val prompt = "\$([ \$? == 0 ] && echo '\\[\\e[1;32m\\]' || echo '\\[\\e[1;31m\\]')\$([ \$UID == 0 ] && echo '#' || echo '➜') \\[\\e[1;36m\\]\\w\\[\\e[m\\] "
                    val env = arrayOf(
                        "PREFIX=${termexDir.absolutePath}",
                        "LD_LIBRARY_PATH=${libDir.absolutePath}:$nativeLibDir",
                        "PATH=${binDir.absolutePath}:$nativeLibDir:$EXTENDED_SYSTEM_PATH",
                        "SHELL=${bashBin.absolutePath}",
                        "TERM=xterm-256color",
                        "COLORTERM=truecolor",
                        "LANG=en_US.UTF-8",
                        "HOME=$validCwd",
                        "TMPDIR=${context.cacheDir.absolutePath}",
                        "HISTFILE=$histFile",
                        "ENV=${initScript.absolutePath}",
                        "PS1=$prompt"
                    )
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
            "PATH=${binDir.absolutePath}:$EXTENDED_SYSTEM_PATH",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=en_US.UTF-8",
            "HOME=$validCwd",
            "TMPDIR=${context.cacheDir.absolutePath}",
            "HISTFILE=$histFile",
            "ENV=${initScript.absolutePath}",
            "PS1=➜ \${PWD##*/} \$ "
        )
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
     * Scans storage for any existing Termex APK (e.g. in /sdcard/Download or Downloads).
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
                    (f.name.contains("termex", ignoreCase = true) || f.name.contains("mterm", ignoreCase = true))
            }?.firstOrNull()
            if (found != null && found.length() > 0) {
                return found
            }
        }
        return null
    }

    /**
     * Launches system PackageInstaller via FileProvider for a given APK file.
     */
    fun installApkFile(activity: Activity, apkFile: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.file_provider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(activity, "Error al iniciar instalador: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    /**
     * Shows an interactive, user-friendly dialog to install the Terminal Expansion Pack (Termex).
     * Provides 3 options:
     * 1. 1-tap install if an APK was detected on local storage.
     * 2. Automatic download & install with progress bar.
     * 3. Select APK from storage.
     */
    fun showExpansionInstallerDialog(activity: Activity, onInstalled: (() -> Unit)? = null) {
        val density = activity.resources.displayMetrics.density
        val localApk = findLocalTermexApk(activity)

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val tvDesc = TextView(activity).apply {
            text = "El Paquete de Expansión de Terminal proporciona un entorno Linux completo con GNU Bash 5.x, utilidades coreutils, tuberías avanzadas y soporte para scripts complejos.\n\nActualmente no se encuentra instalado en este dispositivo."
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 13.5f
        }
        layout.addView(tvDesc)

        if (localApk != null) {
            val mb = String.format("%.1f", localApk.length() / (1024.0 * 1024.0))
            val btnLocal = Button(activity).apply {
                text = "📦 Instalar APK detectado (${localApk.name} - ${mb}MB)"
                setBackgroundColor(Color.parseColor("#2E7D32"))
                setTextColor(Color.WHITE)
                val mTop = (16 * density).toInt()
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = mTop
                layoutParams = lp
            }
            btnLocal.setOnClickListener {
                installApkFile(activity, localApk)
            }
            layout.addView(btnLocal)
        }

        val btnDownload = Button(activity).apply {
            text = "⬇️ Descargar e instalar automáticamente"
            setBackgroundColor(Color.parseColor("#1565C0"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = (12 * density).toInt()
            layoutParams = lp
        }
        layout.addView(btnDownload)

        val btnPick = Button(activity).apply {
            text = "📂 Seleccionar APK del almacenamiento"
            setBackgroundColor(Color.parseColor("#424242"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = (10 * density).toInt()
            layoutParams = lp
        }
        layout.addView(btnPick)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Expansión de Terminal (Termex)")
            .setView(layout)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        btnDownload.setOnClickListener {
            dialog.dismiss()
            downloadAndInstallTermex(activity, onInstalled)
        }

        btnPick.setOnClickListener {
            dialog.dismiss()
            try {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "application/vnd.android.package-archive"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                activity.startActivity(Intent.createChooser(intent, "Seleccionar APK de Termex"))
            } catch (_: Exception) {
                Toast.makeText(activity, "Coloque termex_v3008_arm64.apk en su carpeta Descargas", Toast.LENGTH_LONG).show()
            }
        }

        dialog.show()
    }

    /**
     * Downloads the Termex APK in background with an interactive progress dialog
     * and automatically invokes the PackageInstaller upon completion.
     */
    fun downloadAndInstallTermex(activity: Activity, onCompleted: (() -> Unit)? = null) {
        val density = activity.resources.displayMetrics.density
        val progressLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val tvStatus = TextView(activity).apply {
            text = "Conectando al servidor..."
            textSize = 14f
            setTextColor(Color.parseColor("#E0E0E0"))
        }
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (12 * density).toInt())
            lp.topMargin = (14 * density).toInt()
            layoutParams = lp
        }

        progressLayout.addView(tvStatus)
        progressLayout.addView(progressBar)

        val progressDialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Descargando Paquete de Expansión")
            .setView(progressLayout)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { d, _ ->
                d.dismiss()
            }
            .create()

        progressDialog.show()

        var isCancelled = false
        progressDialog.setOnDismissListener { isCancelled = true }

        CoroutineScope(Dispatchers.IO).launch {
            val downloadFile = File(activity.cacheDir, "termex_install.apk")
            var downloadSuccess = false
            var errorMsg: String? = null

            val urlsToTry = listOf(TERMEX_DOWNLOAD_URL, TERMEX_FALLBACK_URL)
            for (urlStr in urlsToTry) {
                if (isCancelled) break
                var conn: HttpURLConnection? = null
                try {
                    val url = URL(urlStr)
                    conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 25000
                    conn.instanceFollowRedirects = true

                    val code = conn.responseCode
                    if (code in 200..299) {
                        val contentLength = conn.contentLength.toLong()
                        val totalMb = if (contentLength > 0) String.format("%.1f", contentLength / (1024.0 * 1024.0)) else "?"

                        withContext(Dispatchers.Main) {
                            if (!isCancelled) {
                                progressBar.isIndeterminate = contentLength <= 0
                                if (contentLength > 0) {
                                    progressBar.max = 100
                                    progressBar.progress = 0
                                }
                            }
                        }

                        conn.inputStream.use { input ->
                            FileOutputStream(downloadFile).use { output ->
                                val buffer = ByteArray(32768)
                                var bytesRead: Int
                                var downloadedBytes = 0L

                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    if (isCancelled) break
                                    output.write(buffer, 0, bytesRead)
                                    downloadedBytes += bytesRead

                                    val curMb = String.format("%.1f", downloadedBytes / (1024.0 * 1024.0))
                                    val pct = if (contentLength > 0) ((downloadedBytes * 100) / contentLength).toInt() else 0

                                    withContext(Dispatchers.Main) {
                                        if (!isCancelled) {
                                            if (contentLength > 0) {
                                                progressBar.progress = pct
                                                tvStatus.text = "Descargando: $curMb MB / $totalMb MB ($pct%)"
                                            } else {
                                                tvStatus.text = "Descargando: $curMb MB descargados"
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (!isCancelled && downloadFile.length() > 0) {
                            downloadSuccess = true
                            break
                        }
                    }
                } catch (t: Throwable) {
                    errorMsg = t.message
                } finally {
                    conn?.disconnect()
                }
            }

            withContext(Dispatchers.Main) {
                if (progressDialog.isShowing) {
                    progressDialog.dismiss()
                }

                if (downloadSuccess && downloadFile.exists() && downloadFile.length() > 0) {
                    installApkFile(activity, downloadFile)
                    onCompleted?.invoke()
                } else if (!isCancelled) {
                    Toast.makeText(
                        activity,
                        "No se pudo descargar automáticamente: ${errorMsg ?: "Error de red"}.\nPuede colocar termex_v3008_arm64.apk en /sdcard/Download.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
