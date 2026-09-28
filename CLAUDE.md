# CLAUDE.md

Guidance for working in this repo. Project background and scope live in [README.md](README.md).

## Project

GridLoad: an Android app that shows whether now is a good time to run household appliances, based on dynamic electricity prices.

## Stack

- Native Android: Kotlin + Jetpack Compose, single Activity, one screen. Distributed via F-Droid and Obtainium (GitHub Releases).
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
- Regions: the app only works where a utility publishes a dynamic tariff, so the user picks a region (dropdown at the top of the screen). Only CKW (Central Switzerland) exists for now; more are planned. `Region(id, name, utility, pricesUrl)` and the `REGIONS` list (first = default) live in `core/.../Region.kt`, and `fetchPrices(region)` takes the region. The selected region is kept in the `ViewModel` only, so it resets to the default on process death; persisting it is a follow-up, since persistence is out of scope for now. Switching region drops the cached slots and fetches.
- Refresh policy (because of the rate limit): one response covers the whole day. So on resume, recompute the colour from the cached slots, and fetch only when nothing is cached, no slot covers now, or the user taps refresh. Keep the slots in a `ViewModel` so rotation doesn't refetch. Recompute the colour about every minute while the app is visible, so it changes at slot boundaries.

## Current task scope

Very basic MVP: a single field showing red / orange / green.
- Red = not good to run appliances
- Orange = run only if you must
- Green = run now!

Do not add appliance management, optimization, or persistence yet. The user will give further instructions step by step.

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

## Conventions

- Commit only when asked; the user pushes.
