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
- Releases: pushing a tag `vX.Y.Z` (must equal `versionName` in `app/build.gradle.kts`) runs `.github/workflows/release.yml`, which builds a signed APK and attaches `gridload-vX.Y.Z.apk` to a GitHub Release for Obtainium. Signing values come from gitignored `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) or `GRIDLOAD_KEYSTORE_FILE`/`_KEYSTORE_PASSWORD`/`_KEY_ALIAS`/`_KEY_PASSWORD` env vars. With neither, `assembleRelease` gives an unsigned APK, which is what F-Droid wants. `dependenciesInfo` is off in `app/build.gradle.kts` because F-Droid rejects AGP's encrypted dependency block (it's only in signed release APKs). Each release also needs `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (max 500 characters), which F-Droid shows as the release notes. F-Droid uses reproducible builds: it rebuilds each tag and publishes our signed GitHub APK only if its build is byte-identical apart from the signature, so the build must stay deterministic (no timestamps, build paths or machine-specific values in the APK). CI secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. The keystore is never committed; losing it means users can't update in place, on Obtainium and on F-Droid (the fdroiddata recipe pins its certificate in `AllowedAPKSigningKeys`), so back it up. The recipe `metadata/io.github.buerlino.gridload.yml` lives in fdroiddata, not in this repo; the merge request and the recipe choices are in the skill (Phase 2).
- Regions: the app only works where a utility publishes a dynamic tariff, so the user picks a region (dropdown at the top of the screen). Seven regions from four utilities (added 2026-09-29, not released yet; see [Data source](#data-source)). `Region(id, name, utility, pricesUrl, tomorrowFrom)` and the `REGIONS` list (sorted by name) live in `core/.../Region.kt`; the URLs are compiled into the app, so a new region needs a release, and `fetchPrices(region)` takes the region and requests today and tomorrow (`PriceApi.kt`). There is no default region: the first start ends with a region choice, and nothing is fetched before it. Installs from v0.2, which never saved a region, fall back to CKW. The selected region is saved in SharedPreferences (from v0.2). Switching region drops the cached slots and fetches.
- UI: a "?" button at the top right opens a help dialog: a short general part, then one short part per mode (colours, refresh). The same text is the first page of the first start. Keep it in sync with the wording above.
- v0.2 (built 2026-09-29, not released yet): a ⚙ at the top left opens Settings (mode and region picker); the main screen shows the region as plain text. The first start is the help, then **Next**, then the mode choice, then the region choice (added 2026-09-29, not released yet); peak load is shown disabled as "coming soon". The region and "first start done" are saved in SharedPreferences. The mode isn't saved until peak mode exists, because spot is the only choice. The mode logos are emoji placeholders (⚡ spot, 📊 peak) until the user decides.
- Refresh policy (because of the rate limit): one response covers today and, once published, tomorrow. So on resume and every minute while visible, recompute the colour from the cached slots, and fetch only when no slot covers now, when it's after the region's `tomorrowFrom` and the cache doesn't reach tomorrow yet (`wantsFetch`), or when the user taps refresh. That's about two fetches a day. Fetches the user didn't ask for always wait the full 5-minute cooldown. Never fetch more often than a cooldown: 5 minutes after any attempt (successful or not), or 30 seconds when nothing is shown, so tapping refresh repeatedly can't hit the rate limit (`Cooldown.kt`). A blocked refresh shows a short notice instead. Switching region resets the cooldown. Keep the slots in a `ViewModel` so rotation doesn't refetch. Recomputing about every minute makes the colour change at slot boundaries.

## Current task scope

v0.2.1 is released (GitHub Releases/Obtainium) and submitted to F-Droid: merge request https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50583, pipeline green including the reproducible-build check, waiting for review (2026-09-29). The main screen is a single field showing red / orange / green.
- Red = not good to run appliances (shown as "Bad time", "Wait if you can")
- Orange = run only if you must (shown as "Fair time", "Only run what you need")
- Green = run now! (shown as "Good time", "Run your appliances now")

Next work follows the roadmap in the skill (agreed 2026-09-29): v0.2 (Settings, saved settings, first start) and the F-Droid submission are done; the research into other Swiss providers is done (2026-09-29, regions added, not released yet); next is peak load mode. Tomorrow's prices are done (2026-09-29, not released yet): the colour compares with the next 24 hours and the screen shows the next good time; see [Classification](#classification-red--orange--green). The app gets two modes, chosen on first start and switchable in Settings (icon top left):
- **Spot price mode:** the current behaviour. No appliance tracking, because at any moment you either run everything or wait.
- **Peak load mode:** a baseline plus appliances with watts and optional run time, an estimated draw per quarter hour, and advice that keeps the month's peak low and prefers cheap times. See [Peak load mode](#peak-load-mode-discussed-2026-09-29).

Small saved settings (mode, region, first start done) in SharedPreferences are in scope from v0.2; still no database. **Next task (user, 2026-09-29):** the load data import for peak load mode (see "Load data import" below), starting with a discussion of the file format. Appliances are in scope only with peak load mode (Phase 4). The first peak load discussion is recorded below; its open points and the CKW tariff check come before any peak mode code. iOS is out of scope for now. The user will give further instructions step by step.

## Data source

Per region; add new ones to `REGIONS`. Every utility found so far follows the **VSE/AES standard** for dynamic tariffs: the same query parameters and the same JSON as CKW below, so one parser serves all. `classify` is relative to each region's own prices, so it needs no per-utility tuning.

Checked 2026-09-29 (user's list in `research/swiss_energy_price_apis.csv`), each tested with the app's own request and code:

| Region id | Utility, tariff | URL (plus `tariff_type=integrated`) | Tomorrow out | Notes |
|---|---|---|---|---|
| `ckw` | CKW, `home_dynamic` | see below | ~11:20 → 12:00 | hourly prices |
| `ekz` | EKZ, `integrated_400D` (Energie Dynamisch + Netz 400D) | `https://api.tariffs.ekz.ch/v1/tariffs` | ~17:50 → 18:00 | 15-min prices; a `CHF_m` monthly fee comes before the `CHF_kWh` value, so the parser picks by unit. Without `tariff_name` EKZ returns a flat standard tariff. |
| `ekz_einsiedeln` | EKZ Einsiedeln, `integrated_400D_E` | same | ~17:50 → 18:00 | as EKZ |
| `groupe_e` | Groupe E, Vario | `https://api.tariffs.groupe-e.ch/v2/tariffs` (no `tariff_name`) | ~14:50 → 15:00 | 15-min prices, cheapest at noon and at night |
| `primeo` | Primeo Energie, `NetzDynamisch` | `https://tarife.primeo-energie.ch/api/v1/tariffs` | ~17:30 → 18:00 | hourly; only the grid part varies (energy flat 0.13), cheapest at night. Names from `/api/v1/tariffs/names`. From 2027 "Primeo Dynamisch" adds a dynamic energy part. |
| `primeo_avag` | AVAG (Aare Versorgungs AG, ~13 municipalities around Olten), `NetzDynamischAVAG` | same | ~17:30 → 18:00 | Primeo operates this grid |
| `primeo_elag` | ELAG (Elektra Gretzenbach AG), `NetzDynamischELAG` | same | ~17:30 → 18:00 | Primeo operates this grid |

Publication times are single observations (`publication_timestamp`), rounded up; if tomorrow isn't out yet at that time, the app just retries after the cooldown. None of the new APIs sent rate-limit headers; the 5-minute cooldown covers them. The Home Assistant integration `ehnid/swiss-dynamic-tariffs` supports the same set, which is a good place to look for new ones.

Not usable:
- **BKW:** `https://api.bkw.ch/api/dyntariffs/v1/tariffs` works, but has only `feed_in` (what BKW pays for solar), no consumption price.
- **Swisspower ESIT** (`esit.code-fabrik.ch`, AEW, IWB, EWL, SiL and others): per-utility prices need the customer's metering point number and a token issued to HEMS vendors (`/api/v1/metering_code`, 1 request per 5 min). The token-free `/api/v1/tariff_name` only knows generic names `D0` to `D3` and answered 504 after 60 s on every try on 2026-09-29.
- **Energy-Charts** (EPEX spot CH, hourly, EUR/MWh, no key) and **ENTSO-E** (needs a token): market prices, not a household tariff. A possible "no local tariff" fallback later, not a region.
- **ElCom** (LINDAS SPARQL, strompreis.elcom.admin.ch): static annual tariffs only. **strompreise-schweiz.ch:** needs an API key.


CKW (Central Switzerland): `GET https://e-ckw-public-data.de-c1.eu1.cloudhub.io/api/v1/netzinformationen/energie/dynamische-preise`

Query parameters (from CKW's "technische Voraussetzungen" page, tested 2026-09-29), all optional:
- `tariff_type`: `electricity`, `grid_usage`, `grid` or `integrated`. With one set, the response has only that component. The app asks for `integrated`.
- `tariff_name`: `home_dynamic` (under 50 MWh/year, households) or `business_dynamic`. The app asks for `home_dynamic`; without it the values were the same.
- `start_timestamp`, `end_timestamp`: send them in UTC (`2026-09-28T22:00:00Z`), and the response timestamps come back in UTC too (`2026-09-28T22:00Z`). Without them the API returns only today. The span may be at most 31 days (400 otherwise), past days work (August 2026 did), a range past the published data just ends there, and the slot starting at `end_timestamp` is included. The app requests today's local midnight to the midnight after tomorrow and drops that extra slot.

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

- `prices`: quarter-hour slots, 96 per local day (00:00 to 24:00). Without query parameters only the current day.
- Tomorrow's prices are published from 12:00 (CKW's wording); `publication_timestamp` was 11:19 and 11:20 local on the days seen. The app looks for them from 12:00 (`CKW.tomorrowFrom`).
- The prices are **hourly**: all four quarters of an hour always had the same price (Aug and Sep 2026). CKW says it follows the forecast grid load, not the market.
- Timestamps: ISO 8601 with an explicit offset (Europe/Zurich, `+02:00` in summer and `+01:00` in winter, or `Z` when the request used UTC), minute precision, no seconds. Parse them with the offset (`OffsetDateTime`); don't assume a timezone.
- All components are single-element arrays with unit `CHF_kWh`: already CHF per kWh, so no conversion is needed.
- `integrated` = `grid` + `electricity` (the total price). On the inspected day `electricity` was flat at 0.12 and all the variation came from `grid` (and `grid_usage`, which is part of `grid`).
- CKW's stated range is 0.1 to 32 Rp/kWh for the dynamic part. Observed `integrated`, 1 Aug to 30 Sep 2026: the daily minimum was 0.1513 on nearly every day (a floor), the maximum 0.25 to 0.33.
- Every day has the same shape: one cheap valley from about 11:00 to 16:00 (PV). The night is **not** cheap (0.21 to 0.27). On weekdays the morning 06:00 to 09:00 is the peak; on weekends the dearest hour is usually midnight. The same hour varies only about ±1.5 Rp across weekdays. Exceptions happen: on 2026-09-09 the morning was 0.31 to 0.33 and the cheapest hour was 04:00.

### Classification (red / orange / green)

Signal: the `integrated` value of the slot where `start <= now < end`.

Reference window: the 24 hours starting at the current slot (`WINDOW`), about as long as appliances are usually delayed. If the data doesn't reach that far (tomorrow isn't out yet), the window moves back to end with the last known slot. The app fetches today and tomorrow, so before tomorrow is published (12:00 for CKW, later for others) the window is today and afterwards it's the next 24 hours.

Thresholds are relative to the window's range. With `min` and `max` taken over the window and `range = max - min`:
- **green** if `price <= min + range/3`
- **red** if `price >= min + 2*range/3`
- **orange** otherwise
- If `range == 0` (a flat day), show **orange**, since no time is better than any other.
- If no slot covers `now` (stale or missing data), show no colour. Display an error or "no data" state instead of guessing.

When now isn't green, `Status.nextGreen` is the first green slot later in the window. The screen shows it as "Next good time: 11:00" or "Next good time: tomorrow 11:00". If tomorrow's prices are missing in the evening, there is none (today's valley is over), so nothing is shown.

Why: the goal is to shift load to times that are better for the grid than the rest of the same day, not to hit an absolute CHF price. A day-relative split survives tariff changes without retuning, which fixed CHF bands would not. It also only marks slots green or red when they are clearly apart from the rest. The user chose this over rank terciles, which force an even 1/3 split and on 2026-09-28 marked the moderate 20:00 to 23:00 evening red. `integrated` is used rather than `grid` so the colour stays correct if the energy component ever starts varying too.

Why the next 24 hours (checked 2026-09-29 on 1 Aug to 30 Sep 2026): you can't run an appliance in the past, so "wait if you can" should point to a cheaper time that is still coming. The old window, the calendar day, compared the evening with a noon that was over. That worked for CKW only because tomorrow's noon looks the same. Each quarter hour was scored by what waiting up to 24 h would really have saved:
- With the 24-hour window, green was 33% of the time, orange 22%, red 45%. Waiting would have saved on average 1.1 Rp/kWh at green, 6.9 at orange and 9.8 at red. Only 1.1% of greens missed a saving of more than 5 Rp, and no red had less than 2 Rp to gain.
- The calendar day scored almost the same (2.1% of greens missed more than 5 Rp) and differed in about 9% of hours, always orange against red. The main gain of the window is the next good time.
- Rejected: "the rest of today". Late in the evening it compares a few expensive hours with each other and shows green at 0.23 while tomorrow's noon is 0.15; 21% of its greens missed more than 5 Rp.
- Known weak point, left as is: the maximum is often one expensive hour (a spike to 0.33 moves the red line up and turns that evening orange). A percentile instead of `max` would fix it, but the effect is only orange against red.

## Peak load mode (discussed 2026-09-29)

### Tariff (partly confirmed 2026-09-29)

CKW's 2026 price sheet for grid products (Preisinformation Netzprodukte 2026, CKW Netz E/ES/Home dynamic) has a **Leistungstarif**: "Bei der Leistung wird die höchste während 15 Minuten beanspruchte mittlere Leistung (kW) im Monat gemessen und in Rechnung gestellt." It's per kW of the month's peak on top of the kWh prices:
- Home dynamic: 1.00 CHF/kW per month excl. VAT (1.08 incl. 8.1% VAT).
- The standard single tariff E9: 1.50 CHF/kW per month (1.62 incl. VAT); ES10: 0.50.

So lowering the monthly peak by 2 kW saves about 2 CHF a month. By comparison, a 1 kWh run at green instead of red saves about 10 Rp. Still to check in the family's CKW customer portal (the user is getting access):
- Which product the household is on.
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

### Load data import (user idea, 2026-09-29)

The user has the household's smart meter data as one Excel sheet per year for the past 3 years (from CKW). Idea: peak load mode starts with an **import** of such files (Excel or CSV), and the app derives from them what the user would otherwise have to guess. This is the next task; discuss it with the user before building.

The import must not depend on having years of data (the user has 3 years only because that's what the contract covers):
- **Any amount of data:** one year, or only a few months with a fresh contract. The app derives what the imported period covers.
- **Repeatable:** each year the user imports the previous year's file. New months are added; months that were already imported are replaced by the newer data.
- **Months without data** fall back to the app's own tracked peak or a target the user sets.

What the data could give (to discuss, then start with the smallest useful set):
- **Monthly peaks:** the highest quarter-hour average per month. In Switzerland usually in winter. Gives a realistic target peak per month (open point 1 below).
- **Base load:** the always-on load, e.g. from night-time lows (a low percentile rather than the absolute minimum, which may be a glitch). Answers open point 2 below. The user's idea: subtract the appliances known to be always on from that minimum to get the static baseline that always runs.
- **Heating:** monthly average load minus the heating-free (summer) level gives roughly what heating uses, as a function of the month.
- Possibly a typical daily profile per month (average per quarter hour of the day), to show when peaks usually happen.

To settle first, with a real file in front of us:
- **The file format:** columns, units (kWh per quarter hour or kW; kWh per 15 min x 4 = average kW), timestamps, time zone and DST changes, one file per year. Look at an actual export before writing any parser.
- **Excel or CSV:** if the portal can export CSV, parse only CSV (simplest). If it has to be `.xlsx`: that's a zip of XML files, so a small reader with `java.util.zip` and the JDK's XML parser can live in `:core` with no dependency (Claude's suggestion). Not Apache POI (far too big). Old binary `.xls` would be much harder; check what the portal gives.
- **Storage:** keep only the derived numbers (per month: peak, average, base load), not the raw rows (3 years of quarter hours are about 100,000 rows). Still no database.

Constraints: the file is picked with Android's file picker (Storage Access Framework, `ACTION_OPEN_DOCUMENT`), which needs no permission, so `INTERNET` stays the only one. The data is personal: it stays on the phone, is never uploaded, and the user's real files are not committed to the repo. Tests use a small cut-down or made-up sample, and only with the user's consent if it comes from the real data.

### Open (settle before code)

The import above may answer points 1 and 2.

1. **Month start.** A tracked peak starts near the baseline, so early in the month every appliance would "set a new peak". Claude's suggestion: a target peak the user sets (e.g. last month's peak from the portal or bill), and the line to stay under is the higher of the target and this month's tracked peak. Once the month's peak has been exceeded, anything under it costs nothing extra.
2. **Baseline input.** One number the user types in (Claude's suggestion, e.g. read off the portal's night-time load) or a list of always-on appliances that the app sums.
3. **Interruptible.** A washing machine can't be stopped mid-cycle. Claude's suggestion: a per-appliance "can be stopped" flag, so the advice only suggests stopping those.
4. **Price in the start advice.** Suggest starting only on green (Claude's suggestion), or on orange too.

## Conventions

- **Simplest approach that works.** Don't over-engineer and don't clutter the UI or the code: no extra screens, options, layers or abstractions until they are needed. When unsure, or when a simpler idea or a good idea comes up, ask the user instead of deciding alone.
- Commit only when asked; the user pushes.
