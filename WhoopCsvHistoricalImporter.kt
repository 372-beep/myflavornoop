package com.noop.analytics

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Seeds NOOP's local database and rolling 30-day/6-month baselines directly from Alex's official
 * WHOOP CSV data export bundled in `assets/whoop_export/` (636 physiological cycles, 635 sleeps,
 * 390 workouts, and journal entries from 2025-02-17 through 2026-11-16).
 */
object WhoopCsvHistoricalImporter {

    data class CalibratedUserBaselines(
        val rmssd30dMeanMs: Double = 63.67,
        val lnRmssd30dSd: Double = 0.164,
        val rhr30dMeanBpm: Double = 49.80,
        val rhr30dSdBpm: Double = 3.81,
        val resp30dMeanRpm: Double = 14.70,
        val spo230dMeanPct: Double = 95.16,
        val skinTemp30dMeanC: Double = 33.58,
        val maxHrObservedBpm: Int = 203,
        val sleepConsistency6mPct: Double = 63.8,
        val sleepHours6m: Double = 7.53,
        val rhr6mMeanBpm: Double = 52.8,
        val totalHistoricalCycles: Int = 636,
        val totalHistoricalSleeps: Int = 635,
        val totalHistoricalWorkouts: Int = 390
    )

    data class HistoricalCycleRecord(
        val cycleStartTime: String,
        val recoveryScorePct: Int?,
        val rhrBpm: Double?,
        val hrvMs: Double?,
        val skinTempC: Double?,
        val spo2Pct: Double?,
        val dayStrain: Double?,
        val caloriesBurned: Int?,
        val maxHrBpm: Int?,
        val avgHrBpm: Int?,
        val sleepPerformancePct: Int?,
        val respRateRpm: Double?,
        val asleepMinutes: Int?,
        val inBedMinutes: Int?,
        val lightSleepMinutes: Int?,
        val deepSwsMinutes: Int?,
        val remMinutes: Int?,
        val awakeMinutes: Int?,
        val sleepNeedMinutes: Int?,
        val sleepDebtMinutes: Int?,
        val sleepEfficiencyPct: Int?,
        val sleepConsistencyPct: Int?
    )

    data class HistoricalWorkoutRecord(
        val startTime: String,
        val durationMin: Int,
        val activityName: String,
        val activityStrain: Double,
        val caloriesBurned: Int,
        val maxHrBpm: Int,
        val avgHrBpm: Int
    )

    fun loadBundledCycles(context: Context): List<HistoricalCycleRecord> {
        val records = ArrayList<HistoricalCycleRecord>(640)
        runCatching {
            context.assets.open("whoop_export/physiological_cycles.csv").use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
                    val iter = lines.iterator()
                    if (!iter.hasNext()) return emptyList()
                    iter.next() // skip header
                    while (iter.hasNext()) {
                        val cols = parseCsvLine(iter.next())
                        if (cols.size < 26) continue
                        records.add(
                            HistoricalCycleRecord(
                                cycleStartTime = cols[0],
                                recoveryScorePct = cols[3].toDoubleOrNull()?.toInt(),
                                rhrBpm = cols[4].toDoubleOrNull(),
                                hrvMs = cols[5].toDoubleOrNull(),
                                skinTempC = cols[6].toDoubleOrNull(),
                                spo2Pct = cols[7].toDoubleOrNull(),
                                dayStrain = cols[8].toDoubleOrNull(),
                                caloriesBurned = cols[9].toDoubleOrNull()?.toInt(),
                                maxHrBpm = cols[10].toDoubleOrNull()?.toInt(),
                                avgHrBpm = cols[11].toDoubleOrNull()?.toInt(),
                                sleepPerformancePct = cols[14].toDoubleOrNull()?.toInt(),
                                respRateRpm = cols[15].toDoubleOrNull(),
                                asleepMinutes = cols[16].toDoubleOrNull()?.toInt(),
                                inBedMinutes = cols[17].toDoubleOrNull()?.toInt(),
                                lightSleepMinutes = cols[18].toDoubleOrNull()?.toInt(),
                                deepSwsMinutes = cols[19].toDoubleOrNull()?.toInt(),
                                remMinutes = cols[20].toDoubleOrNull()?.toInt(),
                                awakeMinutes = cols[21].toDoubleOrNull()?.toInt(),
                                sleepNeedMinutes = cols[22].toDoubleOrNull()?.toInt(),
                                sleepDebtMinutes = cols[23].toDoubleOrNull()?.toInt(),
                                sleepEfficiencyPct = cols[24].toDoubleOrNull()?.toInt(),
                                sleepConsistencyPct = cols[25].toDoubleOrNull()?.toInt()
                            )
                        )
                    }
                }
            }
        }
        return records
    }

    fun loadBundledWorkouts(context: Context, limit: Int = 40): List<HistoricalWorkoutRecord> {
        val records = ArrayList<HistoricalWorkoutRecord>(limit)
        runCatching {
            context.assets.open("whoop_export/workouts.csv").use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
                    val iter = lines.iterator()
                    if (!iter.hasNext()) return emptyList()
                    iter.next() // skip header
                    while (iter.hasNext() && records.size < limit) {
                        val cols = parseCsvLine(iter.next())
                        if (cols.size < 11) continue
                        records.add(
                            HistoricalWorkoutRecord(
                                startTime = cols[3],
                                durationMin = cols[5].toDoubleOrNull()?.toInt() ?: 0,
                                activityName = cols[6].ifBlank { "Workout" },
                                activityStrain = cols[7].toDoubleOrNull() ?: 0.0,
                                caloriesBurned = cols[8].toDoubleOrNull()?.toInt() ?: 0,
                                maxHrBpm = cols[9].toDoubleOrNull()?.toInt() ?: 0,
                                avgHrBpm = cols[10].toDoubleOrNull()?.toInt() ?: 0
                            )
                        )
                    }
                }
            }
        }
        return records
    }

    internal fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>(28)
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
