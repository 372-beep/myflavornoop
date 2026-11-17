
package com.noop.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noop.BuildConfig
import com.noop.CrashCapture
import com.noop.NoopApplication
import com.noop.R
import com.noop.analytics.WhoopCsvHistoricalImporter
import com.noop.ble.WhoopModel
import com.noop.data.DemoSeeder
import com.noop.data.WhoopRepository
import com.noop.push.SelfHostedPushScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single-activity host. Requests the runtime BLE permissions the strap connection
 * needs, then renders the Compose tree under [NoopTheme]. The design system is
 * dark-only, so we draw edge-to-edge over the near-black [Palette.surfaceBase].
 */
class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguagePrefs.wrap(newBase))
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Permission results flow back into the BLE client's own runtime checks;
            // the UI simply reflects connection state. No blocking here.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        CrashCapture.pendingCrash(this)?.let { crash ->
            setContent {
                NoopTheme {
                    CrashRecoveryScreen(
                        crash = crash,
                        onContinue = {
                            CrashCapture.acknowledge(this, crash)
                            recreate()
                        },
                    )
                }
            }
            return
        }
        CardAppearance.init(this)

        if (BuildConfig.ENABLE_DEMO) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { DemoSeeder.seedIfEmpty(WhoopRepository.from(applicationContext)) }
                runCatching {
                    DemoSeeder.seedDemoDeviceIfNeeded((application as NoopApplication).deviceRegistry)
                }
            }
        }

        if (NoopPrefs.of(this).getBoolean(NoopPrefs.KEY_ONBOARDED, false)) {
            requestBlePermissions()
        }

        runCatching { DebugExportScheduler.reschedule(applicationContext) }
        runCatching { CoachBriefScheduler.reschedule(applicationContext) }
        runCatching { BackupSync.reschedule(applicationContext) }
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { BackupSync.catchUpIfDue(applicationContext) }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { SelfHostedPushScheduler.enqueueLaunchCatchUp(applicationContext) }
        }

        AppearancePrefs.load(this)
        ChartStylePrefs.load(this)
        AccentPrefs.load(this)
        ProfileAvatarStore.load(this)
        BackgroundImageStore.load(this)
        BottomBarStyleStore.load(this)

        setContent {
            NoopTheme {
                NoopRoot()
            }
        }
    }

    /** Request the BLE permissions appropriate to the running OS version. */
    private fun requestBlePermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

        if (needed.isNotEmpty()) permissionLauncher.launch(needed)
    }
}

@Composable
private fun CrashRecoveryScreen(crash: String, onContinue: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val shown = remember(crash) { com.noop.ble.redactStrapLogPii(crash) }
    Surface(Modifier.fillMaxSize(), color = Palette.surfaceBase) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 32.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.crash_recovery_title), style = NoopType.title1, color = Palette.textPrimary)
            Text(stringResource(R.string.crash_recovery_body), style = NoopType.body, color = Palette.textSecondary)
            Button(onClick = { clipboard.setText(AnnotatedString(shown)) }) {
                Text(stringResource(R.string.crash_recovery_copy))
            }
            Button(onClick = onContinue) {
                Text(stringResource(R.string.crash_recovery_continue))
            }
            SelectionContainer {
                Text(shown, style = NoopType.caption, color = Palette.textSecondary)
            }
        }
    }
}

internal fun appLaunchIntent(context: Context): Intent =
    context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?.apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
        }
        ?: Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
        }

object NoopPrefs {
    const val NAME = "noop_prefs"
    const val KEY_ONBOARDED = "noop.onboarded"
    const val KEY_LAST_SEEN_CHANGELOG = "noop.lastSeenChangelogVersion"
    const val KEY_ACCEPTED_TERMS_VERSION = "noop.acceptedTermsVersion"
    const val KEY_ACCEPTED_TERMS_AT = "noop.acceptedTermsAt"
    const val KEY_BACKGROUND_CONNECTION = "noop.backgroundConnection"
    const val KEY_DAY_CYCLE_MODE = "noop.dayCycleMode"

    fun dayCycleMode(context: Context): com.noop.analytics.DayCycleMode =
        com.noop.analytics.DayCycleMode.fromPersisted(
            of(context).getString(KEY_DAY_CYCLE_MODE, null),
        )

    fun setDayCycleMode(context: Context, mode: com.noop.analytics.DayCycleMode) {
        of(context).edit().putString(KEY_DAY_CYCLE_MODE, mode.persistedValue).apply()
    }

    const val KEY_CONTINUOUS_HRV = "noop.continuousHrv"
    const val KEY_CONTINUOUS_HRV_OVERNIGHT = "noop.continuousHrvOvernight"
    const val KEY_SPO2_CANDIDATE_DISPLAY = "noop.spo2CandidateDisplay"
    const val KEY_STRESS_PERSONAL_BASELINE = "noop.stressPersonalBaseline"
    const val KEY_BANISTER_EFFORT = "noop.banisterEffort"

    fun banisterEffort(context: Context): Boolean = of(context).getBoolean(KEY_BANISTER_EFFORT, false)

    fun effortMethod(context: Context): com.noop.analytics.StrainScorer.Method =
        if (banisterEffort(context)) com.noop.analytics.StrainScorer.Method.BANISTER
        else com.noop.analytics.StrainScorer.Method.EDWARDS

    const val KEY_LAST_JOURNAL_PROMPT = "noop.lastJournalPromptDay"
    const val KEY_JOURNAL_REMINDER_ENABLED = "noop.journalReminder"
    const val KEY_JOURNAL_REMINDER_DISMISSED_DAY = "noop.journalReminderDismissedDay"
    const val KEY_DEBUG_LOGGING = "noop.debugLogging"
    const val KEY_HR_BROADCAST = "noop.hrBroadcast"
    const val KEY_ANALYZE_WATERMARK = "noop.analyzeWatermark"
    const val KEY_STEPS_MOTION_CACHE = "noop.stepsMotionCache.v1"
    const val KEY_POWER_SAVING = "noop.powerSaving"
    const val KEY_LOW_REFRESH = "low_refresh"
    const val KEY_POWER_SAVING_BATTERY_PCT = "noop.powerSavingBatteryPct"
    const val KEY_PAUSE_HRV_ON_POWER_SAVE = "noop.pauseHrvOnPowerSave"

