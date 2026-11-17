/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.ClipData
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Paths
import kotlinx.coroutines.launch
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.theme.custom.OpenManagerPalette
import me.zhanghai.android.files.ui.isUiAlive
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.viewer.pdf.PdfViewerActivity
import me.zhanghai.android.files.viewer.video.VideoPlayerActivity

class SmartOpenActivity : AppActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            val target = try {
                SmartUriPathResolver.inspect(this@SmartOpenActivity, intent)
            } catch (_: Throwable) {
                null
            }
            if (!isUiAlive) return@launch

            if (target == null) {
                Toast.makeText(this@SmartOpenActivity, R.string.om_smart_open_error, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            when (target.kind) {
                SmartOpenKind.DIRECTORY -> {
                    val dir = target.directFile
                    if (dir != null) {
                        val dirIntent = FileListActivity.createViewIntent(Paths.get(dir.absolutePath))
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        startActivitySafe(dirIntent)
                    } else {
                        Toast.makeText(this@SmartOpenActivity, R.string.om_smart_open_error, Toast.LENGTH_SHORT).show()
                    }
                    finish()
                }
                SmartOpenKind.PDF -> {
                    openPdf(target)
                    finish()
                }
                SmartOpenKind.VIDEO -> {
                    showVideoChoiceDialog(target)
                }
                SmartOpenKind.FILE_LOCATION -> {
                    locateTargetInExplorer(target)
                }
            }
        }
    }

    private fun openPdf(target: SmartUriPathResolver.ResolvedIncomingTarget) {
        val pdfIntent = Intent(this, PdfViewerActivity::class.java).apply {
            val direct = target.directFile
            if (direct != null && direct.isFile && direct.canRead()) {
                extraPath = Paths.get(direct.absolutePath)
            }
            val uri = target.sourceUri
            if (uri != null) {
                data = uri
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startActivitySafe(pdfIntent)
    }

    private fun openVideo(target: SmartUriPathResolver.ResolvedIncomingTarget) {
        val videoIntent = Intent(this, VideoPlayerActivity::class.java).apply {
            val direct = target.directFile
            if (direct != null && direct.isFile && direct.canRead()) {
                extraPath = Paths.get(direct.absolutePath)
            }
            val uri = target.sourceUri
            if (uri != null) {
                data = uri
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (!target.mimeType.isNullOrBlank()) {
                putExtra(VideoPlayerActivity.EXTRA_DISPLAY_NAME, target.displayName)
            } else {
                putExtra(VideoPlayerActivity.EXTRA_DISPLAY_NAME, target.displayName)
            }
        }
        startActivitySafe(videoIntent)
    }

    private suspend fun locateTargetInExplorer(target: SmartUriPathResolver.ResolvedIncomingTarget) {
        try {
            SmartUriPathResolver.openTargetLocationInExplorer(this, target)
        } catch (_: Throwable) {
            if (isUiAlive) {
                Toast.makeText(this, R.string.om_smart_open_error, Toast.LENGTH_SHORT).show()
            }
        } finally {
            if (isUiAlive) {
                finish()
            }
        }
    }

    private fun showVideoChoiceDialog(target: SmartUriPathResolver.ResolvedIncomingTarget) {
        val palette = OpenManagerPalette.from(this)
        val density = resources.displayMetrics.density
        val padH = (20 * density).toInt()
        val padV = (14 * density).toInt()
        var actionTaken = false

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * density).toInt(), 0, (12 * density).toInt())
        }

        val subtitleText = target.directFile?.parent
            ?: target.sourceUri?.authority
            ?: target.mimeType
        if (!subtitleText.isNullOrBlank()) {
            val tvSubtitle = TextView(this).apply {
                text = subtitleText
                setTextColor(palette.textSecondary)
                textSize = 12f
                setPadding(padH, 0, padH, (10 * density).toInt())
                maxLines = 2
            }
            container.addView(tvSubtitle)
        }

        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)

        lateinit var dialog: androidx.appcompat.app.AlertDialog

        fun addOptionRow(
            iconRes: Int,
            iconTint: Int,
            titleRes: Int,
            subtitleRes: Int,
            onClick: () -> Unit
        ) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(padH, padV, padH, padV)
                setBackgroundResource(outValue.resourceId)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val icon = ImageView(this).apply {
                val size = (28 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = (16 * density).toInt()
                }
                setImageDrawable(ContextCompat.getDrawable(this@SmartOpenActivity, iconRes))
                ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(iconTint))
            }
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvTitle = TextView(this).apply {
                setText(titleRes)
                setTextColor(palette.textPrimary)
                textSize = 15.5f
                setTypeface(null, Typeface.BOLD)
            }
            val tvSub = TextView(this).apply {
                setText(subtitleRes)
                setTextColor(palette.textSecondary)
                textSize = 12.5f
            }
            textCol.addView(tvTitle)
            textCol.addView(tvSub)
            row.addView(icon)
            row.addView(textCol)
            row.setOnClickListener {
                actionTaken = true
                dialog.dismiss()
                onClick()
            }
            container.addView(row)
        }

        addOptionRow(
            iconRes = R.drawable.video_icon_white_24dp,
            iconTint = palette.accentColor,
            titleRes = R.string.om_smart_open_watch_video,
            subtitleRes = R.string.om_smart_open_watch_video_sub
        ) {
            openVideo(target)
            finish()
        }

        addOptionRow(
            iconRes = R.drawable.directory_icon_white_24dp,
            iconTint = palette.iconTint,
            titleRes = R.string.om_smart_open_location,
            subtitleRes = R.string.om_smart_open_location_sub
        ) {
            lifecycleScope.launch {
                locateTargetInExplorer(target)
            }
        }

        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(target.displayName)
            .setView(container)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                actionTaken = true
                finish()
            }
            .setOnCancelListener {
                if (!actionTaken) {
                    finish()
                }
            }
            .setOnDismissListener {
                if (!actionTaken && !isFinishing) {
                    finish()
                }
            }
            .show()
    }
}
