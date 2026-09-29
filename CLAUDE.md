# CLAUDE.md

Guidance for working in this repo. Project background and scope live in [README.md](README.md).

## Project

GridLoad: an Android app that shows whether now is a good time to run household appliances, based on dynamic electricity prices.

## Stack

- Native Android: Kotlin + Jetpack Compose, single Activity. One main screen, plus Settings and first-start screens from v0.2 and an appliance screen in peak load mode. Distributed via F-Droid and Obtainium (GitHub Releases).
- No Flutter / React Native / KMP. No proprietary dependencies (Firebase, Play Services, analytics, ads). Only the `INTERNET` permission.
- A small `core` package with no Android dependencies holds the API client, the JSON model and the classification. Unit-test it directly.
- Detailed build plan and progress: [.claude/skills/build-gridload-android/SKILL.md](.claude/skills/build-gridload-android/SKILL.md). Where it and this file disagree, this file wins.

### Decided (2026-09-28)

- applicationId and namespace: `io.github.buerlino.gridload`. Kotlin packages: `io.github.buerlino.gridload` (app) and `io.github.buerlino.gridload.core`. App name: **GridLoad**. Repo: https://github.com/buerlino/gridload (GPLv3).
- Modules: `:core` is plain Kotlin/JVM (kotlinx.serialization + `HttpURLConnection`, JUnit4 via `kotlin-test-junit`), and `:app` is the Android/Compose app depending on `:core`.
- Versions are in [gradle/libs.versions.toml](gradle/libs.versions.toml): Gradle 9.8.0 wrapper (checksums verified), AGP 9.4.1, Kotlin 2.4.20, Compose BOM 2026.09.00.
  - AGP 9 has built-in Kotlin, so don't apply `org.jetbrains.kotlin.android` in `:app`. Apply only `com.android.application` + `org.jetbrains.kotlin.plugin.compose`.
  - `compileSdk = 37` is required because core-ktx 1.19 and lifecycle 2.11 set minCompileSdk 37. `minSdk = 26` for `java.time`. `targetSdk = 37`. Java/JVM target 17.
- JDK: the system default `java` is 27-ea, which is too new. [gradle/gradle-daemon-jvm.properties](gradle/gradle-daemon-jvm.properties) makes Gradle run on JDK 21 (`java-21-openjdk-devel` from Rocky appstream).
- SDK: `~/Android/Sdk` with `platform-tools`, `platforms;android-37.0`, `build-tools;37.0.0`. The user tests on a real phone over adb, with no emulator.
- Releases: pushing a tag `vX.Y.Z` (must equal `versionName` in `app/build.gradle.kts`) runs `.github/workflows/release.yml`, which builds a signed APK and attaches `gridload-vX.Y.Z.apk` to a GitHub Release for Obtainium. Signing values come from gitignored `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) or `GRIDLOAD_KEYSTORE_FILE`/`_KEYSTORE_PASSWORD`/`_KEY_ALIAS`/`_KEY_PASSWORD` env vars. With neither, `assembleRelease` gives an unsigned APK, which is what F-Droid wants. CI secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. The keystore is never committed; losing it means users can't update in place, so back it up.
- Regions: the app only works where a utility publishes a dynamic tariff, so the user picks a region (dropdown at the top of the screen). Only CKW (Central Switzerland) exists for now; more are planned. `Region(id, name, utility, pricesUrl)` and the `REGIONS` list (first = default) live in `core/.../Region.kt`, and `fetchPrices(region)` takes the region. The selected region is saved in SharedPreferences (from v0.2). Switching region drops the cached slots and fetches.
- UI: a "?" button at the top right opens a help dialog: a short general part, then one short part per mode (colours, refresh). The same text is the first page of the first start. Keep it in sync with the wording above.
- v0.2 (built 2026-09-29, not released yet): a ⚙ at the top left opens Settings (mode and region picker); the main screen shows the region as plain text. The first start is the help, then **Next**, then the mode choice; peak load is shown disabled as "coming soon". The region and "first start done" are saved in SharedPreferences. The mode isn't saved until peak mode exists, because spot is the only choice. The mode logos are emoji placeholders (⚡ spot, 📊 peak) until the user decides.
- Refresh policy (because of the rate limit): one response covers the whole day. So on resume, recompute the colour from the cached slots, and fetch only when nothing is cached, no slot covers now, or the user taps refresh. Never fetch more often than a cooldown: 5 minutes after any attempt (successful or not), or 30 seconds when nothing is shown, so tapping refresh repeatedly can't hit the rate limit (`Cooldown.kt`). A blocked refresh shows a short notice instead. Switching region resets the cooldown. Keep the slots in a `ViewModel` so rotation doesn't refetch. Recompute the colour about every minute while the app is visible, so it changes at slot boundaries.

## Current task scope

v0.1.0 is released: a single field showing red / orange / green.
- Red = not good to run appliances (shown as "Bad time", "Wait if you can")
- Orange = run only if you must (shown as "Fair time", "Only run what you need")
- Green = run now! (shown as "Good time", "Run your appliances now")

Next work follows the roadmap in the skill (agreed 2026-09-29): v0.2 with Settings, saved settings and a first-start flow; then the F-Droid submission; then research (tomorrow's prices, other Swiss providers); then peak load mode. The app gets two modes, chosen on first start and switchable in Settings (icon top left):
- **Spot price mode:** the current behaviour. No appliance tracking, because at any moment you either run everything or wait.
- **Peak load mode:** a baseline plus appliances with watts and optional run time, an estimated draw per quarter hour, and advice that keeps the month's peak low and prefers cheap times. See [Peak load mode](#peak-load-mode-discussed-2026-09-29).

Small saved settings (mode, region, first start done) in SharedPreferences are in scope from v0.2; still no database. Appliances are in scope only with peak load mode (Phase 4). The first peak load discussion is recorded below; its open points and the CKW tariff check come before any peak mode code. iOS is out of scope for now. The user will give further instructions step by step.

## Data source

Per region; add new ones to `REGIONS`. Only CKW so far, and its schema and thresholds are below. A new utility will probably need its own parser and schema notes here, and `classify` may need revisiting if its tariff differs.

CKW (Central Switzerland): `GET https://e-ckw-public-data.de-c1.eu1.cloudhub.io/api/v1/netzinformationen/energie/dynamische-preise`