    fun of(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun powerSaving(context: Context): Boolean =
        of(context).getBoolean(KEY_POWER_SAVING, false)

    fun setPowerSaving(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_POWER_SAVING, enabled).apply()
    }

    fun lowRefresh(context: Context): Boolean =
        of(context).getBoolean(KEY_LOW_REFRESH, false)

    fun setLowRefresh(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_LOW_REFRESH, enabled).apply()
    }

    fun powerSavingBatteryPct(context: Context): Int =
        of(context).getInt(KEY_POWER_SAVING_BATTERY_PCT, 20)

    fun setPowerSavingBatteryPct(context: Context, pct: Int) {
        of(context).edit().putInt(KEY_POWER_SAVING_BATTERY_PCT, pct).apply()
    }

    fun pauseHrvOnPowerSave(context: Context): Boolean =
        of(context).getBoolean(KEY_PAUSE_HRV_ON_POWER_SAVE, true)

    fun setPauseHrvOnPowerSave(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_PAUSE_HRV_ON_POWER_SAVE, enabled).apply()
    }

    const val KEY_FAST_HISTORY_SYNC = "noop.fastHistorySync"

    fun fastHistorySync(context: Context): Boolean =
        of(context).getBoolean(KEY_FAST_HISTORY_SYNC, false)

    fun setFastHistorySync(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_FAST_HISTORY_SYNC, enabled).apply()
    }

    const val KEY_IDLE_THROTTLE_BATTERY_PCT = "noop.idleThrottleBatteryPct"

    fun idleThrottleBatteryPct(context: Context): Int =
        clampIdleThrottlePct(of(context).getInt(KEY_IDLE_THROTTLE_BATTERY_PCT, 0))

    fun setIdleThrottleBatteryPct(context: Context, pct: Int) {
        of(context).edit().putInt(KEY_IDLE_THROTTLE_BATTERY_PCT, clampIdleThrottlePct(pct)).apply()
    }

    internal fun clampIdleThrottlePct(raw: Int): Int = if (raw in 10..30) raw else 0

    const val KEY_FAST_LINK_PHY = "noop.fastLinkPhy"

    fun fastLinkPhy(context: Context): Boolean =
        of(context).getBoolean(KEY_FAST_LINK_PHY, false)

    fun setFastLinkPhy(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_FAST_LINK_PHY, enabled).apply()
    }

    fun analyzeWatermark(context: Context): String? =
        of(context).getString(KEY_ANALYZE_WATERMARK, null)

    fun setAnalyzeWatermark(context: Context, fingerprint: String) {
        of(context).edit().putString(KEY_ANALYZE_WATERMARK, fingerprint).apply()
    }

    fun stepsMotionCache(context: Context): String? =
        of(context).getString(KEY_STEPS_MOTION_CACHE, null)

    fun setStepsMotionCache(context: Context, payload: String) {
        of(context).edit().putString(KEY_STEPS_MOTION_CACHE, payload).apply()
    }

    fun backgroundConnection(context: Context): Boolean =
        of(context).getBoolean(KEY_BACKGROUND_CONNECTION, true)

    fun setBackgroundConnection(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_BACKGROUND_CONNECTION, enabled).apply()
    }

    fun continuousHrv(context: Context): Boolean =
        of(context).getBoolean(KEY_CONTINUOUS_HRV, false)

    fun setContinuousHrv(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_CONTINUOUS_HRV, enabled).apply()
    }

    fun continuousHrvOvernight(context: Context): Boolean =
        of(context).getBoolean(KEY_CONTINUOUS_HRV_OVERNIGHT, true)

    fun migrateContinuousHrvOvernightDefault(context: Context) {
        val prefs = of(context)
        if (shouldPinLegacyOvernightDefault(
                hasOvernightChoice = prefs.contains(KEY_CONTINUOUS_HRV_OVERNIGHT),
                hasUsedContinuousHrv = prefs.contains(KEY_CONTINUOUS_HRV),
            )
        ) {
            prefs.edit().putBoolean(KEY_CONTINUOUS_HRV_OVERNIGHT, false).apply()
        }
    }

    internal fun shouldPinLegacyOvernightDefault(
        hasOvernightChoice: Boolean,
        hasUsedContinuousHrv: Boolean,
    ): Boolean = !hasOvernightChoice && hasUsedContinuousHrv

    fun setContinuousHrvOvernight(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_CONTINUOUS_HRV_OVERNIGHT, enabled).apply()
    }

    fun spo2CandidateDisplay(context: Context): Boolean =
        of(context).getBoolean(KEY_SPO2_CANDIDATE_DISPLAY, false)

    fun spo2CandidateDisplayFlow(context: Context): Flow<Boolean> = callbackFlow {
        val prefs = of(context.applicationContext)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == null || key == KEY_SPO2_CANDIDATE_DISPLAY) {
                trySend(changed.getBoolean(KEY_SPO2_CANDIDATE_DISPLAY, false))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(prefs.getBoolean(KEY_SPO2_CANDIDATE_DISPLAY, false))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun setSpo2CandidateDisplay(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_SPO2_CANDIDATE_DISPLAY, enabled).apply()
    }

    fun stressPersonalBaseline(context: Context): Boolean =
        of(context).getBoolean(KEY_STRESS_PERSONAL_BASELINE, false)

    fun setStressPersonalBaseline(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_STRESS_PERSONAL_BASELINE, enabled).apply()
    }

    fun setBanisterEffort(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_BANISTER_EFFORT, enabled).apply()
    }

    fun debugLogging(context: Context): Boolean =
        of(context).getBoolean(KEY_DEBUG_LOGGING, false)

    fun setDebugLogging(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_DEBUG_LOGGING, enabled).apply()
    }

    const val KEY_POLAR_DEBUG_LOGGING = "noop.polarDebugLogging"

    fun polarDebugLogging(context: Context): Boolean =
        of(context).getBoolean(KEY_POLAR_DEBUG_LOGGING, false)

    fun setPolarDebugLogging(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_POLAR_DEBUG_LOGGING, enabled).apply()
    }

    const val KEY_OURA_ONSET_KEYING = "noop.ouraOnsetKeying"

    fun ouraOnsetKeying(context: Context): Boolean =
        of(context).getBoolean(KEY_OURA_ONSET_KEYING, false)

    fun setOuraOnsetKeying(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_OURA_ONSET_KEYING, enabled).apply()
    }

    const val KEY_OURA_NOTIFY_MASK_FULL = "noop.ouraNotifyMaskFull"

    fun ouraNotifyMaskFull(context: Context): Boolean =
        of(context).getBoolean(KEY_OURA_NOTIFY_MASK_FULL, false)

    fun setOuraNotifyMaskFull(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_OURA_NOTIFY_MASK_FULL, enabled).apply()
    }

    const val KEY_DETAILED_CAPTURE = "noop.detailedCapture"
    fun detailedCapture(context: Context): Boolean =
        of(context).getBoolean(KEY_DETAILED_CAPTURE, false)

    fun setDetailedCapture(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_DETAILED_CAPTURE, enabled).apply()
    }

    fun hrBroadcast(context: Context): Boolean =
        of(context).getBoolean(KEY_HR_BROADCAST, false)

    fun setHrBroadcast(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_HR_BROADCAST, enabled).apply()
    }

    const val KEY_BUZZ_WHOOP4_WITH_ALARM = "noop.buzzWhoop4WithAlarm"
    fun buzzWhoop4WithAlarm(context: Context): Boolean =
        of(context).getBoolean(KEY_BUZZ_WHOOP4_WITH_ALARM, false)

    fun setBuzzWhoop4WithAlarm(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_BUZZ_WHOOP4_WITH_ALARM, enabled).apply()
    }

    const val KEY_APP_ICON_NAVY = "noop.appIconNavy"

    fun appIconNavy(context: Context): Boolean =
        of(context).getBoolean(KEY_APP_ICON_NAVY, false)

    fun setAppIconNavy(context: Context, navy: Boolean) {
        of(context).edit().putBoolean(KEY_APP_ICON_NAVY, navy).apply()
    }

    const val KEY_BOTTOM_BAR_AUTO_HIDE = "noop.bottomBarAutoHide"
    const val KEY_BOTTOM_BAR_OPACITY_STEP = "noop.bottomBarOpacityStep"
    const val KEY_BOTTOM_BAR_SCALE = "noop.bottomBarScale"
    const val KEY_OVERLAY_BOTTOM_BAR = "noop.overlayBottomBar"
    const val KEY_CLOCK_FORMAT = com.noop.analytics.ClockFormatPreference.PREFS_KEY
    const val KEY_UNIT_SYSTEM = "units.system"
    const val KEY_DISTANCE_UNIT_SYSTEM = "units.distance"
    const val KEY_TEMPERATURE_UNIT = "units.temperature"
    const val KEY_SKIN_TEMP_DISPLAY = "units.skinTempDisplay"

    fun setUnitSystem(context: Context, system: UnitSystem) {
        of(context).edit().putString(KEY_UNIT_SYSTEM, system.raw).apply()
    }

    fun setDistanceUnitSystem(context: Context, system: UnitSystem) {
        of(context).edit().putString(KEY_DISTANCE_UNIT_SYSTEM, system.raw).apply()
    }

    fun setSkinTempDisplay(context: Context, kind: com.noop.analytics.SkinTempDisplay.Kind?) {
        of(context).edit().apply {
            if (kind == null || kind == com.noop.analytics.SkinTempDisplay.Kind.ABSOLUTE) {
                remove(KEY_SKIN_TEMP_DISPLAY)
            } else {
                putString(KEY_SKIN_TEMP_DISPLAY, kind.raw)
            }
        }.apply()
    }

    fun setTemperatureUnit(context: Context, unit: TemperatureUnit?) {
        of(context).edit().apply {
            if (unit == null) remove(KEY_TEMPERATURE_UNIT) else putString(KEY_TEMPERATURE_UNIT, unit.raw)
        }.apply()
    }

    const val KEY_HC_AUTO_SYNC = "noop.hcAutoSync"
    const val KEY_HC_SYNC_HOURS = "noop.hcSyncHours"
    const val KEY_HC_LAST_SYNC = "noop.hcLastSync"

    fun hcAutoSync(context: Context): Boolean =
        of(context).getBoolean(KEY_HC_AUTO_SYNC, false)

    fun setHcAutoSync(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_HC_AUTO_SYNC, enabled).apply()
    }

    fun hcSyncHours(context: Context): Int =
        of(context).getInt(KEY_HC_SYNC_HOURS, 12)

    fun setHcSyncHours(context: Context, hours: Int) {
        of(context).edit().putInt(KEY_HC_SYNC_HOURS, hours).apply()
    }

    fun hcLastSync(context: Context): Long =
        of(context).getLong(KEY_HC_LAST_SYNC, 0L)

    fun setHcLastSync(context: Context, epochMs: Long) {
        of(context).edit().putLong(KEY_HC_LAST_SYNC, epochMs).apply()
    }

    const val KEY_HC_WRITEBACK = "noop.hcWriteback"
    const val KEY_HC_VO2MAX_ASKED = "noop.hcVo2MaxAsked"

    fun hcVo2MaxAsked(context: Context): Boolean = of(context).getBoolean(KEY_HC_VO2MAX_ASKED, false)

    fun setHcVo2MaxAsked(context: Context, asked: Boolean) {
        of(context).edit().putBoolean(KEY_HC_VO2MAX_ASKED, asked).apply()
    }

    fun hcWriteback(context: Context): Boolean =
        of(context).getBoolean(KEY_HC_WRITEBACK, false)

    fun setHcWriteback(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_HC_WRITEBACK, enabled).apply()
    }

    const val KEY_HC_WB_STATUS = "noop.hcWritebackStatus"
    const val KEY_HC_WB_AT = "noop.hcWritebackAtMs"
    const val KEY_HC_WB_WRITTEN = "noop.hcWritebackWritten"
    const val HC_WB_OK = "OK"
    const val HC_WB_PERMISSION_DENIED = "PERMISSION_DENIED"
    const val HC_WB_REMOTE_ERROR = "REMOTE_ERROR"

    fun hcWritebackStatus(context: Context): String = of(context).getString(KEY_HC_WB_STATUS, "") ?: ""
    fun hcWritebackAt(context: Context): Long = of(context).getLong(KEY_HC_WB_AT, 0L)
    fun hcWritebackWritten(context: Context): Int = of(context).getInt(KEY_HC_WB_WRITTEN, 0)
    fun setHcWritebackStatus(context: Context, code: String, written: Int, atMs: Long) {
        of(context).edit()
            .putString(KEY_HC_WB_STATUS, code)
            .putInt(KEY_HC_WB_WRITTEN, written)
            .putLong(KEY_HC_WB_AT, atMs)
            .apply()
    }

    const val KEY_HC_HR_FRONTIER = "noop.hcHrFrontierTs"

    fun hcHrFrontier(context: Context): Long =
        of(context).getLong(KEY_HC_HR_FRONTIER, 0L)

    fun setHcHrFrontier(context: Context, tsSec: Long) {
        of(context).edit().putLong(KEY_HC_HR_FRONTIER, tsSec).apply()
    }

    const val KEY_SMART_ALARM = "noop.smartAlarmEnabled"
    const val KEY_SMART_ALARM_MINUTES = "noop.smartAlarmMinutes"

    fun smartAlarmEnabled(context: Context): Boolean =
        of(context).getBoolean(KEY_SMART_ALARM, false)

    fun setSmartAlarmEnabled(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_SMART_ALARM, enabled).apply()
    }

    fun smartAlarmMinutes(context: Context): Int =
        of(context).getInt(KEY_SMART_ALARM_MINUTES, 7 * 60)

    fun setSmartAlarmMinutes(context: Context, minutes: Int) {
        of(context).edit().putInt(KEY_SMART_ALARM_MINUTES, minutes).apply()
    }

    const val KEY_SMART_ALARM_WEEKDAYS = "noop.smartAlarmWeekdays"

    fun smartAlarmWeekdays(context: Context): Set<Int> =
        of(context).getStringSet(KEY_SMART_ALARM_WEEKDAYS, emptySet())
            ?.mapNotNull { it.toIntOrNull() }?.filter { it in 1..7 }?.toSet() ?: emptySet()

    fun setSmartAlarmWeekdays(context: Context, days: Set<Int>) {
        val clean = days.filter { it in 1..7 }.map { it.toString() }.toSet()
        of(context).edit().putStringSet(KEY_SMART_ALARM_WEEKDAYS, clean).apply()
    }

    const val KEY_SMART_ALARM_OVERRIDES = "noop.smartAlarmDayOverrides"

    fun smartAlarmDayOverrides(context: Context): Map<Int, Int> =
        of(context).getStringSet(KEY_SMART_ALARM_OVERRIDES, emptySet())
            ?.mapNotNull { entry ->
                val parts = entry.split(":")
                if (parts.size != 2) return@mapNotNull null
                val dow = parts[0].toIntOrNull() ?: return@mapNotNull null
                val min = parts[1].toIntOrNull() ?: return@mapNotNull null
                if (dow !in 1..7 || min !in 0 until 24 * 60) return@mapNotNull null
                dow to min
            }?.toMap() ?: emptyMap()

    fun setSmartAlarmDayOverrides(context: Context, overrides: Map<Int, Int>) {
        val clean = overrides
            .filterKeys { it in 1..7 }
            .filterValues { it in 0 until 24 * 60 }
            .map { (dow, min) -> "$dow:$min" }
            .toSet()
        of(context).edit().putStringSet(KEY_SMART_ALARM_OVERRIDES, clean).apply()
    }

    const val KEY_ZONE_COACHING = "noop.zoneCoaching"
    const val KEY_ZONE_COACH_RECOVERY = "noop.zoneCoachRecovery"

    fun zoneCoaching(context: Context): Boolean =
        of(context).getBoolean(KEY_ZONE_COACHING, false)

    fun setZoneCoaching(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_ZONE_COACHING, enabled).apply()
    }

    fun zoneCoachRecovery(context: Context): Boolean =
        of(context).getBoolean(KEY_ZONE_COACH_RECOVERY, true)

    fun setZoneCoachRecovery(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_ZONE_COACH_RECOVERY, enabled).apply()
    }

    const val KEY_ILLNESS_WATCH = "noop.illnessWatch"

    fun illnessWatch(context: Context): Boolean =
        of(context).getBoolean(KEY_ILLNESS_WATCH, true)

    fun setIllnessWatch(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_ILLNESS_WATCH, enabled).apply()
    }

    const val KEY_CYCLE_TRACKING = "noop.cycleTracking"

    fun cycleTracking(context: Context): Boolean =
        of(context).getBoolean(KEY_CYCLE_TRACKING, false)

    fun setCycleTracking(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_CYCLE_TRACKING, enabled).apply()
    }

    const val KEY_CYCLE_AWARENESS_HIDDEN = "noop.cycleAwarenessHidden"

    fun cycleAwarenessHidden(context: Context): Boolean =
        of(context).getBoolean(KEY_CYCLE_AWARENESS_HIDDEN, false)

    fun setCycleAwarenessHidden(context: Context, hidden: Boolean) {
        of(context).edit().putBoolean(KEY_CYCLE_AWARENESS_HIDDEN, hidden).apply()
    }

    const val KEY_HYDRATION_TRACKING = "noop.hydrationTracking"

    fun hydrationTracking(context: Context): Boolean =
        of(context).getBoolean(KEY_HYDRATION_TRACKING, false)

    fun setHydrationTracking(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_HYDRATION_TRACKING, enabled).apply()
    }

    const val KEY_SHOW_DAY_CYCLE_BACKGROUND = "noop.showDayCycleBackground"

    fun showDayCycleBackground(context: Context): Boolean =
        of(context).getBoolean(KEY_SHOW_DAY_CYCLE_BACKGROUND, true)

    fun setShowDayCycleBackground(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_SHOW_DAY_CYCLE_BACKGROUND, enabled).apply()
    }

    const val KEY_CARD_OPACITY = "noop.cardOpacityPercent"

    fun cardOpacityPercent(context: Context): Int =
        of(context).getInt(KEY_CARD_OPACITY, 100).coerceIn(0, 100)

    fun setCardOpacityPercent(context: Context, percent: Int) {
        of(context).edit().putInt(KEY_CARD_OPACITY, percent.coerceIn(0, 100)).apply()
    }

    const val KEY_SKY_BEHIND_CARDS = "noop.skyBehindCards"

    fun skyBehindCards(context: Context): Boolean =
        of(context).getBoolean(KEY_SKY_BEHIND_CARDS, true)

    fun setSkyBehindCards(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_SKY_BEHIND_CARDS, enabled).apply()
    }

    const val KEY_BACKGROUND_IMAGE_ENABLED = "noop.backgroundImageEnabled"

    fun backgroundImageEnabled(context: Context): Boolean =
        of(context).getBoolean(KEY_BACKGROUND_IMAGE_ENABLED, false)

    fun setBackgroundImageEnabled(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_BACKGROUND_IMAGE_ENABLED, enabled).apply()
    }

    const val KEY_BACKGROUND_FILL_MODE = "noop.backgroundFillMode"

    fun backgroundFillMode(context: Context): BackgroundFillMode =
        BackgroundFillMode.fromStorage(of(context).getString(KEY_BACKGROUND_FILL_MODE, null))

    fun setBackgroundFillMode(context: Context, mode: BackgroundFillMode) {
        of(context).edit().putString(KEY_BACKGROUND_FILL_MODE, mode.storageValue).apply()
    }

    const val KEY_BACKGROUND_IMAGE_PRESENT = "noop.backgroundImagePresent"

    fun backgroundImagePresent(context: Context): Boolean =
        of(context).getBoolean(KEY_BACKGROUND_IMAGE_PRESENT, false)

    fun setBackgroundImagePresent(context: Context, present: Boolean) {
        of(context).edit().putBoolean(KEY_BACKGROUND_IMAGE_PRESENT, present).apply()
    }

    const val KEY_BACKGROUND_RECENTS = "noop.backgroundRecents"

    fun backgroundRecents(context: Context): String =
        of(context).getString(KEY_BACKGROUND_RECENTS, "") ?: ""

    fun setBackgroundRecents(context: Context, value: String) {
        of(context).edit().putString(KEY_BACKGROUND_RECENTS, value).apply()
    }

    const val KEY_QUIET_MOTION = "noop.quietMotion"

    fun quietMotion(context: Context): Boolean =
        of(context).getBoolean(KEY_QUIET_MOTION, false)

    fun setQuietMotion(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_QUIET_MOTION, enabled).apply()
    }

    const val KEY_TODAY_RING_GAUGES = "noop.todayRingGauges"

    fun todayRingGauges(context: Context): Boolean =
        of(context).getBoolean(KEY_TODAY_RING_GAUGES, true)

    fun setTodayRingGauges(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_TODAY_RING_GAUGES, enabled).apply()
    }

    fun coachModelsRefreshedAt(context: Context, provider: String): Long =
        of(context).getLong("noop.coachModelsRefreshed.$provider", 0L)

    fun setCoachModelsRefreshedAt(context: Context, provider: String, atMillis: Long) {
        of(context).edit().putLong("noop.coachModelsRefreshed.$provider", atMillis).apply()
    }

    const val KEY_COACH_ENABLED = "noop.coachEnabled"

    fun coachEnabled(context: Context): Boolean =
        of(context).getBoolean(KEY_COACH_ENABLED, true)

    fun setCoachEnabled(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_COACH_ENABLED, enabled).apply()
    }

    const val KEY_COACH_SIGNALS = "noop.coachSignals"

    fun coachSignals(context: Context): Boolean =
        of(context).getBoolean(KEY_COACH_SIGNALS, false)

    fun setCoachSignals(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_COACH_SIGNALS, enabled).apply()
    }

    const val KEY_COACH_MULTIMODAL = "noop.coachMultimodal"

    fun coachMultimodal(context: Context): Boolean =
        of(context).getBoolean(KEY_COACH_MULTIMODAL, false)

    fun setCoachMultimodal(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_COACH_MULTIMODAL, enabled).apply()
    }

    const val KEY_COACH_SYSTEM_PROMPT = "noop.coachSystemPrompt"

    fun coachSystemPrompt(context: Context): String =
        of(context).getString(KEY_COACH_SYSTEM_PROMPT, "").orEmpty()

    fun setCoachSystemPrompt(context: Context, prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) of(context).edit().remove(KEY_COACH_SYSTEM_PROMPT).apply()
        else of(context).edit().putString(KEY_COACH_SYSTEM_PROMPT, prompt).apply()
    }

    const val KEY_AUTO_DETECT_WORKOUTS = "noop.autoDetectWorkouts"

    fun autoDetectWorkouts(context: Context): Boolean =
        of(context).getBoolean(KEY_AUTO_DETECT_WORKOUTS, false)

    fun setAutoDetectWorkouts(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_AUTO_DETECT_WORKOUTS, enabled).apply()
    }

    fun journalReminderEnabled(context: Context): Boolean =
        of(context).getBoolean(KEY_JOURNAL_REMINDER_ENABLED, true)

    fun setJournalReminderEnabled(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_JOURNAL_REMINDER_ENABLED, enabled).apply()
    }

    const val KEY_ILLNESS_LAST_NOTIFIED_DAY = "noop.illnessLastNotifiedDay"

    fun illnessLastNotifiedDay(context: Context): String? =
        of(context).getString(KEY_ILLNESS_LAST_NOTIFIED_DAY, null)

    fun setIllnessLastNotifiedDay(context: Context, day: String) {
        of(context).edit().putString(KEY_ILLNESS_LAST_NOTIFIED_DAY, day).apply()
    }

    const val KEY_ILLNESS_WAS_RAISED = "noop.illnessWasRaised"

    fun illnessWasRaised(context: Context): Boolean? {
        val prefs = of(context)
        return if (prefs.contains(KEY_ILLNESS_WAS_RAISED))
            prefs.getBoolean(KEY_ILLNESS_WAS_RAISED, false) else null
    }

    fun setIllnessWasRaised(context: Context, raised: Boolean) {
        of(context).edit().putBoolean(KEY_ILLNESS_WAS_RAISED, raised).apply()
    }

    const val KEY_BATTERY_ALERTS = "noop.batteryAlerts"

    fun batteryAlerts(context: Context): Boolean =
        of(context).getBoolean(KEY_BATTERY_ALERTS, true)

    fun setBatteryAlerts(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_BATTERY_ALERTS, enabled).apply()
    }

    const val KEY_BATTERY_PREDICTIVE_ALERTS = "noop.batteryPredictiveAlerts"

    fun predictiveBatteryAlerts(context: Context): Boolean =
        of(context).getBoolean(KEY_BATTERY_PREDICTIVE_ALERTS, true)

    fun setPredictiveBatteryAlerts(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_BATTERY_PREDICTIVE_ALERTS, enabled).apply()
    }

    const val KEY_BATTERY_LOW_ALERTED = "noop.batteryLowAlerted"
    const val KEY_BATTERY_FULL_ALERTED = "noop.batteryFullAlerted"

    fun batteryLowAlerted(context: Context): Boolean =
        of(context).getBoolean(KEY_BATTERY_LOW_ALERTED, false)

    fun setBatteryLowAlerted(context: Context, alerted: Boolean) {
        of(context).edit().putBoolean(KEY_BATTERY_LOW_ALERTED, alerted).apply()
    }

    const val KEY_BATTERY_STALE_ALERTED_TS = "noop.batteryStaleAlertedTs"

    fun batteryStaleAlertedTs(context: Context): Long? =
        of(context).getLong(KEY_BATTERY_STALE_ALERTED_TS, 0L).takeIf { it > 0L }

    fun setBatteryStaleAlertedTs(context: Context, ts: Long) {
        of(context).edit().putLong(KEY_BATTERY_STALE_ALERTED_TS, ts).apply()
    }

    fun batteryFullAlerted(context: Context): Boolean =
        of(context).getBoolean(KEY_BATTERY_FULL_ALERTED, false)

    fun setBatteryFullAlerted(context: Context, alerted: Boolean) {
        of(context).edit().putBoolean(KEY_BATTERY_FULL_ALERTED, alerted).apply()
    }

    const val KEY_BATTERY_RUNTIME_ALERTED = "noop.batteryRuntimeAlerted"

    fun batteryRuntimeAlerted(context: Context): Boolean =
        of(context).getBoolean(KEY_BATTERY_RUNTIME_ALERTED, false)

    fun setBatteryRuntimeAlerted(context: Context, alerted: Boolean) {
        of(context).edit().putBoolean(KEY_BATTERY_RUNTIME_ALERTED, alerted).apply()
    }

    const val KEY_BATTERY_CRITICAL_ALERTED = "noop.batteryCriticalAlerted"
    const val KEY_BATTERY_BEDTIME_ALERTED = "noop.batteryBedtimeAlerted"

    fun batteryCriticalAlerted(context: Context): Boolean =
        of(context).getBoolean(KEY_BATTERY_CRITICAL_ALERTED, false)

    fun setBatteryCriticalAlerted(context: Context, alerted: Boolean) {
        of(context).edit().putBoolean(KEY_BATTERY_CRITICAL_ALERTED, alerted).apply()
    }

    fun batteryBedtimeAlerted(context: Context): Boolean =
        of(context).getBoolean(KEY_BATTERY_BEDTIME_ALERTED, false)

    fun setBatteryBedtimeAlerted(context: Context, alerted: Boolean) {
        of(context).edit().putBoolean(KEY_BATTERY_BEDTIME_ALERTED, alerted).apply()
    }

    const val KEY_REPORT_MORNING = "noop.report.morningRecap"
    const val KEY_REPORT_WORKOUT = "noop.report.postWorkout"
    const val KEY_REPORT_MORNING_DAY = "noop.report.lastMorningDay"
    const val KEY_REPORT_STRAIN_TARGET = "noop.report.strainTarget"
    const val KEY_REPORT_STRAIN_TARGET_DAY = "noop.report.lastStrainTargetDay"
    const val KEY_REPORT_LAST_WORKOUT_TS = "noop.report.lastWorkoutTs"

    fun morningReportEnabled(context: Context): Boolean =
        of(context).getBoolean(KEY_REPORT_MORNING, false)

    fun setMorningReportEnabled(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_REPORT_MORNING, enabled).apply()
    }

    fun postWorkoutReportEnabled(context: Context): Boolean =
        of(context).getBoolean(KEY_REPORT_WORKOUT, false)

    fun setPostWorkoutReportEnabled(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_REPORT_WORKOUT, enabled).apply()
    }

    fun reportMorningDay(context: Context): String? =
        of(context).getString(KEY_REPORT_MORNING_DAY, null)

    fun setReportMorningDay(context: Context, day: String) {
        of(context).edit().putString(KEY_REPORT_MORNING_DAY, day).apply()
    }

    fun strainTargetEnabled(context: Context): Boolean =
        of(context).getBoolean(KEY_REPORT_STRAIN_TARGET, false)

    fun setStrainTargetEnabled(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_REPORT_STRAIN_TARGET, enabled).apply()
    }

    fun reportStrainTargetDay(context: Context): String? =
        of(context).getString(KEY_REPORT_STRAIN_TARGET_DAY, null)

    fun setReportStrainTargetDay(context: Context, day: String) {
        of(context).edit().putString(KEY_REPORT_STRAIN_TARGET_DAY, day).apply()
    }

    fun reportLastWorkoutTs(context: Context): Long =
        of(context).getLong(KEY_REPORT_LAST_WORKOUT_TS, 0L)

    fun setReportLastWorkoutTs(context: Context, ts: Long) {
        of(context).edit().putLong(KEY_REPORT_LAST_WORKOUT_TS, ts).apply()
    }

    const val KEY_CAFFEINE_CUTOFF = "noop.caffeine.cutoffNudge"
    const val KEY_CAFFEINE_BEDTIME_MIN = "noop.caffeine.bedtimeMinutes"

    fun caffeineCutoffEnabled(context: Context): Boolean =
        of(context).getBoolean(KEY_CAFFEINE_CUTOFF, false)

    fun setCaffeineCutoffEnabled(context: Context, enabled: Boolean) {
        of(context).edit().putBoolean(KEY_CAFFEINE_CUTOFF, enabled).apply()
    }

    fun caffeineBedtimeMinutes(context: Context): Int =
        of(context).getInt(KEY_CAFFEINE_BEDTIME_MIN, 23 * 60)

    fun setCaffeineBedtimeMinutes(context: Context, minutes: Int) {
        of(context).edit().putInt(KEY_CAFFEINE_BEDTIME_MIN, minutes.coerceIn(0, 24 * 60 - 1)).apply()
    }

    const val KEY_EFFORT_RESCORE_DONE = "noop.effortRescore.v313.done"

    fun effortRescoreDone(context: Context): Boolean =
        of(context).getBoolean(KEY_EFFORT_RESCORE_DONE, false)

    fun setEffortRescoreDone(context: Context) {
        of(context).edit().putBoolean(KEY_EFFORT_RESCORE_DONE, true).apply()
    }

    const val KEY_SLEEP_WEAR_RESCORE_DONE = "intelligence.sleepWearRescore.v1.done"

    fun sleepWearRescoreDone(context: Context): Boolean =
        of(context).getBoolean(KEY_SLEEP_WEAR_RESCORE_DONE, false)

    fun setSleepWearRescoreDone(context: Context) {
        of(context).edit().putBoolean(KEY_SLEEP_WEAR_RESCORE_DONE, true).apply()
    }

    const val KEY_TS_HEAL_DONE = "noop.tsHeal.v547.done"

    fun tsHealDone(context: Context): Boolean =
        of(context).getBoolean(KEY_TS_HEAL_DONE, false)

    fun setTsHealDone(context: Context) {
        of(context).edit().putBoolean(KEY_TS_HEAL_DONE, true).apply()
    }

    const val KEY_TS_HEAL_PENDING = "noop.tsHeal.v547.pending"

    fun tsHealPending(context: Context): Boolean =
        of(context).getBoolean(KEY_TS_HEAL_PENDING, false)

    fun setTsHealPending(context: Context, pending: Boolean) {
        of(context).edit().putBoolean(KEY_TS_HEAL_PENDING, pending).apply()
    }

    const val KEY_LAST_DEVICE_ADDR = "noop.lastDeviceAddress"
    const val KEY_LAST_DEVICE_MODEL = "noop.lastDeviceModel"

    fun setLastDevice(context: Context, address: String, model: WhoopModel) {
        of(context).edit()
            .putString(KEY_LAST_DEVICE_ADDR, address)
            .putString(KEY_LAST_DEVICE_MODEL, model.name)
            .apply()
    }

    fun lastDevice(context: Context): Pair<String, WhoopModel>? {
        val addr = of(context).getString(KEY_LAST_DEVICE_ADDR, null) ?: return null
        val model = of(context).getString(KEY_LAST_DEVICE_MODEL, null)
            ?.let { name -> runCatching { WhoopModel.valueOf(name) }.getOrNull() }
            ?: WhoopModel.WHOOP4
        return addr to model
    }

    fun clearLastDevice(context: Context) {
        of(context).edit().remove(KEY_LAST_DEVICE_ADDR).remove(KEY_LAST_DEVICE_MODEL).apply()
    }

    const val KEY_LAST_SYNC_AT = "noop.lastSyncAtSec"

    fun lastSyncAt(context: Context): Long = of(context).getLong(KEY_LAST_SYNC_AT, 0L)

    fun lastSyncAtFor(context: Context, peripheralId: String?): Long =
        com.noop.ble.lastSyncPrefKey(peripheralId)?.let { of(context).getLong(it, 0L) } ?: 0L

    fun setLastSyncAtFor(context: Context, peripheralId: String?, epochSec: Long) {
        val key = com.noop.ble.lastSyncPrefKey(peripheralId) ?: return
        of(context).edit().putLong(key, epochSec).apply()
    }

    fun strapNewestRecordTsFor(context: Context, peripheralId: String?): Long =
        com.noop.ble.strapClockPrefKey(peripheralId)?.let { of(context).getLong(it, 0L) } ?: 0L

    fun setStrapNewestRecordTsFor(context: Context, peripheralId: String?, epochSec: Long) {
        val key = com.noop.ble.strapClockPrefKey(peripheralId) ?: return
        of(context).edit().putLong(key, epochSec).apply()
    }

    const val KEY_LAST_FIRMWARE = "noop.lastFirmware"

    fun lastFirmware(context: Context): String? = of(context).getString(KEY_LAST_FIRMWARE, null)

    fun setLastFirmware(context: Context, fw: String?) {
        of(context).edit().apply {
            if (fw.isNullOrBlank()) remove(KEY_LAST_FIRMWARE) else putString(KEY_LAST_FIRMWARE, fw)
        }.apply()
    }

    fun firmwareFor(context: Context, peripheralId: String?): String? =
        com.noop.ble.firmwarePrefKey(peripheralId)?.let { of(context).getString(it, null) }

    fun helloSuppressed(context: Context, peripheralId: String?): Boolean =
        com.noop.ble.helloSuppressionPrefKey(peripheralId)?.let { of(context).getBoolean(it, false) } ?: false

    fun setHelloSuppressed(context: Context, peripheralId: String?, suppressed: Boolean) {
        val key = com.noop.ble.helloSuppressionPrefKey(peripheralId) ?: return
        of(context).edit().apply { if (suppressed) putBoolean(key, true) else remove(key) }.apply()
    }

    fun setFirmwareFor(context: Context, peripheralId: String?, fw: String?) {
        val key = com.noop.ble.firmwarePrefKey(peripheralId) ?: return
        of(context).edit().apply {
            if (fw.isNullOrBlank()) remove(key) else putString(key, fw)
        }.apply()
    }
}

