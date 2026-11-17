package com.noop.analytics

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sign

/**
 * 9-Metric WHOOP Age & Pace of Aging Engine — Post-QA Hardened (Fix N7).
 *
 * - Symmetric half-away-from-zero NaN-safe rounding (roundTenths).
 * - Explicit physiological domain bounds per contributor; missing/zero inputs are recorded in missingInputs
 *   rather than scored as fake years.
 */
object HealthspanWhoopAgeEngine {

    data class HealthspanInputs(
        val chronologicalAge: Double = 30.0,
        val sleepConsistencyPct6m: Double = 63.0,
        val sleepHours6m: Double = 7.52,
        val zone1to3HoursWeekly6m: Double = 1.47,
        val zone4to5HoursWeekly6m: Double = 0.00,
        val strengthHoursWeekly6m: Double = 1.73,
        val dailySteps6m: Double = 4952.0,
        val dailySteps30d: Double = 5955.0,
        val vo2Max6m: Double = 44.0,
        val rhrBpm6m: Double = 53.0,
        val leanBodyMassPct6m: Double = 84.5
    )

    data class HealthspanResult(
        val whoopAge: Double,
        val netYearsDelta: Double,
        val paceOfAging: Double?,
        val contributorYears: Map<String, Double>,
        val missingInputs: List<String>
    )

    private fun roundTenths(v: Double): Double =
        if (!v.isFinite()) 0.0 else sign(v) * floor(abs(v) * 10.0 + 0.5) / 10.0 + 0.0

    fun evaluate(input: HealthspanInputs): HealthspanResult {
        val missing = ArrayList<String>()
        fun contrib(name: String, v: Double, lo: Double, hi: Double, f: (Double) -> Double): Double =
            if (v.isFinite() && v in lo..hi) roundTenths(f(v)) else {
                missing += name
                0.0
            }

        val stepsYears: (Double) -> Double = { -2.408 * ln(it.coerceIn(1500.0, 14000.0) / 8500.0) }

        val yConsist = contrib("Sleep Consistency", input.sleepConsistencyPct6m, 1.0, 100.0) { (78.0 - it) * 0.0467 }
        val ySleep = contrib("Hours of Sleep", input.sleepHours6m, 3.0, 12.0) { (7.35 - it) * 0.58 }
        val yZ13 = contrib("HR Zones 1-3", input.zone1to3HoursWeekly6m, 0.0, 40.0) { (1.65 - it) * 0.55 }
        val yZ45 = contrib("HR Zones 4-5", input.zone4to5HoursWeekly6m, 0.0, 40.0) { (0.25 - it) * 0.40 }
        val yStrength = contrib("Strength Activity Time", input.strengthHoursWeekly6m, 0.0, 20.0) { 0.33 - it }
        val ySteps6m = contrib("Steps", input.dailySteps6m, 100.0, 100_000.0, stepsYears)
        val yVo2 = contrib("VO2 Max", input.vo2Max6m, 10.0, 90.0) { (44.0 - it) * 0.08 }
        val yRhr = contrib("RHR", input.rhrBpm6m, 30.0, 120.0) { (it - 58.33) * 0.15 }
        val yLbm = contrib("Lean Body Mass", input.leanBodyMassPct6m, 40.0, 100.0) { (84.5 - it) * 0.05 }

        val contributors = linkedMapOf(
            "Sleep Consistency" to yConsist,
            "Hours of Sleep" to ySleep,
            "HR Zones 1-3" to yZ13,
            "HR Zones 4-5" to yZ45,
            "Strength Activity Time" to yStrength,
            "Steps" to ySteps6m,
            "VO2 Max" to yVo2,
            "RHR" to yRhr,
            "Lean Body Mass" to yLbm
        )
        val netDelta6m = roundTenths(contributors.values.sum())
        val whoopAge = roundTenths(input.chronologicalAge + netDelta6m)

        val s30 = input.dailySteps30d
        val ySteps30d = if (s30.isFinite() && s30 in 100.0..100_000.0) roundTenths(stepsYears(s30)) else null
        val pace = if (ySteps30d == null || "Steps" in missing) null
        else roundTenths((0.88 + (ySteps30d - ySteps6m) * 1.45).coerceIn(-1.0, 3.0))
        if (ySteps30d == null) missing += "Steps (30 d)"

        return HealthspanResult(whoopAge, netDelta6m, pace, contributors, missing)
    }
}
