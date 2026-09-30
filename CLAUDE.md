# CLAUDE.md

Guidance for working in this repo. Project background and scope live in [README.md](README.md).

## Project

GridLoad: an Android app that shows whether now is a good time to run household appliances, based on dynamic electricity prices.

## Stack

- Native Android: Kotlin + Jetpack Compose, single Activity. One main screen (in peak load mode with the goal and appliance cards), plus Settings and the setup guide. Distributed via F-Droid and Obtainium (GitHub Releases).
- No Flutter / React Native / KMP. No proprietary dependencies (Firebase, Play Services, analytics, ads). Only the `INTERNET` permission.
- A small `core` package with no Android dependencies holds the API client, the JSON model, the classification and the peak load logic. Unit-test it directly.
- Detailed build plan and progress: [.claude/skills/build-gridload-android/SKILL.md](.claude/skills/build-gridload-android/SKILL.md). Where it and this file disagree, this file wins.

### Decided (2026-09-28)

- applicationId and namespace: `io.github.buerlino.gridload`. Kotlin packages: `io.github.buerlino.gridload` (app) and `io.github.buerlino.gridload.core`. App name: **GridLoad**. Repo: https://github.com/buerlino/gridload (GPLv3).
- Modules: `:core` is plain Kotlin/JVM (kotlinx.serialization + `HttpURLConnection`, JUnit4 via `kotlin-test-junit`), and `:app` is the Android/Compose app depending on `:core`.
- Versions are in [gradle/libs.versions.toml](gradle/libs.versions.toml): Gradle 9.8.0 wrapper (checksums verified), AGP 9.4.1, Kotlin 2.4.20, Compose BOM 2026.09.00.
  - AGP 9 has built-in Kotlin, so don't apply `org.jetbrains.kotlin.android` in `:app`. Apply only `com.android.application` + `org.jetbrains.kotlin.plugin.compose`.
  - `compileSdk = 37` is required because core-ktx 1.19 and lifecycle 2.11 set minCompileSdk 37. `minSdk = 26` for `java.time`. `targetSdk = 37`. Java/JVM target 17.
