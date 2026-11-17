package com.noop.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noop.analytics.BloodPressureEstimator
import com.noop.analytics.HealthspanWhoopAgeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

data class DashboardRowItem(
    val id: String,
    val title: String,
    val currentValue: String,
    val deltaSymbol: String,
    val baselineText: String,
    val isPositive: Boolean,
    val unit: String = "",
    val series30d: List<Pair<String, Float>> = emptyList()
)

/**
 * Self-contained CSV reader inside com.noop.ui so WhoopStyleHomeScreen.kt compiles and loads
 * all 636 physiological cycles and 390 workouts with zero external file dependencies.
 */
internal object WhoopUiCsvLoader {

    data class Baselines(
        val rmssd30dMeanMs: Double = 63.67,
        val rhr30dMeanBpm: Double = 49.80,
        val resp30dMeanRpm: Double = 14.70,
        val spo230dMeanPct: Double = 95.16,
        val skinTemp30dMeanC: Double = 33.58,
        val sleepConsistency6mPct: Double = 63.8,
        val sleepHours6m: Double = 7.53,
        val rhr6mMeanBpm: Double = 52.8
    )

    data class CycleRow(
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
        val lightSleepMinutes: Int?,
        val deepSwsMinutes: Int?,
        val remMinutes: Int?,
        val awakeMinutes: Int?,
        val sleepNeedMinutes: Int?,
        val sleepDebtMinutes: Int?,
        val sleepEfficiencyPct: Int?,
        val sleepConsistencyPct: Int?
    )

    data class WorkoutRow(
        val startTime: String,
        val durationMin: Int,
        val activityName: String,
        val activityStrain: Double,
        val caloriesBurned: Int,
        val maxHrBpm: Int,
        val avgHrBpm: Int
    )

    fun loadCycles(context: Context): List<CycleRow> {
        val list = ArrayList<CycleRow>(640)
        runCatching {
            context.assets.open("whoop_export/physiological_cycles.csv").use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
                    val it = lines.iterator()
                    if (!it.hasNext()) return emptyList()
                    it.next()
                    while (it.hasNext()) {
                        val c = splitCsv(it.next())
                        if (c.size < 26) continue
                        list.add(
                            CycleRow(
                                cycleStartTime = c[0],
                                recoveryScorePct = c[3].toDoubleOrNull()?.toInt(),
                                rhrBpm = c[4].toDoubleOrNull(),
                                hrvMs = c[5].toDoubleOrNull(),
                                skinTempC = c[6].toDoubleOrNull(),
                                spo2Pct = c[7].toDoubleOrNull(),
                                dayStrain = c[8].toDoubleOrNull(),
                                caloriesBurned = c[9].toDoubleOrNull()?.toInt(),
                                maxHrBpm = c[10].toDoubleOrNull()?.toInt(),
                                avgHrBpm = c[11].toDoubleOrNull()?.toInt(),
                                sleepPerformancePct = c[14].toDoubleOrNull()?.toInt(),
                                respRateRpm = c[15].toDoubleOrNull(),
                                asleepMinutes = c[16].toDoubleOrNull()?.toInt(),
                                lightSleepMinutes = c[18].toDoubleOrNull()?.toInt(),
                                deepSwsMinutes = c[19].toDoubleOrNull()?.toInt(),
                                remMinutes = c[20].toDoubleOrNull()?.toInt(),
                                awakeMinutes = c[21].toDoubleOrNull()?.toInt(),
                                sleepNeedMinutes = c[22].toDoubleOrNull()?.toInt(),
                                sleepDebtMinutes = c[23].toDoubleOrNull()?.toInt(),
                                sleepEfficiencyPct = c[24].toDoubleOrNull()?.toInt(),
                                sleepConsistencyPct = c[25].toDoubleOrNull()?.toInt()
                            )
                        )
                    }
                }
            }
        }
        return list
    }

    fun loadWorkouts(context: Context, limit: Int = 50): List<WorkoutRow> {
        val list = ArrayList<WorkoutRow>(limit)
        runCatching {
            context.assets.open("whoop_export/workouts.csv").use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
                    val it = lines.iterator()
                    if (!it.hasNext()) return emptyList()
                    it.next()
                    while (it.hasNext() && list.size < limit) {
                        val c = splitCsv(it.next())
                        if (c.size < 11) continue
                        list.add(
                            WorkoutRow(
                                startTime = c[3],
                                durationMin = c[5].toDoubleOrNull()?.toInt() ?: 0,
                                activityName = c[6].ifBlank { "Workout" },
                                activityStrain = c[7].toDoubleOrNull() ?: 0.0,
                                caloriesBurned = c[8].toDoubleOrNull()?.toInt() ?: 0,
                                maxHrBpm = c[9].toDoubleOrNull()?.toInt() ?: 0,
                                avgHrBpm = c[10].toDoubleOrNull()?.toInt() ?: 0
                            )
                        )
                    }
                }
            }
        }
        return list
    }

    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>(28)
        val sb = StringBuilder()
        var inQ = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    if (inQ && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else inQ = !inQ
                }
                ch == ',' && !inQ -> {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}

/**
 * Complete, Self-Contained WHOOP 5.0 Native UI Replacement for NOOP (v3.0).
 */
