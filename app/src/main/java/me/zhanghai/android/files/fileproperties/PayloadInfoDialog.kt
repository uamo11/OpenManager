/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties

import android.graphics.Typeface
import android.text.format.Formatter
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.archive.archiveFile
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.util.copyText
import androidx.core.content.getSystemService
import android.content.ClipboardManager
import me.zhanghai.android.files.util.showToast

/** What an OTA `payload.bin` contains, without extracting anything. */
object PayloadInfoDialog {

    /**
     * payload.bin inside an OTA zip: it is stored uncompressed and, at several GB, in ZIP64 form,
     * which the archive layer's streaming read did not handle. Java's zip reader opens the entry
     * directly and only its first bytes are read.
     */
    private fun readFromZip(path: Path): PayloadManifest? {
        if (!path.isArchivePath) return null
        val zipFile = runCatching { path.archiveFile.toFile() }.getOrNull() ?: return null
        if (!zipFile.canRead()) return null
        val name = path.toString().removePrefix("/")
        java.util.zip.ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry(name) ?: return null
            return zip.getInputStream(entry).buffered().use { PayloadManifest.read(it) }
        }
    }

    fun isPayloadName(name: String): Boolean = name.equals("payload.bin", ignoreCase = true)

    /**
     * The payload from its first byte. Inside an OTA zip it is read through Java's zip reader (stored
     * and ZIP64, which the archive layer's stream did not handle); the zip closes with the stream.
     */
    @Throws(java.io.IOException::class)
    fun openPayloadStream(path: Path): java.io.InputStream {
        if (path.isArchivePath) {
            val zipFile = runCatching { path.archiveFile.toFile() }.getOrNull()
            if (zipFile != null && zipFile.canRead()) {
                val zip = java.util.zip.ZipFile(zipFile)
                val entry = zip.getEntry(path.toString().removePrefix("/"))
                if (entry != null) {
                    val raw = zip.getInputStream(entry)
                    return object : java.io.FilterInputStream(raw) {
                        override fun close() {
                            try { super.close() } finally { zip.close() }
                        }
                    }
                }
                zip.close()
            }
        }
        return path.newInputStream()
    }

    /** Next to the payload, or next to the OTA zip it is in: `<folder>/<name>_img/`. */
    fun outputDirFor(path: Path): java.io.File? = runCatching {
        if (path.isArchivePath) {
            val zip = path.archiveFile.toFile()
            java.io.File(zip.parentFile, zip.nameWithoutExtension + "_img")
        } else {
            val file = path.toFile()
            java.io.File(file.parentFile, file.nameWithoutExtension + "_img")
        }
    }.getOrNull()

    private fun chooseAndExtract(fragment: Fragment, path: Path, manifest: PayloadManifest) {
        val context = fragment.requireContext()
        val partitions = PayloadExtractor.extractable(manifest)
        if (partitions.isEmpty()) {
            MaterialAlertDialogBuilder(context)
                .setMessage(R.string.om_payload_incremental_cannot)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val outDir = outputDirFor(path) ?: return
        val labels = partitions.map {
            "${it.name}  (${Formatter.formatShortFileSize(context, it.size)})"
        }.toTypedArray()
        val checked = BooleanArray(partitions.size) { true }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.om_payload_extract_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.om_payload_extract) { _, _ ->
                val names = partitions.filterIndexed { i, _ -> checked[i] }.map { it.name }.toSet()
                if (names.isNotEmpty()) {
                    me.zhanghai.android.files.filejob.FileJobService.extractPayload(path, names, outDir, context)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun show(fragment: Fragment, path: Path) {
        val context = fragment.requireContext()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val manifest = withContext(Dispatchers.IO) {
                runCatching { openPayloadStream(path).buffered().use { PayloadManifest.read(it) } }
                    .recoverCatching { first ->
                        Log.w("OMPayload", "Payload stream failed, trying the zip directly", first)
                        readFromZip(path) ?: throw first
                    }
            }.getOrElse {
                Log.w("OMPayload", "Not readable as a payload", it)
                fragment.showToast(context.getString(R.string.om_payload_not_payload))
                return@launch
            }
            val fmt = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }
            val nameWidth = (manifest.partitions.maxOfOrNull { it.name.length } ?: 8).coerceIn(8, 24)
            val table = manifest.partitions.joinToString("\n") { p ->
                p.name.padEnd(nameWidth) + "  " + fmt(p.size).padStart(9) + "  " +
                    p.kinds.take(2).joinToString("+").padEnd(9) + " " + p.operations + " ops"
            }
            val summary = context.getString(
                R.string.om_payload_summary,
                context.getString(if (manifest.isIncremental) R.string.om_payload_incremental else R.string.om_payload_full),
                manifest.partitions.size,
                fmt(manifest.totalSize)
            )
            if (BuildConfig.DEBUG) {
                Log.d("OMPayload", "Payload v${manifest.version} partitions=${manifest.partitions.size} " +
                    manifest.partitions.joinToString(",") { "${it.name}:${it.size}:${it.kinds.firstOrNull()}" })
            }
            val text = TextView(context).apply {
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextIsSelectable(true)
                val pad = (20 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
                setText(summary + "\n\n" + table)
            }
            val scroll = ScrollView(context).apply { addView(text) }
            MaterialAlertDialogBuilder(context)
                .setTitle(path.fileName?.toString())
                .setView(scroll)
                .setPositiveButton(R.string.om_payload_extract) { _, _ ->
                    chooseAndExtract(fragment, path, manifest)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.om_payload_copy) { _, _ ->
                    context.getSystemService<ClipboardManager>()?.copyText(summary + "\n" + table, context)
                }
                .show()
        }
    }
}
