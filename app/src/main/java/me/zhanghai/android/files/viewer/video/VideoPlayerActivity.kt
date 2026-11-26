/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.filelist.SmartUriPathResolver
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.settings.PerformanceModeManager
import me.zhanghai.android.files.ui.isUiAlive
import me.zhanghai.android.files.util.extraPath
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import java.util.Locale

/**
 * Lightweight Media3/ExoPlayer video player tuned for high-resolution files on modest devices:
 * - hardware decoders first, automatic fallback to the next decoder if one fails to initialize;
 * - SurfaceView output (decoder → display overlay, no extra GPU copy);
 * - local files and content:// URIs are streamed in place (no full copy before playing);
 * - late frames are dropped by the renderer to keep audio and video in sync instead of stuttering;
 * - small buffers (smaller still in Ultra RAM mode) since sources are local;
 * - dragging the seek bar previews key frames (cheap even in 4K), the exact frame on release;
 * - long videos reopen where they were left;
 * - a software decoder that cannot keep up with a big video is reported once, not left a mystery.
 */
@OptIn(UnstableApi::class)
class VideoPlayerActivity : AppActivity() {
    private lateinit var videoRoot: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlayControls: FrameLayout
    private lateinit var tvVideoTitle: TextView
    private lateinit var tvVideoElapsed: TextView
    private lateinit var tvVideoDuration: TextView
    private lateinit var tvSeekFeedback: TextView
    private lateinit var tvStats: TextView
    private lateinit var btnSpeed: TextView
    private lateinit var buffering: ProgressBar
    private lateinit var seekBarVideo: SeekBar
    private lateinit var ivVideoPlayPause: ImageView
    private lateinit var btnVideoPlayPause: FrameLayout
    private lateinit var btnVideoAspect: ImageButton

    private var player: ExoPlayer? = null
    private var currentPath: Path? = null
    private var externalUri: Uri? = null
    private var tempFile: File? = null
    private var isUserTracking = false
    private var isControlsVisible = true
    private var isZoomed = false
    private var isLocating = false
    private var orientationChosen = false
    private var speedIndex = NORMAL_SPEED_INDEX
    private var videoAspect = 0f

    private var decoderName: String? = null
    private var exceedsCapabilities = false
    private var lastRenderedFrames = 0
    private var lastStatsAtMs = 0L
    private var measuredFps = 0f
    private var lastDecodedFrames = 0
    private var decodedFps = 0f
    private var resumeChecked = false
    private var struggleReported = false
    private var lastScrubSeekAtMs = 0L
    private lateinit var resumeStore: VideoResumeStore
    private val resumeKey: String?
        get() = currentPath?.toString() ?: externalUri?.toString()

    private val handler = Handler(Looper.getMainLooper())

    private val hideControlsRunnable = Runnable { hideControls() }
    private val hideSeekFeedbackRunnable = Runnable { tvSeekFeedback.visibility = View.GONE }

