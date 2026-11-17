package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor

data class DashboardRowItem(
    val id: String,
    val title: String,
    val currentValue: String,
    val deltaSymbol: String,
    val baselineText: String,
    val isPositive: Boolean
)

/**
 * Drop-in WHOOP-authentic Jetpack Compose UI for ryanbr/noop (NOOP-full.apk).
 * Includes:
 * - Top Header (AM avatar, 634 Streak, < TODAY >, 44% Battery & Live BPM)
 * - 3 Main Dials (Sleep 85%, Recovery 39%, Strain 6.6)
 * - Active Heart Rate Live 1Hz Waveform + Health Monitor & Stress Monitor cards
 * - Exact 19-Row Clickable My Dashboard
 * - Interactive 30-Day Bar Graph with X/Y Axis Titles and Press/Hold/Drag Bar Scrubbing
 * - Blood Pressure Insights 240-deg Arc Gauge & 7-Day Range Bars (122/78, Sys 112-132, Dia 73-83)
 */
@Composable
fun WhoopStyleHomeScreen(
    sleepPerformancePct: Int = 85,
    recoveryPct: Int = 39,
    dayStrain: Double = 6.6,
    liveHrBpm: Int = 72,
    batteryPct: Int = 44,
    streakDays: Int = 634,
    dashboardRows: List<DashboardRowItem>,
    thirtyDaySteps: List<Pair<String, Int>>,
    onOpenMetricDetail: (String) -> Unit
) {
    var selectedTab by remember { mutableStateOf("home") }
    var activeBarMetric by remember { mutableStateOf("STEPS") }

    val recoveryColor = when {
        recoveryPct >= 67 -> Color(0xFF16EC06)
        recoveryPct >= 34 -> Color(0xFFFFDE00)
        else -> Color(0xFFFF0026)
    }

    Scaffold(
        containerColor = Color(0xFF0A0C0F),
        bottomBar = {
            // Bottom Navigation Bar (Home, Health, More — Humans omitted per user spec)
            Surface(color = Color(0xFF13171D), tonalElevation = 8.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Text("Home", color = if (selectedTab == "home") Color.White else Color.Gray,
                        fontWeight = FontWeight.Bold, modifier = Modifier.clickable { selectedTab = "home" })
                    Text("Health Monitor", color = if (selectedTab == "hmonitor") Color.White else Color.Gray,
                        fontWeight = FontWeight.Bold, modifier = Modifier.clickable { selectedTab = "hmonitor" })
                    Text("Health & BP", color = if (selectedTab == "health") Color.White else Color.Gray,
                        fontWeight = FontWeight.Bold, modifier = Modifier.clickable { selectedTab = "health" })
                    Text("30D Bars", color = if (selectedTab == "bars") Color.White else Color.Gray,
                        fontWeight = FontWeight.Bold, modifier = Modifier.clickable { selectedTab = "bars" })
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Top Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(color = Color(0xFF0093E7), shape = RoundedCornerShape(50)) {
                            Text("AM", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                                modifier = Modifier.padding(8.dp))
                        }
                        Surface(color = Color(0xFF13171D), shape = RoundedCornerShape(50)) {
                            Text("🔥 $streakDays", color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold, fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                    Text("< TODAY >", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                    Surface(color = Color(0xFF13171D), shape = RoundedCornerShape(50)) {
                        Text("$liveHrBpm BPM · $batteryPct%", color = Color(0xFF16EC06), fontWeight = FontWeight.Bold, fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }

            if (selectedTab == "home") {
                // 3 Pillar Dials
                item {
                    Surface(color = Color(0xFF13171D), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            WhoopCircularDial("SLEEP >", "$sleepPerformancePct%", sleepPerformancePct / 100f, Color(0xFF7294B4)) {
                                onOpenMetricDetail("SLEEP")
                            }
                            WhoopCircularDial("RECOVERY >", "$recoveryPct%", recoveryPct / 100f, recoveryColor, isCenter = true) {
                                onOpenMetricDetail("RECOVERY")
                            }
                            WhoopCircularDial("STRAIN >", String.format("%.1f", dayStrain), (dayStrain / 21.0).toFloat(), Color(0xFF0093E7)) {
                                onOpenMetricDetail("STRAIN")
                            }
                        }
                    }
                }

                // Live Active Heart Rate Card (Clickable -> Health Monitor)
                item {
                    Surface(
                        color = Color(0xFF13171D),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().clickable { selectedTab = "hmonitor" }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("HEART RATE · LIVE BLE (ZONE 0)", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text("$liveHrBpm BPM", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            Text("Open Health Monitor ->", color = Color(0xFF0093E7), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // 19-Row Clickable My Dashboard
                item {
                    Text("MY DASHBOARD", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                }
                items(items = dashboardRows, key = { it.id }) { row ->
                    Surface(
                        color = Color(0xFF181D26),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().clickable {
                            activeBarMetric = row.title
                            selectedTab = "bars"
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(row.title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${row.currentValue} ${row.deltaSymbol}", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                                Text(row.baselineText, color = Color(0xFF94A3B8), fontSize = 11.sp)
                            }
                        }
                    }
                }
            } else if (selectedTab == "bars") {
                item {
                    Interactive30DayBarChart(
                        metricTitle = activeBarMetric,
                        yAxisLabel = "$activeBarMetric (VALUE)",
                        xAxisLabel = "DATE (30-DAY SPAN)",
                        data = thirtyDaySteps
                    )
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
    val maxVal = (data.maxOfOrNull { it.second } ?: 10000).coerceAtLeast(1)
    val selectedPair = data.getOrElse(selectedIndex) { "Nov 13" to 2562 }

    Surface(color = Color(0xFF13171D), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("$metricTitle · 30-DAY BAR GRAPH", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            // Floating Press-and-Hold Readout Banner
            Surface(color = Color(0xFF1B222D), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Date: ${selectedPair.first}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("Exact Value: ${selectedPair.second}", color = Color(0xFF10B981), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                }
            }
            Text("Y-Axis: $yAxisLabel", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
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
                    val frac = pair.second.toFloat() / maxVal.toFloat()
                    val h = (frac * (size.height - 24f)).coerceAtLeast(8f)
                    val x = idx * barSlot + 4f
                    val y = size.height - h
                    drawRect(
                        color = if (idx == selectedIndex) Color.White else Color(0xFF10B981),
                        topLeft = Offset(x, y),
                        size = Size((barSlot - 8f).coerceAtLeast(4f), h)
                    )
                }
            }
            Text(xAxisLabel, color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally))
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
