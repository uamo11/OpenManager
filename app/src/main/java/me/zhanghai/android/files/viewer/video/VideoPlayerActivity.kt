/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.lifecycle.lifecycleScope
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.ui.isUiAlive
import me.zhanghai.android.files.util.extraPath
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class VideoPlayerActivity : AppActivity() {
    private lateinit var videoRoot: FrameLayout
    private lateinit var videoView: VideoView
    private lateinit var overlayControls: FrameLayout
    private lateinit var tvVideoTitle: TextView
    private lateinit var tvVideoElapsed: TextView
    private lateinit var tvVideoDuration: TextView
    private lateinit var seekBarVideo: SeekBar
    private lateinit var ivVideoPlayPause: ImageView
    private lateinit var btnVideoPlayPause: FrameLayout
    private lateinit var btnVideoAspect: ImageButton

    private var tempFile: File? = null
    private var isUserTracking = false
    private var isControlsVisible = true
    private var isAspectFill = false

    private val handler = Handler(Looper.getMainLooper())

    private val hideControlsRunnable = Runnable {
        hideControls()
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            if (videoView.isPlaying && !isUserTracking) {
                val current = videoView.currentPosition
                seekBarVideo.progress = current
                tvVideoElapsed.text = formatTime(current)
            }
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        setContentView(R.layout.activity_video_player)

        videoRoot = findViewById(R.id.videoRoot)
        videoView = findViewById(R.id.videoView)
        overlayControls = findViewById(R.id.overlayControls)
        tvVideoTitle = findViewById(R.id.tvVideoTitle)
        tvVideoElapsed = findViewById(R.id.tvVideoElapsed)
        tvVideoDuration = findViewById(R.id.tvVideoDuration)
        seekBarVideo = findViewById(R.id.seekBarVideo)
        ivVideoPlayPause = findViewById(R.id.ivVideoPlayPause)
        btnVideoPlayPause = findViewById(R.id.btnVideoPlayPause)
        btnVideoAspect = findViewById(R.id.btnVideoAspect)
        val btnVideoBack = findViewById<ImageButton>(R.id.btnVideoBack)

        val path = intent.extraPath
        if (path == null) {
            Toast.makeText(this, R.string.om_video_invalid_path, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        tvVideoTitle.text = path.fileName.toString()
        tvVideoTitle.isSelected = true

        btnVideoBack.setOnClickListener { finish() }

        btnVideoPlayPause.setOnClickListener {
            togglePlayPause()
            scheduleControlsAutoHide()
        }

        btnVideoAspect.setOnClickListener {
            isAspectFill = !isAspectFill
            val lp = videoView.layoutParams
            if (isAspectFill) {
                lp.width = FrameLayout.LayoutParams.MATCH_PARENT
                lp.height = FrameLayout.LayoutParams.MATCH_PARENT
                btnVideoAspect.alpha = 1.0f
            } else {
                lp.width = FrameLayout.LayoutParams.WRAP_CONTENT
                lp.height = FrameLayout.LayoutParams.WRAP_CONTENT
                btnVideoAspect.alpha = 0.7f
            }
            videoView.layoutParams = lp
            scheduleControlsAutoHide()
        }

        videoRoot.setOnClickListener {
            toggleControls()
        }

        seekBarVideo.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvVideoElapsed.text = formatTime(progress)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                isUserTracking = true
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                isUserTracking = false
                sb?.let {
                    try {
                        videoView.seekTo(it.progress)
                    } catch (e: Exception) {
                        // Ignore seek before player is prepared
                    }
                }
                scheduleControlsAutoHide()
            }
        })

        initVideo(path)
    }

    private fun initVideo(path: Path) {
        lifecycleScope.launch {
            try {
                val videoFile: File = if (!path.isArchivePath && path.isLinuxPath) {
                    path.toFile()
                } else {
                    withContext(Dispatchers.IO) {
                        val ext = path.fileName.toString().substringAfterLast('.', "tmp")
                        val temp = File(cacheDir, "video_preview_${System.currentTimeMillis()}.$ext")
                        path.newInputStream().use { input ->
                            FileOutputStream(temp).use { output ->
                                input.copyTo(output)
                            }
                        }
                        temp
                    }
                }
                if (!isUiAlive) {
                    if (path.isArchivePath || !path.isLinuxPath) {
                        videoFile.delete()
                    }
                    return@launch
                }
                if (path.isArchivePath || !path.isLinuxPath) {
                    tempFile = videoFile
                }

                videoView.setVideoPath(videoFile.absolutePath)
                videoView.setOnPreparedListener { mp ->
                    if (!isUiAlive) return@setOnPreparedListener
                    val duration = mp.duration
                    seekBarVideo.max = duration
                    tvVideoDuration.text = formatTime(duration)
                    videoView.start()
                    ivVideoPlayPause.setImageResource(R.drawable.ic_pause_24dp)
                    handler.post(progressRunnable)
                    scheduleControlsAutoHide()
                }
                videoView.setOnCompletionListener {
                    ivVideoPlayPause.setImageResource(R.drawable.ic_play_arrow_24dp)
                    showControls()
                }
                videoView.setOnErrorListener { _, what, extra ->
                    if (isUiAlive) {
                        Toast.makeText(this@VideoPlayerActivity, getString(R.string.om_video_play_error_format, what, extra), Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    true
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
                if (isUiAlive) {
                    Toast.makeText(this@VideoPlayerActivity, getString(R.string.om_video_load_error_format, e.message ?: ""), Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun togglePlayPause() {
        if (videoView.isPlaying) {
            videoView.pause()
            ivVideoPlayPause.setImageResource(R.drawable.ic_play_arrow_24dp)
            showControls()
        } else {
            videoView.start()
            ivVideoPlayPause.setImageResource(R.drawable.ic_pause_24dp)
            scheduleControlsAutoHide()
        }
    }

    private fun toggleControls() {
        if (isControlsVisible) {
            hideControls()
        } else {
            showControls()
            scheduleControlsAutoHide()
        }
    }

    private fun showControls() {
        isControlsVisible = true
        overlayControls.visibility = View.VISIBLE
        overlayControls.animate().alpha(1.0f).setDuration(200).start()
    }

    private fun hideControls() {
        if (!videoView.isPlaying) return
        isControlsVisible = false
        overlayControls.animate().alpha(0.0f).setDuration(300).withEndAction {
            overlayControls.visibility = View.GONE
        }.start()
        hideSystemUI()
    }

    private fun scheduleControlsAutoHide() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 3500)
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
    }

    private fun formatTime(millis: Int): String {
        val totalSec = millis / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.ROOT, "%02d:%02d", min, sec)
    }

    override fun onPause() {
        super.onPause()
        if (videoView.isPlaying) {
            videoView.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        videoView.stopPlayback()
        tempFile?.delete()
        tempFile = null
    }

    companion object {
        fun start(context: Context, path: Path) {
            val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                extraPath = path
            }
            context.startActivity(intent)
        }
    }
}
