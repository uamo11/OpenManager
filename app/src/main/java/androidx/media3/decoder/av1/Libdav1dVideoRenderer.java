/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.decoder.av1;

import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION;

import android.os.Handler;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.TraceUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.video.DecoderVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

/** Decodes and renders video using libdav1d decoder. */
@UnstableApi
public class Libdav1dVideoRenderer extends DecoderVideoRenderer {

  // Attempts to use as many threads as is the default for the dav1d library.
  public static final int THREAD_COUNT_DECODER_DEFAULT = 0;
  // Current default: # of performance cores.
  public static final int THREAD_COUNT_PERFORMACE_CORES = -1;
  // Current experimental: # of cores / 2.
  public static final int THREAD_COUNT_EXPERIMENTAL = -2;

  private static final String TAG = "Libdav1dVideoRenderer";
  private static final int DEFAULT_NUM_OF_INPUT_BUFFERS = 4;
  private static final int DEFAULT_NUM_OF_OUTPUT_BUFFERS = 4;
  private static final int DEFAULT_MAX_FRAME_DELAY = 2;

  /**
   * Default input buffer size in bytes, based on 720p resolution video compressed by a factor of
   * two.
   */
  private static final int DEFAULT_INPUT_BUFFER_SIZE =
      Util.ceilDivide(1280, 64) * Util.ceilDivide(720, 64) * (64 * 64 * 3 / 2) / 2;

  /** The number of input buffers. */
  private final int numInputBuffers;

  /**
   * The number of output buffers. The renderer may limit the minimum possible value due to
   * requiring multiple output buffers to be dequeued at a time for it to make progress.
   */
  private final int numOutputBuffers;

  private final int threads;

  // OpenManager: when the decoder falls behind, AV1 frames nothing depends on are not decoded at
  // all (like MediaCodecVideoRenderer does for hardware decoders). Decoding them only to drop
  // them late was most of the work: a 4K clip showed 6 fps out of 27 decoded.
  private static final long LATE_THRESHOLD_TO_SKIP_INPUT_US = 15_000;
  private final androidx.media3.exoplayer.video.Av1SampleDependencyParser av1Parser =
      new androidx.media3.exoplayer.video.Av1SampleDependencyParser();
  private long lastOutputEarlyUs;
  /** Frames not decoded because they were late and not depended on (debug statistics). */
  public static volatile long skippedNonReferenceInputs;
  /** Debug comparisons on the emulator can turn the skipping off. */
  public static volatile boolean skipLateNonReferenceFrames = true;

  /**
   * OpenManager: once late, keep skipping the frames nothing depends on for this long. Skipping
   * only while late made the decoder catch up, stop skipping, fall behind again and decode frames
   * that were then dropped as late (215 of 779 on a Galaxy A07 with 4K60): holding it gives an even
   * half frame rate (30 of 60 fps) with every decoded frame shown on time.
   */
  private static final long SKIP_HOLD_US = 8_000_000;

  /** Debug comparisons: 0 skips only while late (the v2.0.29 behaviour). */
  public static volatile long skipHoldUs = SKIP_HOLD_US;

  /** Until when frames nothing depends on are skipped even if on time (elapsed realtime, µs). */
  private long skipNonReferenceUntilUs = Long.MIN_VALUE;

  /**
   * Late outputs in a row. The hold starts after a few, so one late frame (a seek, a hiccup) on a
   * phone that keeps up does not halve its frame rate.
   */
  private int consecutiveLateOutputs;

  /** OpenManager: no film grain synthesis (dav1d {@code apply_grain = 0}). */
  public static final int SPEED_NO_GRAIN = 1;

  /** OpenManager: deblocking only, without CDEF and loop restoration. */
  public static final int SPEED_DEBLOCK_ONLY = 2;

  /** OpenManager: forces the speed flags (debug comparisons); -1 picks them from the stream. */
  public static volatile int forcedSpeedFlags = -1;

  /** OpenManager: the speed flags of the last decoder created, for the player's statistics. */
  public static volatile int speedFlags;

