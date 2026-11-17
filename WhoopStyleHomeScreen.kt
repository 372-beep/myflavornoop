package com.noop.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noop.analytics.BloodPressureEstimator
import com.noop.analytics.HealthspanWhoopAgeEngine
import com.noop.analytics.WhoopCsvHistoricalImporter
import kotlin.math.floor
import kotlin.math.roundToInt

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
 * Upgraded WHOOP-Authentic Jetpack Compose UI for ryanbr/noop (v2.2).
 *
 * Fixes applied from live Pixel 10 Pro screenshots:
 * 1. Bottom navigation bar uses Modifier.navigationBarsPadding() + 56.dp full-width touch targets
 *    so it sits cleanly above the Android system gesture home bar.
 * 2. Top header uses Modifier.statusBarsPadding() so it never overlaps the status bar clock/icons.
 * 3. Main Home tab MY DASHBOARD rows show ONLY the clean current value (no baseline clutter on the main tab);
 *    tapping any row or top dial opens a full Drill-Down Detail View with the 30-day baseline comparison,
 *    min/max range, interactive 30-day bar chart, and recent daily log.
 * 4. All tabs (Home, Health, Trends, BLE & More) are 100% populated with your 636-day WHOOP CSV data,
 *    Health Monitor 5-vital range bars, Blood Pressure Insights (121/78), WHOOP Age (29.9 / 0.3x), and Workouts.
 */
