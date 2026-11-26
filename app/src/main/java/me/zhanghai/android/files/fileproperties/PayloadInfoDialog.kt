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
import me.zhanghai.android.files.util.copyText
import androidx.core.content.getSystemService
import android.content.ClipboardManager
import me.zhanghai.android.files.util.showToast

/** What an OTA `payload.bin` contains, without extracting anything. */
object PayloadInfoDialog {

    fun isPayloadName(name: String): Boolean = name.equals("payload.bin", ignoreCase = true)

    fun show(fragment: Fragment, path: Path) {
        val context = fragment.requireContext()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val manifest = withContext(Dispatchers.IO) {
                runCatching { path.newInputStream().buffered().use { PayloadManifest.read(it) } }
            }.getOrElse {
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
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.om_payload_copy) { _, _ ->
                    context.getSystemService<ClipboardManager>()?.copyText(summary + "\n" + table, context)
                }
                .show()
        }
    }
}
