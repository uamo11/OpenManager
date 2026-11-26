/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */
package androidx.media3.decoder.av1;

/**
 * OpenManager: decides how many AV1 frames that nothing depends on to leave undecoded when the
 * processor cannot decode every frame in time (4K60 on a phone without AV1 hardware).
 *
 * <p>Skipping none wastes work: frames are decoded late and then dropped (a Galaxy A07 decoded 35
 * frames per second of a 60 fps video but showed 26). Skipping all of them for a while (v2.0.31)
 * caps the video at the rate of the frames others depend on, which in some streams is a third of
 * the frames (16 fps on the same phone). This controller skips one at once when the last frame
 * came out late (as v2.0.30), and on top of that keeps a share of them undecoded, raised a little
 * at each late frame and lowered slowly while frames are on time: the decoder settles near what
 * the processor can do, wasting few decoded frames. Simulated against both earlier behaviours
 * (NonReferenceSkipControllerTest) it shows at least as many frames in every case.
 */
final class NonReferenceSkipController {

  /** An output this late means the decoder is behind. */
  static final long LATE_US = 15_000;

  private static final double RAISE_WHEN_LATE = 0.02;
  private static final double LOWER_WHEN_ON_TIME = 0.005;

  private double skipFraction;
  private double credit;
  private long lastEarlyUs;

  /** Each output frame: how early (positive) or late (negative) it is. */
  void onOutput(long earlyUs) {
    lastEarlyUs = earlyUs;
    if (earlyUs <= -LATE_US) {
      skipFraction = Math.min(1.0, skipFraction + RAISE_WHEN_LATE);
    } else if (earlyUs >= 0) {
      skipFraction = Math.max(0.0, skipFraction - LOWER_WHEN_ON_TIME);
    }
  }

  /**
   * For a frame nothing depends on, about to be queued: whether to leave it undecoded. The
   * skipped ones are spread evenly (one in three, not three in a row), for an even cadence.
   */
  boolean shouldSkip() {
    if (lastEarlyUs <= -LATE_US) {
      return true;
    }
    credit += skipFraction;
    if (credit >= 1.0) {
      credit -= 1.0;
      return true;
    }
    return false;
  }

  double skipFraction() {
    return skipFraction;
  }

  void reset() {
    skipFraction = 0;
    credit = 0;
    lastEarlyUs = 0;
  }
}