    private val progressRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!isUserTracking) {
                val position = p.currentPosition
                seekBarVideo.progress = position.toInt()
                seekBarVideo.secondaryProgress = p.bufferedPosition.toInt()
                tvVideoElapsed.text = formatTime(position)
            }
            if (tvStats.visibility == View.VISIBLE) updateStats()
            checkDecoderKeepsUp(p)
            // Tick slower while controls and stats are hidden: nothing visible changes.
            val visible = isControlsVisible || tvStats.visibility == View.VISIBLE
            handler.postDelayed(this, if (visible) 250L else 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()
        setContentView(R.layout.activity_video_player)

        videoRoot = findViewById(R.id.videoRoot)
        surfaceView = findViewById(R.id.videoSurface)
        overlayControls = findViewById(R.id.overlayControls)
        tvVideoTitle = findViewById(R.id.tvVideoTitle)
        tvVideoElapsed = findViewById(R.id.tvVideoElapsed)
        tvVideoDuration = findViewById(R.id.tvVideoDuration)
        tvSeekFeedback = findViewById(R.id.tvVideoSeekFeedback)
        tvStats = findViewById(R.id.tvVideoStats)
        btnSpeed = findViewById(R.id.btnVideoSpeed)
        buffering = findViewById(R.id.videoBuffering)
        seekBarVideo = findViewById(R.id.seekBarVideo)
        ivVideoPlayPause = findViewById(R.id.ivVideoPlayPause)
        btnVideoPlayPause = findViewById(R.id.btnVideoPlayPause)
        btnVideoAspect = findViewById(R.id.btnVideoAspect)

        if (me.zhanghai.android.files.BuildConfig.DEBUG) {
            Av1SoftwareDecoder.debugOverride = intent.getStringExtra("DEMO_AV1_DECODER")
        }
        resumeStore = VideoResumeStore(this)
        currentPath = intent.extraPath
        externalUri = intent.data
        if (currentPath == null && externalUri == null) {
            Toast.makeText(this, R.string.om_video_invalid_path, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        tvVideoTitle.text = intent.getStringExtra(EXTRA_DISPLAY_NAME)
            ?: currentPath?.fileName?.toString()
            ?: externalUri?.lastPathSegment?.substringAfterLast('/')
            ?: getString(R.string.om_video_title)
        tvVideoTitle.isSelected = true

        findViewById<ImageButton>(R.id.btnVideoBack).setOnClickListener { leaveToIncomingFile() }
        findViewById<ImageButton>(R.id.btnVideoLocate).setOnClickListener { locateVideoInExplorer() }
        btnVideoPlayPause.setOnClickListener {
            togglePlayPause()
            scheduleControlsAutoHide()
        }
        btnVideoAspect.alpha = 0.7f
        btnVideoAspect.setOnClickListener {
            setZoomed(!isZoomed)
            scheduleControlsAutoHide()
        }
        btnSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % SPEEDS.size
            val speed = SPEEDS[speedIndex]
            player?.setPlaybackSpeed(speed)
            btnSpeed.text = formatSpeed(speed)
            scheduleControlsAutoHide()
        }
        findViewById<TextView>(R.id.btnVideoStats).setOnClickListener {
            tvStats.visibility = if (tvStats.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            updateStats()
            scheduleControlsAutoHide()
        }
        setupGestures()
        videoRoot.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) layoutSurface()
        }

        seekBarVideo.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                tvVideoElapsed.text = formatTime(progress.toLong())
                // Live preview while dragging: only key frames, which decode at once even in 4K.
                val now = System.currentTimeMillis()
                if (isUserTracking && now - lastScrubSeekAtMs >= SCRUB_INTERVAL_MS) {
                    lastScrubSeekAtMs = now
                    player?.seekTo(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                isUserTracking = true
                lastScrubSeekAtMs = 0L
                player?.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                isUserTracking = false
                player?.setSeekParameters(SeekParameters.EXACT)
                sb?.let { player?.seekTo(it.progress.toLong()) }
                scheduleControlsAutoHide()
            }
        })

        initPlayer()
        resolveSource()
    }

    private fun initPlayer() {
        val ultraRam = PerformanceModeManager.isEnabled
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>
            ) {
                // First in the list: AV1 goes to dav1d on every core when there is no AV1
                // hardware decoder; every other format falls through to MediaCodec.
                if (Av1SoftwareDecoder.shouldUse()) {
                    out.add(Av1SoftwareDecoder.createRenderer(allowedVideoJoiningTimeMs, eventHandler, eventListener))
                }
                super.buildVideoRenderers(
                    context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
                    eventHandler, eventListener, allowedVideoJoiningTimeMs, out
                )
            }
        }
            // If the preferred (hardware) decoder cannot be configured for this stream, try the
            // next one instead of failing: the main reason 4K files "don't play" on some devices.
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        // Sources are local, so a few MB of queued samples are plenty. ExoPlayer's default video
        // target (~128 MB) kept ~40 MB of 4K samples on the Java heap, which on low-RAM devices
        // means GC pauses and dropped frames.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (ultraRam) 2_500 else 5_000,
                if (ultraRam) 8_000 else 15_000,
                1_000,
                2_000
            )
            .setTargetBufferBytes(sampleBufferBytes(Runtime.getRuntime().maxMemory(), ultraRam))
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        exoPlayer.setVideoSurfaceView(surfaceView)
        exoPlayer.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        exoPlayer.addListener(playerListener)
        exoPlayer.addAnalyticsListener(analyticsListener)
        player = exoPlayer
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val p = player ?: return
            buffering.visibility = if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (playbackState == Player.STATE_READY) {
                val duration = p.duration
                if (duration != C.TIME_UNSET) {
                    seekBarVideo.max = duration.toInt()
                    tvVideoDuration.text = formatTime(duration)
                    maybeResume(p, duration)
                }
            }
            if (playbackState == Player.STATE_ENDED) {
                resumeKey?.let { resumeStore.remove(it) }
                ivVideoPlayPause.setImageResource(R.drawable.ic_play_arrow_24dp)
                showControls()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            ivVideoPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause_24dp else R.drawable.ic_play_arrow_24dp)
            if (isPlaying) scheduleControlsAutoHide() else showControls()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width == 0 || videoSize.height == 0) return
            videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            if (!orientationChosen) {
                orientationChosen = true
                requestedOrientation = if (videoAspect > 1.05f) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
            layoutSurface()
        }

        override fun onTracksChanged(tracks: Tracks) {
            val video = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return
            val support = if (video.length > 0) video.getTrackSupport(0) else C.FORMAT_HANDLED
            if (support == C.FORMAT_EXCEEDS_CAPABILITIES && !exceedsCapabilities) {
                exceedsCapabilities = true
                val format = video.getTrackFormat(0)
                Toast.makeText(
                    this@VideoPlayerActivity,
                    getString(R.string.om_video_exceeds_capabilities, "${format.width}×${format.height}"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!isUiAlive) return
            Log.w(TAG, "Playback error ${error.errorCodeName}", error)
            Toast.makeText(
                this@VideoPlayerActivity,
                getString(R.string.om_video_load_error_format, error.errorCodeName),
                Toast.LENGTH_LONG
            ).show()
            showControls()
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            this@VideoPlayerActivity.decoderName = decoderName
            Log.i(TAG, "Video decoder: $decoderName (${initializationDurationMs} ms)")
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            Log.i(TAG, "Video format: ${format.sampleMimeType} ${format.width}x${format.height} @${format.frameRate}")
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            Log.i(TAG, "Dropped $droppedFrames frames in $elapsedMs ms")
        }
    }

    /** Opens local files and content:// URIs in place; other providers (archives, SMB...) via a cache copy. */
    private fun resolveSource() {
        val path = currentPath
        val uri = externalUri
        if (path != null && !path.isArchivePath && path.isLinuxPath && path.toFile().canRead()) {
            play(Uri.fromFile(path.toFile()))
            return
        }
        if (path == null && uri != null) {
            play(uri)
            return
        }
        buffering.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val copy = withContext(Dispatchers.IO) {
                    val rawName = tvVideoTitle.text?.toString() ?: "video.mp4"
                    val ext = rawName.substringAfterLast('.', "mp4").takeIf { it.isNotBlank() && it.length <= 6 } ?: "mp4"
                    val temp = File(cacheDir, "video_preview_${System.currentTimeMillis()}.$ext")
                    path!!.newInputStream().use { input ->
                        FileOutputStream(temp).use { output -> input.copyTo(output, bufferSize = 256 * 1024) }
                    }
                    temp
                }
                if (!isUiAlive) {
                    copy.delete()
                    return@launch
                }
                tempFile = copy
                play(Uri.fromFile(copy))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (isUiAlive) {
                    Toast.makeText(
                        this@VideoPlayerActivity,
                        getString(R.string.om_video_load_error_format, e.message ?: ""),
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }
        }
    }

    private fun play(uri: Uri) {
        val p = player ?: return
        p.setMediaItem(MediaItem.fromUri(uri))
        p.prepare()
        p.playWhenReady = true
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
    }

    /** Sizes the SurfaceView to the video aspect: letterboxed (fit) or cropped to fill (zoom). */
    private fun layoutSurface() {
        val width = videoRoot.width
        val height = videoRoot.height
        if (width == 0 || height == 0 || videoAspect <= 0f) return
        val viewAspect = width.toFloat() / height
        val fitWidth: Int
        val fitHeight: Int
        if ((videoAspect > viewAspect) != isZoomed) {
            fitWidth = width
            fitHeight = (width / videoAspect).toInt()
        } else {
            fitHeight = height
            fitWidth = (height * videoAspect).toInt()
        }
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != fitWidth || lp.height != fitHeight) {
            lp.width = fitWidth
            lp.height = fitHeight
            lp.gravity = android.view.Gravity.CENTER
            surfaceView.layoutParams = lp
        }
    }

    /**
     * Gestures of a full player: tap (controls), double tap (±10 s / pause), horizontal drag
     * (seek with a live preview), vertical drag on the left (brightness) or the right (volume),
     * long press (2× while held) and pinch (fit or fill the screen).
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val third = videoRoot.width / 3f
                when {
                    e.x < third -> seekBy(-SEEK_STEP_MS)
                    e.x > third * 2 -> seekBy(SEEK_STEP_MS)
                    else -> togglePlayPause()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (drag == VideoDrag.NONE && !isPinching) startSpeedHold()
            }
        })
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var factor = 1f

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                factor = 1f
                isPinching = true
                endDrag(commit = false)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                factor *= detector.scaleFactor
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                when {
                    factor > 1.12f && !isZoomed -> setZoomed(true)
                    factor < 0.89f && isZoomed -> setZoomed(false)
                }
            }
        })
        val slop = ViewConfiguration.get(this).scaledTouchSlop * 1.5f
        videoRoot.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isPinching = false
                    drag = VideoDrag.NONE
                    dragStartX = event.x
                    dragStartY = event.y
                }
                MotionEvent.ACTION_MOVE -> if (!isPinching && !isSpeedHeld && event.pointerCount == 1) {
                    if (drag == VideoDrag.NONE) {
                        drag = VideoGestures.classify(
                            event.x - dragStartX, event.y - dragStartY, slop, dragStartX, videoRoot.width
                        )
                        if (drag != VideoDrag.NONE) beginDrag(event)
                    }
                    if (drag != VideoDrag.NONE) updateDrag(event)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    endDrag(commit = event.actionMasked == MotionEvent.ACTION_UP)
                    stopSpeedHold()
                }
            }
            // A drag or pinch is not also a tap, double tap or long press.
            if (drag == VideoDrag.NONE && !isPinching) {
                detector.onTouchEvent(event)
            } else if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                detector.onTouchEvent(cancel)
                cancel.recycle()
            }
            true
        }
    }

    private var drag = VideoDrag.NONE
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragStartPositionMs = 0L
    private var dragTargetMs = 0L
    private var dragStartBrightness = 0.5f
    private var dragStartVolume = 0
    private var isPinching = false
    private var isSpeedHeld = false
    private var lastGestureSeekAtMs = 0L

    private val audioManager: AudioManager by lazy { getSystemService(AudioManager::class.java) }

    private fun beginDrag(event: MotionEvent) {
        handler.removeCallbacks(hideSeekFeedbackRunnable)
        when (drag) {
            VideoDrag.SEEK -> {
                val p = player ?: run { drag = VideoDrag.NONE; return }
                dragStartPositionMs = p.currentPosition
                dragTargetMs = dragStartPositionMs
                lastGestureSeekAtMs = 0L
                // Preview with key frames: they decode at once, even in 4K.
                p.setSeekParameters(SeekParameters.CLOSEST_SYNC)
            }
            VideoDrag.BRIGHTNESS -> {
                val current = window.attributes.screenBrightness
                dragStartBrightness = if (current >= 0f) current else systemBrightness()
            }
            VideoDrag.VOLUME -> dragStartVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            VideoDrag.NONE -> Unit
        }
        // Start from where the finger is now, so the value does not jump by the slop.
        dragStartX = event.x
        dragStartY = event.y
    }

    private fun updateDrag(event: MotionEvent) {
        when (drag) {
            VideoDrag.SEEK -> {
                val p = player ?: return
                val duration = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                dragTargetMs = VideoGestures.seekTarget(
                    dragStartPositionMs, event.x - dragStartX, videoRoot.width, duration
                )
                showGestureFeedback(
                    VideoGestures.seekLabel(dragStartPositionMs, dragTargetMs, duration),
                    if (dragTargetMs < dragStartPositionMs) R.drawable.ic_gesture_fast_rewind_32dp
                    else R.drawable.ic_gesture_fast_forward_32dp
                )
                val now = System.currentTimeMillis()
                if (now - lastGestureSeekAtMs >= SCRUB_INTERVAL_MS) {
                    lastGestureSeekAtMs = now
                    p.seekTo(dragTargetMs)
                }
            }
            VideoDrag.BRIGHTNESS -> {
                val value = VideoGestures.brightness(dragStartBrightness, event.y - dragStartY, videoRoot.height)
                window.attributes = window.attributes.apply { screenBrightness = value }
                showGestureFeedback(
                    getString(R.string.om_video_brightness, (value * 100).roundToInt()),
                    R.drawable.ic_gesture_brightness_32dp
                )
            }
            VideoDrag.VOLUME -> {
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val value = VideoGestures.volume(dragStartVolume, event.y - dragStartY, videoRoot.height, max)
                if (value != audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                }
                showGestureFeedback(
                    getString(R.string.om_video_volume, value * 100 / max.coerceAtLeast(1)),
                    when {
                        value == 0 -> R.drawable.ic_gesture_volume_off_32dp
                        value * 2 < max -> R.drawable.ic_gesture_volume_down_32dp
                        else -> R.drawable.ic_gesture_volume_up_32dp
                    }
                )
            }
            VideoDrag.NONE -> Unit
        }
    }

    private fun endDrag(commit: Boolean) {
        val ended = drag
        drag = VideoDrag.NONE
        if (ended == VideoDrag.NONE) return
        if (ended == VideoDrag.SEEK) {
            player?.let { p ->
                p.setSeekParameters(SeekParameters.EXACT)
                if (commit) p.seekTo(dragTargetMs)
            }
        }
        if (me.zhanghai.android.files.BuildConfig.DEBUG) {
            Log.i(TAG, when (ended) {
                VideoDrag.SEEK -> "Gesture seek from=$dragStartPositionMs to=$dragTargetMs"
                VideoDrag.BRIGHTNESS -> "Gesture brightness=${window.attributes.screenBrightness} from=$dragStartBrightness"
                VideoDrag.VOLUME -> "Gesture volume=${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)}" +
                    "/${audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)} from=$dragStartVolume"
                VideoDrag.NONE -> ""
            })
        }
        handler.removeCallbacks(hideSeekFeedbackRunnable)
        handler.postDelayed(hideSeekFeedbackRunnable, 600)
    }

    private fun systemBrightness(): Float = try {
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
    } catch (_: Exception) {
        0.5f
    }

    /** The value of a gesture, with its icon above it (brightness, volume, seek, speed). */
    private fun showGestureFeedback(text: String, iconRes: Int = 0) {
        tvSeekFeedback.text = text
        if (tvSeekFeedback.tag != iconRes) {
            tvSeekFeedback.tag = iconRes
            tvSeekFeedback.setCompoundDrawablesRelativeWithIntrinsicBounds(0, iconRes, 0, 0)
        }
        tvSeekFeedback.visibility = View.VISIBLE
    }

    /** Long press: 2× while the finger stays down, then back to the chosen speed. */
    private fun startSpeedHold() {
        val p = player ?: return
        if (!p.isPlaying) return
        isSpeedHeld = true
        p.setPlaybackSpeed(VideoGestures.HOLD_SPEED)
        videoRoot.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        handler.removeCallbacks(hideSeekFeedbackRunnable)
        showGestureFeedback(formatSpeed(VideoGestures.HOLD_SPEED), R.drawable.ic_gesture_fast_forward_32dp)
        if (me.zhanghai.android.files.BuildConfig.DEBUG) Log.i(TAG, "Gesture speed hold ${VideoGestures.HOLD_SPEED}")
    }

    private fun stopSpeedHold() {
        if (!isSpeedHeld) return
        isSpeedHeld = false
        player?.setPlaybackSpeed(SPEEDS[speedIndex])
        handler.removeCallbacks(hideSeekFeedbackRunnable)
        handler.postDelayed(hideSeekFeedbackRunnable, 300)
        if (me.zhanghai.android.files.BuildConfig.DEBUG) Log.i(TAG, "Gesture speed back ${SPEEDS[speedIndex]}")
    }

    private fun setZoomed(zoomed: Boolean) {
        isZoomed = zoomed
        btnVideoAspect.alpha = if (isZoomed) 1f else 0.7f
        layoutSurface()
        handler.removeCallbacks(hideSeekFeedbackRunnable)
        showGestureFeedback(getString(if (isZoomed) R.string.om_video_fill else R.string.om_video_fit))
        handler.postDelayed(hideSeekFeedbackRunnable, 900)
        if (me.zhanghai.android.files.BuildConfig.DEBUG) Log.i(TAG, "Gesture zoom=${if (isZoomed) "fill" else "fit"}")
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val duration = p.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0, duration))
        tvSeekFeedback.text = if (deltaMs > 0) "+${deltaMs / 1000} s" else "−${-deltaMs / 1000} s"
        tvSeekFeedback.visibility = View.VISIBLE
        handler.removeCallbacks(hideSeekFeedbackRunnable)
        handler.postDelayed(hideSeekFeedbackRunnable, 700)
    }

    private fun maybeResume(p: ExoPlayer, durationMs: Long) {
        if (resumeChecked) return
        resumeChecked = true
        val key = resumeKey ?: return
        val saved = resumeStore.get(key) ?: return
        if (!shouldRememberPosition(saved, durationMs) || p.currentPosition > RESUME_EDGE_MS) return
        p.seekTo(saved)
        Log.i(TAG, "Resumed at $saved ms")
        Toast.makeText(this, getString(R.string.om_video_resumed, formatTime(saved)), Toast.LENGTH_SHORT).show()
    }

    /** Tells the user once when a software decoder drops most frames of a big video. */
    private fun checkDecoderKeepsUp(p: ExoPlayer) {
        if (struggleReported || !p.isPlaying) return
        val format = p.videoFormat ?: return
        if (minOf(format.width, format.height) < 1440) return
        val counters = p.videoDecoderCounters ?: return
        val software = isSoftwareDecoder(decoderName)
        if (isDecoderStruggling(counters.renderedOutputBufferCount, counters.droppedBufferCount, software)) {
            struggleReported = true
            Log.i(TAG, "Software decoder cannot keep up with ${format.width}x${format.height}")
            Toast.makeText(
                this,
                getString(R.string.om_video_software_decoding, "${format.width}×${format.height}"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updateStats() {
        val p = player ?: return
        val format = p.videoFormat
        val counters = p.videoDecoderCounters
        val now = System.currentTimeMillis()
        val rendered = counters?.renderedOutputBufferCount ?: 0
        // Decoded = shown + decoded too late to show: what the decoder itself manages per second.
        val decoded = rendered + (counters?.droppedBufferCount ?: 0)
        if (lastStatsAtMs > 0 && now - lastStatsAtMs >= 900) {
            measuredFps = (rendered - lastRenderedFrames) * 1000f / (now - lastStatsAtMs)
            decodedFps = (decoded - lastDecodedFrames) * 1000f / (now - lastStatsAtMs)
            lastRenderedFrames = rendered
            lastDecodedFrames = decoded
            lastStatsAtMs = now
        } else if (lastStatsAtMs == 0L) {
            lastRenderedFrames = rendered
            lastDecodedFrames = decoded
            lastStatsAtMs = now
        }
        val isDav1d = decoderName?.contains("libdav1d", ignoreCase = true) == true
        val software = decoderName != null && isSoftwareDecoder(decoderName)
        tvStats.text = buildString {
            append(decoderName ?: "—")
            if (decoderName != null) append(if (isSoftwareDecoder(decoderName)) " · software" else " · hardware")
            append('\n')
            if (format != null) {
                append(format.sampleMimeType?.substringAfter('/') ?: "?")
                append("  ").append(format.width).append('×').append(format.height)
                if (format.frameRate > 0) append(String.format(Locale.ROOT, " @ %.2f", format.frameRate))
                append('\n')
            }
            if (isDav1d) {
                append("dav1d ").append(Av1SoftwareDecoder.modeLabel())
                append(" · ").append(Runtime.getRuntime().availableProcessors()).append(" cores\n")
            }
            append(String.format(Locale.ROOT, "fps %.1f  ×%s", measuredFps, formatSpeed(SPEEDS[speedIndex])))
            append(String.format(Locale.ROOT, "  decoded %.1f/s\n", decodedFps))
            append("dropped ").append(counters?.droppedBufferCount ?: 0)
            append(" / rendered ").append(rendered)
            if (isDav1d) {
                append(" / not decoded ").append(androidx.media3.decoder.av1.Libdav1dVideoRenderer.skippedNonReferenceInputs)
                val share = androidx.media3.decoder.av1.Libdav1dVideoRenderer.skipShare
                if (share > 0.005) append(String.format(Locale.ROOT, " (skip %.0f%%)", share * 100))
            }
            // When the processor is the limit, say it plainly instead of leaving a low number.
            val target = (format?.frameRate ?: 0f) * SPEEDS[speedIndex]
            if (software && target > 0 && decodedFps > 0 && decodedFps < target * 0.8f && p.isPlaying) {
                append(String.format(Locale.ROOT, "\nCPU limit: decodes %.0f of %.0f fps", decodedFps, target))
            }
            if (exceedsCapabilities) append("\nexceeds declared decoder caps")
        }
    }

    private fun togglePlayPause() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) {
            p.seekTo(0)
            p.play()
        } else if (p.isPlaying) {
            p.pause()
        } else {
            p.play()
        }
    }

    private fun toggleControls() {
        if (isControlsVisible) hideControls() else {
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
        if (player?.isPlaying != true) return
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

    @Suppress("DEPRECATION")
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

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(progressRunnable)
        if (player != null) handler.post(progressRunnable)
    }

    override fun onStop() {
        super.onStop()
        player?.let { p ->
            val counters = p.videoDecoderCounters
            Log.i(
                TAG, "Summary decoder=$decoderName rendered=${counters?.renderedOutputBufferCount ?: 0} " +
                    "dropped=${counters?.droppedBufferCount ?: 0} skipped=${counters?.skippedOutputBufferCount ?: 0} " +
                    "notDecoded=${androidx.media3.decoder.av1.Libdav1dVideoRenderer.skippedNonReferenceInputs} " +
                    "mode=${androidx.media3.decoder.av1.Libdav1dVideoRenderer.speedFlags} " +
                    "positionMs=${p.currentPosition}"
            )
            val key = resumeKey
            if (key != null && p.duration != C.TIME_UNSET && p.playbackState != Player.STATE_ENDED) {
                resumeStore.put(key, p.currentPosition, p.duration)
            }
        }
        // Free the decoder while hidden; position and media item are kept.
        player?.pause()
        handler.removeCallbacks(progressRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player?.run {
            removeListener(playerListener)
            removeAnalyticsListener(analyticsListener)
            release()
        }
        player = null
        tempFile?.delete()
        tempFile = null
    }

    private fun locateVideoInExplorer() {
        if (isLocating) return
        isLocating = true
        lifecycleScope.launch {
            try {
                val ok = SmartUriPathResolver.locateCurrentOrIncomingInExplorer(
                    this@VideoPlayerActivity,
                    intent,
                    currentPath
                )
                if (!isUiAlive) return@launch
                if (ok) {
                    finish()
                } else {
                    Toast.makeText(this@VideoPlayerActivity, R.string.om_smart_open_error, Toast.LENGTH_SHORT).show()
                }
            } catch (_: Throwable) {
                if (isUiAlive) {
                    Toast.makeText(this@VideoPlayerActivity, R.string.om_smart_open_error, Toast.LENGTH_SHORT).show()
                }
            } finally {
                isLocating = false
            }
        }
    }

    companion object {
        const val EXTRA_DISPLAY_NAME = "me.zhanghai.android.files.viewer.video.extra.DISPLAY_NAME"
        private const val TAG = "OMVideoPlayer"
        private const val SEEK_STEP_MS = 10_000L
        private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        private const val NORMAL_SPEED_INDEX = 2
        private const val SCRUB_INTERVAL_MS = 120L

        fun start(context: Context, path: Path) {
            val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                extraPath = path
            }
            context.startActivity(intent)
        }
    }
}