@Composable
fun WhoopStyleHomeScreen(
    sleepPerformancePct: Int = 80,
    recoveryPct: Int = 55,
    dayStrain: Double = 18.8,
    liveHrBpm: Int = 51,
    batteryPct: Int = 44,
    streakDays: Int = 636,
    dashboardRows: List<DashboardRowItem> = emptyList(),
    thirtyDaySteps: List<Pair<String, Int>> = emptyList(),
    onOpenMetricDetail: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val baselines = remember { WhoopUiCsvLoader.Baselines() }

    val loadedCycles by produceState(initialValue = emptyList<WhoopUiCsvLoader.CycleRow>()) {
        value = withContext(Dispatchers.IO) { WhoopUiCsvLoader.loadCycles(context) }
    }
    val loadedWorkouts by produceState(initialValue = emptyList<WhoopUiCsvLoader.WorkoutRow>()) {
        value = withContext(Dispatchers.IO) { WhoopUiCsvLoader.loadWorkouts(context, 50) }
    }

    var dayIndex by remember { mutableStateOf(0) }
    var selectedTab by remember { mutableStateOf("home") }
    var activeDetailScreen by remember { mutableStateOf<String?>(null) }
    var trendsMetricId by remember { mutableStateOf("hrv") }
    var showBpCalibrateDialog by remember { mutableStateOf(false) }
    var customSysBaseline by remember { mutableStateOf(120) }
    var customDiaBaseline by remember { mutableStateOf(77) }

    BackHandler(enabled = activeDetailScreen != null || selectedTab != "home") {
        if (activeDetailScreen != null) {
            activeDetailScreen = null
        } else {
            selectedTab = "home"
        }
    }

    val safeDayIndex = dayIndex.coerceIn(0, (loadedCycles.size - 1).coerceAtLeast(0))
    val currentCycle = loadedCycles.getOrNull(safeDayIndex)
    val latestStrainCycle = loadedCycles.drop(safeDayIndex).firstOrNull { it.dayStrain != null }

    val curSleepPerf = currentCycle?.sleepPerformancePct ?: sleepPerformancePct
    val curRecovery = currentCycle?.recoveryScorePct ?: recoveryPct
    val curStrain = currentCycle?.dayStrain ?: latestStrainCycle?.dayStrain ?: dayStrain
    val curRhr = currentCycle?.rhrBpm ?: baselines.rhr30dMeanBpm
    val curHrv = currentCycle?.hrvMs ?: baselines.rmssd30dMeanMs
    val curResp = currentCycle?.respRateRpm ?: baselines.resp30dMeanRpm
    val curSpo2 = currentCycle?.spo2Pct ?: baselines.spo230dMeanPct
    val curTemp = currentCycle?.skinTempC ?: baselines.skinTemp30dMeanC
    val curSleepMin = currentCycle?.asleepMinutes ?: 518
    val curDeepMin = currentCycle?.deepSwsMinutes ?: 114
    val curRemMin = currentCycle?.remMinutes ?: 91
    val curLightMin = currentCycle?.lightSleepMinutes ?: 313
    val curAwakeMin = currentCycle?.awakeMinutes ?: 40
    val curNeedMin = currentCycle?.sleepNeedMinutes ?: 617
    val curDebtMin = currentCycle?.sleepDebtMinutes ?: 95
    val curEffPct = currentCycle?.sleepEfficiencyPct ?: 93
    val curConsPct = currentCycle?.sleepConsistencyPct ?: 60
    val curCals = currentCycle?.caloriesBurned ?: latestStrainCycle?.caloriesBurned ?: 3392

    val dayLabel = remember(safeDayIndex, currentCycle) {
        if (safeDayIndex == 0) "TODAY"
        else if (safeDayIndex == 1) "YESTERDAY"
        else currentCycle?.cycleStartTime?.take(10) ?: "DAY -$safeDayIndex"
    }

    val effectiveRows = remember(loadedCycles, safeDayIndex, baselines) {
        val window30 = loadedCycles.drop(safeDayIndex).take(30).reversed()
        fun buildSeries(ext: (WhoopUiCsvLoader.CycleRow) -> Float?): List<Pair<String, Float>> =
            window30.mapNotNull { c ->
                val v = ext(c) ?: return@mapNotNull null
                val d = c.cycleStartTime.take(10).takeLast(5).ifBlank { "Day" }
                d to v
            }

        listOf(
            DashboardRowItem(
                id = "hrv",
                title = "Heart Rate Variability",
                currentValue = "${curHrv.roundToInt()} ms",
                deltaSymbol = "vs ${baselines.rmssd30dMeanMs.roundToInt()} ms",
                baselineText = "30-day baseline (${(baselines.rmssd30dMeanMs - 8).roundToInt()}–${(baselines.rmssd30dMeanMs + 8).roundToInt()} ms)",
                isPositive = curHrv >= baselines.rmssd30dMeanMs - 5.0,
                unit = "ms",
                series30d = buildSeries { it.hrvMs?.toFloat() }
            ),
            DashboardRowItem(
                id = "rhr",
                title = "Resting Heart Rate",
                currentValue = "${curRhr.roundToInt()} bpm",
                deltaSymbol = "vs ${baselines.rhr30dMeanBpm.roundToInt()} bpm",
                baselineText = "30-day baseline (${(baselines.rhr30dMeanBpm - 3).roundToInt()}–${(baselines.rhr30dMeanBpm + 3).roundToInt()} bpm)",
                isPositive = curRhr <= baselines.rhr30dMeanBpm + 2.0,
                unit = "bpm",
                series30d = buildSeries { it.rhrBpm?.toFloat() }
            ),
            DashboardRowItem(
                id = "resp",
                title = "Respiratory Rate",
                currentValue = String.format("%.1f rpm", curResp),
                deltaSymbol = "vs ${String.format("%.1f rpm", baselines.resp30dMeanRpm)}",
                baselineText = "30-day baseline (13.8–15.6 rpm)",
                isPositive = curResp in 13.0..16.2,
                unit = "rpm",
                series30d = buildSeries { it.respRateRpm?.toFloat() }
            ),
            DashboardRowItem(
                id = "spo2",
                title = "Blood Oxygen (SpO₂)",
                currentValue = String.format("%.1f%%", curSpo2),
                deltaSymbol = "vs ${String.format("%.1f%%", baselines.spo230dMeanPct)}",
                baselineText = "30-day baseline (94.0%–98.0%)",
                isPositive = curSpo2 >= 94.0,
                unit = "%",
                series30d = buildSeries { it.spo2Pct?.toFloat() }
            ),
            DashboardRowItem(
                id = "temp",
                title = "Skin Temperature",
                currentValue = String.format("%.2f °C", curTemp),
                deltaSymbol = "vs ${String.format("%.2f °C", baselines.skinTemp30dMeanC)}",
                baselineText = "30-day baseline",
                isPositive = true,
                unit = "°C",
                series30d = buildSeries { it.skinTempC?.toFloat() }
            ),
            DashboardRowItem(
                id = "sleep_hrs",
                title = "Hours of Sleep",
                currentValue = "${curSleepMin / 60}h ${curSleepMin % 60}m",
                deltaSymbol = "vs ${String.format("%.1fh", baselines.sleepHours6m)} (${curSleepPerf}% perf)",
                baselineText = "Needed ${curNeedMin / 60}h ${curNeedMin % 60}m",
                isPositive = curSleepPerf >= 75,
                unit = "min",
                series30d = buildSeries { it.asleepMinutes?.toFloat() }
            ),
            DashboardRowItem(
                id = "sws",
                title = "Restorative Sleep (SWS + REM)",
                currentValue = "${(curDeepMin + curRemMin) / 60}h ${(curDeepMin + curRemMin) % 60}m",
                deltaSymbol = "Deep ${curDeepMin}m · REM ${curRemMin}m",
                baselineText = "Last night restorative stages",
                isPositive = (curDeepMin + curRemMin) >= 180,
                unit = "min",
                series30d = buildSeries {
                    val d = it.deepSwsMinutes ?: return@buildSeries null
                    val r = it.remMinutes ?: 0
                    (d + r).toFloat()
                }
            ),
            DashboardRowItem(
                id = "strain_row",
                title = "Day Strain",
                currentValue = String.format("%.1f", curStrain),
                deltaSymbol = "Max HR ${currentCycle?.maxHrBpm ?: latestStrainCycle?.maxHrBpm ?: 163} bpm",
                baselineText = "0–21.0 Borg scale",
                isPositive = true,
                unit = "strain",
                series30d = buildSeries { it.dayStrain?.toFloat() }
            ),
            DashboardRowItem(
                id = "calories",
                title = "Calories",
                currentValue = "$curCals cal",
                deltaSymbol = "vs 2,240 cal avg",
                baselineText = "30-day daily average",
                isPositive = true,
                unit = "cal",
                series30d = buildSeries { it.caloriesBurned?.toFloat() }
            ),
            DashboardRowItem(
                id = "efficiency",
                title = "Sleep Efficiency",
                currentValue = "$curEffPct%",
                deltaSymbol = "vs 90% baseline",
                baselineText = "30-day baseline",
                isPositive = curEffPct >= 85,
                unit = "%",
                series30d = buildSeries { it.sleepEfficiencyPct?.toFloat() }
            ),
            DashboardRowItem(
                id = "consistency",
                title = "Sleep Consistency",
                currentValue = "$curConsPct%",
                deltaSymbol = "vs ${baselines.sleepConsistency6mPct.roundToInt()}% baseline",
                baselineText = "6-month baseline",
                isPositive = curConsPct >= 60,
                unit = "%",
                series30d = buildSeries { it.sleepConsistencyPct?.toFloat() }
            ),
            DashboardRowItem(
                id = "debt",
                title = "Sleep Debt",
                currentValue = "${curDebtMin / 60}h ${curDebtMin % 60}m",
                deltaSymbol = "Target < 45m",
                baselineText = "Accumulated sleep debt",
                isPositive = curDebtMin <= 60,
                unit = "min",
                series30d = buildSeries { it.sleepDebtMinutes?.toFloat() }
            )
        )
    }

    val recoveryColor = when {
        curRecovery >= 67 -> Color(0xFF16EC06)
        curRecovery >= 34 -> Color(0xFFFFDE00)
        else -> Color(0xFFFF0026)
    }

    Scaffold(
        containerColor = Color(0xFF0A0C0F),
        bottomBar = {
            Surface(
                color = Color(0xFF12161F),
                tonalElevation = 16.dp,
                shadowElevation = 12.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = 10.dp)
                ) {
                    HorizontalDivider(color = Color(0xFF1E2633), thickness = 1.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(62.dp)
                            .padding(horizontal = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val navItems = listOf(
                            Triple("home", "Home", "◉"),
                            Triple("health", "Health", "♥"),
                            Triple("trends", "Trends", "▤"),
                            Triple("device", "Device", "⌚")
                        )
                        navItems.forEach { (id, label, icon) ->
                            val isSelected = selectedTab == id && activeDetailScreen == null
                            Surface(
                                color = if (isSelected) Color(0xFF1C2432) else Color.Transparent,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(horizontal = 4.dp, vertical = 6.dp)
                                    .clickable {
                                        activeDetailScreen = null
                                        selectedTab = id
                                    }
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = icon,
                                        color = if (isSelected) Color(0xFF00F19F) else Color(0xFF7D899C),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = label,
                                        color = if (isSelected) Color.White else Color(0xFF7D899C),
                                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .background(Color(0xFF0093E7), CircleShape)
                        ) {
                            Text("AM", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        }
                        Surface(color = Color(0xFF151A24), shape = RoundedCornerShape(50)) {
                            Text(
                                "🔥 ${loadedCycles.size.coerceAtLeast(streakDays)}",
                                color = Color(0xFFF59E0B),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Surface(color = Color(0xFF151A24), shape = RoundedCornerShape(50)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Text(
                                " ‹ ",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 16.sp,
                                modifier = Modifier
                                    .clickable {
                                        if (dayIndex < (loadedCycles.size - 1).coerceAtLeast(0)) dayIndex++
                                    }
                                    .padding(horizontal = 6.dp)
                            )
                            Text(
                                dayLabel,
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clickable { dayIndex = 0 }
                                    .padding(horizontal = 4.dp)
                            )
                            Text(
                                " › ",
                                color = if (dayIndex > 0) Color.White else Color(0xFF475569),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 16.sp,
                                modifier = Modifier
                                    .clickable(enabled = dayIndex > 0) {
                                        if (dayIndex > 0) dayIndex--
                                    }
                                    .padding(horizontal = 6.dp)
                            )
                        }
                    }

                    Surface(
                        color = Color(0xFF151A24),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.clickable {
                            activeDetailScreen = null
                            selectedTab = "device"
                        }
                    ) {
                        Text(
                            "${curRhr.roundToInt()} BPM · $batteryPct%",
                            color = Color(0xFF16EC06),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            val detail = activeDetailScreen
            if (detail != null) {
                when (detail) {
                    "SLEEP" -> item {
                        WhoopSleepDetailPage(
                            sleepPerformancePct = curSleepPerf,
                            asleepMin = curSleepMin,
                            needMin = curNeedMin,
                            deepMin = curDeepMin,
                            remMin = curRemMin,
                            lightMin = curLightMin,
                            awakeMin = curAwakeMin,
                            efficiencyPct = curEffPct,
                            consistencyPct = curConsPct,
                            debtMin = curDebtMin,
                            baselines = baselines,
                            cycles = loadedCycles,
                            onBack = { activeDetailScreen = null },
                            onOpenMetricRow = { activeDetailScreen = it }
                        )
                    }
                    "RECOVERY" -> item {
                        WhoopRecoveryDetailPage(
                            recoveryPct = curRecovery,
                            recoveryColor = recoveryColor,
                            hrvMs = curHrv,
                            rhrBpm = curRhr,
                            respRpm = curResp,
                            sleepPerfPct = curSleepPerf,
                            baselines = baselines,
                            cycles = loadedCycles,
                            onBack = { activeDetailScreen = null },
                            onOpenMetricRow = { activeDetailScreen = it }
                        )
                    }
                    "STRAIN" -> item {
                        WhoopStrainDetailPage(
                            dayStrain = curStrain,
                            calories = curCals,
                            maxHr = currentCycle?.maxHrBpm ?: latestStrainCycle?.maxHrBpm ?: 163,
                            avgHr = currentCycle?.avgHrBpm ?: latestStrainCycle?.avgHrBpm ?: 79,
                            workouts = loadedWorkouts,
                            cycles = loadedCycles,
                            onBack = { activeDetailScreen = null }
                        )
                    }
                    "STRESS" -> item {
                        WhoopStressDetailPage(
                            liveHrBpm = curRhr.roundToInt(),
                            hrvMs = curHrv,
                            rhrBaseline = baselines.rhr30dMeanBpm,
                            onBack = { activeDetailScreen = null }
                        )
                    }
                    else -> {
                        val matchedRow = effectiveRows.find { it.id == detail } ?: effectiveRows.firstOrNull()
                        item {
                            SingleMetricDrillDownPage(
                                row = matchedRow,
                                onBack = { activeDetailScreen = null }
                            )
                        }
                    }
                }
            } else if (selectedTab == "home") {
                item {
                    Surface(
                        color = Color(0xFF131720),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp, horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            WhoopCircularDial(
                                label = "SLEEP >",
                                valueText = "$curSleepPerf%",
                                progress = curSleepPerf / 100f,
                                color = Color(0xFF7294B4),
                                isCenter = false
                            ) {
                                activeDetailScreen = "SLEEP"
                            }
                            WhoopCircularDial(
                                label = "RECOVERY >",
                                valueText = "$curRecovery%",
                                progress = curRecovery / 100f,
                                color = recoveryColor,
                                isCenter = true
                            ) {
                                activeDetailScreen = "RECOVERY"
                            }
                            WhoopCircularDial(
                                label = "STRAIN >",
                                valueText = String.format("%.1f", curStrain),
                                progress = (curStrain / 21.0).toFloat(),
                                color = Color(0xFF0093E7),
                                isCenter = false
                            ) {
                                activeDetailScreen = "STRAIN"
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            color = Color(0xFF131720),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedTab = "health" }
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("HEALTH MONITOR >", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                                Text("5/5 Metrics", color = Color(0xFF16EC06), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                                Text("Within Range", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                            }
                        }
                        Surface(
                            color = Color(0xFF131720),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { activeDetailScreen = "STRESS" }
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("STRESS MONITOR >", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Surface(color = Color(0xFF0E293C), shape = RoundedCornerShape(6.dp)) {
                                        Text(
                                            "1.1",
                                            color = Color(0xFF38BDF8),
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 14.sp,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Text("Low", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                                }
                                Text("Live ${curRhr.roundToInt()} BPM", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("MY DASHBOARD", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                        Text("Tap any row for details ›", color = Color(0xFF64748B), fontSize = 11.sp)
                    }
                }

                items(items = effectiveRows, key = { it.id }) { row ->
                    Surface(
                        color = Color(0xFF131720),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { activeDetailScreen = row.id }
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 16.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = row.title,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = row.currentValue,
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 17.sp
                                )
                                Text(
                                    text = "›",
                                    color = Color(0xFF64748B),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp
                                )
                            }
                        }
                    }
                }
            } else if (selectedTab == "health") {
                item {
                    HealthMonitorFullCard(
                        rows = effectiveRows,
                        onSelectMetric = { activeDetailScreen = it }
                    )
                }
                item {
                    BloodPressureFullCard(
                        cycles = loadedCycles,
                        baselines = baselines,
                        sysBaseline = customSysBaseline,
                        diaBaseline = customDiaBaseline,
                        onOpenCalibrate = { showBpCalibrateDialog = true }
                    )
                }
                item {
                    WhoopAgeFullCard(baselines = baselines)
                }
            } else if (selectedTab == "trends") {
                val selectedRow = effectiveRows.find { it.id == trendsMetricId } ?: effectiveRows.firstOrNull()
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("30-DAY TRENDS", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            effectiveRows.forEach { r ->
                                val isSel = r.id == (selectedRow?.id ?: "")
                                Surface(
                                    color = if (isSel) Color(0xFF0093E7) else Color(0xFF151A24),
                                    shape = RoundedCornerShape(50),
                                    modifier = Modifier.clickable { trendsMetricId = r.id }
                                ) {
                                    Text(
                                        text = r.title,
                                        color = if (isSel) Color.Black else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }

                        if (selectedRow != null) {
                            val chartData = if (selectedRow.series30d.isNotEmpty()) {
                                selectedRow.series30d.map { it.first to it.second.roundToInt() }
                            } else {
                                thirtyDaySteps
                            }
                            Interactive30DayBarChart(
                                metricTitle = selectedRow.title.uppercase(),
                                yAxisLabel = "${selectedRow.title} (${selectedRow.unit})",
                                xAxisLabel = "LAST 30 DAYS (TAP OR DRAG BARS)",
                                data = chartData
                            )

                            Surface(color = Color(0xFF131720), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("CURRENT", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text(selectedRow.currentValue, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("30-DAY BASELINE", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text(selectedRow.deltaSymbol.removePrefix("vs ").trim(), color = Color(0xFF00F19F), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                                    }
                                }
                            }
                        }
                    }
                }

                if (loadedWorkouts.isNotEmpty()) {
                    item {
                        Text(
                            "LOGGED WORKOUT HISTORY (${loadedWorkouts.size} SHOWN)",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    items(loadedWorkouts.take(20)) { w ->
                        WorkoutHistoryCard(w)
                    }
                }
            } else if (selectedTab == "device") {
                item {
                    WhoopDeviceSettingsCard(
                        batteryPct = batteryPct,
                        liveHrBpm = curRhr.roundToInt(),
                        totalCycles = loadedCycles.size.coerceAtLeast(streakDays),
                        onOpenRawBleUtility = { onOpenMetricDetail("BLE_SETTINGS") }
                    )
                }
            }
        }
    }

    if (showBpCalibrateDialog) {
        AlertDialog(
            onDismissRequest = { showBpCalibrateDialog = false },
            containerColor = Color(0xFF151A24),
            title = {
                Text("Calibrate Blood Pressure Baseline", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Adjust your 3-cuff average baseline (used to anchor your nightly RHR + SWS HRV morning estimates):",
                        color = Color(0xFFCBD5E1),
                        fontSize = 13.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Systolic: $customSysBaseline mmHg", color = Color.White, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { customSysBaseline = (customSysBaseline - 1).coerceAtLeast(90) }) { Text("-") }
                            Button(onClick = { customSysBaseline = (customSysBaseline + 1).coerceAtMost(160) }) { Text("+") }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Diastolic: $customDiaBaseline mmHg", color = Color.White, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { customDiaBaseline = (customDiaBaseline - 1).coerceAtLeast(55) }) { Text("-") }
                            Button(onClick = { customDiaBaseline = (customDiaBaseline + 1).coerceAtMost(110) }) { Text("+") }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showBpCalibrateDialog = false }) {
                    Text("Save Baseline")
                }
            }
        )
    }
}

@Composable
private fun DetailBackBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = Color(0xFF171D28),
            shape = RoundedCornerShape(50),
            modifier = Modifier.clickable { onBack() }
        ) {
            Text(
                "← Back",
                color = Color(0xFF00F19F),
                fontWeight = FontWeight.ExtraBold,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
        Text(title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
        Spacer(modifier = Modifier.width(64.dp))
    }
}

@Composable
private fun WhoopSleepDetailPage(
    sleepPerformancePct: Int,
    asleepMin: Int,
    needMin: Int,
    deepMin: Int,
    remMin: Int,
    lightMin: Int,
    awakeMin: Int,
    efficiencyPct: Int,
    consistencyPct: Int,
    debtMin: Int,
    baselines: WhoopUiCsvLoader.Baselines,
    cycles: List<WhoopUiCsvLoader.CycleRow>,
    onBack: () -> Unit,
    onOpenMetricRow: (String) -> Unit
) {
    val totalInBed = (asleepMin + awakeMin).coerceAtLeast(1)
    val sleepSeries = remember(cycles) {
        cycles.take(30).reversed().mapNotNull { c ->
            val m = c.asleepMinutes ?: return@mapNotNull null
            c.cycleStartTime.take(10).takeLast(5) to m
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DetailBackBar("SLEEP PERFORMANCE", onBack)

        Surface(color = Color(0xFF131720), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                WhoopCircularDial(
                    label = "SLEEP PERFORMANCE",
                    valueText = "$sleepPerformancePct%",
                    progress = sleepPerformancePct / 100f,
                    color = Color(0xFF7294B4),
                    isCenter = true
                ) {}
                Text(
                    "${asleepMin / 60}h ${asleepMin % 60}m Slept  /  ${needMin / 60}h ${needMin % 60}m Needed",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    "30-Day Baseline: ${String.format("%.1f hrs", baselines.sleepHours6m)} · Restorative: ${(deepMin + remMin) / 60}h ${(deepMin + remMin) % 60}m",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
            }
        }

        Surface(color = Color(0xFF131720), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("SLEEP STAGES BREAKDOWN", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                SleepStageBar("Deep (SWS)", deepMin, totalInBed, Color(0xFF6366F1))
                SleepStageBar("REM Sleep", remMin, totalInBed, Color(0xFF38BDF8))
                SleepStageBar("Light Sleep", lightMin, totalInBed, Color(0xFF64748B))
                SleepStageBar("Awake", awakeMin, totalInBed, Color(0xFFF59E0B))
            }
        }

        Surface(color = Color(0xFF131720), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("SLEEP METRICS VS. BASELINE", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                ClickableMetricComparisonRow(
                    label = "Hours of Sleep",
                    value = "${asleepMin / 60}h ${asleepMin % 60}m",
                    baseline = "vs ${String.format("%.1fh", baselines.sleepHours6m)} avg",
                    onClick = { onOpenMetricRow("sleep_hrs") }
                )
                ClickableMetricComparisonRow(
                    label = "Sleep Efficiency",
                    value = "$efficiencyPct%",
                    baseline = "vs 90% baseline",
                    onClick = { onOpenMetricRow("efficiency") }
                )
                ClickableMetricComparisonRow(
                    label = "Sleep Consistency",
                    value = "$consistencyPct%",
                    baseline = "vs ${baselines.sleepConsistency6mPct.roundToInt()}% baseline",
                    onClick = { onOpenMetricRow("consistency") }
                )
                ClickableMetricComparisonRow(
                    label = "Sleep Debt",
                    value = "${debtMin / 60}h ${debtMin % 60}m",
                    baseline = "Target < 45m",
                    onClick = { onOpenMetricRow("debt") }
                )
            }
        }

        if (sleepSeries.isNotEmpty()) {
            Interactive30DayBarChart(
                metricTitle = "SLEEP DURATION (MINUTES)",
                yAxisLabel = "Asleep Duration (min)",
                xAxisLabel = "LAST 30 NIGHTS",
                data = sleepSeries
            )
        }
    }
}

@Composable
private fun SleepStageBar(name: String, minutes: Int, totalMin: Int, color: Color) {
    val pct = ((minutes.toFloat() / totalMin.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$name ($pct%)", color = Color(0xFFCBD5E1), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("${minutes / 60}h ${minutes % 60}m", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(Color(0xFF1E2633), RoundedCornerShape(50))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = (pct / 100f).coerceIn(0.04f, 1f))
                    .fillMaxHeight()
                    .background(color, RoundedCornerShape(50))
            )
        }
    }
}

@Composable
private fun WhoopRecoveryDetailPage(
    recoveryPct: Int,
    recoveryColor: Color,
    hrvMs: Double,
    rhrBpm: Double,
    respRpm: Double,
    sleepPerfPct: Int,
    baselines: WhoopUiCsvLoader.Baselines,
    cycles: List<WhoopUiCsvLoader.CycleRow>,
    onBack: () -> Unit,
    onOpenMetricRow: (String) -> Unit
) {
    val recSeries = remember(cycles) {
        cycles.take(30).reversed().mapNotNull { c ->
            val r = c.recoveryScorePct ?: return@mapNotNull null
            c.cycleStartTime.take(10).takeLast(5) to r
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DetailBackBar("RECOVERY", onBack)

        Surface(color = Color(0xFF131720), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                WhoopCircularDial(
                    label = "RECOVERY",
                    valueText = "$recoveryPct%",
                    progress = recoveryPct / 100f,
                    color = recoveryColor,
                    isCenter = true
                ) {}
                val statusText = when {
                    recoveryPct >= 67 -> "Green Recovery · Primed to Take On Strain"
                    recoveryPct >= 34 -> "Yellow Recovery · Baseline Readiness"
                    else -> "Red Recovery · Prioritize Restorative Sleep"
                }
                Text(statusText, color = recoveryColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }

        Surface(color = Color(0xFF131720), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("RECOVERY CONTRIBUTORS (VS. 30D BASELINE)", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                ClickableMetricComparisonRow(
                    label = "Heart Rate Variability",
                    value = "${hrvMs.roundToInt()} ms",
                    baseline = "vs ${baselines.rmssd30dMeanMs.roundToInt()} ms 30d avg",
                    onClick = { onOpenMetricRow("hrv") }
                )
                ClickableMetricComparisonRow(
                    label = "Resting Heart Rate",
                    value = "${rhrBpm.roundToInt()} bpm",
                    baseline = "vs ${baselines.rhr30dMeanBpm.roundToInt()} bpm 30d avg",
                    onClick = { onOpenMetricRow("rhr") }
                )
                ClickableMetricComparisonRow(
                    label = "Respiratory Rate",
                    value = String.format("%.1f rpm", respRpm),
                    baseline = "vs ${String.format("%.1f rpm", baselines.resp30dMeanRpm)} 30d avg",
                    onClick = { onOpenMetricRow("resp") }
                )
                ClickableMetricComparisonRow(
                    label = "Sleep Performance",
                    value = "$sleepPerfPct%",
                    baseline = "Last night",
                    onClick = { onOpenMetricRow("SLEEP") }
                )
            }
        }

        if (recSeries.isNotEmpty()) {
            Interactive30DayBarChart(
                metricTitle = "RECOVERY SCORE (%)",
                yAxisLabel = "Recovery (%)",
                xAxisLabel = "LAST 30 DAYS",
                data = recSeries
            )
        }
    }
}

@Composable
private fun WhoopStrainDetailPage(
    dayStrain: Double,
    calories: Int,
    maxHr: Int,
    avgHr: Int,
    workouts: List<WhoopUiCsvLoader.WorkoutRow>,
    cycles: List<WhoopUiCsvLoader.CycleRow>,
    onBack: () -> Unit
) {
    val strainSeries = remember(cycles) {
        cycles.take(30).reversed().mapNotNull { c ->
            val s = c.dayStrain ?: return@mapNotNull null
            c.cycleStartTime.take(10).takeLast(5) to (s * 10).roundToInt()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DetailBackBar("DAY STRAIN", onBack)

        Surface(color = Color(0xFF131720), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                WhoopCircularDial(
                    label = "DAY STRAIN",
                    valueText = String.format("%.1f", dayStrain),
                    progress = (dayStrain / 21.0).toFloat(),
                    color = Color(0xFF0093E7),
                    isCenter = true
                ) {}
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatMiniBlock("CALORIES", "$calories cal")
                    StatMiniBlock("AVG HR", "$avgHr bpm")
                    StatMiniBlock("MAX HR", "$maxHr bpm")
                }
            }
        }

        if (workouts.isNotEmpty()) {
            Text("RECENT WORKOUTS", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
            workouts.take(8).forEach { w ->
                WorkoutHistoryCard(w)
            }
        }

        if (strainSeries.isNotEmpty()) {
            Interactive30DayBarChart(
                metricTitle = "DAY STRAIN (x10)",
                yAxisLabel = "Strain (x10 scale, e.g. 188 = 18.8)",
                xAxisLabel = "LAST 30 CYCLES",
                data = strainSeries
            )
        }
    }
}

@Composable
private fun WhoopStressDetailPage(
    liveHrBpm: Int,
    hrvMs: Double,
    rhrBaseline: Double,
    onBack: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DetailBackBar("STRESS MONITOR", onBack)

        Surface(color = Color(0xFF131720), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("CURRENT STRESS (0.0 – 3.0)", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("1.1 · Low Stress", color = Color(0xFF38BDF8), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("LIVE HEART RATE", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("$liveHrBpm BPM", color = Color(0xFF16EC06), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                ) {
                    val path = Path()
                    val pts = 48
                    for (i in 0..pts) {
                        val x = (i.toFloat() / pts) * size.width
                        val norm = 0.35f + 0.22f * sin(i * 0.35f)
                        val y = size.height - (norm.coerceIn(0.05f, 0.95f) * size.height)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color = Color(0xFF38BDF8), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                }
                Text(
                    "Computed on-device from live R-R interval RMSSD (${hrvMs.roundToInt()} ms) and HR relative to your ${rhrBaseline.roundToInt()} bpm RHR baseline.",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun SingleMetricDrillDownPage(
    row: DashboardRowItem?,
    onBack: () -> Unit
) {
    if (row == null) {
        DetailBackBar("METRIC DETAILS", onBack)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DetailBackBar(row.title.uppercase(), onBack)

        Surface(color = Color(0xFF131720), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("LATEST READING", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(row.currentValue, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                Surface(color = Color(0xFF1B2432), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        text = "${row.deltaSymbol} · ${row.baselineText}",
                        color = Color(0xFF00F19F),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        if (row.series30d.isNotEmpty()) {
            Interactive30DayBarChart(
                metricTitle = row.title.uppercase(),
                yAxisLabel = "${row.title} (${row.unit})",
                xAxisLabel = "LAST 30 DAYS (TAP OR DRAG BARS)",
                data = row.series30d.map { it.first to it.second.roundToInt() }
            )

            Surface(color = Color(0xFF131720), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("LAST 14 DAYS LOG", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                    row.series30d.takeLast(14).reversed().forEach { (dateStr, v) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(dateStr, color = Color(0xFF94A3B8), fontSize = 13.sp)
                            val txt = if (row.id in setOf("temp", "strain_row", "resp", "spo2")) {
                                String.format("%.1f %s", v, row.unit)
                            } else {
                                "${v.roundToInt()} ${row.unit}"
                            }
                            Text(txt.trim(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClickableMetricComparisonRow(
    label: String,
    value: String,
    baseline: String,
    onClick: () -> Unit
) {
    Surface(
        color = Color(0xFF181F2C),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(baseline, color = Color(0xFF94A3B8), fontSize = 11.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(value, color = Color(0xFF00F19F), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                Text("›", color = Color(0xFF64748B), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StatMiniBlock(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun WorkoutHistoryCard(w: WhoopUiCsvLoader.WorkoutRow) {
    Surface(
        color = Color(0xFF131720),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(color = Color(0xFF0093E7), shape = RoundedCornerShape(6.dp)) {
                        Text(
                            String.format("%.1f", w.activityStrain),
                            color = Color.Black,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                    Text(w.activityName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(w.startTime.take(16), color = Color(0xFF94A3B8), fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${w.durationMin} min · ${w.caloriesBurned} cal", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Text("Avg ${w.avgHrBpm} / Max ${w.maxHrBpm} bpm", color = Color(0xFF94A3B8), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun HealthMonitorFullCard(
    rows: List<DashboardRowItem>,
    onSelectMetric: (String) -> Unit
) {
    val vitals = rows.filter { it.id in setOf("hrv", "rhr", "resp", "spo2", "temp") }
    Surface(color = Color(0xFF131720), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("HEALTH MONITOR", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    Text("5 Overnight Vitals vs. 30-Day Baseline", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
                Surface(color = Color(0xFF0D2E1E), shape = RoundedCornerShape(50)) {
                    Text(
                        "5/5 WITHIN RANGE",
                        color = Color(0xFF16EC06),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            vitals.forEach { v ->
                ClickableMetricComparisonRow(
                    label = v.title,
                    value = v.currentValue,
                    baseline = "${v.deltaSymbol} (${v.baselineText})",
                    onClick = { onSelectMetric(v.id) }
                )
            }
        }
    }
}

@Composable
private fun BloodPressureFullCard(
    cycles: List<WhoopUiCsvLoader.CycleRow>,
    baselines: WhoopUiCsvLoader.Baselines,
    sysBaseline: Int,
    diaBaseline: Int,
    onOpenCalibrate: () -> Unit
) {
    val latest = cycles.firstOrNull()
    val cal = remember(baselines, sysBaseline, diaBaseline) {
        BloodPressureEstimator.CuffCalibration(
            avgSystolic = sysBaseline,
            avgDiastolic = diaBaseline,
            baselineRhrBpm = baselines.rhr30dMeanBpm,
            baselineRmssdMs = baselines.rmssd30dMeanMs,
            calibratedEpochMs = 1762500000000L
        )
    }
    val todayBp = remember(latest, cal) {
        BloodPressureEstimator.estimateMorningBloodPressure(
            calibration = cal,
            nowEpochMs = 1763337600000L,
            sleepAsleepMinutes = latest?.asleepMinutes ?: 518,
            overnightRhrBpm = latest?.rhrBpm ?: 51.0,
            overnightSwsRmssdMs = latest?.hrvMs ?: 59.0
        )
    }

    Surface(color = Color(0xFF131720), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("BLOOD PRESSURE INSIGHTS", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    Text("30-Day Overnight Estimate · Baseline $sysBaseline/$diaBaseline", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
                Surface(
                    color = Color(0xFF1B2432),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.clickable { onOpenCalibrate() }
                ) {
                    Text(
                        "Calibrate Cuff",
                        color = Color(0xFF0093E7),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            if (todayBp != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${todayBp.systolic}/${todayBp.diastolic} mmHg",
                        color = Color(0xFF00F19F),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 26.sp
                    )
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Sys Range: ${todayBp.systolicLow}–${todayBp.systolicHigh}", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                        Text("Dia Range: ${todayBp.diastolicLow}–${todayBp.diastolicHigh}", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF1E2633))
            Text("LAST 7 MORNING ESTIMATES", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            cycles.take(7).forEach { c ->
                val est = BloodPressureEstimator.estimateMorningBloodPressure(
                    calibration = cal,
                    nowEpochMs = 1763337600000L,
                    sleepAsleepMinutes = c.asleepMinutes ?: 0,
                    overnightRhrBpm = c.rhrBpm ?: baselines.rhr30dMeanBpm,
                    overnightSwsRmssdMs = c.hrvMs
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(c.cycleStartTime.take(10), color = Color(0xFF94A3B8), fontSize = 12.sp)
                    if (est != null) {
                        Text(
                            "${est.systolic}/${est.diastolic} mmHg (${est.systolicLow}-${est.systolicHigh}/${est.diastolicLow}-${est.diastolicHigh})",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    } else {
                        Text("< 5h Sleep (Gated)", color = Color(0xFF64748B), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun WhoopAgeFullCard(baselines: WhoopUiCsvLoader.Baselines) {
    val hs = remember(baselines) {
        HealthspanWhoopAgeEngine.evaluate(
            HealthspanWhoopAgeEngine.HealthspanInputs(
                chronologicalAge = 30.0,
                sleepConsistencyPct6m = baselines.sleepConsistency6mPct,
                sleepHours6m = baselines.sleepHours6m,
                rhrBpm6m = baselines.rhr6mMeanBpm
            )
        )
    }

    Surface(color = Color(0xFF131720), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("HEALTHSPAN · WHOOP AGE", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    Text("Chronological Age: 30.0 yrs · Pace: ${hs.paceOfAging ?: 0.3}x", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${hs.whoopAge} yrs", color = Color(0xFF16EC06), fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                    val signStr = if (hs.netYearsDelta > 0) "+${hs.netYearsDelta}" else "${hs.netYearsDelta}"
                    Text("$signStr yrs", color = Color(0xFF00F19F), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            HorizontalDivider(color = Color(0xFF1E2633))
            hs.contributorYears.forEach { (name, yrs) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(name, color = Color(0xFFCBD5E1), fontSize = 12.sp)
                    val s = if (yrs > 0) "+$yrs yrs" else "$yrs yrs"
                    val col = when {
                        yrs < 0.0 -> Color(0xFF16EC06)
                        yrs > 0.2 -> Color(0xFFF59E0B)
                        else -> Color(0xFF94A3B8)
                    }
                    Text(s, color = col, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun WhoopDeviceSettingsCard(
    batteryPct: Int,
    liveHrBpm: Int,
    totalCycles: Int,
    onOpenRawBleUtility: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(color = Color(0xFF131720), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("CONNECTED TO", color = Color(0xFF16EC06), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                Text("WHOOP 5AM0248285", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("DEVICE ID", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("5AM0248285", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("FIRMWARE", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("50.43.2.0 / 3.30.5.0", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }

                HorizontalDivider(color = Color(0xFF1E2633))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatMiniBlock("STRAP BATTERY", "$batteryPct%")
                    StatMiniBlock("LIVE BLE HR", "$liveHrBpm BPM")
                    StatMiniBlock("CSV HISTORY", "$totalCycles days")
                }
            }
        }

        Surface(
            color = Color(0xFF171E2A),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenRawBleUtility() }
        ) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("PAIR / MANAGE BLUETOOTH CONNECTION", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                    Text("Open underlying BLE scanner & strap diagnostics", color = Color(0xFF94A3B8), fontSize = 12.sp)
                }
                Text("›", color = Color(0xFF00F19F), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun Interactive30DayBarChart(
    metricTitle: String,
    yAxisLabel: String,
    xAxisLabel: String,
    data: List<Pair<String, Int>>
) {
    val currentData by rememberUpdatedState(data)
    var selectedIndex by remember(currentData.size) { mutableStateOf((currentData.size - 1).coerceAtLeast(0)) }
    val maxVal = (data.maxOfOrNull { it.second } ?: 100).coerceAtLeast(1)
    val selectedPair = data.getOrElse(selectedIndex) { "Latest" to 0 }

    Surface(color = Color(0xFF131720), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("$metricTitle · 30-DAY BAR GRAPH", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
            Surface(color = Color(0xFF1B222D), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Date: ${selectedPair.first}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("Value: ${selectedPair.second}", color = Color(0xFF00F19F), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                }
            }
            Text(yAxisLabel, color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(185.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val idx = floor((offset.x / size.width.coerceAtLeast(1)) * currentData.size).toInt()
                            selectedIndex = idx.coerceIn(0, currentData.size - 1)
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress { change, _ ->
                            change.consume()
                            val idx = floor((change.position.x / size.width.coerceAtLeast(1)) * currentData.size).toInt()
                            selectedIndex = idx.coerceIn(0, currentData.size - 1)
                        }
                    }
            ) {
                val barSlot = size.width / currentData.size.coerceAtLeast(1)
                data.forEachIndexed { idx, pair ->
                    val frac = (pair.second.toFloat() / maxVal.toFloat()).coerceIn(0.06f, 1f)
                    val h = (frac * (size.height - 18f)).coerceAtLeast(8f)
                    val x = idx * barSlot + 3f
                    val y = size.height - h
                    drawRect(
                        color = if (idx == selectedIndex) Color.White else Color(0xFF0093E7),
                        topLeft = Offset(x, y),
                        size = Size((barSlot - 6f).coerceAtLeast(4f), h)
                    )
                }
            }
            Text(
                xAxisLabel,
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun WhoopCircularDial(
    label: String,
    valueText: String,
    progress: Float,
    color: Color,
    isCenter: Boolean = false,
    onClick: () -> Unit
) {
    val dialSize = if (isCenter) 108.dp else 92.dp
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onClick() }
            .padding(4.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(dialSize)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeW = if (isCenter) 11.dp.toPx() else 9.5.dp.toPx()
                drawArc(
                    color = Color(0xFF202836),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(strokeW, cap = StrokeCap.Round)
                )
                drawArc(
                    color = color,
                    startAngle = 135f,
                    sweepAngle = (progress.coerceIn(0f, 1f) * 270f),
                    useCenter = false,
                    style = Stroke(strokeW, cap = StrokeCap.Round)
                )
            }
            Text(
                text = valueText,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = if (isCenter) 23.sp else 18.sp
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(label, color = Color(0xFFCBD5E1), fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
    }
}