- JDK: the system default `java` is 27-ea, which is too new. [gradle/gradle-daemon-jvm.properties](gradle/gradle-daemon-jvm.properties) makes Gradle run on JDK 21 (`java-21-openjdk-devel` from Rocky appstream).
- SDK: `~/Android/Sdk` with `platform-tools`, `platforms;android-37.0`, `build-tools;37.0.0`. The user tests on a real phone over adb, with no emulator.
- R8 (minify + shrinkResources, `proguard-android-optimize.txt`) is on for release builds since v0.3.1, as the F-Droid review asked. The APK went from 8.5 to 1.3 MB, and two builds of the same commit in different folders were byte-identical, so it stays reproducible. kotlinx.serialization ships its own keep rules. Unit tests don't cover R8: test new code in a release build on the phone (sign the unsigned APK with `~/.android/debug.keystore` via `zipalign -p 4` + `apksigner`, which installs over debug builds).
- Releases: pushing a tag `vX.Y.Z` (must equal `versionName` in `app/build.gradle.kts`) runs `.github/workflows/release.yml`, which builds a signed APK and attaches `gridload-vX.Y.Z.apk` to a GitHub Release for Obtainium. Signing values come from gitignored `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) or `GRIDLOAD_KEYSTORE_FILE`/`_KEYSTORE_PASSWORD`/`_KEY_ALIAS`/`_KEY_PASSWORD` env vars. With neither, `assembleRelease` gives an unsigned APK, which is what F-Droid wants. `dependenciesInfo` is off in `app/build.gradle.kts` because F-Droid rejects AGP's encrypted dependency block (it's only in signed release APKs). Each release also needs `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (max 500 characters), which F-Droid shows as the release notes. F-Droid uses reproducible builds: it rebuilds each tag and publishes our signed GitHub APK only if its build is byte-identical apart from the signature, so the build must stay deterministic (no timestamps, build paths or machine-specific values in the APK). CI secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. The keystore is never committed; losing it means users can't update in place, on Obtainium and on F-Droid (the fdroiddata recipe pins its certificate in `AllowedAPKSigningKeys`), so back it up. The recipe `metadata/io.github.buerlino.gridload.yml` lives in fdroiddata, not in this repo; the merge request and the recipe choices are in the skill (Phase 2).
- Regions: the app only works where a utility publishes a dynamic tariff, so the user picks a region (dropdown at the top of the screen). Seven regions from four utilities (since v0.3.0; see [Data source](#data-source)). `Region(id, name, utility, pricesUrl, tomorrowFrom)` and the `REGIONS` list (sorted by name) live in `core/.../Region.kt`; the URLs are compiled into the app, so a new region needs a release, and `fetchPrices(region)` takes the region and requests today and tomorrow (`PriceApi.kt`). There is no default region: the first start ends with a region choice, and nothing is fetched before it. Installs from v0.2, which never saved a region, fall back to CKW. The selected region is saved in SharedPreferences (from v0.2). Switching region drops the cached slots and fetches.
- UI: a "?" button at the top right opens a help dialog: a short general part, then one short part per mode (spot: the colours, the 24-hour comparison and when tomorrow's prices come out; peak load: the monthly peak, start/stop, the goal). The same text is the first page of the setup guide. Keep it in sync with the wording above.
- Settings and setup guide (v0.2, extended for peak load 2026-09-29, reworked for the load data 2026-09-30): a ⚙ at the top left opens Settings; the main screen shows the region as plain text. **Setup guide** (user), shown on first start and from "Open the setup guide" in Settings, is a decision tree: the help and **Next** (first start only), the mode, the region, **Your usage** (the load data, both modes), and for peak load the goal. At that step spot price mode has **Skip** and **Done** once this month has its 7 days, both of which end the guide; peak load mode **requires** the data (**Next** to the goal only with 7 days, else "Use spot price instead", which ends the guide there). **Settings:** both modes have the mode cards, the region and **Your usage** (import, progress, per-month summary, "Delete usage data" with a confirmation, which takes spot mode back to prices only); peak load adds the goal offset. The whatwatt step and card are built but hidden until whatwatt phase 0 (see [Data: CKW import and whatwatt](#data-ckw-import-and-whatwatt-decided-2026-09-30)). Region, mode (`"spot"`/`"peak"`) and "first start done" are saved in SharedPreferences, the imported days and peak load's data in `files/peak.json`. The mode logos are emoji: ⚡ spot, 📊 peak (the user likes them, 2026-09-29). The mode cards say "Spot price" and "Peak load" (no "(manual)" any more). See [Data: CKW import and whatwatt](#data-ckw-import-and-whatwatt-decided-2026-09-30).
- Refresh policy (because of the rate limit): one response covers today and, once published, tomorrow. So on resume and every minute while visible, recompute the colour from the cached slots, and fetch only when no slot covers now, when it's after the region's `tomorrowFrom` and the cache doesn't reach tomorrow yet (`wantsFetch`), or when the user taps refresh. That's about two fetches a day. Fetches the user didn't ask for always wait the full 5-minute cooldown. Never fetch more often than a cooldown: 5 minutes after any attempt (successful or not), or 30 seconds when nothing is shown, so tapping refresh repeatedly can't hit the rate limit (`Cooldown.kt`). A blocked refresh shows a short notice instead. Switching region resets the cooldown. Keep the slots in a `ViewModel` so rotation doesn't refetch. Recomputing about every minute makes the colour change at slot boundaries.

## Current task scope

Released: **v0.5.0** (the load data rework; tag `v0.5.0`, versionCode 7) on GitHub Releases/Obtainium, after v0.4.0 (peak load mode, versionCode 6). F-Droid: merge request https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50583, in review; the recipe moves from v0.3.1 (the first version with R8, which the reviewer asked for) to v0.5.0 once its GitHub Release exists, and auto update picks up later tags once merged. The roadmap and each phase's status are in the skill.

The app has two modes, chosen on first start and switchable in Settings (⚙ top left):
- **Spot price mode:** the screen is one colour. No appliance tracking, because at any moment you either run everything or wait.
  - Red = not good to run appliances (shown as "Bad time", "Wait if you can")
  - Orange = run only if you must (shown as "Fair time", "Only run what you need")
  - Green = run now! (shown as "Good time", "Run your appliances now")
- **Peak load mode:** the same colour, then a baseline plus appliances with watts and optional run time, an estimated draw per quarter hour, and advice that keeps the month's peak low and prefers cheap times. See [Peak load mode](#peak-load-mode-discussed-2026-09-29).

Small saved settings (mode, region, first start done) in SharedPreferences; still no database. Appliances only in peak load mode. iOS is out of scope for now. The user will give further instructions step by step.

**Current task (user, 2026-09-30):** a tight load data import for both modes (only CKW day exports, 7 days of the current calendar month; see [Load data import](#load-data-import-reworked-2026-09-30)) and whatwatt's first phase (see [Data: CKW import and whatwatt](#data-ckw-import-and-whatwatt-decided-2026-09-30); its UI is hidden for now). Released as v0.5.0 (2026-09-30); the R8 test on the phone is in the skill (Phase 4 item 5).

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

So lowering the monthly peak by 2 kW saves about 2 CHF a month. By comparison, a 1 kWh run at green instead of red saves about 10 Rp. Checked in the CKW customer portal (see Load data import): it exports only hourly load and monthly totals, no 15-minute profile or measured peak (the user may ask CKW directly for those). Still to check:
- Which product the household is on.
- Are the 15-minute billing windows the fixed quarter hours (:00, :15, :30, :45), like the price slots?

Until then, build against the model below and keep CHF amounts for the peak out of the code.

### Goal

Keep the month's highest quarter-hour average draw as low as possible. Within that limit, shift flexible load to cheap times (noon PV), using the same price classification as spot mode.

### Model (hourly baseline, 2026-09-30)

The app can't read the smart meter (until whatwatt); it knows the imported hourly days and what the user enters (why hourly days and not monthly totals: [Load data import](#load-data-import-reworked-2026-09-30)):
- **Baseline** (`Baseline.at`): the draw with no tracked appliance running, per hour of day from the imported days of that calendar month (`HourlyMonth.baseKw`, the median across days, any year). 0 without data for the month.
- **Level** (`Baseline.levelKw`): that month's average draw from the same days.
- **Goal (user, 2026-09-29):** the month's level + a **goal offset** the user sets (`PeakData.goalOffsetKw`, default 2.0 kW, set in the setup guide and Settings). This is the line to stay under. Without 7 days of the month there is no goal at all: `PeakData.status` returns null and the main screen asks for the import (see [Main screen](#main-screen-peak-load-mode)).
- **Raised goal (user, 2026-09-29):** once a finished quarter hour of this month went above the planned goal, that peak is billed anyway, so anything up to it costs nothing extra: the goal rises to it (`PeakStatus.goalKw`, with `pastPeak`, `meterPeak` and `usualPeak`, see Load data import) until the month ends, then it's back to the planned goal on its own (a new month has no runs). Only finished quarter hours count (`pastPeak`), or going over the goal would raise it at once and the warning would never show. The app says so in bold at the top of the goal card and under Goal in Settings (`raisedGoalText`): "Goal raised to 3.8 kW: this month's peak (29 Sep, 17:00) already reached it, so up to it costs nothing extra. Back to 2.8 kW on 1 Oct."
- **Estimated draw** per quarter hour (`quarterHourKw`) = the baseline at that time + the tracked runs' share of that quarter hour. The peak is a 15-minute average over fixed quarter hours, so a 2000 W kettle for 3 minutes adds 400 W to that quarter hour. A run still going counts until the quarter ends.
- **Monthly peak** (`monthPeak`): the highest estimated quarter hour of the month so far, computed from the saved runs (nothing to reset: a new month has no runs yet).
- **Appliances:** name, watts, optional run time, "can be stopped" flag. The user taps start when switching it on; the app ends the run when the run time is up, or when the user taps stop. Without a run time it runs until stopped. A run copies the watts, so editing an appliance doesn't change the past.
- **Power entry (user, 2026-09-29):** the Add dialog offers "Common appliances" (`APPLIANCE_PRESETS` in `Presets.kt`: washing machine, tumble dryer, dishwasher, oven, cooktop, kettle, coffee machine, microwave, vacuum cleaner, hair dryer, space heater, car charger), which fills in the fields; the user can change them. **Watts is the average draw during the heavy part, the run time how long that part lasts**, so watts × time is about one real use (a washing machine: 2000 W for 30 min ≈ 1 kWh, not 2000 W for its 2-hour programme). That keeps the quarter-hour peak right. The dialog says so in one line. Heat pump and boiler are left out: they are in the baseline, and adding them would count them twice.
- **No background work:** the estimate depends only on the imports and the saved start and stop times, so the app computes everything, including the peak, when it is opened or resumed. No service, no WorkManager, no extra permission. So no notification when a run ends (a possible later add-on).
- **Storage:** `PeakData` (imported days, appliances, runs, goal offset) as one JSON file (`files/peak.json`, written with `AtomicFile`) with kotlinx.serialization; spot mode reads the days from it too. Files from before 2026-09-30 also hold `usage` (monthly totals), which is ignored (`ignoreUnknownKeys`). Still no database. Old runs could be thinned later (keep per-month results); not needed yet.
- The logic lives in `:core` (`CkwExport.kt`, `LoadProfile.kt` (the hourly profile), `PeakLoad.kt`, `PeakAdvice.kt`) and is unit-tested (`PeakTest`) with made-up exports. The screen is `PeakScreen.kt`.

Known limits: hourly values are a lower bound of the billed quarter hour; only 15-minute data (not in the portal) or a whatwatt would show the real quarter-hour peaks of the heat pump and water heater. Don't refine the appliance model (power curves) further: for 15-minute averages the heavy phase is what matters, and the unknown heating dwarfs the rest.

### Advice

The goal is the hard limit; the price decides within it (user, 2026-09-29: the price wording depends on the mode; in peak load mode the appliance and the remaining budget count first).
- **Current and next quarter hour** (`PeakStatus`): an appliance started now adds part of its draw to this quarter hour and all of it to the next, while running ones only end. So both are estimated, and the **budget** ("4.0 kW free for appliances") is the goal minus the higher of the two. Found on the phone: a heater started late in a quarter hour looked fine for it but put the next one far over the goal; the screen now says "Over your goal from 19:00 (4.3 kW)".
- **Start** (per appliance, `roomAt`/`fitsAt`): if it fits the goal now, the price colour decides: green "Fits. Good time to start.", orange "Fits. Only if you need it.", red "Fits, but a cheaper time is coming." If it doesn't fit: "Would pass your goal. Room from 19:52." (the first time within a day when it fits: a run ends or a new quarter hour starts), or "Stop something first." when nothing ending makes room.
- **Over the goal** (`suggestStop`): when the current or next quarter hour will pass the goal, suggest the smallest appliance that can be stopped whose stop brings this quarter hour back under the goal, else the next one. Never an appliance that can't be stopped midway (the per-appliance flag).

### Load data import (reworked 2026-09-30)

The household's smart meter data comes from the CKW customer portal, which exports Excel files from three views: **year** (one row per month), **month** (one row per day) and **day** (one row per hour; checked: `Einheit` = `Stunde`, 24 rows, **not** 15 minutes). **Only day exports are imported** (user, 2026-09-30); both modes use them. The year and month exports are refused with a message saying which export to take (`parseCkwExport` throws for `Einheit` `Monat` and `Tag`; `Tag` is a guess, since no month export was inspected).

**The minimum (tested 2026-09-30 on the user's January and September 2026 day files):**
- Neither the year nor the month export is needed. They have no hours, so they can't see the 01:00 water heater that sets the bill. The year export alone gave a flat 3.24 kW in January against a real usual peak of **7.38 kW at 01:00**. Carrying one month's hourly shape over to another with the monthly totals didn't work either (September's shape + January's total: 5.71 kW; scaled: 15.2 kW).
- **7 days of the current calendar month** (`REQUIRED_DAYS`) are enough. Random subsets against the whole month: 1 day ±0.87 kW per hour (January), 3 days ±0.57, **7 days ±0.39** (usual peak 6.6 to 8.5 kW), 14 days ±0.25; September 0.08 kW at 7 days. Every calendar week of both months was within 0.5 kW, and 2 days or more always found the peak at 01:00. A week also covers weekdays and weekends.
- **Last year's same month works.** The profile is per calendar month over all years (January was 2382, 2231 and 2410 kWh in 2024 to 2026), so early in a month the app asks for the same week a year earlier.

**Workflow:** the load data step (setup guide) and Settings say exactly what to export: "Export 7 days of October from the CKW customer portal: day view, one Excel file per day, e.g. 1 to 7 October 2025. Then pick all 7 files." (`suggestedWeek`: the month's first 7 days once they are over, else a year earlier). The portal exports one day per file, no ranges (user), so the picker takes several at once (`OpenMultipleDocuments`). Progress shows "October: 3 of 7 days" (`daysIn`, days of that calendar month in any year). The import message says what was imported and what was left out and why ("1 file skipped: It's a year export. Use the day view's exports.", or "skipped: that day isn't over yet" for today's). Each new month without its 7 days: spot mode's main screen (only if some data is imported already) says "No usage data for November yet. Import a week in Settings."; peak mode's main screen shows the same import (`LoadImport`, shared with Settings and the setup guide) in place of the goal and appliance cards (see [Main screen](#main-screen-peak-load-mode)). Once every month has a week (first year), nothing more is needed.

**The export format (inspected 2026-09-29):** "Aktueller Zeitraum (Excel) - 01. Sept. 2026.xlsx": `.xlsx` written by Apache POI, one sheet "Stromverbrauch". Rows 1 to 10 are a header block (customer, address, contract, metering points, export date, `Auswertungszeitraum: 01.09.2026 - 01.09.2026`, `Einheit` `Stunde`). The table starts at row 13 (`Zeitraum`, `Energieverbrauch (kWh)`, `Gesamtkosten (CHF)`), then rows `1.9.2026  01:00` (two spaces) with kWh per hour (= average kW over the hour) and CHF, and a `Total` row. Numbers are real numeric cells, labels shared strings. The contract combines two metering points, house and heat pump + boiler. `parseCkwExport` reads it with a small `.xlsx` reader (`java.util.zip` + the JDK's XML parser, no dependency, not Apache POI). Days are kept only when complete (23/24/25 values), stored as `DayUsage(date, kwh)` in `PeakData.days` (hour starts from the position, so DST days work), merged by date (`mergeDays`). (The year export has the same layout with `Einheit` `Monat` and rows `Jan.-24`.)

The user's September 2026 (28 days, `private/september_2026/`) showed:
- Always on: 0.21 kW (the nightly lows, every day). The old monthly model's floor (0.64 kW) was three times that.
- Every night 01:00 to 02:00: 2.1 to 5.1 kW (the water heater, switched on at night), and 03:00 to 04:00 a steady ~1.1 kW (probably the heat pump). The month's highest hour was at 01:00 on **every** day; the highest daytime hour was 2.1 kW. So in September the billed peak (at least 5.06 kW, 27 Sep) is set by the water heater, not by the household's appliances.

January 2026 (31 days, `private/january_2026/`, total 2409.6 kWh = the monthly export):
- Always on 0.2 to 0.5 kW. The heat pump draws ~3 to 4 kW in the morning (05:00 to 09:00) and evening (17:00 to 21:00); around midday it's on some days (~3 kW) and off on others (~0.4 kW).
- 01:00 to 02:00: water heater **and** heat pump together, 7.4 kW on a typical night, up to **9.67 kW** (6 Jan). The month's highest hour was at 01:00 on 30 of 31 days. So in winter too the billed peak is set at night by heating, not by the household's appliances; the biggest lever would be to keep the water heater and heat pump from running at the same time (a matter for the installer or CKW, not the app).

What the app derives (`hourlyMonths`, per calendar month over all imported days): `baseKw` per hour of day = **median** across the days (the always-on load, timers and the heat pump's usual draw; the household's own use mostly drops out because it moves between days), `staticKw` = 5th percentile of all hours, `averageKw`, `peakHour`; and `highestHour(month)`, a lower bound of that month's billed peak. The median, not a low percentile (checked on both months): with the 10th percentile 49% of January hours were more than 1 kW above the baseline and 36% more than 2 kW (the heat pump's off days pulled it down); with the median 13% and 2%. In September both work (5% vs 1%); the median adds ~0.5 kW of regular evening use, on the safe side.

`Baseline.at` follows the hour of day (so the water heater at 01:00 is in the estimate and a washing machine isn't advised into it). Because the baseline can rise during a run, `fitsAt` checks every quarter hour of a run with a run time. The goal is the highest of the planned goal and three things that are billed anyway (`GoalRaise`): the measured highest hour of the current month (`meterPeak`, only when days of this month are imported), the usual draw's highest hour (`usualPeak`, e.g. 7.4 kW at 01:00 in January, so the night isn't shown as over the goal when only last year's data exists), and the app's own estimate of finished quarter hours (`pastPeak`). Checked on the real data: September now → goal 5.06 kW (meter), 4.2 kW free in the evening; January at noon → 3.0 kW usual draw, goal 7.4 kW (usual), 4.4 kW free; January 00:50 → 0 kW free until the night's heating is over. Settings shows per month: always on, usually highest at 01:00 (kW), highest hour.

**Spot mode with data (user, 2026-09-30):** under the price, "Usually 3.8 kW at this hour · about 0.95 CHF/h" (`UiState.usualKw` = `Baseline.at(now)` × the current slot's price). The same place later shows whatwatt's measured value.

Constraints: the file is picked with Android's file picker (Storage Access Framework, `ACTION_OPEN_DOCUMENT`), which needs no permission, so `INTERNET` stays the only one. The data is personal: it stays on the phone, is never uploaded, and the user's real files (`private/`, gitignored) are not committed. Tests build a made-up export in the same layout.

### Main screen (peak load mode)

The price colour, headline and price as in spot mode, then two cards: **This quarter hour** ("2.5 of 3.3 kW", a bar, the budget or the over-the-goal warning with the stop suggestion, the usual draw at this hour, and the month's estimated peak, shown only while no measured hour is higher, since a higher measured one is already in the raised-goal text) and **Appliances** (name, watts, run time, the advice or "Running until 19:52", Start/Stop; tap to edit or delete; "+ Add appliance" opens a dialog). Refresh at the bottom.

**Without this month's imported days (fixed 2026-09-30):** both cards are replaced by one "Your usage" card (`NeedsDataCard`: the same `LoadImport` as Settings, i.e. what to export, the import button and progress), since a goal and appliance list computed from a zero baseline isn't useful (this happens after deleting load data, or when a new month starts before its week is imported). The gate is in `:core`: `PeakData.status` returns null until the month has `REQUIRED_DAYS`.

## Data: CKW import and whatwatt (decided 2026-09-30)

**There is no third mode and no `dataSource` setting.** The two modes stay **spot price** and **peak load**. On top of either, two **add-ons that stack** (user, 2026-09-30):
1. **CKW import** (built): 7 days of hourly values for the current month (see [Load data import](#load-data-import-reworked-2026-09-30)). Spot mode can skip it ("prices only"); peak load mode needs it.
2. **whatwatt** (phase 1 built but hidden, see the phases below): reads the household's real draw from a whatwatt Go (CHF 90, plus the CHF 19 Plus licence for the local API) plugged into the smart meter's customer interface. Offered in the setup guide **after** the import, optional ("Not now"). The user's research and the device's API as they understood it: `private/smart_meter_research.md` (gitignored: it has the user's name and area). The user is buying the device.

Nothing extra is saved to say which add-ons are on: it follows from the data (imported days yes/no; a whatwatt address saved yes/no). Existing installs keep working: peak load with its imported days, spot without days = prices only.

Architecture (Claude's proposal, presented to the user):
- **The app talks to the whatwatt directly over the home Wi-Fi with plain HTTP** (the local REST API, polled every few seconds while the screen is visible, or its SSE stream), not MQTT via a Raspberry Pi and Tailscale. No library (the Paho Android service is unmaintained and breaks on current Android), no broker, no Pi; the same `HttpURLConnection` as the price API. Remote access later: a Tailscale subnet router on the Pi makes the same HTTP calls work from anywhere, still without MQTT.
- SharedPreferences (not DataStore), English UI, and the existing classification (the research file's "25th percentile" rule for green contradicts [Classification](#classification-red--orange--green)).
- Research features 3 (live cost rate, "1.34 kW now · 0.34 CHF/h") yes, in both modes; 4 (the "not now" card) is covered by the colour, next good time and per-appliance advice; 5 and 6 (recording an appliance's power curve, cheapest start time) later, as their own feature.

The combinations:
| | Spot, prices only | Spot + import | Spot + import + whatwatt | Peak load + import | Peak load + import + whatwatt |
|---|---|---|---|---|---|
| Draw now | – | usual draw at this hour | measured | estimate: usual draw + started appliances | measured |
| Cost rate ("3.8 kW · 0.95 CHF/h") | – | estimated ("Usually … at this hour") | measured | – (the goal card shows the usual draw) | measured |
| Start/stop per appliance | – | – | – | yes | not needed, the meter sees it |
| Appliance list | – | – | – | yes | yes, only for "does it fit now?" |
| Goal (level + offset, raised when billed anyway) | – | – | – | yes | yes, same logic |

### Hardware, CKW key and the REST API (verified 2026-09-30)

Checked against whatwatt's docs and pricing page, the smart-me wiki and Android's docs. Anything marked *unverified* comes only from the research notes; confirm it before relying on it.
- **Buy:** whatwatt Go (CHF 90 at whatwatt; Digitec listed it at 86.90) + the Kamstrup Omnipower adapter (CHF 20, needed for Kamstrup meters) + the Plus licence (CHF 19, one-time) that unlocks the REST API. That's about CHF 129. Without Plus, `/api/v1/report` answers `404 License required`. Check that the household's meter really is a Kamstrup Omnipower before buying the adapter.
- **CKW key:** email `messtechnik@ckw.ch` with the meter number (on the meter face, after the barcode) and CKW sends the key (smart-me wiki). CKW's maintenance on 2025-05-14 renewed all Kamstrup keys, so any key from before that date is invalid. *Unverified:* the address `smartmeter@ckw.ch`, the subject "Anfrage: smart-me Kamstrup Verschlüsselungscode" (seen only in search snippets), no fee, 1–2 business days.
- **The REST API:** `GET http://<device>/api/v1/report`. There's no auth unless the device's Web UI password is set; then it's HTTP Digest (MD5-sess, firmware 1.10+) or Basic (older firmware). Skip auth until someone hits a 401. Confirmed fields:
  - `report.instantaneous_power.active.positive.total`: kW, the current draw.
  - `report.energy.active.positive.total`: kWh imported since install, the register for the quarter-hour projection.
  - `report.id`: counts up with each reading, so an unchanged `id` means stale data.
  - `meter.status`: e.g. `"OK"`, `"NO DATA"`, `"NOT CONNECTED"`. Trust the values only when it's `"OK"`. *Unverified:* the exact string when the key is missing. The manual shows "Key required" and the research notes say `"ENCRYPTION KEY"`; see what the device sends.
  - The docs list `protocol` `"KMP"` and `interface` `"TTL"`, so Kamstrup is supported, but model every field as nullable and check the real response (Phase 0).
  - Also there: a REST streaming (SSE) endpoint, and SD-card CSV logging. Whether that history can be read over the API is unclear.
- **Android's local network permission:** Android 17 (API 37) adds `ACCESS_LOCAL_NETWORK`. It's a runtime (dangerous) permission in the `NEARBY_DEVICES` group. This app targets 37, so every LAN connection needs it, and so does resolving `.local` names.
  - It has to be declared in the manifest of every install, so **INTERNET would no longer be the only permission** (see [Stack](#stack)). That's the user's call.
  - When the permission is missing, TCP connections just time out. So "Test connection" should check the permission first; otherwise "denied" looks the same as "wrong address".
  - **Exemption:** if the app finds devices with `NsdManager` and `DiscoveryRequest.FLAG_SHOW_PICKER`, the user picks the device in a system picker, and the app can connect to its addresses without the permission. The whatwatt announces itself as `whatwatt-XXXXXX.local` (last 6 hex digits of its id), but its DNS-SD service type isn't documented. Check in Phase 0 whether the picker finds it, and whether the exemption lasts across app restarts.
- **Cleartext:** `<domain>` in the network security config takes exact hostnames or IPs, **not CIDR ranges**, so "allow cleartext on 192.168.x.x" can't be written. Two ways to do it:
  - `<domain includeSubdomains="true">local</domain>` should cover the mDNS name (test it).
  - For an IP the user types in, `base-config cleartextTrafficPermitted="true"` allows cleartext app-wide. That's acceptable because the compiled-in price URLs are all HTTPS.

The whatwatt address is saved as `whatwatt_address` in SharedPreferences. In the setup guide, the whatwatt step comes after the load data in both modes (optional, "Not now"; it ends the guide for spot and leads to the goal for peak), and Settings has a whatwatt card after the load data.

This quarter hour in peak load + whatwatt: energy so far in the quarter (from the meter's cumulative register) + the current draw for the rest, averaged over the 15 minutes.

Phases:
0. **Check first:**
   - Before buying: the household's meter model, since the adapter depends on it. Request the CKW key as soon as the device is ordered.
   - Once the device is there: the exact JSON from CKW's meter, the status string for a missing key, and whether history (SD card) can be read over the API.
   - Permission: whether the `NsdManager` picker finds the whatwatt. If it does, that avoids `ACCESS_LOCAL_NETWORK`. Otherwise declare the permission and request it only when the user picks whatwatt (see above).
   - Cleartext: choose between the `.local` domain-config and `base-config` (see above).
1. **whatwatt step [built 2026-09-30 in commit `0f9b17b`; hidden from the UI for v0.5.0]** (no hardware needed). Hidden because the manifest sets `usesCleartextTraffic="false"`, so the plain-HTTP test could never connect. Once phase 0 settles cleartext and the permission, revert the commit "Hide the whatwatt setup step and Settings card" to bring the UI back: in the setup guide after the load data (both modes, optional, "Not now") and a card in Settings: device address + "Test connection", which calls `fetchMeterReading` off the main thread and shows the result (`MainViewModel.setWhatwattAddress`/`testWhatwattConnection`; the shared fields are `WhatwattFields` in `SettingsScreen.kt`, which also holds the one explanation of the device; it says live readings come later, since nothing uses the address yet beyond the test). The `:core` client is written (`Whatwatt.kt`: `parseMeterReading`, `fetchMeterReading`, unit-tested with a sample response). `MeterReading` has only what the test shows (`powerKw`, `meterStatus`).
2. **Live reading:** add `report.id` (the stale check) to `MeterReading`, poll while visible (no background work), live kW + CHF/h shown under the price colour in *both* modes, greyed out after 10 s without data. Test against a fake whatwatt on the PC that the phone reaches over Wi-Fi.
3. **Peak load + whatwatt logic:** quarter-hour projection from the meter's cumulative register (add it to `MeterReading`), budget, goal and advice as in peak load mode today, but skip appliance start/stop — the meter already knows the draw; keep the appliance list only for "does it fit now?" checks.
4. **Test with the real device.**
5. Later: remote access (Tailscale), power curve recording and cheapest start (research features 5 and 6).

Open questions (carried over from 2026-09-29, not answered yet): home Wi-Fi only first (Claude: yes)? The month's peak in peak load + whatwatt: the app sees the meter only while open; is "highest observed quarter hour + imports" enough, or the device's SD-card log if the API exposes it (check in Phase 0), or a logger on the Pi? Accept `ACCESS_LOCAL_NETWORK` as a second permission (asked only for whatwatt), or insist on the picker exemption and skip whatwatt if it doesn't work? Keep the appliance list in peak load + whatwatt (Claude: yes)? Icon for whatwatt in its setup step (📟 or 🔌?). Does peak load + whatwatt still require the import (today peak load does)?

## UI text (reworked 2026-09-30)

The UI copy is inline Kotlin string literals in `MainActivity.kt`, `PeakScreen.kt`,
`SettingsScreen.kt` and `MainViewModel.kt`, plus the import errors in `CkwExport.kt`. No
`strings.xml`: the app is English-only, and the user chose to reword in place (2026-09-30);
extracting the strings stays an optional later step.

Rules (user, 2026-09-30: the texts were too long, and some titles misleading):
- Short, one idea per line. Drop anything the screen already shows.
- Each concept is explained in one place: the colours and modes in the help (`HelpContent`) and
  the mode cards (one line each), the goal in `GoalSetting`, the import in `LoadImport`. The goal
  card shows numbers only (`raisedGoalText` says why a goal was raised).
- **"Your usage"**, not "load data", in the UI (user: "load" reads like a verb, as if something
  had to be loaded). The docs and code still call the concept load data (`LoadImport`,
  `hourlyMonths`, the "Load data import" section).
- "Average use", not "level", for the month's average draw in the UI.

## Conventions

- **Simplest approach that works.** Don't over-engineer and don't clutter the UI or the code: no extra screens, options, layers or abstractions until they are needed. When unsure, or when a simpler idea or a good idea comes up, ask the user instead of deciding alone.
- Commit only when asked; the user pushes.