/**
 * Root gate around [WhoopStyleHomeScreen] + [AppRoot].
 */
@Composable
fun NoopRoot() {
    val context = LocalContext.current
    val prefs = remember { NoopPrefs.of(context) }
    val appViewModel: AppViewModel = viewModel()

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, appViewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                appViewModel.ble.onForeground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var onboarded by remember {
        mutableStateOf(prefs.getBoolean(NoopPrefs.KEY_ONBOARDED, false))
    }
    var lastSeenChangelog by remember {
        mutableStateOf(prefs.getString(NoopPrefs.KEY_LAST_SEEN_CHANGELOG, "") ?: "")
    }

    LaunchedEffect(onboarded) {
        if (onboarded) UpdateStore.from(context).seedWhatsNewIfNeeded()
        val termsCurrent =
            prefs.getString(NoopPrefs.KEY_ACCEPTED_TERMS_VERSION, "") == Terms.CURRENT_VERSION
        if (onboarded && termsCurrent) {
            com.noop.update.UpdateWatch.runIfDue(context, BuildConfig.VERSION_NAME)
        }
    }

    var acceptedTerms by remember {
        mutableStateOf(prefs.getString(NoopPrefs.KEY_ACCEPTED_TERMS_VERSION, "") ?: "")
    }
    if (acceptedTerms != Terms.CURRENT_VERSION) {
        TermsGateScreen(onAccept = {
            prefs.edit()
                .putString(NoopPrefs.KEY_ACCEPTED_TERMS_VERSION, Terms.CURRENT_VERSION)
                .putString(NoopPrefs.KEY_ACCEPTED_TERMS_AT, java.time.Instant.now().toString())
                .apply()
            acceptedTerms = Terms.CURRENT_VERSION
        })
        return
    }

    if (!onboarded) {
        OnboardingScreen(
            viewModel = appViewModel,
            onFinished = {
                prefs.edit()
                    .putBoolean(NoopPrefs.KEY_ONBOARDED, true)
                    .putString(NoopPrefs.KEY_LAST_SEEN_CHANGELOG, AppChangelog.CURRENT_VERSION)
                    .apply()
                lastSeenChangelog = AppChangelog.CURRENT_VERSION
                onboarded = true
            },
        )
        return
    }

    // Seed bundled 636-day WHOOP CSV export off the main thread
    val cycles by produceState<List<WhoopCsvHistoricalImporter.HistoricalCycleRecord>>(initialValue = emptyList()) {
        value = withContext(Dispatchers.IO) {
            runCatching { WhoopCsvHistoricalImporter.loadBundledCycles(context) }.getOrDefault(emptyList())
        }
    }
    val baselines = remember { WhoopCsvHistoricalImporter.CalibratedUserBaselines() }
    val latest = cycles.firstOrNull()
    val prevStrainCycle = cycles.firstOrNull { it.dayStrain != null }

    val dashboardRows = remember(latest, baselines) {
        val hrv = latest?.hrvMs ?: baselines.rmssd30dMeanMs
        val rhr = latest?.rhrBpm ?: baselines.rhr30dMeanBpm
        val resp = latest?.respRateRpm ?: baselines.resp30dMeanRpm
        val spo2 = latest?.spo2Pct ?: baselines.spo230dMeanPct
        val temp = latest?.skinTempC ?: baselines.skinTemp30dMeanC
        val sleepMin = latest?.asleepMinutes ?: 518
        val deepMin = latest?.deepSwsMinutes ?: 114
        val remMin = latest?.remMinutes ?: 91
        listOf(
            DashboardRowItem("hrv", "HRV", "${hrv.toInt()} ms", "vs ${baselines.rmssd30dMeanMs.toInt()} ms", "30d baseline", hrv >= baselines.rmssd30dMeanMs),
            DashboardRowItem("rhr", "Resting Heart Rate", "${rhr.toInt()} bpm", "vs ${baselines.rhr30dMeanBpm.toInt()} bpm", "30d baseline", rhr <= baselines.rhr30dMeanBpm),
            DashboardRowItem("resp", "Respiratory Rate", String.format("%.1f rpm", resp), "vs ${String.format("%.1f", baselines.resp30dMeanRpm)}", "30d baseline", true),
            DashboardRowItem("spo2", "Blood Oxygen", String.format("%.1f%%", spo2), "vs ${String.format("%.1f%%", baselines.spo230dMeanPct)}", "30d baseline", spo2 >= 94.0),
            DashboardRowItem("temp", "Skin Temperature", String.format("%.2f °C", temp), "vs ${String.format("%.2f °C", baselines.skinTemp30dMeanC)}", "30d baseline", true),
            DashboardRowItem("sleep", "Hours of Sleep", "${sleepMin / 60}h ${sleepMin % 60}m", "${latest?.sleepPerformancePct ?: 80}% perf", "Last night", (latest?.sleepPerformancePct ?: 80) >= 75),
            DashboardRowItem("sws", "Restorative (SWS + REM)", "${(deepMin + remMin) / 60}h ${(deepMin + remMin) % 60}m", "SWS ${deepMin}m · REM ${remMin}m", "Last night", true),
            DashboardRowItem("strain", "Day Strain", String.format("%.1f", prevStrainCycle?.dayStrain ?: 18.8), "Max ${prevStrainCycle?.maxHrBpm ?: 163} bpm", "Latest completed", true),
        )
    }

    val thirtyDaySeries = remember(cycles) {
        cycles.take(30).reversed().mapIndexed { idx, c ->
            val label = c.cycleStartTime.take(10).takeLast(5)
            val valInt = c.recoveryScorePct ?: 55
            (if (label.isNotBlank()) label else "D${idx + 1}") to valInt
        }.ifEmpty {
            listOf("11-12" to 39, "11-13" to 68, "11-14" to 53, "11-15" to 55)
        }
    }

    var showStockNoop by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (showStockNoop) {
            AppRoot(viewModel = appViewModel)
        } else {
            WhoopStyleHomeScreen(
                sleepPerformancePct = latest?.sleepPerformancePct ?: 80,
                recoveryPct = latest?.recoveryScorePct ?: 55,
                dayStrain = prevStrainCycle?.dayStrain ?: 18.8,
                liveHrBpm = (latest?.rhrBpm ?: 51.0).toInt(),
                batteryPct = 44,
                streakDays = cycles.size.coerceAtLeast(baselines.totalHistoricalCycles),
                dashboardRows = dashboardRows,
                thirtyDaySteps = thirtyDaySeries,
                onOpenMetricDetail = { showStockNoop = true },
            )
        }

        Surface(
            color = Color(0xFF1E242E).copy(alpha = 0.92f),
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 14.dp, bottom = 72.dp)
                .clickable { showStockNoop = !showStockNoop },
        ) {
            Text(
                text = if (showStockNoop) "WHOOP UI" else "BLE & Settings",
                color = Color(0xFF00F19F),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }

    if (lastSeenChangelog != AppChangelog.CURRENT_VERSION) {
        Dialog(
            onDismissRequest = {
                prefs.edit()
                    .putString(NoopPrefs.KEY_LAST_SEEN_CHANGELOG, AppChangelog.CURRENT_VERSION)
                    .apply()
                lastSeenChangelog = AppChangelog.CURRENT_VERSION
            },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = Palette.surfaceBase) {
                WhatsNewSheet(
                    onClose = {
                        prefs.edit()
                            .putString(NoopPrefs.KEY_LAST_SEEN_CHANGELOG, AppChangelog.CURRENT_VERSION)
                            .apply()
                        lastSeenChangelog = AppChangelog.CURRENT_VERSION
                    },
                )
            }
        }
    }
}
```