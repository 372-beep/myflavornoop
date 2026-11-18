package com.noop.ble

import com.noop.analytics.roundToIntOrNull
import kotlin.math.max
import kotlin.math.sqrt

/**
 * StepFusionAndImuBridge — Post-QA Hardened (Fixes N8, N9, N10).
 *
 * - N8: Timestamp-keyed 5-minute step bucket fusion using maxOf(phoneSteps, wristSteps) so a jostled phone
 *       logging 3 steps never overwrites 410 wrist steps, plus negative reboot-delta protection.
 * - N9: Refractory window (380ms) retains the strongest peak rather than the first peak.
 * - N10: Trapezoidal velocity integration, signed linear zero-end-velocity detrend, positive concentric window
 *        isolation around v[peak], and 5-point moving average smoothing before sticking-point argmin.
 */
object StepFusionAndImuBridge {

    const val OPCODE_START_RAW_DATA: Byte = 81
    const val OPCODE_STOP_RAW_DATA: Byte = 82
    const val OPCODE_TOGGLE_IMU_MODE: Byte = 106

    fun buildEnable100HzImuPayloads(): List<Pair<Byte, ByteArray>> = listOf(
        OPCODE_START_RAW_DATA to byteArrayOf(0x01),
        OPCODE_TOGGLE_IMU_MODE to byteArrayOf(0x01, 0x01)
    )

    fun buildDisable100HzImuPayloads(): List<Pair<Byte, ByteArray>> = listOf(
        OPCODE_STOP_RAW_DATA to byteArrayOf(0x01),
        OPCODE_TOGGLE_IMU_MODE to byteArrayOf(0x01, 0x00)
    )

    data class AccelSample(
        val timestampMs: Long,
        val axG: Double,
        val ayG: Double,
        val azG: Double
    )

    fun detectWristStepsVerisense(
        samples: List<AccelSample>,
        enmoThresholdG: Double = 0.14
    ): Int {
        if (samples.size < 4) return 0
        val enmo = DoubleArray(samples.size) { i ->
            val s = samples[i]
            val mag = sqrt(s.axG * s.axG + s.ayG * s.ayG + s.azG * s.azG)
            if (mag.isFinite()) max(0.0, mag - 1.0) else 0.0
        }
        val peakT = ArrayList<Long>()
        val peakE = ArrayList<Double>()

        for (i in 1 until samples.size - 1) {
            val e = enmo[i]
            if (e > enmoThresholdG && e >= enmo[i - 1] && e >= enmo[i + 1]) {
                val t = samples[i].timestampMs
                if (peakT.isEmpty() || t - peakT.last() >= 380L) {
                    peakT.add(t)
                    peakE.add(e)
                } else if (e > peakE.last()) {
                    peakT[peakT.lastIndex] = t
                    peakE[peakE.lastIndex] = e
                }
            }
        }

        var verifiedSteps = 0
        var streak = 1
        for (k in 1 until peakT.size) {
            val dt = peakT[k] - peakT[k - 1]
            if (dt in 380L..1200L) {
                streak++
                if (streak == 4) {
                    verifiedSteps += 4
                } else if (streak > 4) {
                    verifiedSteps += 1
                }
            } else {
                streak = 1
            }
        }
        return verifiedSteps
    }

    /**
     * Fuses 5-minute buckets keyed by (epochMs / 300_000L) using maxOf(phone, wrist) (QA Fix N8).
     */
    fun fuseFiveMinuteStepBuckets(
        phoneStepsByBucket: Map<Long, Int>,
        wristStepsByBucket: Map<Long, Int>
    ): Int = (phoneStepsByBucket.keys + wristStepsByBucket.keys)
        .sumOf { k ->
            val p = (phoneStepsByBucket[k] ?: 0).coerceAtLeast(0)
            val w = (wristStepsByBucket[k] ?: 0).coerceAtLeast(0)
            maxOf(p, w)
        }

    /**
     * Computes step delta from Android Sensor.TYPE_STEP_COUNTER cumulative hardware counter,
     * handling phone reboot resets where currentCounter < previousCounter.
     */
    fun computeHardwareStepDelta(previousCounter: Long, currentCounter: Long): Int {
        if (currentCounter < 0L) return 0
        return if (currentCounter >= previousCounter && previousCounter > 0L) {
            (currentCounter - previousCounter).toInt()
        } else {
            currentCounter.toInt()
        }
    }

    data class LiftTrackRepMetrics(
        val concentricDurationSec: Double,
        val meanConcentricVelocityMps: Double,
        val peakConcentricVelocityMps: Double,
        val estimatedRomMeters: Double,
        val stickingPointTimeSec: Double
    )

    fun analyzeBarbellConcentric100Hz(
        verticalLinearAccelMps2: List<Double>,
        sampleRateHz: Double = 100.0
    ): LiftTrackRepMetrics? {
        val a = verticalLinearAccelMps2
        val n = a.size
        if (n < 10 || !sampleRateHz.isFinite() || sampleRateHz <= 0.0 || a.any { !it.isFinite() }) return null
        val dt = 1.0 / sampleRateHz

        val v = DoubleArray(n)
        for (i in 1 until n) {
            v[i] = v[i - 1] + 0.5 * (a[i - 1] + a[i]) * dt
        }
        val slope = v[n - 1] / (n - 1).toDouble()
        for (i in 0 until n) {
            v[i] -= slope * i
        }

        var peak = 0
        for (i in 1 until n) if (v[i] > v[peak]) peak = i
        if (v[peak] <= 0.0) return null

        var s = peak
        while (s > 0 && v[s - 1] > 0.0) s--
        var e = peak
        while (e < n - 1 && v[e + 1] > 0.0) e++
        if (e - s < 2) return null

        var rom = 0.0
        for (i in s until e) {
            rom += 0.5 * (v[i] + v[i + 1]) * dt
        }
        val durationSec = (e - s) * dt
        if (durationSec <= 0.0) return null

        fun smoothed(idx: Int): Double {
            val lo = maxOf(0, idx - 2)
            val hi = minOf(n - 1, idx + 2)
            var t = 0.0
            for (k in lo..hi) t += a[k]
            return t / (hi - lo + 1).toDouble()
        }

        val len = e - s + 1
        var stick = s + len / 2
        var minA = Double.MAX_VALUE
        for (i in (s + len / 5)..(s + 4 * len / 5)) {
            val sa = smoothed(i)
            if (sa < minA) {
                minA = sa
                stick = i
            }
        }

        fun r2(x: Double): Double = ((x * 100.0).roundToIntOrNull() ?: 0) / 100.0
        return LiftTrackRepMetrics(
            concentricDurationSec = r2(durationSec),
            meanConcentricVelocityMps = r2(rom / durationSec),
            peakConcentricVelocityMps = r2(v[peak]),
            estimatedRomMeters = r2(rom),
            stickingPointTimeSec = r2((stick - s) * dt)
        )
    }
}
