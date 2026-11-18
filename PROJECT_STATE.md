# NOOP / MyFlavorNoop — Project State & Handoff Guide

> **Usage:** Copy and paste the contents of this file into the Gemini phone app (Argon) or Antigravity at the start of any planning or coding session so both environments stay perfectly aligned without re-explaining context.

---

## 1. Project Overview & Alex's Goals
- **Product Owner:** Alex (PM & Decision Maker)
- **Primary Objective:** Eliminate the recurring WHOOP subscription by building a private, offline-by-default, native Android companion app for WHOOP 4.0 and WHOOP 5.0 / MG.
- **Base Project:** `myflavornoop` (forked from `ryanbr/noop` at `https://github.com/372-beep/myflavornoop`).
- **Target Hardware:**
  - Phone: Google Pixel 10 Pro (Android 15/16)
  - Strap: WHOOP 5.0 / MG (`5AM0248285`, Firmware `50.43.2.0` / `3.30.5.0`)
- **Key Hardware/Sub-project Extension:**
  - 100 Hz raw IMU Accelerometer bridge from the WHOOP strap into Alex's barbell velocity-based training app (**LiftTrack**, package `com.alexmayorga.lifttrack`).
- **Core Philosophy:** 100% offline, on-device SQLite, zero telemetry, zero accounts, clean-room protocol interop, PolyForm Noncommercial 1.0.0.

---

## 2. Tech Stack & Environment
- **Platform:** Android Native (target SDK 34/35, Kotlin 1.9+, Jetpack Compose, Material 3)
- **Database:** Room SQLite (mirrors Swift GRDB schemas)
- **Concurrency & State:** Kotlin Coroutines, `StateFlow`, `SharedFlow`
- **BLE / Hardware:** Custom GATT client handling WHOOP command/event frames, 0x2A37 Heart Rate service, 100 Hz IMU optical/motion buffers
- **Build System:** Gradle 8.7 (JDK 17)
- **CI / Build Automation:** GitHub Actions (`.github/workflows/build-noop-apk.yml`) building `NOOP-Custom-WHOOP-Debug-APK`
- **Host Workstation:** Windows 11 (without local Git or Android SDK in system PATH; builds run in GitHub Actions)

---

## 3. Repository Structure & Key Paths
```text
c:\Users\Alex\Documents\Noop UI_Myflavornoop\
├── android/
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── assets/whoop_export/          # Bundled 636-day historical WHOOP CSVs
│   │   │   ├── java/com/noop/
│   │   │   │   ├── analytics/                # Pure metrics math (Recovery, Strain, Sleep, HRV)
│   │   │   │   ├── ble/                      # BLE GATT client, LiveStreamBankerService, StepFusion
│   │   │   │   ├── data/                     # Room DB, DAOs, Entities, NoopPrefs
│   │   │   │   ├── ingest/                   # WhoopCsvImporter (auto-imports bundled assets)
│   │   │   │   ├── protocol/                 # WHOOP packet decoders, commands, CRC
│   │   │   │   └── ui/                       # Compose UI screens:
│   │   │   │       ├── MainActivity.kt       # Startup entrypoint (auto-bypasses onboarding)
│   │   │   │       ├── WhoopStyleHomeScreen.kt # WHOOP dark-theme UI, dials, live BPM pill, VBT card
│   │   │   │       └── AppViewModel.kt       # Central state: live, bpm, activeDevice
│   │   └── src/test/java/com/noop/analytics/
│   │       └── NoopAnalyticsTest.kt          # 18 QA defect classes + VBT + Verisense tests
│   └── build.gradle.kts
├── .github/workflows/
│   └── build-noop-apk.yml                    # Automated debug APK compilation workflow
└── PROJECT_STATE.md                          # This sync document
```

---

## 4. Current Progress & Fixes Completed

### A. Frictionless Sideloading (No More Redoing Setup)
- **Problem:** Every new APK installation forced Alex through a 12-page onboarding flow, terms acceptance, profile entry, and strap re-pairing.
- **Solution:** Added `ensureAlexDefaultsAndSeedHistory()` in `MainActivity.kt`:
  - Automatically marks onboarding complete (`KEY_ONBOARDED = true`) and accepts terms (`CURRENT_VERSION = "2.0"`).
  - Pre-configures Alex's profile (Age 30, Male, Max HR 203).
  - Selects `WhoopModel.WHOOP5_MG` as the active strap model.
  - Automatically requests runtime BLE permissions on launch.