  /**
   * OpenManager: this renderer only runs on phones without an AV1 hardware decoder. Above 1080p30
   * the film grain synthesis is skipped, and above 1080p60 (4K, 1440p60) CDEF and loop restoration
   * too: together they are a large part of the decoding time, and on a phone screen (often 720p or
   * 1080p) a 4K picture without them looks the same. Below that, everything is decoded.
   */
  public static int speedFlagsFor(Format format) {
    int forced = forcedSpeedFlags;
    if (forced >= 0) {
      return forced;
    }
    if (format.width <= 0 || format.height <= 0) {
      return 0;
    }
    float fps = format.frameRate > 0 ? format.frameRate : 30f;
    double pixelsPerSecond = (double) format.width * format.height * fps;
    int flags = 0;
    if (pixelsPerSecond > 1920.0 * 1080 * 31) {
      flags |= SPEED_NO_GRAIN;
    }
    if (pixelsPerSecond > 1920.0 * 1080 * 61) {
      flags |= SPEED_DEBLOCK_ONLY;
    }
    return flags;
  }

  private final int maxFrameDelay;

  private final boolean useCustomAllocator;

  @Nullable private Dav1dDecoder decoder;

  /**
   * Creates a new instance.
   *
   * @param allowedJoiningTimeMs The maximum duration in milliseconds for which this video renderer
   *     can attempt to seamlessly join an ongoing playback.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param maxDroppedFramesToNotify The maximum number of frames that can be dropped between
   *     invocations of {@link VideoRendererEventListener#onDroppedFrames(int, long)}.
   */
  public Libdav1dVideoRenderer(
      long allowedJoiningTimeMs,
      @Nullable Handler eventHandler,
      @Nullable VideoRendererEventListener eventListener,
      int maxDroppedFramesToNotify) {
    this(
        allowedJoiningTimeMs,
        eventHandler,
        eventListener,
        maxDroppedFramesToNotify,
        THREAD_COUNT_DECODER_DEFAULT,
        DEFAULT_MAX_FRAME_DELAY,
        DEFAULT_NUM_OF_INPUT_BUFFERS,
        DEFAULT_NUM_OF_OUTPUT_BUFFERS,
        /* useCustomAllocator= */ false);
  }

  /**
   * Creates a new instance.
   *
   * @param allowedJoiningTimeMs The maximum duration in milliseconds for which this video renderer
   *     can attempt to seamlessly join an ongoing playback.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param maxDroppedFramesToNotify The maximum number of frames that can be dropped between
   *     invocations of {@link VideoRendererEventListener#onDroppedFrames(int, long)}.
   * @param threads Number of threads libdav1d will use to decode. If {@link
   *     #THREAD_COUNT_DECODER_DEFAULT} is passed, then the number of threads to use is determined
   *     by the dav1d library. If {@link #THREAD_COUNT_PERFORMACE_CORES} is passed, then the number
   *     of threads to use is the number of performance cores on the device. If {@link
   *     #THREAD_COUNT_EXPERIMENTAL} is passed, then the number of threads to use is the number of
   *     cores on the device divided by 2.
   * @param maxFrameDelay Maximum frame delay for libdav1d.
   * @param numInputBuffers Number of input buffers.
   * @param numOutputBuffers Number of output buffers.
   * @param useCustomAllocator Whether to use a custom allocator for libdav1d.
   */
  public Libdav1dVideoRenderer(
      long allowedJoiningTimeMs,
      @Nullable Handler eventHandler,
      @Nullable VideoRendererEventListener eventListener,
      int maxDroppedFramesToNotify,
      int threads,
      int maxFrameDelay,
      int numInputBuffers,
      int numOutputBuffers,
      boolean useCustomAllocator) {
    super(allowedJoiningTimeMs, eventHandler, eventListener, maxDroppedFramesToNotify);
    this.threads = threads;
    this.numInputBuffers = numInputBuffers;
    this.numOutputBuffers = numOutputBuffers;
    this.maxFrameDelay = maxFrameDelay;
    this.useCustomAllocator = useCustomAllocator;
  }