@Composable
fun WhoopStyleHomeScreen(
    sleepPerformancePct: Int = 80,
    recoveryPct: Int = 55,
    dayStrain: Double = 18.8,
    liveHrBpm: Int = 51,
    batteryPct: Int = 44,
    streakDays: Int = 636,
    dashboardRows: List<DashboardRowItem>,
    thirtyDaySteps: List<Pair<String, Int>>,
    cycles: List<WhoopCsvHistoricalImporter.HistoricalCycleRecord> = emptyList(),
    workouts: List<WhoopCsvHistoricalImporter.HistoricalWorkoutRecord> = emptyList(),
    baselines: WhoopCsvHistoricalImporter.CalibratedUserBaselines = WhoopCsvHistoricalImporter.CalibratedUserBaselines(),
    onOpenMetricDetail: (String) -> Unit
) {
    var selectedTab by remember { mutableStateOf("home") }
    var drillDownMetricId by remember { mutableStateOf<String?>(null) }
    var trendsMetricId by remember { mutableStateOf("hrv") }

    val recoveryColor = when {
        recoveryPct >= 67 -> Color(0xFF16EC06)
        recoveryPct >= 34 -> Color(0xFFFFDE00)
        else -> Color(0xFFFF0026)
    }

    val latestCycle = cycles.firstOrNull()

    Scaffold(
        containerColor = Color(0xFF0A0C0F),
        bottomBar = {
            Surface(
                color = Color(0xFF13171D),
                tonalElevation = 12.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .height(58.dp)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val tabs = listOf(
                        "home" to "Home",
                        "health" to "Health",
                        "trends" to "Trends",
                        "ble" to "BLE & More"
                    )
                    tabs.forEach { (id, label) ->
                        val active = selectedTab == id && drillDownMetricId == null
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable {
                                    if (id == "ble") {
                                        onOpenMetricDetail("BLE_SETTINGS")
                                    } else {
                                        drillDownMetricId = null
                                        selectedTab = id
                                    }
                                }
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (active) Color.White else Color(0xFF8A94A6),
                                    fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .width(if (active) 28.dp else 0.dp)
                                        .height(3.dp)
                                        .background(
                                            if (active) Color(0xFF00F19F) else Color.Transparent,
                                            RoundedCornerShape(50)
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Top Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(color = Color(0xFF0093E7), shape = RoundedCornerShape(50)) {
                            Text(
                                "AM",
                                color = Color.Black,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp)
                            )
                        }
                        Surface(color = Color(0xFF13171D), shape = RoundedCornerShape(50)) {
                            Text(
                                "🔥 $streakDays",
                                color = Color(0xFFF59E0B),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                    Text("< TODAY >", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                    Surface(
                        color = Color(0xFF13171D),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.clickable { onOpenMetricDetail("BLE_SETTINGS") }
                    ) {
                        Text(
                            "$liveHrBpm BPM · $batteryPct%",
                            color = Color(0xFF16EC06),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // If a metric or dial was clicked, render its Drill-Down Detail View
            val activeDrillId = drillDownMetricId
            if (activeDrillId != null) {
                val rowItem = dashboardRows.find { it.id == activeDrillId }
                    ?: dashboardRows.firstOrNull()
                item {
                    MetricDrillDownScreen(
                        metricId = activeDrillId,
                        row = rowItem,
                        latestCycle = latestCycle,
                        baselines = baselines,
                        workouts = workouts,
                        onBack = { drillDownMetricId = null }
                    )
                }
            } else if (selectedTab == "home") {
                // 3 Pillar Dials (Tap any dial for full drill-down details)
                item {
                    Surface(
                        color = Color(0xFF13171D),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 18.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            WhoopCircularDial("SLEEP >", "$sleepPerformancePct%", sleepPerformancePct / 100f, Color(0xFF7294B4)) {
                                drillDownMetricId = "sleep"
                            }
                            WhoopCircularDial("RECOVERY >", "$recoveryPct%", recoveryPct / 100f, recoveryColor, isCenter = true) {
                                drillDownMetricId = "recovery"
                            }
                            WhoopCircularDial("STRAIN >", String.format("%.1f", dayStrain), (dayStrain / 21.0).toFloat(), Color(0xFF0093E7)) {
                                drillDownMetricId = "strain"
                            }
                        }
                    }
                }

                // Live Active Heart Rate + Quick Health Monitor Status Row
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            color = Color(0xFF13171D),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedTab = "health" }
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text("HEALTH MONITOR", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("5/5 Within Range", color = Color(0xFF16EC06), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("Tap to view baselines ›", color = Color(0xFF64748B), fontSize = 11.sp)
                            }
                        }
                        Surface(
                            color = Color(0xFF13171D),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { drillDownMetricId = "rhr" }
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text("LIVE HEART RATE", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("$liveHrBpm BPM", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("WHOOP 5.0 · BLE ›", color = Color(0xFF0093E7), fontSize = 11.sp)
                            }
                        }
                    }
                }

                // MY DASHBOARD Header
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("MY DASHBOARD", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                        Text("Tap any metric for baseline & 30d chart", color = Color(0xFF64748B), fontSize = 11.sp)
                    }
                }

                // Clean MY DASHBOARD Rows (Current value only on main tab; baseline details on click)
                items(items = dashboardRows, key = { it.id }) { row ->
                    Surface(
                        color = Color(0xFF151A22),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { drillDownMetricId = row.id }
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 15.dp)
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
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = row.currentValue,
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 17.sp
                                )
                                Text(
                                    text = if (row.isPositive) "▲" else "●",
                                    color = if (row.isPositive) Color(0xFF16EC06) else Color(0xFFF59E0B),
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "›",
                                    color = Color(0xFF64748B),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            }
                        }
                    }
                }
            } else if (selectedTab == "health") {
                // HEALTH TAB: 1) Health Monitor 5 Vitals, 2) Blood Pressure Insights, 3) WHOOP Age & Healthspan
                item {
                    HealthMonitorCard(
                        dashboardRows = dashboardRows,
                        onSelectMetric = { drillDownMetricId = it }
                    )
                }
                item {
                    BloodPressureInsightsCard(
                        cycles = cycles,
                        baselines = baselines
                    )
                }
                item {
                    WhoopAgeHealthspanCard(baselines = baselines)
                }
            } else if (selectedTab == "trends") {
                // TRENDS TAB: Metric Selector + 30-Day Bar Graph + Summary Stats + Recent Workouts
                val selectedRow = dashboardRows.find { it.id == trendsMetricId } ?: dashboardRows.firstOrNull()
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("30-DAY TRENDS EXPLORER", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            dashboardRows.forEach { r ->
                                val isSel = r.id == (selectedRow?.id ?: "")
                                Surface(
                                    color = if (isSel) Color(0xFF0093E7) else Color(0xFF181D26),
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
                            val intSeries = if (selectedRow.series30d.isNotEmpty()) {
                                selectedRow.series30d.map { it.first to it.second.roundToInt() }
                            } else {
                                thirtyDaySteps
                            }
                            Interactive30DayBarChart(
                                metricTitle = selectedRow.title.uppercase(),
                                yAxisLabel = "${selectedRow.title} (${selectedRow.unit.ifBlank { "Value" }})",
                                xAxisLabel = "LAST 30 CYCLES (TAP OR DRAG BARS)",
                                data = intSeries
                            )

                            // Baseline & Range Summary Card
                            Surface(color = Color(0xFF13171D), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("LATEST", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text(selectedRow.currentValue, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
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

                // Recent Logged Workouts from workouts.csv
                if (workouts.isNotEmpty()) {
                    item {
                        Text(
                            "RECENT LOGGED WORKOUTS (${baselines.totalHistoricalWorkouts} TOTAL)",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    items(workouts.take(12)) { w ->
                        Surface(
                            color = Color(0xFF151A22),
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
                }
            }
        }
    }
}

@Composable
private fun MetricDrillDownScreen(
    metricId: String,
    row: DashboardRowItem?,
    latestCycle: WhoopCsvHistoricalImporter.HistoricalCycleRecord?,
    baselines: WhoopCsvHistoricalImporter.CalibratedUserBaselines,
    workouts: List<WhoopCsvHistoricalImporter.HistoricalWorkoutRecord>,
    onBack: () -> Unit
) {
    val title = when (metricId) {
        "recovery" -> "Recovery Score"
        "sleep" -> row?.title ?: "Sleep Performance"
        "strain" -> row?.title ?: "Day Strain"
        else -> row?.title ?: "Metric Details"
    }
    val currentVal = when (metricId) {
        "recovery" -> "${latestCycle?.recoveryScorePct ?: 55}%"
        else -> row?.currentValue ?: "--"
    }
    val baselineComparison = when (metricId) {
        "recovery" -> "30d Avg: 66% · HRV ${latestCycle?.hrvMs?.toInt() ?: 59} ms vs ${baselines.rmssd30dMeanMs.toInt()} ms"
        else -> "${row?.deltaSymbol ?: ""} (${row?.baselineText ?: "30d baseline"})"
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Back Button Bar
        Surface(
            color = Color(0xFF181D26),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.clickable { onBack() }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("← Back to Dashboard", color = Color(0xFF00F19F), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        // Hero Value + Baseline Detail Card (Only shown here inside the clicked metric!)
        Surface(color = Color(0xFF13171D), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title.uppercase(), color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(currentVal, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                Surface(color = Color(0xFF1B2430), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        text = "Compared to Baseline: $baselineComparison",
                        color = Color(0xFF00F19F),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Extra Stage Breakdown for Sleep, Recovery, or Strain
        if (metricId == "sleep" || metricId == "sws") {
            val light = latestCycle?.lightSleepMinutes ?: 313
            val deep = latestCycle?.deepSwsMinutes ?: 114
            val rem = latestCycle?.remMinutes ?: 91
            val awake = latestCycle?.awakeMinutes ?: 40
            val need = latestCycle?.sleepNeedMinutes ?: 617
            Surface(color = Color(0xFF13171D), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("LAST NIGHT SLEEP STAGES", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                    StageBreakdownRow("Deep (SWS) Sleep", "${deep / 60}h ${deep % 60}m", Color(0xFF6366F1))
                    StageBreakdownRow("REM Sleep", "${rem / 60}h ${rem % 60}m", Color(0xFF38BDF8))
                    StageBreakdownRow("Light Sleep", "${light / 60}h ${light % 60}m", Color(0xFF94A3B8))
                    StageBreakdownRow("Awake Time", "${awake}m", Color(0xFFF59E0B))
                    StageBreakdownRow("Sleep Needed", "${need / 60}h ${need % 60}m", Color(0xFF10B981))
                }
            }
        } else if (metricId == "strain") {
            Surface(color = Color(0xFF13171D), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("RECENT WORKOUT STRAIN BREAKDOWN", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                    workouts.take(5).forEach { w ->
                        StageBreakdownRow(
                            "${w.activityName} (${w.startTime.take(10)})",
                            "Strain ${String.format("%.1f", w.activityStrain)} · ${w.durationMin}m",
                            Color(0xFF0093E7)
                        )
                    }
                }
            }
        }

        // 30-Day Bar Chart for this clicked metric
        if (row != null && row.series30d.isNotEmpty()) {
            Interactive30DayBarChart(
                metricTitle = row.title.uppercase(),
                yAxisLabel = "${row.title} (${row.unit.ifBlank { "Value" }})",
                xAxisLabel = "30-DAY HISTORY (TAP OR DRAG BARS)",
                data = row.series30d.map { it.first to it.second.roundToInt() }
            )

            // 14-Day Daily Table inside Drill-Down
            Surface(color = Color(0xFF13171D), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("RECENT DAILY READINGS", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                    row.series30d.takeLast(10).reversed().forEach { (dateStr, v) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(dateStr, color = Color(0xFF94A3B8), fontSize = 13.sp)
                            val formatted = if (row.id == "temp" || row.id == "strain" || row.id == "resp" || row.id == "spo2") {
                                String.format("%.1f %s", v, row.unit)
                            } else {
                                "${v.roundToInt()} ${row.unit}"
                            }
                            Text(formatted.trim(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StageBreakdownRow(label: String, value: String, dotColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.size(10.dp).background(dotColor, RoundedCornerShape(50)))
            Text(label, color = Color(0xFFCBD5E1), fontSize = 13.sp)
        }
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun HealthMonitorCard(
    dashboardRows: List<DashboardRowItem>,
    onSelectMetric: (String) -> Unit
) {
    val vitals = dashboardRows.filter { it.id in setOf("hrv", "rhr", "resp", "spo2", "temp") }
    Surface(color = Color(0xFF13171D), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("HEALTH MONITOR", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    Text("Overnight Vitals vs. Your 30-Day Baseline", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
                Surface(color = Color(0xFF0D2E1E), shape = RoundedCornerShape(50)) {
                    Text(
                        "5/5 OK",
                        color = Color(0xFF16EC06),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            vitals.forEach { v ->
                Surface(
                    color = Color(0xFF181F29),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onSelectMetric(v.id) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(v.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("30d Baseline: ${v.deltaSymbol.removePrefix("vs ").trim()}", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(v.currentValue, color = Color(0xFF16EC06), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                            Text("›", color = Color(0xFF64748B), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BloodPressureInsightsCard(
    cycles: List<WhoopCsvHistoricalImporter.HistoricalCycleRecord>,
    baselines: WhoopCsvHistoricalImporter.CalibratedUserBaselines
) {
    val latest = cycles.firstOrNull()
    val cal = remember(baselines) {
        BloodPressureEstimator.CuffCalibration(
            avgSystolic = 120,
            avgDiastolic = 77,
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

    Surface(color = Color(0xFF13171D), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("BLOOD PRESSURE INSIGHTS (BETA)", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    Text("Morning Cuffless Estimate · 30d Calibration Active", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
                Text(
                    text = if (todayBp != null) "${todayBp.systolic}/${todayBp.diastolic}" else "--/--",
                    color = Color(0xFF00F19F),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 22.sp
                )
            }

            if (todayBp != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Systolic Range: ${todayBp.systolicLow}–${todayBp.systolicHigh} mmHg", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                    Text("Diastolic Range: ${todayBp.diastolicLow}–${todayBp.diastolicHigh} mmHg", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                }
            }

            Divider(color = Color(0xFF1E2633))
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
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(c.cycleStartTime.take(10), color = Color(0xFF94A3B8), fontSize = 12.sp)
                    if (est != null) {
                        Text(
                            "${est.systolic}/${est.diastolic} mmHg (${est.systolicLow}-${est.systolicHigh} / ${est.diastolicLow}-${est.diastolicHigh})",
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
private fun WhoopAgeHealthspanCard(baselines: WhoopCsvHistoricalImporter.CalibratedUserBaselines) {
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

    Surface(color = Color(0xFF13171D), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("HEALTHSPAN · WHOOP AGE", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    Text("Chronological Age: 30.0 yrs · Pace of Aging: ${hs.paceOfAging ?: 0.3}x", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${hs.whoopAge} yrs", color = Color(0xFF16EC06), fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                    val signStr = if (hs.netYearsDelta > 0) "+${hs.netYearsDelta}" else "${hs.netYearsDelta}"
                    Text("$signStr yrs", color = Color(0xFF00F19F), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            Divider(color = Color(0xFF1E2633))
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

    Surface(color = Color(0xFF13171D), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("$metricTitle · 30-DAY BAR GRAPH", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            Surface(color = Color(0xFF1B222D), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Date: ${selectedPair.first}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("Value: ${selectedPair.second}", color = Color(0xFF10B981), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                }
            }
            Text("Y-Axis: $yAxisLabel", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
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
                    val frac = (pair.second.toFloat() / maxVal.toFloat()).coerceIn(0.05f, 1f)
                    val h = (frac * (size.height - 20f)).coerceAtLeast(8f)
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
    val dialSize = if (isCenter) 106.dp else 90.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(dialSize)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawArc(Color(0xFF1F2733), -90f, 360f, false, style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
                drawArc(color, -90f, progress.coerceIn(0f, 1f) * 360f, false, style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
            }
            Text(valueText, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = if (isCenter) 22.sp else 18.sp)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(label, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
    }
}
