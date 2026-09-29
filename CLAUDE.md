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
- Regions: the app only works where a utility publishes a dynamic tariff, so the user picks a region (dropdown at the top of the screen). Seven regions from four utilities (since v0.3.0; see [Data source](#data-source)). `Region(id, name, utility, pricesUrl, tomorrowFrom)` and the `REGIONS` list (sorted by name) live in `core/.../Region.kt`; the URLs are compiled into the app, so a new region needs a release, and `fetchPrices(region)` takes the region and requests today and tomorrow (`PriceApi.kt`). There is no default region: the first start ends with a region choice, and nothing is fetched before it. Installs from v0.2, which never saved a region, fall back to CKW. The selected region is saved in SharedPreferences (from v0.2). Switching region drops the cached slots and fetches.
- UI: a "?" button at the top right opens a help dialog: a short general part, then one short part per mode (colours, refresh; peak load: start/stop, goal). The same text is the first page of the setup guide. Keep it in sync with the wording above.
- Settings and setup guide (v0.2, extended for peak load 2026-09-29): a ⚙ at the top left opens Settings; the main screen shows the region as plain text. **Setup guide** (user, 2026-09-29), shown on first start and from "Open the setup guide" in Settings, is a decision tree: the help and **Next** (first start only), the mode, the region, and for peak load only the load data import ("Skip for now" possible) and the goal. Spot price ends after the region. **Settings differ by mode:** both have the mode cards and the region; peak load adds the load data (import and per-month table), the goal offset and the calibration note. Region, mode (`"spot"`/`"peak"`) and "first start done" are saved in SharedPreferences, peak load's data in `files/peak.json`. The mode logos are emoji: ⚡ spot, 📊 peak (the user likes them, 2026-09-29).
- Refresh policy (because of the rate limit): one response covers today and, once published, tomorrow. So on resume and every minute while visible, recompute the colour from the cached slots, and fetch only when no slot covers now, when it's after the region's `tomorrowFrom` and the cache doesn't reach tomorrow yet (`wantsFetch`), or when the user taps refresh. That's about two fetches a day. Fetches the user didn't ask for always wait the full 5-minute cooldown. Never fetch more often than a cooldown: 5 minutes after any attempt (successful or not), or 30 seconds when nothing is shown, so tapping refresh repeatedly can't hit the rate limit (`Cooldown.kt`). A blocked refresh shows a short notice instead. Switching region resets the cooldown. Keep the slots in a `ViewModel` so rotation doesn't refetch. Recomputing about every minute makes the colour change at slot boundaries.

## Current task scope

v0.3.0 is released on GitHub Releases/Obtainium (2026-09-29). v0.2.1 is submitted to F-Droid (auto update picks up later tags once merged): merge request https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50583, pipeline green including the reproducible-build check, waiting for review (2026-09-29). The main screen is a single field showing red / orange / green.
- Red = not good to run appliances (shown as "Bad time", "Wait if you can")
- Orange = run only if you must (shown as "Fair time", "Only run what you need")
- Green = run now! (shown as "Good time", "Run your appliances now")

Next work follows the roadmap in the skill (agreed 2026-09-29): v0.2 (Settings, saved settings, first start) and the F-Droid submission are done; the research into other Swiss providers is done (regions added in v0.3.0); next is peak load mode. Tomorrow's prices are done (v0.3.0): the colour compares with the next 24 hours and the screen shows the next good time; see [Classification](#classification-red--orange--green). The app gets two modes, chosen on first start and switchable in Settings (icon top left):
- **Spot price mode:** the current behaviour. No appliance tracking, because at any moment you either run everything or wait.
- **Peak load mode:** a baseline plus appliances with watts and optional run time, an estimated draw per quarter hour, and advice that keeps the month's peak low and prefers cheap times. See [Peak load mode](#peak-load-mode-discussed-2026-09-29).

Small saved settings (mode, region, first start done) in SharedPreferences are in scope from v0.2; still no database. **Current task (user, 2026-09-29):** build peak load mode. A first full version works on the phone (not released): import, month function, goal, appliances with start/stop, advice, mode switch, setup guide. Appliances are in scope only with peak load mode (Phase 4). iOS is out of scope for now. The user will give further instructions step by step.

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

### Model (month function decided by the user, 2026-09-29)

The app cannot read the smart meter; it knows only the imported monthly totals and what the user enters. The imports give a **month function**, and each quarter hour is estimated on top of it:
- **Month level** (`LoadProfile.averageKw`): the average draw per calendar month over all imported years (kWh / hours covered). High in winter because of heating (the user's contract includes the heat pump and boiler), lowest in summer.
- **Floor** (`floorKw`): the lowest month, the summer level without heating.
- **Heating** (`heatingKw(month)`): the month's level minus the floor.
- **Static baseload** (`staticBaseloadKw`): the floor minus the average draw of the appliances tracked in the app. The always-on load (fridge, standby, router). The user's idea: with all appliances in the app and known, the difference is what always runs.
- **Calibration (user, 2026-09-29): summer only.** `summerAppliancesKw` measures the tracked appliances only from June to August (`SUMMER`), from the first run on, and only after 7 days of it (`MIN_CALIBRATION`); until then the static baseload is the whole floor, an upper bound. The UI explains this briefly where the user meets it (setup guide's goal step, Settings).
- **Baseline for the month** (`baselineKw(month)`): static baseload + that month's heating. This is the **benchmark**: the draw with no tracked appliance running. In January the recommendations are built around the higher level.
- **Goal (user, 2026-09-29):** the month's baseline + a **goal offset** the user sets (`PeakData.goalOffsetKw`, default 2.0 kW, set in the setup guide and Settings). Without imported data for the month the goal is the offset alone. This is the line to stay under.
- **Raised goal (user, 2026-09-29):** once a finished quarter hour of this month went above the planned goal, that peak is billed anyway, so anything up to it costs nothing extra: the goal rises to it (`PeakStatus.goalKw = max(plannedGoalKw, pastPeak)`) until the month ends, then it's back to the planned goal on its own (a new month has no runs). Only finished quarter hours count (`pastPeak`), or going over the goal would raise it at once and the warning would never show. The app says so in bold at the top of the goal card and under Goal in Settings (`raisedGoalText`): "Goal raised to 3.8 kW: this month's peak (29 Sep, 17:00) is billed anyway, so anything up to it costs nothing extra. From 1 Oct it's back to your planned 2.8 kW."
- **Estimated draw** per quarter hour (`quarterHourKw`) = the month's baseline + the tracked runs' share of that quarter hour. The peak is a 15-minute average over fixed quarter hours, so a 2000 W kettle for 3 minutes adds 400 W to that quarter hour. A run still going counts until the quarter ends.
- **Monthly peak** (`monthPeak`): the highest estimated quarter hour of the month so far, computed from the saved runs (nothing to reset: a new month has no runs yet). How far it is above the baseline shows how well the household stays at the benchmark; track it per month.
- **Appliances:** name, watts, optional run time, "can be stopped" flag. The user taps start when switching it on; the app ends the run when the run time is up, or when the user taps stop. Without a run time it runs until stopped. A run copies the watts, so editing an appliance doesn't change the past.
- **Power entry (user, 2026-09-29):** the Add dialog offers "Choose a common appliance" (`APPLIANCE_PRESETS` in `Presets.kt`: washing machine, tumble dryer, dishwasher, oven, cooktop, kettle, coffee machine, microwave, vacuum cleaner, hair dryer, space heater, car charger), which fills in the fields; the user can change them. **Watts is the average draw during the heavy part, the run time how long that part lasts**, so watts × time is about one real use (a washing machine: 2000 W for 30 min ≈ 1 kWh, not 2000 W for its 2-hour programme). That keeps the quarter-hour peak and the summer calibration (which subtracts the runs' energy) right. The dialog says so in one line. Heat pump and boiler are left out: they are in the baseline, and adding them would count them twice.
- **No background work:** the estimate depends only on the imports and the saved start and stop times, so the app computes everything, including the peak, when it is opened or resumed. No service, no WorkManager, no extra permission. So no notification when a run ends (a possible later add-on).
- **Storage:** `PeakData` (imported months, appliances, runs, goal offset) as one JSON file (`files/peak.json`, written with `AtomicFile`) with kotlinx.serialization. Still no database. Old runs could be thinned later (keep per-month results); not needed yet.
- The logic lives in `:core` (`MonthlyExport.kt`, `LoadProfile.kt`, `PeakLoad.kt`, `PeakAdvice.kt`) and is unit-tested (`PeakTest`) with a made-up export. The screen is `PeakScreen.kt`.

Known limits: the heat pump and boiler run on their own schedule, so the month's heating level is an average, and real heating peaks sit above it. Only 15-minute data from the portal would show them; if the portal can export it later, add that parser next to the monthly one.

### Advice

The goal is the hard limit; the price decides within it (user, 2026-09-29: the price wording depends on the mode; in peak load mode the appliance and the remaining budget count first).
- **Current and next quarter hour** (`PeakStatus`): an appliance started now adds part of its draw to this quarter hour and all of it to the next, while running ones only end. So both are estimated, and the **budget** ("kW left for appliances you start now") is the goal minus the higher of the two. Found on the phone: a heater started late in a quarter hour looked fine for it but put the next one far over the goal; the screen now says "From 19:00: 4.3 kW, over your goal".
- **Start** (per appliance, `roomAt`/`fitsAt`): if it fits the goal now, the price colour decides: green "Fits. Good time to start.", orange "Fits. Only start it if you have to.", red "Fits, but a cheaper time is coming." If it doesn't fit: "Would pass your goal. Room from 19:52." (the first time within a day when it fits: a run ends or a new quarter hour starts), or "Stop something first." when nothing ending makes room.
- **Over the goal** (`suggestStop`): when the current or next quarter hour will pass the goal, suggest the smallest appliance that can be stopped whose stop brings this quarter hour back under the goal, else the next one. Never an appliance that can't be stopped midway (the per-appliance flag).

### Load data import

The user has the household's smart meter data from CKW, one Excel file per year (2024 to 2026). Peak load mode starts with an **import** of such files; the app derives the month function from them.
- **Any amount of data:** one year, or only a few months with a fresh contract. Months without data have no baseline (`null`), and the goal is then the offset alone.
- **Repeatable:** each year the user imports the previous year's file. `mergeUsage` adds new months and replaces months already imported (a partial month is completed by the next export).
- **Storage:** only the monthly numbers (`MonthUsage`: kWh and hours covered), not the file.

**The export format (inspected 2026-09-29):** CKW portal exports named "Aktueller Zeitraum (Excel) - <year>.xlsx": `.xlsx` written by Apache POI, one sheet "Stromverbrauch". Rows 1 to 10 are a header block (customer, address, contract, metering points, export date, `Auswertungszeitraum: 01.01.2024 - 31.12.2024`, unit `Monat`). The table starts at row 13 (`Zeitraum`, `Energieverbrauch (kWh)`, `Gesamtkosten (CHF)`), then one row per month with German month labels (`Jan.-24`, `März-24`, `Sept.-24`) and a `Total` row. Numbers are real numeric cells and labels are shared strings. All three files have the same layout (`A1:C26`). Months after the export date are the string `-`, and the last month is partial (the hours covered come from the period). CHF is 0.0 for all of 2024. Monthly totals only: no quarter-hour data. The contract combines two metering points, house and heat pump + boiler. `parseCkwMonthlyExport` reads it with a small `.xlsx` reader (`java.util.zip` + the JDK's XML parser, no dependency, not Apache POI). The user's real files: 0.64 kW floor (Jul/Aug), 3.15 kW in January.

**Hourly day exports (user, 2026-09-29).** The portal's day view exports one file per day ("Aktueller Zeitraum (Excel) - 01. Sept. 2026.xlsx", downloaded by hand): same layout, `Einheit` = `Stunde`, period `01.09.2026 - 01.09.2026`, rows `1.9.2026  01:00` (two spaces) with kWh per hour (= average kW over the hour) and CHF. 15-minute values don't exist in the portal (the user may ask CKW). `parseCkwExport` reads both kinds by `Einheit`; hourly days are kept only when complete (23/24/25 values), stored as `DayUsage(date, kwh)` in `PeakData.days` (hour starts from the position, so DST days work), merged by date (`mergeDays`). The import takes several files at once (`OpenMultipleDocuments`). The user's September 2026 (28 days, `private/september_2026/`) showed:
- Always on: 0.21 kW (the nightly lows, every day). The monthly model's floor (0.64 kW) was three times that.
- Every night 01:00 to 02:00: 2.1 to 5.1 kW (the water heater, switched on at night), and 03:00 to 04:00 a steady ~1.1 kW (probably the heat pump). The month's highest hour was at 01:00 on **every** day; the highest daytime hour was 2.1 kW. So in September the billed peak (at least 5.06 kW, 27 Sep) is set by the water heater, not by the household's appliances.

January 2026 (31 days, `private/january_2026/`, total 2409.6 kWh = the monthly export):
- Always on 0.2 to 0.5 kW. The heat pump draws ~3 to 4 kW in the morning (05:00 to 09:00) and evening (17:00 to 21:00); around midday it's on some days (~3 kW) and off on others (~0.4 kW).
- 01:00 to 02:00: water heater **and** heat pump together, 7.4 kW on a typical night, up to **9.67 kW** (6 Jan). The month's highest hour was at 01:00 on 30 of 31 days. So in winter too the billed peak is set at night by heating, not by the household's appliances; the biggest lever would be to keep the water heater and heat pump from running at the same time (a matter for the installer or CKW, not the app).

What the app derives (`hourlyMonths`, per calendar month over all imported days): `baseKw` per hour of day = **median** across the days (the always-on load, timers and the heat pump's usual draw; the household's own use mostly drops out because it moves between days), `staticKw` = 5th percentile of all hours, `averageKw`, `peakHour`; and `highestHour(month)`, a lower bound of that month's billed peak. The median, not a low percentile (checked on both months): with the 10th percentile 49% of January hours were more than 1 kW above the baseline and 36% more than 2 kW (the heat pump's off days pulled it down); with the median 13% and 2%. In September both work (5% vs 1%); the median adds ~0.5 kW of regular evening use, on the safe side.

With hourly data for the month, `Baseline.at` follows the hour of day (so the water heater at 01:00 is in the estimate and a washing machine isn't advised into it); without, the monthly model. Because the baseline can rise during a run, `fitsAt` checks every quarter hour of a run with a run time. The goal is the highest of the planned goal and three things that are billed anyway (`GoalRaise`): the measured highest hour of the current month (`meterPeak`), the usual draw's highest hour (`usualPeak`, e.g. 7.4 kW at 01:00 in January, so the night isn't shown as over the goal when only last year's data exists), and the app's own estimate of finished quarter hours (`pastPeak`). Checked on the real data: September now → goal 5.06 kW (meter), 4.2 kW free in the evening; January at noon → 3.0 kW usual draw, goal 7.4 kW (usual), 4.4 kW free; January 00:50 → 0 kW free until the night's heating is over. Settings shows per month: always on, usually highest at 01:00 (kW), highest hour.

Constraints: the file is picked with Android's file picker (Storage Access Framework, `ACTION_OPEN_DOCUMENT`), which needs no permission, so `INTERNET` stays the only one. The data is personal: it stays on the phone, is never uploaded, and the user's real files (`private/`, gitignored) are not committed. Tests build a made-up export in the same layout.

### Main screen (peak load mode)

The price colour, headline and price as in spot mode, then two cards: **This quarter hour** ("2.5 of 3.3 kW", a bar, the budget or the over-the-goal warning with the stop suggestion, how the goal is made up, the month's highest quarter hour) and **Appliances** (name, watts, run time, the advice or "Running until 19:52", Start/Stop; tap to edit or delete; "+ Add appliance" opens a dialog). Refresh at the bottom.

### Open

- When to release peak load mode (v0.4.0), with store texts and screenshots.
- **Two versions (user, 2026-09-29):** a meter-free peak load mode now (estimates from imports and tracked appliances), and later a real one reading the smart meter's customer interface live, since only measuring sees the heat pump and water heater. Don't refine the appliance model (power curves) further: for 15-minute averages the heavy phase is what matters, and the unknown heating dwarfs the rest.
- With hourly data for the month, the summer calibration isn't used (the hourly baseline already leaves the household's appliances out); Settings still shows its note. Hide it then, or keep it for the monthly model only.

## Peak load (whatwatt) mode (planned 2026-09-29, waiting for the device)

A third mode next to spot price and **Peak load (manual)** (the current peak load mode, renamed): **Peak load (whatwatt)** reads the household's real draw from a whatwatt Go (CHF 90, plus the CHF 19 Plus licence for the local API) plugged into the smart meter's customer interface. The user's research and the device's API as they understood it: `private/smart_meter_research.md` (gitignored: it has the user's name and area). The user is buying the device; start as soon as it's there (phase 1 can start before).

Claude's take, presented to the user (differs from the research file's architecture):
- **The app talks to the whatwatt directly over the home Wi-Fi with plain HTTP** (the local REST API, polled every few seconds while the screen is visible, or its SSE stream), not MQTT via a Raspberry Pi and Tailscale. No library (the Paho Android service is unmaintained and breaks on current Android), no broker, no Pi; the same `HttpURLConnection` as the price API. Remote access later: a Tailscale subnet router on the Pi makes the same HTTP calls work from anywhere, still without MQTT.
- SharedPreferences (not DataStore), English UI, and the existing classification (the research file's "25th percentile" rule for green contradicts [Classification](#classification-red--orange--green)).
- Research features 3 (live cost rate, "1.34 kW now · 0.34 CHF/h") yes; 4 (the "not now" card) is covered by the colour, next good time and per-appliance advice; 5 and 6 (recording an appliance's power curve, cheapest start time) later, as their own feature.

The modes compared:
| | Spot price | Peak load (manual) | Peak load (whatwatt) |
|---|---|---|---|
| Draw now | – | estimate: baseline + started appliances | measured |
| Start/stop per appliance | – | yes | not needed, the meter sees it |
| Appliance list | – | yes | yes, only for "does it fit now?" |
| Goal (level + offset, raised when billed anyway) | – | yes | yes, same logic |
| CKW imports | – | yes | optional (usual nightly peak) |

This quarter hour in whatwatt mode: energy so far in the quarter (from the meter's cumulative register) + the current draw for the rest, averaged over the 15 minutes.

Phases:
0. **Check first:** the real API (endpoint, JSON fields, whether the local REST API needs the Plus licence, whether the device keeps quarter-hour or peak history); the user's meter model and the CKW decryption key (email as in the research file); **Android permissions**: newer Android may require a local network permission for LAN access when targeting SDK 37. Verify; if so it's unavoidable for any LAN approach and would break "INTERNET only", so request it only when the user picks whatwatt mode. Plain HTTP on the LAN also needs cleartext allowed (network security config).
1. **Rename and third mode** (no hardware needed): enum `PEAK_MANUAL` (keep the saved `"peak"`), `PEAK_METER`; setup guide for whatwatt: region → device address + "Test connection" → goal (imports optional); Settings per mode.
2. **Live reading:** whatwatt client in `:core` (unit-tested with a sample response), polling while visible (no background work), live kW + CHF/h, greyed out after 10 s without data. Test against a fake whatwatt on the PC that the phone reaches over Wi-Fi.
3. **Peak logic with measured values:** quarter-hour projection, budget, goal and advice as in manual mode.
4. **Test with the real device.**
5. Later: remote access (Tailscale), power curve recording and cheapest start (research features 5 and 6).

Open questions (asked 2026-09-29, not answered yet): home Wi-Fi only first (Claude: yes)? The month's peak: the app sees the meter only while open; is "highest observed quarter hour + imports" enough, or the device's own history (if any) or a logger on the Pi? Keep the appliance list in whatwatt mode (Claude: yes)? Logo for the mode (📟 or 🔌?).

## Conventions

- **Simplest approach that works.** Don't over-engineer and don't clutter the UI or the code: no extra screens, options, layers or abstractions until they are needed. When unsure, or when a simpler idea or a good idea comes up, ask the user instead of deciding alone.
- Commit only when asked; the user pushes.
