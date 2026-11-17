package com.noop.analytics

import com.noop.ble.StepFusionAndImuBridge
import com.noop.ble.Whoop5Fw5043Patch
import org.junit.Assert.*
import org.junit.Test

class NoopAnalyticsTest {

    @Test
    fun testParse2A37Vectors() {
        fun p(vararg b: Int) = Whoop5Fw5043Patch.parseStandardHeartRate2A37(ByteArray(b.size) { b[it].toByte() })!!
        assertEquals(listOf(1000), p(0x10, 0x3C, 0x00, 0x04).rrIntervalsMs)
        assertEquals(65, p(0x11, 0x41, 0x00, 0x66, 0x03).hrBpm)
        assertEquals(listOf(850), p(0x18, 0x3E, 0x10, 0x00, 0x66, 0x03).rrIntervalsMs)
        assertEquals(listOf(1000), p(0x10, 0x3C, 0x00, 0x04, 0xFF).rrIntervalsMs)
        assertEquals(listOf(299), p(0x10, 0x3C, 0x32, 0x01).rrIntervalsMs) // Retained at ingest (QA Fix B1)
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
    fun testBreakerFailsOpenWhenClockMovesBackwards() {
        val state = Whoop5Fw5043Patch.BackfillCircuitState(consecutiveEmptyCount = 3, lastEmptyTimestampMs = 500_000L)
        assertTrue(Whoop5Fw5043Patch.shouldAttemptHistoricalBackfill(state, firmwareMajor = 50, nowMs = 400_000L))
    }

    @Test
    fun testStepBucketFusionUsesMaxSoJostledPhoneDoesNotBeatWrist() {
        val phone = mapOf(100L to 3)
        val wrist = mapOf(100L to 410)
        assertEquals(410, StepFusionAndImuBridge.fuseFiveMinuteStepBuckets(phone, wrist))
    }
}