CKW serves only Central Switzerland (Zentralschweiz). HTTPS, no auth, `access-control-allow-origin: *`. Rate limited: `x-ratelimit-limit: 4` per window (the reset header said 999 s). Fetch only on app open/resume and manual refresh, and cache responses locally while developing.

### Response schema (inspected 2026-09-28)

```json
{
  "publication_timestamp": "2026-09-27T11:19:17.128697+02:00",
  "prices": [
    {
      "start_timestamp": "2026-09-28T00:00+02:00",
      "end_timestamp":   "2026-09-28T00:15+02:00",
      "grid":        [{ "unit": "CHF_kWh", "value": 0.1074 }],
      "electricity": [{ "unit": "CHF_kWh", "value": 0.1200 }],
      "integrated":  [{ "unit": "CHF_kWh", "value": 0.2274 }],
      "grid_usage":  [{ "unit": "CHF_kWh", "value": 0.0771 }]
    }
  ]
}
```

- `prices`: 96 quarter-hour slots covering the current local day (00:00 to 24:00). Published around 11:00 the day before. Without query parameters the endpoint returned only the current day, not tomorrow.
- Timestamps: ISO 8601 with an explicit offset (Europe/Zurich, `+02:00` in summer and `+01:00` in winter), minute precision, no seconds. Parse them with the offset (`OffsetDateTime`); don't assume a timezone.
- All components are single-element arrays with unit `CHF_kWh`: already CHF per kWh, so no conversion is needed.
- `integrated` = `grid` + `electricity` (the total price). On the inspected day `electricity` was flat at 0.12 and all the variation came from `grid` (and `grid_usage`, which is part of `grid`).
- Observed range on 2026-09-28: `integrated` 0.1513 to 0.2801. Cheapest around 11:00 to 16:00 (PV), peaks at 06:00 to 09:00 and 18:00 to 20:00.

### Classification (red / orange / green)

Signal: the `integrated` value of the slot where `start <= now < end`.

Thresholds are relative to that day's range. With `min` and `max` taken over all slots in the response and `range = max - min`:
- **green** if `price <= min + range/3`
- **red** if `price >= min + 2*range/3`
- **orange** otherwise
- If `range == 0` (a flat day), show **orange**, since no time is better than any other.
- If no slot covers `now` (stale or missing data), show no colour. Display an error or "no data" state instead of guessing.

