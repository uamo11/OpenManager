/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package androidx.media3.decoder.av1

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * A 4K60 AV1 stream on a phone that decodes fewer frames than the video has (Galaxy A07: ~35 of
 * 60), simulated: 3 of every 10 frames are depended on, the rest can be skipped; decoding takes
 * a varying time and the decoder can run 8 frames ahead; a frame more than 30 ms late is dropped.
 * The controller must show at least as many frames as both earlier behaviours:
 * v2.0.30 (skip only while late) and v2.0.31 (skip all of them for 8 s after falling behind).
 */
class NonReferenceSkipControllerTest {

    private interface Strategy {
        fun onOutput(earlyUs: Long, nowS: Double)
        fun shouldSkip(nowS: Double): Boolean
    }

    private class Controller : Strategy {
        val c = NonReferenceSkipController()
        override fun onOutput(earlyUs: Long, nowS: Double) = c.onOutput(earlyUs)
        override fun shouldSkip(nowS: Double) = c.shouldSkip()
    }

    private class OnlyWhileLate : Strategy {
        var early = 0L
        override fun onOutput(earlyUs: Long, nowS: Double) { early = earlyUs }
        override fun shouldSkip(nowS: Double) = early <= -15_000
    }

    private class HoldEightSeconds : Strategy {
        var early = 0L
        var lateInRow = 0
        var until = -1.0
        override fun onOutput(earlyUs: Long, nowS: Double) {
            early = earlyUs
            if (earlyUs <= -15_000) {
                if (++lateInRow >= 4) until = nowS + 8.0
            } else {
                lateInRow = 0
            }
        }
        override fun shouldSkip(nowS: Double) = early <= -15_000 || nowS < until
    }

    /** Frames shown per second. */
    private fun play(strategy: Strategy, decodesPerSecond: Double, seed: Int, refCost: Double, otherCost: Double): Double {
        val random = Random(seed)
        val fps = 60
        val seconds = 30
        var decoderTime = 0.0
        var shown = 0
        for (i in 0 until fps * seconds) {
            val frameTime = i.toDouble() / fps
            val isReference = i % 10 in setOf(0, 3, 6)
            val start = maxOf(decoderTime, frameTime - 8.0 / fps)
            if (!isReference && strategy.shouldSkip(start)) continue
            val cost = (1.0 / decodesPerSecond) * random.nextDouble(0.6, 1.4) *
                (if (isReference) refCost else otherCost) * (if (i % 120 == 0) 3.0 else 1.0)
            decoderTime = start + cost
            val earlyUs = ((frameTime - decoderTime) * 1e6).toLong()
            strategy.onOutput(earlyUs, decoderTime)
            if (earlyUs >= -30_000) shown++
        }
        return shown.toDouble() / seconds
    }

    private fun average(make: () -> Strategy, cpu: Double, refCost: Double, otherCost: Double): Double =
        (0 until 6).map { play(make(), cpu, it, refCost, otherCost) }.average()

    @Test
    fun showsAtLeastAsManyFramesAsBothEarlierBehaviours() {
        for ((refCost, otherCost) in listOf(1.0 to 1.0, 1.6 to 0.75)) {
            for (cpu in listOf(35.0, 45.0)) {
                val controller = average({ Controller() }, cpu, refCost, otherCost)
                val v30 = average({ OnlyWhileLate() }, cpu, refCost, otherCost)
                val v31 = average({ HoldEightSeconds() }, cpu, refCost, otherCost)
                println("cpu $cpu/s costs $refCost/$otherCost: controller $controller, v2.0.30 $v30, v2.0.31 $v31")
                assertTrue("cpu $cpu: $controller vs v2.0.30 $v30", controller >= v30 - 0.5)
                assertTrue("cpu $cpu: $controller vs v2.0.31 $v31", controller >= v31 - 0.5)
            }
        }
    }

    @Test
    fun fastPhoneSkipsNothing() {
        val controller = Controller()
        val shown = play(controller, 90.0, 1, 1.0, 1.0)
        assertTrue("a phone that keeps up shows all 60 fps ($shown)", shown >= 59.5)
        assertTrue(controller.c.skipFraction() == 0.0)
    }
}
