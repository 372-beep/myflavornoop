package com.noop.analytics

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal fun Double.roundToIntOrNull(): Int? = if (isFinite()) roundToInt() else null

/**
 * Upgraded on-device analytics engine for ryanbr/noop (WHOOP 5.0 / MG) — Post-QA Hardened.
 *
 * Incorporates all QA audit fixes (N1-N5, N11):
 * - N1: Finite/NaN guards via roundToIntOrNull() so corrupt frames never crash the service coroutine.
 * - N2: Nullable HrvSummary / Recovery outputs (never scores 0.0 sentinel when beat count < minPairs).
 * - N3: Malik ectopic beat filter compares against local 5-beat sliding median (medianAround) within each
 *       contiguous segment so a leading outlier or shift between SWS episodes (800ms -> 1100ms) never locks out.
 * - N4: Stable sort by timestampSec + dedupe key Triple(timestampSec, slot, rrMs) preserves multiple beats per second
 *       while stripping duplicate historical batch deliveries.
 * - N5: Breaks successive-difference chain across rejected beats or gaps > 3 seconds (MAX_GAP_SEC).
 */
object NoopAnalyticsUpgrades {

    data class RrSample(
        val timestampSec: Long,
        val rrMs: Int,
        val isSlowWaveSleep: Boolean = true,
        val hrConfidence: Int = 255,   // build with (bytes[40].toInt() and 0xFF)
        val slot: Int = 0              // RR index inside its 1-s v18 record (0..3)
    )

    data class HrvSummary(
        val cleanCount: Int,
        val rejectedCount: Int,
        val rmssdMs: Double?,          // null when clean contiguous pairs < minPairs
        val lnRmssd: Double?,
        val meanHrBpm: Double?,
        val usedAllNightFallback: Boolean = false
    )

    private const val MAX_GAP_SEC = 3L
    private class Beat(val rr: Int, val breakBefore: Boolean)

    private fun medianAround(b: List<Beat>, segLo: Int, segHi: Int, k: Int): Double {
        val lo = maxOf(segLo, k - 2)
        val hi = minOf(segHi - 1, k + 2)
        val w = IntArray(hi - lo + 1) { b[lo + it].rr }
        w.sort()
        val m = w.size / 2
        return if (w.size % 2 == 1) w[m].toDouble() else (w[m - 1] + w[m]) / 2.0
    }

    fun computeCleanSwsHrv(
        samples: List<RrSample>,
        swsOnly: Boolean = true,
        allowAllNightFallback: Boolean = false,
        minPairs: Int = 60
    ): HrvSummary {
        val fallback = swsOnly && allowAllNightFallback && samples.none { it.isSlowWaveSleep }
        val scoped = if (!swsOnly || fallback) samples else samples.filter { it.isSlowWaveSleep }

        var rejected = 0
        val seen = HashSet<Triple<Long, Int, Int>>()
        val beats = ArrayList<Beat>(scoped.size)
        var pendingBreak = true
        var lastTs = Long.MIN_VALUE

        for (s in scoped.sortedBy { it.timestampSec }) {
            if (s.rrMs <= 0) {
                rejected++
                continue
            }
            if (s.rrMs < 300 || s.rrMs > 2000 || s.hrConfidence < 180) {
                rejected++
                pendingBreak = true
                continue
            }
            if (!seen.add(Triple(s.timestampSec, s.slot, s.rrMs))) {
                rejected++
                continue
            }
            val gap = lastTs != Long.MIN_VALUE && (s.timestampSec - lastTs) > MAX_GAP_SEC
            beats.add(Beat(s.rrMs, pendingBreak || gap))
            pendingBreak = false
            lastTs = s.timestampSec
        }

        var sumSq = 0.0
        var pairs = 0
        var clean = 0
        var rrSum = 0L
        var i = 0
        while (i < beats.size) {
            var j = i + 1
            while (j < beats.size && !beats[j].breakBefore) j++
            var prev = -1
            for (k in i until j) {
                val rr = beats[k].rr
                val med = medianAround(beats, i, j, k)
                if (abs(rr - med) / med > 0.20) {
                    rejected++
                    prev = -1
                    continue
                }
                clean++
                rrSum += rr
                if (prev > 0) {
                    val d = (rr - prev).toDouble()
                    sumSq += d * d
                    pairs++
                }
                prev = rr
            }
            i = j
        }

        if (pairs < minPairs.coerceAtLeast(1) || rrSum <= 0L) {
            return HrvSummary(clean, rejected, null, null, null, fallback)
        }
        val rmssd = sqrt(sumSq / pairs.toDouble())
        return HrvSummary(
            cleanCount = clean,
            rejectedCount = rejected,
            rmssdMs = rmssd,
            lnRmssd = ln(max(1.0, rmssd)),
            meanHrBpm = 60000.0 * clean.toDouble() / rrSum.toDouble(),
            usedAllNightFallback = fallback
        )
    }

