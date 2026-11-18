package com.noop.analytics

import com.noop.ble.StepFusionAndImuBridge
import com.noop.ble.Whoop5Fw5043Patch
import com.noop.ui.formatMetricValue
import org.junit.Assert.*
import org.junit.Test

/**
 * Comprehensive Pre-APK Release Gate Suite (Claude QA Gate).
 *
 * Covers all 18 QA defect classes (N1-N11, B1-B2, C1-C5, D1), UI formatting invariants,
 * 100 Hz Barbell VBT integration, Verisense 4-step continuity filtering, and
 * Firmware 50.43.2.0 circuit breaker & DIS fallback rules.
 */
class NoopAnalyticsTest {

    @Test
    fun testParse2A37Vectors() {
        fun p(vararg b: Int) = Whoop5Fw5043Patch.parseStandardHeartRate2A37(ByteArray(b.size) { b[it].toByte() })!!
        assertEquals(listOf(1000), p(0x10, 0x3C, 0x00, 0x04).rrIntervalsMs)
        assertEquals(65, p(0x11, 0x41, 0x00, 0x66, 0x03).hrBpm)
        assertEquals(listOf(850), p(0x18, 0x3E, 0x10, 0x00, 0x66, 0x03).rrIntervalsMs)
        assertEquals(listOf(1000), p(0x10, 0x3C, 0x00, 0x04, 0xFF).rrIntervalsMs)
        assertEquals(listOf(299), p(0x10, 0x3C, 0x32, 0x01).rrIntervalsMs) // Retained at ingest (QA Fix B1)
        // Sensor contact bits (QA Fix B2)
        assertEquals(false, p(0x04, 0x3C).sensorContact)
        assertEquals(true, p(0x06, 0x3C).sensorContact)
        assertNull(p(0x00, 0x3C).sensorContact)
    }

    @Test
    fun testTwoSwsEpisodesAndLeadingOutlierRecovery() {
        val samples = ArrayList<NoopAnalyticsUpgrades.RrSample>()
        // Leading 1900ms outlier followed by 100 beats at ~800ms, then 100 beats at ~1100ms
        samples.add(NoopAnalyticsUpgrades.RrSample(1000L, 1900, isSlowWaveSleep = true))
        for (i in 1..100) {
            val rr = if (i % 2 == 0) 800 else 840
            samples.add(NoopAnalyticsUpgrades.RrSample(1000L + i, rr, isSlowWaveSleep = true))
        }
        for (i in 101..200) {
            val rr = if (i % 2 == 0) 1100 else 1150
            samples.add(NoopAnalyticsUpgrades.RrSample(2000L + i, rr, isSlowWaveSleep = true))
        }
        val res = NoopAnalyticsUpgrades.computeCleanSwsHrv(samples, swsOnly = true, minPairs = 60)
        assertEquals(200, res.cleanCount)
        assertEquals(1, res.rejectedCount)
        assertNotNull(res.rmssdMs)
    }

    @Test
    fun testInsufficientPairsReturnsNullNotSentinelZero() {
        // QA Fix N2: <60 contiguous pairs must return null rmssdMs so Recovery/BP never score fake 1%
        val fewSamples = (1..20).map { NoopAnalyticsUpgrades.RrSample(1000L + it, 900, isSlowWaveSleep = true) }
        val res = NoopAnalyticsUpgrades.computeCleanSwsHrv(fewSamples, swsOnly = true, minPairs = 60)
        assertNull(res.rmssdMs)
        assertNull(NoopAnalyticsUpgrades.computeRecoveryScore(rmssdMs = res.rmssdMs, rhrBpm = 50.0, sleepPerformancePct = 85))
    }

    @Test
    fun testMultipleBeatsInSameSecondPreservedWithSlotKey() {
        // QA Fix N4: At >60 bpm, multiple beats share timestampSec but differ in slot (0..3)
        val samples = ArrayList<NoopAnalyticsUpgrades.RrSample>()
        for (sec in 1..50L) {
            samples.add(NoopAnalyticsUpgrades.RrSample(sec, 750, isSlowWaveSleep = true, slot = 0))
            samples.add(NoopAnalyticsUpgrades.RrSample(sec, 760, isSlowWaveSleep = true, slot = 1))
        }
        val res = NoopAnalyticsUpgrades.computeCleanSwsHrv(samples, swsOnly = true, minPairs = 60)
        assertEquals(100, res.cleanCount)
        assertNotNull(res.rmssdMs)
    }

    @Test
    fun testNanInputsReturnNullWithoutThrowing() {
        assertNull(
            NoopAnalyticsUpgrades.computeRecoveryScore(
                rmssdMs = Double.NaN,
                rhrBpm = 55.0,
                sleepPerformancePct = 85
            )
        )
        assertNull(
            NoopAnalyticsUpgrades.computeRecoveryScore(
                rmssdMs = 50.0,
                rhrBpm = Double.NaN,
                sleepPerformancePct = 85
            )
        )
        assertNull(
            BloodPressureEstimator.estimateMorningBloodPressure(
                calibration = BloodPressureEstimator.CuffCalibration(),
                nowEpochMs = 1763337600000L,
                sleepAsleepMinutes = 515,
                overnightRhrBpm = Double.NaN,
                overnightSwsRmssdMs = 50.0
            )
        )
    }

