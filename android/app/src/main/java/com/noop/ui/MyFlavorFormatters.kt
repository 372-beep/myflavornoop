package com.noop.ui

import kotlin.math.roundToInt

/**
 * Formats any metric's numeric value into its natural WHOOP display string (never "Value: X").
 */
internal fun formatMetricValue(metricId: String, rawValue: Float): String {
    return when (metricId) {
        "sleep_hrs", "SLEEP", "sws", "debt" -> {
            val mins = rawValue.roundToInt().coerceAtLeast(0)
            "${mins / 60}h ${mins % 60}m"
        }
        "strain_row", "STRAIN" -> String.format("%.1f", rawValue)
        "temp" -> String.format("%.2f °C", rawValue)
        "resp" -> String.format("%.1f rpm", rawValue)
        "spo2" -> String.format("%.1f%%", rawValue)
        "hrv" -> "${rawValue.roundToInt()} ms"
        "rhr" -> "${rawValue.roundToInt()} bpm"
        "calories" -> String.format("%,d cal", rawValue.roundToInt())
        "recovery", "RECOVERY", "efficiency", "consistency" -> "${rawValue.roundToInt()}%"
        else -> "${rawValue.roundToInt()}"
    }
}
