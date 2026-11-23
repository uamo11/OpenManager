/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import java8.nio.file.Paths
import kotlinx.coroutines.launch
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.ui.isUiAlive
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.viewer.audio.AudioPlayerActivity
import me.zhanghai.android.files.viewer.docx.DocxViewerActivity
import me.zhanghai.android.files.viewer.image.ImageViewerActivity
import me.zhanghai.android.files.viewer.pdf.PdfViewerActivity
import me.zhanghai.android.files.viewer.text.TextEditorActivity
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
                // One tap from the other app straight into the viewer; back shows the file in the
                // explorer to keep, copy or move it (see AppActivity.leaveToIncomingFile).
                SmartOpenKind.PDF -> {
                    openInViewer(target, PdfViewerActivity::class.java)
                    finish()
                }
                SmartOpenKind.VIDEO -> {
                    openInViewer(target, VideoPlayerActivity::class.java)
                    finish()
                }
                SmartOpenKind.IMAGE -> {
                    openInViewer(target, ImageViewerActivity::class.java)
                    finish()
                }
                SmartOpenKind.AUDIO -> {
                    openInViewer(target, AudioPlayerActivity::class.java)
                    finish()
                }
                SmartOpenKind.TEXT -> {
                    openInViewer(target, TextEditorActivity::class.java)
                    finish()
                }
                SmartOpenKind.DOCUMENT -> {
                    openInViewer(target, DocxViewerActivity::class.java)
                    finish()
                }
                SmartOpenKind.FILE_LOCATION -> {
                    locateTargetInExplorer(target)
                }
            }
        }
    }

    private fun openInViewer(target: SmartUriPathResolver.ResolvedIncomingTarget, viewer: Class<*>) {
        val incoming = intent
        // OpenManager itself also lands here (open with, files inside archives): keep its extras
        // (image list, path) and its usual back behaviour.
        val fromOwnApp = referrer?.host == packageName
        val viewerIntent = Intent(this, viewer).apply {
            if (fromOwnApp) {
                incoming.extras?.let { putExtras(it) }
            } else {
                putExtra(AppActivity.EXTRA_OPENED_FROM_OTHER_APP, true)
            }
            val uri = target.sourceUri
            if (uri != null) {
                // Re-grant to the viewer: the other app's grant ends with this activity.
                setDataAndType(uri, target.mimeType)
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // Pass on write access only when the other app gave it (text edits save back).
                if (incoming.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) {
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
            }
            val direct = target.directFile
            if (direct != null && direct.isFile && direct.canRead()) {
                // A real path beats the URI (editing, siblings, faster reads).
                extraPath = Paths.get(direct.absolutePath)
            }
            putExtra(VideoPlayerActivity.EXTRA_DISPLAY_NAME, target.displayName)
        }
        startActivitySafe(viewerIntent)
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

}