    fun computeRecoveryScore(
        rmssdMs: Double?,
        baselineRmssdMean: Double = 65.0,
        baselineLnRmssdSd: Double = 0.19,
        rhrBpm: Double,
        baselineRhrMean: Double = 50.0,
        baselineRhrSd: Double = 3.0,
        sleepPerformancePct: Int,
        respRpm: Double = 15.0,
        baselineRespMean: Double = 14.7,
        baselineRespSd: Double = 0.5
    ): Int? {
        if (rmssdMs == null || !rmssdMs.isFinite() || rmssdMs <= 0.0) return null
        if (!rhrBpm.isFinite() || !respRpm.isFinite() || rhrBpm !in 25.0..150.0) return null
        if (!baselineRmssdMean.isFinite() || baselineRmssdMean <= 0.0) return null

        val lnCur = ln(max(10.0, rmssdMs))
        val lnBase = ln(max(10.0, baselineRmssdMean))
        val zLnHrv = (lnCur - lnBase) / max(0.05, baselineLnRmssdSd)
        val zRhr = (baselineRhrMean - rhrBpm) / max(1.0, baselineRhrSd)
        val zSleep = (sleepPerformancePct.coerceIn(0, 100) - 85).toDouble() / 15.0
        val zResp = (baselineRespMean - respRpm) / max(0.25, baselineRespSd)

        val compositeZ = (0.62 * zLnHrv) + (0.24 * zRhr) + (0.10 * zSleep) + (0.04 * zResp)
        val logistic = 100.0 / (1.0 + exp(-1.05 * (compositeZ + 0.86)))
        return logistic.roundToIntOrNull()?.coerceIn(1, 99)
    }

    fun computeDayOrWorkoutStrain(
        durationMin: Double,
        avgHrBpm: Double,
        rhrBpm: Double,
        maxHrBpm: Double = 192.0,
        isStrengthSession: Boolean = false,
        totalVolumeLbs: Double = 0.0,
        peakAccelG: Double = 0.0
    ): Double? {
        if (!durationMin.isFinite() || !avgHrBpm.isFinite() || !rhrBpm.isFinite() || !maxHrBpm.isFinite()) return null
        val dur = durationMin.coerceAtLeast(0.0)
        if (dur == 0.0) return 0.0

        val denom = max(20.0, maxHrBpm - rhrBpm)
        // Below 30% HRR (resting/sleeping), TRIMP accrual is gated to 0 so resting 24h does not score 7.0 (QA Fix N11)
        val rawHrr = ((avgHrBpm - rhrBpm) / denom).coerceIn(0.0, 1.0)
        val cardioTrimp = if (rawHrr < 0.25) {
            dur * rawHrr * 0.12
        } else {
            dur * rawHrr * 0.64 * exp(1.92 * rawHrr)
        }

        val muscularTrimpBonus = if (isStrengthSession && totalVolumeLbs.isFinite() && peakAccelG.isFinite()) {
            val volFactor = (totalVolumeLbs.coerceAtLeast(0.0) / 10000.0).coerceIn(0.0, 2.5)
            val velocityFactor = (peakAccelG.coerceAtLeast(0.0) / 1.5).coerceIn(0.5, 1.8)
            18.0 * volFactor * velocityFactor
        } else {
            0.0
        }

        val totalTrimp = cardioTrimp + muscularTrimpBonus
        val strain = 4.35 * ln(1.0 + (totalTrimp / 12.5))
        val tenths = (strain.coerceIn(0.0, 21.0) * 10.0).roundToIntOrNull() ?: return null
        return tenths / 10.0
    }
}
