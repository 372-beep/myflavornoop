package com.noop.ble

/**
 * Firmware 50.43.2.0 Compatibility & 0x2A37 Parser — Post-QA Hardened (Fixes B1, B2, C1-C4, D1).
 *
 * - B1: Retains all RR > 0 at ingest so raw data is never lost; range filtering happens in analytics.
 * - B2: Decodes sensorContact bits ((flags shr 1) and 0x03).
 * - C1: Distinguishes STRAP_HAS_NOTHING from TRANSPORT_ERROR so flaky disconnects never trip the 12h lock.
 * - C2: Provides encode()/decode() for per-strap persistence in SharedPreferences / SQLite.
 * - C3: Fails open if elapsed < 0L (wall clock moved backwards).
 * - C4/D1: Regex version parser returns Int? = null when unknown; breaker only activates when major == 50.
 */
object Whoop5Fw5043Patch {

    private const val BACKFILL_COOLDOWN_MS = 12L * 60L * 60L * 1000L // 12 hours

    enum class BackfillOutcome { BANKED, STRAP_HAS_NOTHING, TRANSPORT_ERROR }

    data class BackfillCircuitState(
        val consecutiveEmptyCount: Int = 0,
        val lastEmptyTimestampMs: Long = 0L
    ) {
        fun encode(): String = "$consecutiveEmptyCount:$lastEmptyTimestampMs"

        companion object {
            fun decode(s: String?): BackfillCircuitState {
                val p = s?.split(":") ?: return BackfillCircuitState()
                return BackfillCircuitState(
                    consecutiveEmptyCount = p.getOrNull(0)?.toIntOrNull() ?: 0,
                    lastEmptyTimestampMs = p.getOrNull(1)?.toLongOrNull() ?: 0L
                )
            }
        }
    }

    fun shouldAttemptHistoricalBackfill(
        state: BackfillCircuitState,
        firmwareMajor: Int?,
        nowMs: Long
    ): Boolean {
        if (firmwareMajor != 50) return true
        if (state.consecutiveEmptyCount < 2) return true
        val elapsed = nowMs - state.lastEmptyTimestampMs
        return elapsed < 0L || elapsed >= BACKFILL_COOLDOWN_MS
    }

    fun recordBackfillOutcome(
        state: BackfillCircuitState,
        firmwareMajor: Int?,
        outcome: BackfillOutcome,
        nowMs: Long
    ): BackfillCircuitState {
        if (firmwareMajor != 50) return state
        return when (outcome) {
            BackfillOutcome.BANKED -> BackfillCircuitState()
            BackfillOutcome.TRANSPORT_ERROR -> state
            BackfillOutcome.STRAP_HAS_NOTHING -> BackfillCircuitState(state.consecutiveEmptyCount + 1, nowMs)
        }
    }

    private val FW_VERSION = Regex("""(\d+)\.(\d+)\.(\d+)\.(\d+)""")

    fun resolveFirmwareMajor(helloPacket: ByteArray, disFirmwareString: String?): Int? {
        if (helloPacket.size > 93) {
            val b = helloPacket[93].toInt() and 0xFF
            if (b != 0) return b
        }
        val m = disFirmwareString?.let { FW_VERSION.find(it) } ?: return null
        return m.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
    }

    data class Parsed2A37Packet(
        val hrBpm: Int,
        val rrIntervalsMs: List<Int>,
        val sensorContact: Boolean?
    )

    fun parseStandardHeartRate2A37(payload: ByteArray): Parsed2A37Packet? {
        if (payload.size < 2) return null
        val flags = payload[0].toInt() and 0xFF
        val hrBpm: Int
        var offset: Int
        if ((flags and 0x01) != 0) {
            if (payload.size < 3) return null
            hrBpm = (payload[1].toInt() and 0xFF) or ((payload[2].toInt() and 0xFF) shl 8)
            offset = 3
        } else {
            hrBpm = payload[1].toInt() and 0xFF
            offset = 2
        }
        if ((flags and 0x08) != 0) offset += 2
        val rr = ArrayList<Int>(4)
        if ((flags and 0x10) != 0) {
            while (offset + 1 < payload.size) {
                val raw = (payload[offset].toInt() and 0xFF) or ((payload[offset + 1].toInt() and 0xFF) shl 8)
                offset += 2
                if (raw > 0) rr.add(((raw * 1000L + 512L) / 1024L).toInt())
            }
        }
        val contact = when ((flags shr 1) and 0x03) {
            2 -> false
            3 -> true
            else -> null
        }
        return Parsed2A37Packet(hrBpm, rr, contact)
    }

    fun isPlausibleRr(ms: Int): Boolean = ms in 300..2000
}
