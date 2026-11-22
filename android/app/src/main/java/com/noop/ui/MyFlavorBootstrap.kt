package com.noop.ui

import android.content.Context
import com.noop.NoopApplication
import com.noop.ble.WhoopModel
import com.noop.ingest.WhoopCsvImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Isolated fork startup configuration for Alex's WHOOP 5.0 / MG setup.
 *
 * Kept out of [MainActivity] so upstream `ryanbr/noop` merges stay conflict-free:
 * 1. Auto-completes first-launch onboarding, terms acceptance, and profile defaults.
 * 2. Applies a one-shot battery-drain fix (disables 24/7 continuous HRV left on by earlier builds).
 * 3. Seeds clean WHOOP-style stock UI defaults once (user can customize freely afterward).
 * 4. Seeds the bundled 636-day WHOOP CSV history into Room SQLite once.
 */
internal object MyFlavorBootstrap {

    private const val KEY_BATTERY_FIX_V1 = "myflavor.batteryFix.v1"
    private const val KEY_UI_DEFAULTS_V1 = "myflavor.uiDefaults.v1"
    private const val KEY_BUNDLED_CSV_SEEDED_V1 = "noop.bundledCsvSeeded.v1"
    private const val KEY_SELECTED_WHOOP_MODEL = "noop.selectedWhoopModel"
    private const val KEY_TODAY_HIDDEN_SECTIONS = "today.hiddenSections"

    /** Apply one-time profile, battery, UI, and historical CSV defaults on launch. */
    fun apply(context: Context, scope: CoroutineScope) {
        val appContext = context.applicationContext
        val prefs = NoopPrefs.of(appContext)

        if (!prefs.getBoolean(NoopPrefs.KEY_ONBOARDED, false) ||
            prefs.getString(NoopPrefs.KEY_ACCEPTED_TERMS_VERSION, "") != Terms.CURRENT_VERSION
        ) {
            prefs.edit()
                .putString(NoopPrefs.KEY_ACCEPTED_TERMS_VERSION, Terms.CURRENT_VERSION)
                .putString(NoopPrefs.KEY_ACCEPTED_TERMS_AT, java.time.Instant.now().toString())
                .putBoolean(NoopPrefs.KEY_ONBOARDED, true)
                .putString(NoopPrefs.KEY_LAST_SEEN_CHANGELOG, AppChangelog.CURRENT_VERSION)
                .putBoolean(NoopPrefs.KEY_BACKGROUND_CONNECTION, true)
                .putString(KEY_SELECTED_WHOOP_MODEL, WhoopModel.WHOOP5_MG.name)
                .apply()

            runCatching {
                val profile = ProfileStore.from(appContext)
                profile.setAge(30)
                profile.sex = "male"
                profile.hrMaxOverride = 203
            }
        } else if (!prefs.contains(KEY_SELECTED_WHOOP_MODEL)) {
            prefs.edit().putString(KEY_SELECTED_WHOOP_MODEL, WhoopModel.WHOOP5_MG.name).apply()
        }

        if (!prefs.getBoolean(KEY_BATTERY_FIX_V1, false)) {
            NoopPrefs.setContinuousHrv(appContext, false)
            NoopPrefs.setContinuousHrvOvernight(appContext, true)
            NoopPrefs.setPauseHrvOnPowerSave(appContext, true)
            prefs.edit().putBoolean(KEY_BATTERY_FIX_V1, true).apply()
        }

        if (!prefs.getBoolean(KEY_UI_DEFAULTS_V1, false)) {
            UnitPrefs.setEffortScale(appContext, EffortScale.WHOOP)
            AppearancePrefs.set(appContext, AppearanceMode.DARK)
            AccentPrefs.setColor(appContext, AccentColor.WHOOP_BLUE)
            ChartStylePrefs.set(appContext, ChartStyle.CLASSIC)
            NoopPrefs.setShowDayCycleBackground(appContext, false)
            NoopPrefs.setCardOpacityPercent(appContext, 100)
            CardAppearance.init(appContext)
            if (!prefs.contains(KEY_TODAY_HIDDEN_SECTIONS)) {
                TodayLayoutPrefs.setHidden(
                    appContext,
                    listOf(
                        TodaySection.MENSTRUAL_CYCLE,
                        TodaySection.LIVE_SESSION,
                        TodaySection.YOUR_CARDS,
                        TodaySection.ADDED_CARDS,
                    ),
                )
            }
            prefs.edit().putBoolean(KEY_UI_DEFAULTS_V1, true).apply()
        }

        if (!prefs.getBoolean(KEY_BUNDLED_CSV_SEEDED_V1, false)) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val repo = (appContext as? NoopApplication)?.repository ?: return@launch
                    val summary = WhoopCsvImporter.importBundledAssets(appContext, repo)
                    if (summary.counts.isNotEmpty()) {
                        prefs.edit().putBoolean(KEY_BUNDLED_CSV_SEEDED_V1, true).apply()
                    }
                }
            }
        }
    }
}
