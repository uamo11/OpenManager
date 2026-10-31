/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.FileVisitResult
import java8.nio.file.FileVisitor
import java8.nio.file.Files
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.packageManager
import me.zhanghai.android.files.compat.longVersionCodeCompat
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.file.fileSize
import me.zhanghai.android.files.file.isText
import me.zhanghai.android.files.filelist.FileIconHelper
import me.zhanghai.android.files.filelist.ScriptRunnerHelper
import me.zhanghai.android.files.filelist.extension
import me.zhanghai.android.files.filelist.getMimeTypeName
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.filelist.toUserFriendlyString
import me.zhanghai.android.files.fileproperties.permission.MtPermissionsHelper
import me.zhanghai.android.files.fileproperties.permission.SetGroupDialogFragment
import me.zhanghai.android.files.fileproperties.permission.SetOwnerDialogFragment
import me.zhanghai.android.files.fileproperties.permission.SetSeLinuxContextDialogFragment
import me.zhanghai.android.files.provider.archive.ArchiveFileAttributes
import me.zhanghai.android.files.provider.archive.archiveFile
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.common.PosixFileAttributes
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.getPackageArchiveInfoCompat
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show
import me.zhanghai.android.files.util.viewModels
import me.zhanghai.android.files.viewer.text.TextEditorActivity
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32

class FilePropertiesDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    // Shared view model retained for child dialogs (SetOwner / SetGroup / SetSeLinuxContext)
    private val viewModel by viewModels { { FilePropertiesFileViewModel(args.file) } }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        // Initialize shared fileLiveData
        viewModel.fileLiveData

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_mt_file_properties, null)

        val ivIcon = view.findViewById<ImageView>(R.id.ivPropIcon)
        val tvTitle = view.findViewById<TextView>(R.id.tvPropTitle)
        val tvSubtitle = view.findViewById<TextView>(R.id.tvPropSubtitle)
        val table = view.findViewById<TableLayout>(R.id.tableProperties)

        val btnAction1 = view.findViewById<Button>(R.id.btnPropAction1)
        val btnAction2 = view.findViewById<Button>(R.id.btnPropAction2)
        val btnAction3 = view.findViewById<Button>(R.id.btnPropAction3)
        val btnAction4 = view.findViewById<Button>(R.id.btnPropAction4)

        val file = args.file
        val path = file.path
        val isDir = file.attributes.isDirectory
        val isScript = !isDir && ScriptRunnerHelper.isExecutableOrScript(file)
        val isApk = !isDir && (file.name.endsWith(".apk", ignoreCase = true) ||
            file.mimeType.value == "application/vnd.android.package-archive")

        ivIcon.setImageResource(FileIconHelper.getIconRes(file))
        tvTitle.text = file.name
        tvSubtitle.text = when {
            isDir -> getString(R.string.file_properties_basic)
            isScript -> "Script Ejecutable (/system/bin/sh)"
            else -> "${file.getMimeTypeName(context)} (${file.mimeType.value})"
        }

        fun copyText(label: String, value: CharSequence?) {
            val str = value?.toString()?.removeSuffix("  ✎")?.trim() ?: return
            if (str.isNotBlank() && str != "-") {
                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cb?.setPrimaryClip(ClipData.newPlainText(label, str))
                Toast.makeText(context, "$label copiado", Toast.LENGTH_SHORT).show()
            }
        }

        tvTitle.setOnClickListener { copyText("Nombre", tvTitle.text) }

        val density = context.resources.displayMetrics.density
        fun addRow(
            label: String,
            initialValue: String,
            valueColor: Int = Color.parseColor("#E0E0E0"),
            boldValue: Boolean = false,
            singleLineMiddle: Boolean = false,
            onClick: ((TextView) -> Unit)? = null
        ): Pair<TableRow, TextView> {
            val row = TableRow(context).apply {
                val vPad = (4 * density).toInt()
                setPadding(0, vPad, 0, vPad)
                gravity = Gravity.CENTER_VERTICAL
            }
            val tvLabel = TextView(context).apply {
                layoutParams = TableRow.LayoutParams((115 * density).toInt(), TableRow.LayoutParams.WRAP_CONTENT)
                text = label
                setTextColor(Color.parseColor("#9E9E9E"))
                textSize = 13f
            }
            val tvVal = TextView(context).apply {
                layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
                text = initialValue
                setTextColor(valueColor)
                textSize = 13f
                if (boldValue) {
                    setTypeface(typeface, Typeface.BOLD)
                }
                if (singleLineMiddle) {
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                }
            }
            if (onClick != null) {
                row.isClickable = true
                row.isFocusable = true
                val clickListener = View.OnClickListener { onClick(tvVal) }
                row.setOnClickListener(clickListener)
                tvVal.setOnClickListener(clickListener)
            } else {
                val copyListener = View.OnClickListener { copyText(label, tvVal.text) }
                row.setOnClickListener(copyListener)
                tvVal.setOnClickListener(copyListener)
            }
            row.addView(tvLabel)
            row.addView(tvVal)
            table.addView(row)
            return row to tvVal
        }

        // 1. Basic Information Rows (APK Info style)
        addRow(getString(R.string.file_properties_basic_name), file.name)

        if (path.isArchivePath) {
            val archiveFile = path.archiveFile
            addRow(
                getString(R.string.file_properties_basic_archive_file),
                archiveFile.toUserFriendlyString(),
                singleLineMiddle = true
            )
            val attributes = file.attributes as? ArchiveFileAttributes
            if (attributes != null) {
                addRow(getString(R.string.file_properties_basic_archive_entry), attributes.entryName())
            }
        } else {
            val parentPath = path.parent
            if (parentPath != null) {
                addRow(
                    getString(R.string.file_properties_basic_parent_directory),
                    parentPath.toUserFriendlyString(),
                    singleLineMiddle = true
                )
            }
            addRow("Ruta completa", path.toUserFriendlyString(), singleLineMiddle = true)
        }

        addRow(
            getString(R.string.file_properties_basic_type),
            "${file.getMimeTypeName(context)} (${file.mimeType.value})"
        )

        val symbolicLinkTarget = file.symbolicLinkTarget
        if (symbolicLinkTarget != null) {
            addRow(getString(R.string.file_properties_basic_symbolic_link_target), symbolicLinkTarget)
        }

        val tvSizeOrContents: TextView
        if (isDir) {
            val (_, tvContents) = addRow(
                getString(R.string.file_properties_basic_contents),
                "Calculando..."
            )
            tvSizeOrContents = tvContents
            lifecycleScope.launch(Dispatchers.IO) {
                var folderCount = 0
                var fileCount = 0
                var totalBytes = 0L
                try {
                    Files.walkFileTree(path, object : FileVisitor<Path> {
                        override fun preVisitDirectory(
                            dir: Path,
                            attrs: BasicFileAttributes
                        ): FileVisitResult {
                            if (dir != path) folderCount++
                            return if (isActive) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE
                        }

                        override fun visitFile(
                            visited: Path,
                            attrs: BasicFileAttributes
                        ): FileVisitResult {
                            fileCount++
                            totalBytes += attrs.size()
                            return if (isActive) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE
                        }

                        override fun visitFileFailed(
                            visited: Path,
                            exc: IOException
                        ): FileVisitResult = FileVisitResult.CONTINUE

                        override fun postVisitDirectory(
                            dir: Path,
                            exc: IOException?
                        ): FileVisitResult = FileVisitResult.CONTINUE
                    })
                } catch (_: Throwable) {}
                withContext(Dispatchers.Main) {
                    val humanSize = totalBytes.asFileSize().formatHumanReadable(context)
                    tvContents.text = "Carpetas: $folderCount · Archivos: $fileCount ($humanSize)"
                }
            }
        } else {
            val sizeBytes = file.attributes.fileSize
            val (_, tvSize) = addRow(
                getString(R.string.file_properties_basic_size),
                "${sizeBytes.formatHumanReadable(context)} (${sizeBytes.value} B)"
            )
            tvSizeOrContents = tvSize
        }

        val lastModMillis = try { file.attributes.lastModifiedTime().toMillis() } catch (_: Throwable) { 0L }
        val modFormatted = if (lastModMillis > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(lastModMillis))
        } else {
            "-"
        }
        addRow(getString(R.string.file_properties_basic_last_modification_time), modFormatted)

        // 2. Interactive Permissions & Ownership Rows (MT Manager style)
        var tvPermissionsRef: TextView? = null
        if (!path.isArchivePath) {
            val permInfo = MtPermissionsHelper.getPermissions(context, path, file)
            val (_, tvPerm) = addRow(
                label = getString(R.string.file_properties_permission_mode),
                initialValue = "${permInfo.displayString}  ✎",
                valueColor = Color.parseColor("#64B5F6"),
                boldValue = true
            ) { tv ->
                MtPermissionsHelper.showPermissionsDialog(
                    context = context,
                    path = path,
                    fileItem = file
                ) { updated ->
                    tv.text = "${updated.displayString}  ✎"
                }
            }
            tvPermissionsRef = tvPerm

            val posixAttrs = file.attributes as? PosixFileAttributes
            if (!permInfo.ownerDisplay.isNullOrBlank()) {
                addRow(
                    label = getString(R.string.file_properties_permission_owner),
                    initialValue = permInfo.ownerDisplay,
                    onClick = if (posixAttrs?.owner() != null) {
                        { SetOwnerDialogFragment.show(file, this@FilePropertiesDialogFragment) }
                    } else null
                )
            }
            if (!permInfo.groupDisplay.isNullOrBlank()) {
                addRow(
                    label = getString(R.string.file_properties_permission_group),
                    initialValue = permInfo.groupDisplay,
                    onClick = if (posixAttrs?.group() != null) {
                        { SetGroupDialogFragment.show(file, this@FilePropertiesDialogFragment) }
                    } else null
                )
            }
            if (!permInfo.seLinuxContext.isNullOrBlank()) {
                addRow(
                    label = getString(R.string.file_properties_permission_selinux_context),
                    initialValue = permInfo.seLinuxContext,
                    singleLineMiddle = true,
                    onClick = if (posixAttrs?.seLinuxContext() != null) {
                        { SetSeLinuxContextDialogFragment.show(file, this@FilePropertiesDialogFragment) }
                    } else null
                )
            }
        }

        // 3. Script-Specific Details (if executable or script)
        if (isScript) {
            val isElf = ScriptRunnerHelper.isElfBinary(path)
            val (_, tvInterp) = addRow("Intérprete", if (isElf) "ELF Nativo" else "/system/bin/sh")
            if (!isElf) {
                val (rowLe, tvLe) = addRow("Formato línea", "Analizando...")
                val (_, tvStats) = addRow("Líneas", "Analizando...")
                lifecycleScope.launch(Dispatchers.IO) {
                    var shebang: String? = null
                    var lines = 0
                    var hasCrlf = false
                    try {
                        path.newInputStream().use { s ->
                            val bytes = s.readBytes()
                            for (i in bytes.indices) {
                                if (bytes[i] == '\n'.code.toByte()) {
                                    lines++
                                    if (i > 0 && bytes[i - 1] == '\r'.code.toByte()) hasCrlf = true
                                }
                            }
                            if (lines == 0 && bytes.isNotEmpty()) lines = 1
                        }
                        path.newInputStream().use { s ->
                            BufferedReader(InputStreamReader(s, StandardCharsets.UTF_8)).use { r ->
                                val first = r.readLine()
                                if (first != null && first.startsWith("#!")) {
                                    shebang = first.trim()
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                    withContext(Dispatchers.Main) {
                        if (!shebang.isNullOrBlank()) {
                            tvInterp.text = shebang
                        }
                        tvStats.text = "$lines líneas · UTF-8"
                        if (hasCrlf) {
                            tvLe.text = "CRLF (Windows) · Tocar para convertir a LF"
                            tvLe.setTextColor(Color.parseColor("#FFB74D"))
                            val convertClick = View.OnClickListener {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    try {
                                        val raw = Files.readAllBytes(path)
                                        val converted = String(raw, StandardCharsets.UTF_8)
                                            .replace("\r\n", "\n")
                                            .replace("\r", "\n")
                                            .toByteArray(StandardCharsets.UTF_8)
                                        Files.write(path, converted)
                                        withContext(Dispatchers.Main) {
                                            tvLe.text = "LF (Unix)"
                                            tvLe.setTextColor(Color.parseColor("#81C784"))
                                            tvSizeOrContents.text = "${converted.size.toLong().asFileSize().formatHumanReadable(context)} (${converted.size} B)"
                                            Toast.makeText(context, R.string.file_properties_script_convert_success, Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                            rowLe.setOnClickListener(convertClick)
                            tvLe.setOnClickListener(convertClick)
                        } else {
                            tvLe.text = "LF (Unix)"
                            tvLe.setTextColor(Color.parseColor("#81C784"))
                        }
                    }
                }
            }
        }

        // 4. APK-Specific Details (if APK)
        if (isApk) {
            try {
                var flags = PackageManager.GET_SIGNATURES
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    flags = flags or PackageManager.GET_SIGNING_CERTIFICATES
                }
                val (pkgInfo, closeable) = packageManager.getPackageArchiveInfoCompat(path, flags)
                closeable.use {
                    if (pkgInfo != null) {
                        val appInfo = pkgInfo.applicationInfo
                        if (appInfo != null) {
                            try {
                                appInfo.sourceDir = path.toUserFriendlyString()
                                appInfo.publicSourceDir = path.toUserFriendlyString()
                                appInfo.loadIcon(packageManager)?.let { ivIcon.setImageDrawable(it) }
                                val label = appInfo.loadLabel(packageManager)?.toString()
                                if (!label.isNullOrBlank()) tvTitle.text = label
                            } catch (_: Throwable) {}
                        }
                        tvSubtitle.text = "v${pkgInfo.versionName ?: "1.0"} (${pkgInfo.longVersionCodeCompat})"
                        addRow("Paquete", pkgInfo.packageName ?: "-")
                        addRow("Versión", "v${pkgInfo.versionName ?: "1.0"} (${pkgInfo.longVersionCodeCompat})")
                        if (appInfo != null) {
                            val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) appInfo.minSdkVersion else 0
                            addRow("SDK (Min / Target)", "API $minSdk / API ${appInfo.targetSdkVersion}")
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        // 5. Checksums / Hashes Function
        var hashesCalculated = false
        fun calculateAndShowHashes() {
            if (hashesCalculated || isDir) return
            hashesCalculated = true
            btnAction2.isEnabled = false
            btnAction2.alpha = 0.5f

            val (_, tvCrc) = addRow("CRC32", "Calculando...")
            val (_, tvMd5) = addRow("MD5", "Calculando...")
            val (_, tvSha1) = addRow("SHA-1", "Calculando...")
            val (_, tvSha256) = addRow("SHA-256", "Calculando...")

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val crc = CRC32()
                    val md5 = MessageDigest.getInstance("MD5")
                    val sha1 = MessageDigest.getInstance("SHA-1")
                    val sha256 = MessageDigest.getInstance("SHA-256")
                    path.newInputStream().use { inp ->
                        val buf = ByteArray(16384)
                        var n: Int
                        while (inp.read(buf).also { n = it } != -1 && isActive) {
                            crc.update(buf, 0, n)
                            md5.update(buf, 0, n)
                            sha1.update(buf, 0, n)
                            sha256.update(buf, 0, n)
                        }
                    }
                    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
                    val crcHex = "%08x".format(crc.value)
                    val md5Hex = md5.digest().toHex()
                    val sha1Hex = sha1.digest().toHex()
                    val sha256Hex = sha256.digest().toHex()
                    withContext(Dispatchers.Main) {
                        tvCrc.text = crcHex
                        tvMd5.text = md5Hex
                        tvSha1.text = sha1Hex
                        tvSha256.text = sha256Hex
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        tvMd5.text = "Error: ${e.message}"
                    }
                }
            }
        }

        // Auto-calculate hashes for small/medium files (<= 16 MB)
        if (!isDir && file.attributes.size() in 1..(16 * 1024 * 1024L)) {
            calculateAndShowHashes()
        }

        val dialog = MaterialAlertDialogBuilder(context, theme)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor("#303030")))

        // Bottom Buttons Bar (APK Info style)
        if (!path.isArchivePath) {
            btnAction1.visibility = View.VISIBLE
            btnAction1.text = getString(R.string.file_properties_permission)
            btnAction1.setTextColor(Color.parseColor("#2196F3"))
            btnAction1.setOnClickListener {
                MtPermissionsHelper.showPermissionsDialog(
                    context = context,
                    path = path,
                    fileItem = file
                ) { updated ->
                    tvPermissionsRef?.text = "${updated.displayString}  ✎"
                }
            }
        }

        if (!isDir) {
            if (!hashesCalculated) {
                btnAction2.visibility = View.VISIBLE
                btnAction2.text = "HASH"
                btnAction2.setTextColor(Color.parseColor("#FF9800"))
                btnAction2.setOnClickListener {
                    calculateAndShowHashes()
                }
            }
        } else {
            btnAction2.visibility = View.VISIBLE
            btnAction2.text = "COPIAR RUTA"
            btnAction2.setTextColor(Color.parseColor("#FF9800"))
            btnAction2.setOnClickListener {
                copyText("Ruta", path.toUserFriendlyString())
            }
        }

        val extLower = file.extension.lowercase()
        val isEditableText = !isDir && (isScript || file.mimeType.isText ||
            extLower in setOf("txt", "log", "md", "json", "xml", "html", "css", "js", "py", "java", "kt", "smali", "ini", "conf", "properties", "yaml", "yml"))

        if (isScript) {
            btnAction3.visibility = View.VISIBLE
            btnAction3.text = getString(R.string.script_action_execute)
            btnAction3.setTextColor(Color.parseColor("#4CAF50"))
            btnAction3.setOnClickListener {
                val act = activity
                dialog.dismiss()
                if (act != null) {
                    ScriptRunnerHelper.showScriptActionDialog(act, file)
                }
            }
        } else if (isEditableText) {
            btnAction3.visibility = View.VISIBLE
            btnAction3.text = getString(R.string.script_action_edit)
            btnAction3.setTextColor(Color.parseColor("#FFFFFF"))
            btnAction3.setOnClickListener {
                dialog.dismiss()
                val intent = Intent(context, TextEditorActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    extraPath = path
                }
                startActivity(intent)
            }
        }

        btnAction4.visibility = View.VISIBLE
        btnAction4.text = getString(android.R.string.ok)
        btnAction4.setTextColor(Color.parseColor("#2196F3"))
        btnAction4.setOnClickListener {
            dialog.dismiss()
        }

        return dialog
    }

    companion object {
        fun show(file: FileItem, fragment: Fragment) {
            FilePropertiesDialogFragment().putArgs(Args(file)).show(fragment)
        }
    }

    @Parcelize
    class Args(val file: FileItem) : ParcelableArgs
}