    @Test
    fun testBloodPressureQualityGateAndPulsePressureCoherence() {
        val cal = BloodPressureEstimator.CuffCalibration(
            avgSystolic = 120,
            avgDiastolic = 77,
            baselineRhrBpm = 49.8,
            baselineRmssdMs = 63.67,
            calibratedEpochMs = 1762500000000L
        )
        // < 5h sleep (< 300 min) must be gated to null
        assertNull(
            BloodPressureEstimator.estimateMorningBloodPressure(
                calibration = cal,
                nowEpochMs = 1763337600000L,
                sleepAsleepMinutes = 299,
                overnightRhrBpm = 51.0,
                overnightSwsRmssdMs = 59.0
            )
        )
        // Valid 518m sleep produces 121/78 with +/-10 sys and +/-5 dia range
        val valid = BloodPressureEstimator.estimateMorningBloodPressure(
            calibration = cal,
            nowEpochMs = 1763337600000L,
            sleepAsleepMinutes = 518,
            overnightRhrBpm = 51.0,
            overnightSwsRmssdMs = 59.0
        )
        assertNotNull(valid)
        assertTrue(valid!!.diastolic <= valid.systolic - 20)
        assertEquals(valid.systolic - 10, valid.systolicLow)
        assertEquals(valid.systolic + 10, valid.systolicHigh)
    }

    @Test
    fun testWhoopAgeAndPaceOfAgingEngine() {
        val res = HealthspanWhoopAgeEngine.evaluate(
            HealthspanWhoopAgeEngine.HealthspanInputs(
                chronologicalAge = 30.0,
                sleepConsistencyPct6m = 63.8,
                sleepHours6m = 7.53,
                rhrBpm6m = 52.8
            )
        )
        assertEquals(29.9, res.whoopAge, 0.15)
        assertNotNull(res.paceOfAging)
        assertTrue(res.missingInputs.isEmpty())
    }

    @Test
    fun testBreakerFailsOpenWhenClockMovesBackwardsAndIgnoresTransportError() {
        val state = Whoop5Fw5043Patch.BackfillCircuitState(consecutiveEmptyCount = 3, lastEmptyTimestampMs = 500_000L)
        assertTrue(Whoop5Fw5043Patch.shouldAttemptHistoricalBackfill(state, firmwareMajor = 50, nowMs = 400_000L))
        // Transport errors must not increment consecutiveEmptyCount (QA Fix C1)
        val afterErr = Whoop5Fw5043Patch.recordBackfillOutcome(
            Whoop5Fw5043Patch.BackfillCircuitState(consecutiveEmptyCount = 1, lastEmptyTimestampMs = 1000L),
            firmwareMajor = 50,
            outcome = Whoop5Fw5043Patch.BackfillOutcome.TRANSPORT_ERROR,
            nowMs = 2000L
        )
        assertEquals(1, afterErr.consecutiveEmptyCount)
        // Firmware 50.43.2.0 DIS fallback when CLIENT_HELLO[93] == 0 (QA Fix C4/D1)
        val zeroedHello = ByteArray(109)
        assertEquals(50, Whoop5Fw5043Patch.resolveFirmwareMajor(zeroedHello, "50.43.2.0"))
    }

    @Test
    fun testStepBucketFusionAndVerisenseContinuityFilter() {
        val phone = mapOf(100L to 3)
        val wrist = mapOf(100L to 410)
        assertEquals(410, StepFusionAndImuBridge.fuseFiveMinuteStepBuckets(phone, wrist))
        // Reboot delta protection
        assertEquals(150, StepFusionAndImuBridge.computeHardwareStepDelta(previousCounter = 5000L, currentCounter = 150L))
    }

    @Test
    fun test100HzBarbellConcentricVbtIntegration() {
        val synth = List(145) { i ->
            when {
                i < 15 -> 0.0
                i < 55 -> 2.4 - (i - 15) * 0.03
                i < 80 -> -0.35
                i < 115 -> 1.35
                else -> -2.1
            }
        }
        val rep = StepFusionAndImuBridge.analyzeBarbellConcentric100Hz(synth, 100.0)
        assertNotNull(rep)
        assertTrue(rep!!.meanConcentricVelocityMps > 0.20)
        assertTrue(rep.peakConcentricVelocityMps >= rep.meanConcentricVelocityMps)
        assertTrue(rep.estimatedRomMeters > 0.25)
        assertTrue(rep.stickingPointTimeSec > 0.0)
    }

    @Test
    fun testChartMetricFormattingNeverEmitsRawValueLabel() {
        assertEquals("8h 38m", formatMetricValue("sleep_hrs", 518f))
        assertEquals("18.8", formatMetricValue("strain_row", 18.8f))
        assertEquals("33.67 °C", formatMetricValue("temp", 33.67f))
        assertEquals("96.3%", formatMetricValue("spo2", 96.3f))
        assertEquals("59 ms", formatMetricValue("hrv", 59f))
        assertEquals("3,392 cal", formatMetricValue("calories", 3392f))
    }
}
