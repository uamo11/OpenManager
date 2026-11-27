/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.audio

import android.app.Dialog
import android.content.ComponentName
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.ListenableFuture
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.ui.isUiAlive
import me.zhanghai.android.files.util.Haptics
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.putArgs
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * The player window. The music itself plays in [MusicService], so it goes on in the background
 * (notification, lock screen) when this window is closed with Back or a tap outside; ✕ stops it.
 * The songs are the audio files of the folder, as in MT's player (user request).
 */
class AudioPlayerDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var speedIndex = 0
    private val speeds = floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f, 0.5f, 0.75f)

    private val handler = Handler(Looper.getMainLooper())
    private var isUserTracking = false
    private var shownSecond = -1L
    private var shownDurationSecond = -1L

    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvElapsed: TextView
    private lateinit var tvDuration: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var ivArt: ImageView
    private lateinit var ivPlayPause: ImageView
    private lateinit var btnLoop: ImageButton
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnSpeed: TextView

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (isUiAlive) render()
        }
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            val c = controller ?: return
            if (!isUiAlive) return
            renderProgress(c)
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_audio_player, null)

        tvTitle = view.findViewById(R.id.tvAudioTitle)
        tvSubtitle = view.findViewById(R.id.tvAudioSubtitle)
        tvElapsed = view.findViewById(R.id.tvAudioElapsed)
        tvDuration = view.findViewById(R.id.tvAudioDuration)
        seekBar = view.findViewById(R.id.seekBarAudio)
        ivArt = view.findViewById(R.id.ivAudioArt)
        ivPlayPause = view.findViewById(R.id.ivAudioPlayPause)
        btnLoop = view.findViewById(R.id.btnAudioLoop)
        btnShuffle = view.findViewById(R.id.btnAudioShuffle)
        btnSpeed = view.findViewById(R.id.btnAudioSpeed)
        val btnClose = view.findViewById<ImageButton>(R.id.btnAudioClose)
        val btnPrevious = view.findViewById<ImageButton>(R.id.btnAudioPrevious)
        val btnNext = view.findViewById<ImageButton>(R.id.btnAudioNext)
        val btnPlayPause = view.findViewById<FrameLayout>(R.id.btnAudioPlayPause)

        args.path?.let { path ->
            val fileName = path.fileName.toString()
            tvTitle.text = fileName
            tvSubtitle.text = getString(
                R.string.om_audio_player_subtitle, fileName.substringAfterLast('.', "AUDIO").uppercase(Locale.ROOT)
            )
        }
        tvTitle.isSelected = true

        // ✕ stops the music; Back or a tap outside only hides the window.
        btnClose.setOnClickListener {
            controller?.run {
                stop()
                clearMediaItems()
            }
            dismiss()
        }
        btnPlayPause.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.isPlaying) {
                c.pause()
            } else {
                if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                c.play()
            }
        }
        btnPrevious.setOnClickListener { controller?.seekToPrevious() }
        btnNext.setOnClickListener { controller?.seekToNext() }
        // Holding them jumps 10 s, as the old buttons did.
        btnPrevious.setOnLongClickListener {
            controller?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) }
            Haptics.longPress(it)
            true
        }
        btnNext.setOnLongClickListener {
            controller?.let { c ->
                val end = c.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
                c.seekTo((c.currentPosition + 10_000).coerceAtMost(end))
            }
            Haptics.longPress(it)
            true
        }
        btnLoop.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            val next = when (c.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            c.repeatMode = next
            Toast.makeText(
                context,
                when (next) {
                    Player.REPEAT_MODE_ALL -> R.string.om_audio_repeat_all
                    Player.REPEAT_MODE_ONE -> R.string.om_audio_repeat_one
                    else -> R.string.om_audio_repeat_off
                },
                Toast.LENGTH_SHORT
            ).show()
        }
        btnShuffle.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            c.shuffleModeEnabled = !c.shuffleModeEnabled
            Toast.makeText(
                context, if (c.shuffleModeEnabled) R.string.om_audio_shuffle_on else R.string.om_audio_shuffle_off,
                Toast.LENGTH_SHORT
            ).show()
        }
        btnSpeed.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            speedIndex = (speedIndex + 1) % speeds.size
            c.setPlaybackSpeed(speeds[speedIndex])
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) tvElapsed.text = formatTime(progress.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                isUserTracking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                isUserTracking = false
                sb?.let { controller?.seekTo(it.progress.toLong()) }
            }
        })

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(view)
            .create()
        connect()
        return dialog
    }

    private fun connect() {
        val context = requireContext()
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = runCatching { future.get() }.getOrNull()
            if (c == null || !isUiAlive) {
                if (c != null) MediaController.releaseFuture(future)
                if (c == null && isUiAlive) dismissAllowingStateLoss()
                return@addListener
            }
            controller = c
            c.addListener(listener)
            val path = args.path
            if (path == null) {
                // From the notification: show what is playing.
                if (c.mediaItemCount == 0) dismissAllowingStateLoss() else onReady(c)
            } else {
                load(c, path)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun load(c: MediaController, path: Path) {
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            try {
                val (files, index) = withContext(Dispatchers.IO) {
                    if (!path.isArchivePath && path.isLinuxPath) {
                        MusicPlaylist.around(path.toFile())
                    } else {
                        // Archives and remote storage: a copy of this one song.
                        val ext = path.fileName.toString().substringAfterLast('.', "tmp")
                        val copy = File(appContext.cacheDir, "${MusicService.COPY_PREFIX}${System.currentTimeMillis()}.$ext")
                        path.newInputStream().use { input -> FileOutputStream(copy).use { input.copyTo(it) } }
                        MusicService.deleteCopies(appContext.cacheDir, keep = copy)
                        listOf(copy) to 0
                    }
                }
                if (!isUiAlive) return@launch
                val wanted = files[index].absolutePath
                // Opened again while it plays: keep going instead of starting over.
                if (c.currentMediaItem?.mediaId != wanted || c.playbackState == Player.STATE_IDLE) {
                    val items = files.map { file ->
                        MediaItem.Builder()
                            .setMediaId(file.absolutePath)
                            .setUri(Uri.fromFile(file))
                            .setMediaMetadata(MediaMetadata.Builder().setDisplayTitle(file.name).build())
                            .build()
                    }
                    c.setMediaItems(items, index, 0)
                    c.prepare()
                }
                c.play()
                onReady(c)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
                if (isUiAlive) {
                    Toast.makeText(appContext, getString(R.string.om_audio_open_error_format, e.message ?: ""), Toast.LENGTH_SHORT).show()
                    dismissAllowingStateLoss()
                }
            }
        }
    }

    private fun onReady(c: MediaController) {
        speedIndex = speeds.indexOfFirst { it == c.playbackParameters.speed }.coerceAtLeast(0)
        render()
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
    }

    private fun render() {
        val c = controller ?: return
        val metadata = c.mediaMetadata
        val fileName = c.currentMediaItem?.mediaId?.substringAfterLast('/')
        val title = metadata.title ?: metadata.displayTitle ?: fileName
        if (title != null && tvTitle.text != title) tvTitle.text = title
        val parts = ArrayList<String>()
        (metadata.artist ?: metadata.albumArtist)?.let { parts += it.toString() }
        if (c.mediaItemCount > 1) {
            parts += getString(R.string.om_audio_position_format, c.currentMediaItemIndex + 1, c.mediaItemCount)
        }
        fileName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }?.let { parts += it.uppercase(Locale.ROOT) }
        val subtitle = parts.joinToString(" · ")
        if (subtitle.isNotEmpty() && tvSubtitle.text != subtitle) tvSubtitle.text = subtitle
        renderArt(metadata)
        ivPlayPause.setImageResource(if (c.isPlaying) R.drawable.ic_pause_24dp else R.drawable.ic_play_arrow_24dp)
        val on = Color.parseColor("#2196F3")
        val off = Color.parseColor("#666666")
        btnLoop.setImageResource(
            if (c.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one_24dp else R.drawable.ic_repeat_24dp
        )
        btnLoop.imageTintList = ColorStateList.valueOf(if (c.repeatMode == Player.REPEAT_MODE_OFF) off else on)
        btnShuffle.imageTintList = ColorStateList.valueOf(if (c.shuffleModeEnabled) on else off)
        val speed = c.playbackParameters.speed
        val speedText = "${speed}x"
        if (btnSpeed.text != speedText) btnSpeed.text = speedText
        renderProgress(c)
    }

    private var artKey: Int = 0

    private fun renderArt(metadata: MediaMetadata) {
        val data = metadata.artworkData
        val key = data?.contentHashCode() ?: 0
        if (key == artKey) return
        artKey = key
        val bitmap = data?.let {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(it, 0, it.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 256 && bounds.outHeight / (sample * 2) >= 256) sample *= 2
            runCatching {
                BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }
        if (bitmap != null) {
            ivArt.scaleType = ImageView.ScaleType.CENTER_CROP
            ivArt.setImageBitmap(bitmap)
        } else {
            ivArt.scaleType = ImageView.ScaleType.FIT_CENTER
            ivArt.setImageResource(R.drawable.file_audio_icon)
        }
    }

    /** Texts only change when the second does: a text rewritten 4 times a second keeps the UI busy. */
    private fun renderProgress(c: Player) {
        val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val durationSecond = duration / 1000
        if (durationSecond != shownDurationSecond) {
            shownDurationSecond = durationSecond
            seekBar.max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            tvDuration.text = formatTime(duration)
        }
        if (isUserTracking) return
        val position = c.currentPosition.coerceAtLeast(0)
        val second = position / 1000
        if (second != shownSecond) {
            shownSecond = second
            seekBar.progress = position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            tvElapsed.text = formatTime(position)
        }
    }

    private fun formatTime(millis: Long): String {
        val totalSec = millis / 1000
        val hours = totalSec / 3600
        val min = (totalSec / 60) % 60
        val sec = totalSec % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, min, sec)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", min, sec)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        releaseController()
        (activity as? AudioPlayerActivity)?.leaveToIncomingFile()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseController()
    }

    /** Only the window's link to the service: the music keeps playing. */
    private fun releaseController() {
        handler.removeCallbacksAndMessages(null)
        controller?.removeListener(listener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

    companion object {
        fun show(path: Path, fragmentManager: FragmentManager) {
            AudioPlayerDialogFragment().putArgs(Args(path)).show(fragmentManager, null)
        }
    }

    /** [path] null: show what the service is playing (opened from the notification). */
    @Parcelize
    class Args(val path: @WriteWith<ParcelableParceler> Path?) : ParcelableArgs
}
