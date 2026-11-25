/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.av1.Dav1dLibrary
import androidx.media3.decoder.av1.Libdav1dVideoRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * AV1 without a hardware decoder (most phones before 2023, like the Galaxy A07) is decoded in
 * software. Android's own dav1d codec uses only half of the cores; ours uses all of them with
 * dav1d's automatic frame threading, which is what 4K needs. Phones with an AV1 hardware decoder
 * keep using it: it is faster still and saves battery.
 */
@OptIn(UnstableApi::class)
object Av1SoftwareDecoder {
    private const val AV1_MIME = "video/av01"

    /** Debug builds only: "system" or "dav1d" forces a decoder, for comparisons on the emulator. */
    @Volatile
    var debugOverride: String? = null

    val hasHardwareDecoder: Boolean by lazy {
        try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(AV1_MIME, ignoreCase = true) } &&
                    isHardware(info)
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated && !info.isSoftwareOnly
        } else {
            !isSoftwareDecoder(info.name)
        }

    fun shouldUse(): Boolean = when (debugOverride) {
        "system" -> false
        "dav1d", "dav1d-noskip", "dav1d-full" -> Dav1dLibrary.isAvailable()
        else -> !hasHardwareDecoder && Dav1dLibrary.isAvailable()
    }

    fun createRenderer(
        allowedJoiningTimeMs: Long,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener
    ): Renderer {
        Libdav1dVideoRenderer.skippedNonReferenceInputs = 0
        Libdav1dVideoRenderer.skipLateNonReferenceFrames = debugOverride != "dav1d-noskip"
        // "dav1d-full": every filter and the film grain, to compare with the fast mode.
        Libdav1dVideoRenderer.forcedSpeedFlags = if (debugOverride == "dav1d-full") 0 else -1
        Libdav1dVideoRenderer.speedFlags = 0
        return newRenderer(allowedJoiningTimeMs, eventHandler, eventListener)
    }

    private fun newRenderer(
        allowedJoiningTimeMs: Long,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener
    ): Renderer = Libdav1dVideoRenderer(
        allowedJoiningTimeMs,
        eventHandler,
        eventListener,
        MAX_DROPPED_FRAMES_TO_NOTIFY,
        Libdav1dVideoRenderer.THREAD_COUNT_DECODER_DEFAULT, // dav1d's default: every core
        0, // automatic frame delay: several frames decoded in parallel
        INPUT_BUFFERS,
        OUTPUT_BUFFERS,
        false
    )

    /** How the current stream is decoded, for the statistics: "full" or what is left out. */
    fun modeLabel(): String {
        val flags = Libdav1dVideoRenderer.speedFlags
        if (flags == 0) return "full quality"
        val skipped = buildList {
            if (flags and Libdav1dVideoRenderer.SPEED_NO_GRAIN != 0) add("grain")
            if (flags and Libdav1dVideoRenderer.SPEED_DEBLOCK_ONLY != 0) add("CDEF/LR")
        }
        return "fast: no " + skipped.joinToString(", ")
    }

    private const val MAX_DROPPED_FRAMES_TO_NOTIFY = 50
    private const val INPUT_BUFFERS = 8
    private const val OUTPUT_BUFFERS = 8
}
