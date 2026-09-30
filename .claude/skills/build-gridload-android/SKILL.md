---
name: build-gridload-android
description: Execution brief for building the GridLoad Android app — a native Kotlin/Jetpack Compose app showing a red/orange/green "good time to run appliances" indicator, with a planned peak load mode, distributed via F-Droid and Obtainium. Use this skill whenever working in the gridload repo on the Android app itself: the API client or classification logic, the Compose UI, settings, peak load mode, building/installing on the phone, releases, or the F-Droid (fdroiddata) merge request. The platform choice (native Android, no Flutter/React Native/KMP) is already decided here — do not re-open that question; just execute.
---

# Build GridLoad Android

Roadmap, status and practical know-how for the app. `CLAUDE.md` at the repo root is the source
of truth for the stack, versions, API schema, thresholds, UI wording, release/signing and the
peak load design. Read it first. If this skill and `CLAUDE.md` disagree, `CLAUDE.md` wins;
update this skill to match. Finished work is kept here only as far as later work needs it; the
history is in git.

## Settled (don't relitigate)

- **Native Android, Kotlin + Jetpack Compose.** The app is "fetch one JSON endpoint, compare a
  number, show a colour": nothing that justifies Flutter, React Native or KMP, and plain
  Kotlin/Compose is the easiest for F-Droid to build and review. iOS is deferred; if it comes,
  the default is a small native SwiftUI rewrite. Don't build for it now.
- **Architecture:** single Activity. `:core` (plain Kotlin/JVM, no Android) holds regions, the
  API client, parsing, classification, the load data import and the peak load logic, all
  unit-tested. `:app` holds the screens: main (`MainActivity.kt`, peak load's cards in
  `PeakScreen.kt`), Settings and the setup guide (`SettingsScreen.kt`).
- **No background work:** fetch on open/resume and manual refresh only, so no permission
  beyond `INTERNET` is needed for it (whatwatt may add `ACCESS_LOCAL_NETWORK`; open, Phase 5). Peak load mode keeps this by computing everything from saved start/stop times
  when the app opens.
- **No proprietary dependencies** (Firebase, Play Services, ads, analytics, crash SDKs).
- **Beyond the roadmap:** no accounts, no cloud, no optimization engine.

## Working on the phone

- Build and test: `ANDROID_HOME=~/Android/Sdk ./gradlew :core:test :app:assembleDebug`.
- The user's phone is a Fairphone 6 over USB, no emulator. `adb` isn't on PATH in Claude's
  shell: use `~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk`.
  The debug build is signed with a different key than releases, so switching between the two
  needs an uninstall. `adb shell pm clear io.github.buerlino.gridload` shows the first start
  again.
- Screenshots: `adb exec-out screencap -p > file.png`. For store screenshots, use SystemUI demo
  mode (`settings put global sysui_demo_allowed 1`, then `am broadcast -a
  com.android.systemui.demo -e command enter|clock|notifications|status|exit ...`) so the user's
  status bar icons don't show. Afterwards exit demo mode and set `sysui_demo_allowed` back to 0.
- If the phone is locked, don't try to unlock it; ask the user.

## Icon

The user's SVGs in `logo/` (108 x 108, yellow background, tower with "GL" as foreground) are
the source. `res/drawable/ic_launcher_{background,foreground}.xml` are vector drawables
converted from them, combined in `res/mipmap-anydpi/ic_launcher.xml` (adaptive icon), and
`fastlane/.../images/icon.png` is a 512 px render of both (`rsvg-convert` + `magick`). If the
SVGs change, regenerate all three. No monochrome (themed) layer yet.

## Releasing

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (max 500 characters).
3. Commit and tag `vX.Y.Z` (only when the user asks); the user pushes. The tag builds the signed
   GitHub Release for Obtainium.
4. F-Droid: once the merge request below is merged, auto update picks up new tags by itself. It
   rebuilds the tag and publishes the GitHub APK only if the two are byte-identical apart from
   the signature, so keep the build deterministic.

## Roadmap (agreed with the user 2026-09-29)