Why: the goal is to shift load to times that are better for the grid than the rest of the same day, not to hit an absolute CHF price. A day-relative split survives tariff changes without retuning, which fixed CHF bands would not. It also only marks slots green or red when they are clearly apart from the rest. The user chose this over rank terciles, which force an even 1/3 split and on 2026-09-28 marked the moderate 20:00 to 23:00 evening red. `integrated` is used rather than `grid` so the colour stays correct if the energy component ever starts varying too.

## Peak load mode (discussed 2026-09-29)

### Tariff (not confirmed yet)

The user's understanding (from university) is that a new peak load tariff bills consumption based on the month's highest 15-minute average draw. The user is getting access to the family's CKW customer portal. Check there, and record the answers here:
- Does the household tariff have a peak charge at all?
- How is it charged: CHF per kW of the monthly peak on top of the energy price, or does the peak change the price of all kWh?
- Are the 15-minute windows the fixed quarter hours (:00, :15, :30, :45), like the price slots?
- Does the portal show the 15-minute load profile and the month's measured peak? That would let the user calibrate the baseline and check the app's estimate.

Until then, build against the model below and keep CHF amounts for the peak out of the code.

### Goal

Keep the month's highest quarter-hour average draw as low as possible. Within that limit, shift flexible load to cheap times (noon PV), using the same price classification as spot mode.

### Model

- **Estimated draw** = baseline + the appliances currently running in the app. The app cannot read the smart meter; it only knows what the user enters.
- **Baseline:** an estimate of the always-on load (fridge, standby, router).
- **Appliances:** name, watts, optional run time (e.g. washing machine 2000 W for 1 h). The user taps start when switching it on; the app ends the run when the run time is up, or when the user taps stop. Without a run time it runs until stopped.
- **Quarter-hour average, not instantaneous draw:** the peak is a 15-minute average, so a 2000 W kettle for 3 minutes adds 400 W to that quarter hour. The app computes the average per quarter hour from the saved start and stop times.
- **Monthly peak:** the app saves the highest quarter-hour average it has estimated this month and resets it when a new month starts (local time). This is the line to stay under.
- **No background work:** the estimate depends only on the baseline and the saved start and stop times, so the app computes everything, including the peak, when it is opened or resumed. No service, no WorkManager, no extra permission. So no notification when a run ends (a possible later add-on).
- **Storage:** the appliance list, the month's runs and the peak as a JSON file with kotlinx.serialization (Claude's suggestion). Still no database.
- The quarter-hour, peak and advice logic lives in `:core` and is unit-tested like the classification.

### Advice

The peak is the hard limit; the price decides within it.
- **Start:** suggest starting a flexible appliance when its whole run stays under the peak, counting what else is running and when those runs end, and the price allows it. If it fits but the price is red, say it fits but a cheaper time is coming. If the price is good but it would set a new peak, say so and say when there will be room (when a running appliance finishes) or which one to stop.
- **Over the peak:** suggest the smallest flexible appliance whose stop brings the quarter-hour average back under the peak, not simply the smallest one. Never suggest an always-on or non-interruptible appliance.

### Open (settle before code)

1. **Month start.** A tracked peak starts near the baseline, so early in the month every appliance would "set a new peak". Claude's suggestion: a target peak the user sets (e.g. last month's peak from the portal or bill), and the line to stay under is the higher of the target and this month's tracked peak. Once the month's peak has been exceeded, anything under it costs nothing extra.
2. **Baseline input.** One number the user types in (Claude's suggestion, e.g. read off the portal's night-time load) or a list of always-on appliances that the app sums.
3. **Interruptible.** A washing machine can't be stopped mid-cycle. Claude's suggestion: a per-appliance "can be stopped" flag, so the advice only suggests stopping those.
4. **Price in the start advice.** Suggest starting only on green (Claude's suggestion), or on orange too.

## Conventions

- **Simplest approach that works.** Don't over-engineer and don't clutter the UI or the code: no extra screens, options, layers or abstractions until they are needed. When unsure, or when a simpler idea or a good idea comes up, ask the user instead of deciding alone.
- Commit only when asked; the user pushes.