### B. Live Heart Rate & Connection Disconnect Fix
- **Problem:** Strap was connected in the background backend, but the custom UI showed "Disconnected" and BPM never updated.
- **Root Cause:** `WhoopStyleHomeScreen.kt` was using reflection on `Any? appViewModel` with mismatched field names and never invoked `requestRealtimeHr()`.
- **Solution:**
  - Converted `WhoopStyleHomeScreen` to strongly typed `AppViewModel`.
  - Added `DisposableEffect(appViewModel)` that actively calls `vm.requestRealtimeHr()` and `vm.getBattery()` on mount.
  - Wired live observation to `appViewModel.live` and `appViewModel.bpm` StateFlows.
  - Top status pill now displays live green pulse (`#16EC06`) and real battery percentage.

### C. 636-Day Historical Data Pre-Seeding
- Integrated `WhoopCsvImporter.importBundledAssets(...)` to parse and insert Alex's historical cycles, sleeps, workouts, and journal entries from `assets/whoop_export/` into Room SQLite on first launch.

### D. 100 Hz IMU Accelerometer Bridge for LiftTrack
- Created `StepFusionAndImuBridge.kt` with:
  - 100 Hz IMU motion buffer capture (`startGroundTruthImuCapture()`).
  - Barbell concentric velocity integration (ZUPT / Zero-Velocity Update).
  - Sticking point velocity loss and peak velocity detection.
  - Added "Start 100Hz Set" and "Stop & Analyze" control cards under the Device tab in `WhoopStyleHomeScreen.kt`.

### E. Background Stability & Foreground Service Compliance
- Updated `LiveStreamBankerService.kt` to comply with Android 14/15/16 foreground service requirements (`startForeground(4201, ...)` with health/connected-device types and bounded wake locks).

### F. Exhaustive QA Test Suite (`NoopAnalyticsTest.kt`)
- Implemented and verified all 18 QA defect classes:
  - **N1-N11:** NaN guards, null vs 0.0 separation, Malik ectopic filter, duplicate timestamps, gap break detection, blood pressure pulse-pressure rules, Whoop Age / Pace of Aging, Verisense step filtering, ZUPT velocity integration, TRIMP resting floors.
  - **B1-B2:** GATT 0x2A37 sub-300ms ingest, skin contact bits.
  - **C1-C5 & D1:** Transport circuit breakers, DIS fallback on firmware `50.43.2.0`, UI metric formatters.

---

## 5. Active Priorities & Next Steps

1. **GitHub Actions APK Build & Download:**
   - Commit and push changes to `https://github.com/372-beep/myflavornoop` or trigger `.github/workflows/build-noop-apk.yml`.
   - Download `NOOP-Custom-WHOOP-Debug-APK.zip` from GitHub Actions artifacts.
2. **On-Device Hardware Verification (Pixel 10 Pro + WHOOP 5.0):**
   - Sideload APK onto Pixel 10 Pro.
   - Confirm launch goes straight to Home Screen (skipping onboarding).
   - Tap "Pair BLE" / status pill to confirm live BPM and battery update immediately in `#16EC06` green.
   - Test "Start 100Hz Set" on the Device screen to verify IMU logging.
3. **LiftTrack App IPC Integration:**
   - Expose an Android `BroadcastReceiver` or shared database content provider so `com.alexmayorga.lifttrack` can pull barbell velocity and rep data directly from Noop without duplicate BLE pairing.

---

## 6. Copy-Paste Prompt for Gemini App (Argon)

Whenever you start a session in the Gemini phone app, paste this prompt:

```text
Hey Argon, I am Alex working on my custom offline Android app "NOOP" for my WHOOP 5.0 band and Google Pixel 10 Pro. Here is the current PROJECT_STATE.md file showing our architecture, recent code changes, and open tasks:

[PASTE THIS ENTIRE PROJECT_STATE.MD HERE]

We are currently at Step 5: testing the live BLE stream on my Pixel 10 Pro and integrating the 100 Hz IMU accelerometer data into my LiftTrack barbell app. Please review where we left off and help me with: [YOUR QUESTION OR TASK HERE].
```