Two **modes**, chosen on first start and changeable in Settings: **spot price** (the colour
says run everything or wait) and **peak load** (appliances, staying under the month's peak).
On top of either mode, two add-ons that stack (user, 2026-09-30): the **CKW import** (7 days of
hourly values for the current month; spot mode can skip it for prices only, peak load needs it)
and **whatwatt** (live measured draw, phase 5). Work phase by phase and update the status in
brackets.

### Phase 1: v0.2, Settings and first start [done, released as v0.2.0]

Settings behind ⚙ (mode cards, region picker), first start (help, Next, mode choice), region
and "first start done" saved, app icon.

### Phase 2: F-Droid [submitted 2026-09-29, in review]

Review so far: linsui asked for R8 (2026-09-29). Answer: v0.3.1 enables it, and the recipe's
build moves to v0.3.1 (versionCode 5, commit `02168b8`).

Merge request https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50583, from the user's fork
`buerlino/fdroiddata`, branch `io.github.buerlino.gridload`, file
`metadata/io.github.buerlino.gridload.yml`. The pipeline is green: `fdroid build` (Debian 13,
JDK 21 by default, so no `sudo` block) and the reproducible-build check. Recipe choices:
`NonFreeNet` anti-feature (the utilities' APIs), category `Market & Price`, `GPL-3.0-only`, `Binaries` +
`AllowedAPKSigningKeys` for reproducible builds.

Next: answer reviewer comments in the merge request. The user posts on GitLab (it's public
under their name); Claude drafts the answers and any recipe changes, and can check the merge
request and pipelines through GitLab's public API (`/api/v4/projects/fdroid%2Ffdroiddata/merge_requests/50583`).

### Phase 3: research [done, released as v0.3.0]

1. **Early vs late in the day [done]:** the API returns tomorrow with `start_timestamp` and
   `end_timestamp` in UTC (from noon). The app now fetches today + tomorrow, refetches once after
   noon, compares with the next 24 hours (today before noon) and shows the next good time.
   Findings, numbers and the rejected "rest of today" are in `CLAUDE.md` under Data source and
   Classification. To analyse prices again, a request of up to 31 days (past days work) gives
   plenty of data in one call; mind the rate limit of 4 per window.
2. **Other Swiss providers [done]:** all public dynamic-tariff APIs follow the VSE/AES standard
   (same schema as CKW), so EKZ, EKZ Einsiedeln, Groupe E and Primeo (3 grid areas) were added
   to `REGIONS` with a per-region `tomorrowFrom`. What was tested and rejected (BKW feed-in
   only, Swisspower needs per-customer tokens, spot markets, ElCom) is in `CLAUDE.md` under
   Data source. A new region: test the URL with the app's request, add it to `REGIONS`, the
   table in `CLAUDE.md`, `README.md` and the store description.

### Phase 4: peak load mode [released as v0.4.0; the load data rework as v0.5.0, versionCode 7]

The design is in `CLAUDE.md` under "Peak load mode". The CKW price sheet confirms the peak
charge (1.00 CHF/kW per month on Home dynamic). Still open: the points under "Tariff" there
(the household's product, whether the billed quarter hours are the fixed ones).

1. **Load data import [reworked 2026-09-30, released as v0.5.0].**
   Only CKW day exports (hourly), `REQUIRED_DAYS` = 7 of the current calendar month (any year);
   year and month exports are refused with a message. The year export, the monthly model
   (`LoadProfile` class, `MonthUsage`, summer calibration) and its UI are removed; why (tested
   on the real data) is in `CLAUDE.md` under "Load data import". Both modes use the days: spot
   mode shows "Usually 3.8 kW at this hour · about 0.95 CHF/h", or skips the import (prices
   only). `:core`: `CkwExport.kt` (xlsx reader + day parser), `LoadProfile.kt` (`hourlyMonths`,
   `highestHour`, `daysIn`, `suggestedWeek`). Peak load's main screen without this month's
   data shows a single "Your usage" import card (the same `LoadImport` as Settings);
   `PeakData.status` returns null until the month has `REQUIRED_DAYS`. See `CLAUDE.md` under
   "Main screen (peak load mode)". Known gap: without last year's data for the month (e.g. a
   new meter), peak load mode can't be used until the 8th.
2. **Appliances, runs, goal, advice [done].** `PeakLoad.kt` (appliances, runs, quarter-hour
   estimate, month peak, `PeakData`), `PeakAdvice.kt` (`Baseline`, `PeakStatus` with
   current and next quarter hour and the raised goal, `fitsAt`/`roomAt`, `suggestStop`).
   `:app`: `PeakScreen.kt` with the appliance dialog; data in `files/peak.json`.
3. **Mode choice and setup guide [done].** The mode is saved; Settings differ by mode; the
   setup guide (`SetupGuide` in `SettingsScreen.kt`) is the first start and can be reopened.
   The emoji logos (⚡, 📊) stay (user, 2026-09-29).
4. **Release v0.4.0 [done].** Tested on the phone 2026-09-29: a fresh first start through the
   setup guide (peak load, CKW, import of 3 yearly files and 59 day files, goal) and restarts,
   and in an **R8 release build** (signed with the debug key: `zipalign -p 4`, then
   `apksigner`): loading the saved JSON, the .xlsx import, appliances with presets,
   start/stop, deleting and restarts all work. `versionCode` 6, `versionName` 0.4.0 (v0.3.1 took
   5), `changelogs/6.txt`.
5. **Release v0.5.0 [tagged 2026-09-30].** The load data rework (item 1), README and store text,
   and whatwatt phase 1 built but hidden (Phase 5). `versionCode` 7, `changelogs/7.txt`.
   Tested 2026-09-30 in an **R8 release build** (debug-signed as above) on a fresh install: first
   start through the guide (peak load, CKW, 28 September day files, goal), the year-export
   refusal, raised goal (5.06 kW, 27 Sep), a preset appliance with start/stop and delete,
   Settings without whatwatt, region switch (the peak cards stay), delete load data → the
   "Load data" card → re-import from it, and spot mode's "Usually 0.9 kW at this hour" line.

Testing on the phone:
- The user's exports are in the phone's Download folder (the days in `Download/september_2026`
  and `Download/january_2026`; both are imported in the app). The picker reopens the last
  folder, so go back to Downloads first. Files pushed with adb into a subfolder only show up in the picker
  after a media scan: `adb shell content call --method scan_volume --uri content://media --arg external_primary`.
  Several files: long-press one, ⋮ → Select all, then Select.
- DocumentsUI shows the folders as a grid; tap the tile, not its top-right expand icon, which
  opens the file in another app instead.
- A helper that taps the first node containing a text can hit a label instead of a button
  ("Stop the Heater…" vs "Stop"); match whole texts for buttons.
- Test runs end up in the user's real `peak.json` and this month's peak. Remove them afterwards:
  force-stop the app, `run-as io.github.buerlino.gridload cat files/peak.json`, drop
  `appliances`/`runs`/`goalOffsetKw`, write it back with `run-as ... sh -c 'cat > files/peak.json'`.

### Phase 5: whatwatt [remodeled 2026-09-30; phase 1 built and hidden, waiting for the device]

There is no third mode and no `dataSource` setting (user, 2026-09-30). The plan, the
combinations table, the confirmed hardware/API/permission facts and the open questions are in
`CLAUDE.md` under "Data: CKW import and whatwatt"; the user's research in
`private/smart_meter_research.md` (not in the repo). In short: whatwatt is an optional add-on on
top of the CKW import, in either mode, offered in the setup guide after the load data step
("Not now" possible) and as a card in Settings. It reads the real draw from a whatwatt Go on the
home Wi-Fi over plain HTTP (no MQTT, Pi or library): in spot mode it replaces the estimated
"Usually … at this hour" with a measured kW / CHF/h; in peak load mode it replaces the baseline
estimate with a measurement and drops manual appliance start/stop. `Whatwatt.kt` (report
parser and client) is written and unit-tested.

**Phase 1 [built 2026-09-30 in `0f9b17b`, hidden from the UI for v0.5.0]:** the setup guide
step and Settings card, both without hardware. Hidden because the manifest blocks cleartext
HTTP, so the test could never connect; revert the commit "Hide the whatwatt setup step and
Settings card" once phase 0 settles cleartext and the permission. What it does: Device address field + "Test connection", which calls
`fetchMeterReading` off the main thread and shows the result or the error
(`MainViewModel.setWhatwattAddress`/`testWhatwattConnection`; the shared UI is `WhatwattFields`
in `SettingsScreen.kt`, with the one explanation of the device, which says live readings come
later). The address is saved in SharedPreferences (`whatwatt_address`,
alongside `region` and `mode`). No `ACCESS_LOCAL_NETWORK` permission has been added yet, so
"Test connection" may just time out on a real Android 17 device until phase 0 is resolved.

Next: **phase 0** — check the meter model before buying the adapter, get the CKW key, check
the real JSON, test whether the `NsdManager` system picker finds the whatwatt (it's exempt from
`ACCESS_LOCAL_NETWORK`, so INTERNET could stay the only permission), and choose the cleartext
config. **Phase 2** (live reading) can then be tested against a fake whatwatt on the PC. The
details, and what's still unverified, are in `CLAUDE.md`.

### Phase 6: UI text [reworked 2026-09-30, in v0.5.0]

Every text rewritten in place for length, with each concept explained once and "Your usage"
instead of "load data"; the rules are in `CLAUDE.md` under "UI text". `strings.xml` extraction
was dropped for now (English-only app). Checked on the phone: main screen, Settings, help.

## Conventions

- Commit only when the user asks; the user pushes themselves — don't `git push`.
- Simplest approach that works (user, 2026-09-29): don't over-engineer, don't clutter the UI
  or the code. When unsure, or when you see a simpler or better idea, ask the user first.
- Keep to the roadmap phases, in order. Ask about genuinely open product decisions rather than
  guessing, but don't ask about anything settled above or in `CLAUDE.md`.