  @Override
  public String getName() {
    return TAG;
  }

  @Override
  public final @Capabilities int supportsFormat(Format format) {
    if (!MimeTypes.VIDEO_AV1.equalsIgnoreCase(format.sampleMimeType)
        || !Dav1dLibrary.isAvailable()) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
    }
    if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM);
    }
    return RendererCapabilities.create(
        C.FORMAT_HANDLED, ADAPTIVE_SEAMLESS, TUNNELING_NOT_SUPPORTED);
  }

  @Override
  protected final Dav1dDecoder createDecoder(Format format, @Nullable CryptoConfig cryptoConfig)
      throws Dav1dDecoderException {
    TraceUtil.beginSection("createDav1dDecoder");
    int initialInputBufferSize =
        format.maxInputSize != Format.NO_VALUE ? format.maxInputSize : DEFAULT_INPUT_BUFFER_SIZE;
    Dav1dDecoder decoder =
        new Dav1dDecoder(
            numInputBuffers,
            numOutputBuffers,
            initialInputBufferSize,
            threads,
            maxFrameDelay,
            useCustomAllocator,
            speedFlags = speedFlagsFor(format));
    this.decoder = decoder;
    TraceUtil.endSection();
    return decoder;
  }

  @Override
  protected void renderOutputBufferToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
      throws Dav1dDecoderException {
    if (decoder == null) {
      throw new Dav1dDecoderException(
          "Failed to render output buffer to surface: decoder is not initialized.");
    }
    try {
      decoder.renderToSurface(outputBuffer, surface);
    } finally {
      outputBuffer.release();
    }
  }

  @Override
  protected boolean shouldDropOutputBuffer(long earlyUs, long elapsedRealtimeUs) {
    lastOutputEarlyUs = earlyUs;
    if (earlyUs <= -LATE_THRESHOLD_TO_SKIP_INPUT_US) {
      if (++consecutiveLateOutputs >= 4) {
        skipNonReferenceUntilUs = elapsedRealtimeUs + skipHoldUs;
      }
    } else {
      consecutiveLateOutputs = 0;
    }
    return super.shouldDropOutputBuffer(earlyUs, elapsedRealtimeUs);
  }

  @Override
  protected void onQueueInputBuffer(DecoderInputBuffer buffer) {
    super.onQueueInputBuffer(buffer);
    if (buffer.data == null) {
      return;
    }
    if (buffer.isKeyFrame()) {
      // Key frames carry the sequence header the parser needs.
      av1Parser.queueInputBuffer(buffer.data);
      return;
    }
    if (!skipLateNonReferenceFrames
        || (lastOutputEarlyUs > -LATE_THRESHOLD_TO_SKIP_INPUT_US
            && android.os.SystemClock.elapsedRealtime() * 1000 >= skipNonReferenceUntilUs)
        || buffer.isLastSample()
        || buffer.hasSupplementalData()) {
      return;
    }
    java.nio.ByteBuffer sample = buffer.data.asReadOnlyBuffer();
    // Only whole samples: a partly skipped one would still output no frame for its input.
    if (av1Parser.sampleLimitAfterSkippingNonReferenceFrame(sample, /* skipFrameHeaders= */ true)
        == sample.position()) {
      buffer.addFlag(C.BUFFER_FLAG_NOT_DEPENDED_ON);
      skippedNonReferenceInputs++;
    }
  }

  @Override
  protected void setDecoderOutputMode(@C.VideoOutputMode int outputMode) {
    if (decoder != null) {
      decoder.setOutputMode(outputMode);
    }
  }

  @Override
  protected DecoderReuseEvaluation canReuseDecoder(
      String decoderName, Format oldFormat, Format newFormat) {
    return new DecoderReuseEvaluation(
        decoderName,
        oldFormat,
        newFormat,
        REUSE_RESULT_YES_WITHOUT_RECONFIGURATION,
        /* discardReasons= */ 0);
  }
}
