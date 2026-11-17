package com.noop.analytics

import kotlin.math.ln
import kotlin.math.max

/**
 * Custom On-Device Cuffless Blood Pressure Insights Module for NOOP — Post-QA Hardened (Fix N6).
 *
 * - Requires calibratedEpochMs > 0L and computes daysSince from nowEpochMs (guards backwards clock).
 * - Requires sleepAsleepMinutes >= 300 (5.0h quality gate, matching blank Tue Nov 10 bar).
 * - Propagates null when overnightSwsRmssdMs is null or inputs are non-finite / out of physiological bounds.
 * - Enforces pulse pressure coherence (estDia <= estSys - 20).
 */
object BloodPressureEstimator {

    data class CuffCalibration(
        val avgSystolic: Int = 120,
        val avgDiastolic: Int = 77,
        val baselineRhrBpm: Double = 50.0,
        val baselineRmssdMs: Double = 65.0,
        val calibratedEpochMs: Long = 1760572800000L // Default valid calibration timestamp
    )

    data class BpEstimateResult(
        val systolic: Int,
        val diastolic: Int,
        val systolicLow: Int,
        val systolicHigh: Int,
        val diastolicLow: Int,
        val diastolicHigh: Int,
        val isCalibrationExpired: Boolean
    )

    fun estimateMorningBloodPressure(
        calibration: CuffCalibration,
        nowEpochMs: Long,
        sleepAsleepMinutes: Int,
        overnightRhrBpm: Double,
        overnightSwsRmssdMs: Double?,
        ppgSystolicSlopeZ: Double = 0.0
    ): BpEstimateResult? {
        if (calibration.calibratedEpochMs <= 0L) return null
        if (sleepAsleepMinutes < 300) return null
        val rmssd = overnightSwsRmssdMs ?: return null
        if (!overnightRhrBpm.isFinite() || !rmssd.isFinite() || !ppgSystolicSlopeZ.isFinite()) return null
        if (overnightRhrBpm !in 30.0..120.0 || rmssd < 5.0) return null

        val daysSince = (nowEpochMs - calibration.calibratedEpochMs) / 86_400_000L
        if (daysSince < 0L) return null

        val rhrDelta = overnightRhrBpm - calibration.baselineRhrBpm
        val lnHrvDelta = ln(max(10.0, calibration.baselineRmssdMs)) - ln(max(10.0, rmssd))
        val sysShift = (rhrDelta * 0.35) + (lnHrvDelta * 1.15) + (ppgSystolicSlopeZ * 1.25)
        val diaShift = (rhrDelta * 0.18) + (lnHrvDelta * 0.55) + (ppgSystolicSlopeZ * 0.65)

        val rawSys = (calibration.avgSystolic + sysShift).roundToIntOrNull() ?: return null
        val rawDia = (calibration.avgDiastolic + diaShift).roundToIntOrNull() ?: return null
        val estSys = rawSys.coerceIn(85, 190)
        val estDia = rawDia.coerceIn(50, minOf(125, estSys - 20))

        return BpEstimateResult(
            systolic = estSys,
            diastolic = estDia,
            systolicLow = estSys - 10,
            systolicHigh = estSys + 10,
            diastolicLow = estDia - 5,
            diastolicHigh = estDia + 5,
            isCalibrationExpired = daysSince >= 30L
        )
    }
}
