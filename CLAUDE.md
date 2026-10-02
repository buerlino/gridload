# CLAUDE.md

Guidance for working in this repo. Project background and scope live in [README.md](README.md).

## Project

GridLoad: an Android app that shows whether now is a good time to run household appliances, based on dynamic electricity prices.

## Stack

- Native Android: Kotlin + Jetpack Compose, single Activity. One main screen, plus Settings and the setup guide. Distributed via F-Droid and Obtainium (GitHub Releases).
- No Flutter / React Native / KMP. No proprietary dependencies (Firebase, Play Services, analytics, ads). Permissions: `INTERNET` and, for the whatwatt, `ACCESS_LOCAL_NETWORK` and `VIBRATE` for the in-app peak alarm (user, 2026-10-02; see [Peak load with whatwatt](#peak-load-with-whatwatt-planned-2026-10-02)).
- A small `core` package with no Android dependencies holds the regions, the API client, the JSON model and the classification (later the whatwatt client and the peak logic). Unit-test it directly.
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
- UI: a "?" button at the top right opens a help dialog (`HelpContent`): a short general part, then "⚡ Prices" (the colours, the 24-hour comparison, when tomorrow's prices come out, the green dot for the next good time, the whatwatt's cost line) and "📊 Peak load" (the month's highest quarter hour, the scale and kW free, the red bar and vibration, that it needs a whatwatt and only sees quarter hours while open). The same text is the first page of the setup guide. Keep it in sync with the wording above.
- Settings and setup guide (since the [UI rework](#ui-rework-user-2026-10-02-built-not-released)): a ⚙ at the top left opens Settings, and tapping the region text in the top bar opens Settings with the region list open. **Settings:** the title row with a "Setup guide" button, then **⚡ Region** (dropdown) and **Measurement**: the **📟 whatwatt** switch; when on, the connection block and the **📊 Peak load** switch; when peak load is on, the **Goal**, **Show the scale without a reading** and **Quarter-hour countdown** switches. The **setup guide** is shown on first start and from the Settings button: the help and **Next** (first start only), **⚡ Region** (cards), **Measurement** (whatwatt connection, the Peak load switch once connected, Done or Skip). Labels with ⓘ open a short help dialog. The emoji are the user's (⚡ prices, 📟 whatwatt, 📊 peak load; "keep all emojis", 2026-10-02). Saved in SharedPreferences: `region`, `first_start_done`, `whatwatt_enabled`, `whatwatt_address`, `peak_enabled`, `peak_goal_enabled`, `peak_goal_kw`, `peak_scale_without_reading`, `peak_countdown`.
- Refresh policy (because of the rate limit): one response covers today and, once published, tomorrow. So on resume and every minute while visible, recompute the colour from the cached slots, and fetch only when no slot covers now, when it's after the region's `tomorrowFrom` and the cache doesn't reach tomorrow yet (`wantsFetch`), or when the user taps refresh. That's about two fetches a day. Fetches the user didn't ask for always wait the full 5-minute cooldown. Never fetch more often than a cooldown: 5 minutes after any attempt (successful or not), or 30 seconds when nothing is shown, so tapping refresh repeatedly can't hit the rate limit (`Cooldown.kt`). A blocked refresh shows a short notice instead. Switching region resets the cooldown. Keep the slots in a `ViewModel` so rotation doesn't refetch. Recomputing about every minute makes the colour change at slot boundaries.

## Current task scope

Released: **v0.6.0** (tag `v0.6.0`, versionCode 8) on GitHub Releases/Obtainium. F-Droid: merge request https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50583, in review; the recipe moves from v0.3.1 (the first version with R8, which the reviewer asked for) straight to v0.6.0, skipping v0.5.0 (user, 2026-10-02), and auto update picks up later tags once merged. The roadmap and each phase's status are in the skill.

**Reset (user, 2026-10-02).** v0.4.0 and v0.5.0 built peak load mode on manually tracked appliances (start/stop, an estimated draw per quarter hour, a goal) and an import of CKW's Excel exports. Both are dropped: the import mixes the household's two meters (see [Household and tariff](#household-and-tariff)) and only works for CKW, and a whatwatt measures what the appliance model could only estimate. The app goes back to its lean core, and peak load mode is rebuilt on the whatwatt. **No dead code:** what isn't used goes; git history keeps it.

The order:
1. **Cleanup [done, v0.6.0]:** back to spot/peak + utility. The setup guide is help, mode, region; the main screen is the colour, the price, the next good time and refresh. Peak load mode can still be chosen and shows the spot screen until the whatwatt is built. The removed code is in commit `71f954f`; the old whatwatt client in `0f9b17b`, to reuse. Migration for v0.5.0 installs: on start, `files/peak.json` (personal imported hourly data, appliances, runs) and its backup are deleted; the saved mode stays.
2. **whatwatt [phases 0 to 3 done 2026-10-02, not released]:** see [Peak load with whatwatt](#peak-load-with-whatwatt-planned-2026-10-02).
3. **UI rework [built 2026-10-02, tested on the phone in debug and R8 builds, not released]:** the user's feedback on phase 3, see [UI rework](#ui-rework-user-2026-10-02-built-not-released). Next: release v0.7.0, then whatwatt phase 4.

No modes since the UI rework (user, 2026-10-02): the spot price always shows, and peak load is a switch under Measurement.
- **The price:** the screen is one colour, because at any moment you either run everything or wait. Only the headline shows; the help explains the colours.
  - Red = not good to run appliances ("Bad time")
  - Orange = run only if you must ("Fair time")
  - Green = run now! ("Good time")
  - Below: the price, with a whatwatt the cost right now ("1.3 kW now · 0.34 CHF/h", since phase 1), and the next good time as a green dot and the time ("● tomorrow 10:00").
- **Peak load (needs a whatwatt, since phase 3):** the same colour, plus a white window with the month's peak on a vertical scale and an in-app alarm before a new monthly peak.

Small saved settings in SharedPreferences; no database. iOS is out of scope for now. The user will give further instructions step by step.

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
- **Energy-Charts** (EPEX spot CH, hourly, EUR/MWh, no key) and **ENTSO-E** (needs a token): market prices, not a household tariff in Switzerland. Outside Switzerland (Germany, Austria, Liechtenstein), though, the spot price gives the same colour as a real dynamic tariff: see [research/neighbouring_countries.md](research/neighbouring_countries.md) (2026-09-30, not implemented yet).
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

## Peak load with whatwatt (planned 2026-10-02)

Step by step, as the user decides.

### Household and tariff

- The household has **two meters, billed separately** (user, 2026-10-02): heat pump + boiler, and everything else. The peak tariff is billed per meter, so GridLoad only cares about the **household meter**; heat pump and boiler peaks are ignored. The whatwatt Go goes on the household meter. An always-on Raspberry Pi is at home, but it is a last resort (see Recording).
- CKW's 2026 price sheet for grid products (Preisinformation Netzprodukte 2026, CKW Netz E/ES/Home dynamic) has a **Leistungstarif**: "Bei der Leistung wird die höchste während 15 Minuten beanspruchte mittlere Leistung (kW) im Monat gemessen und in Rechnung gestellt." It's per kW of the month's peak on top of the kWh prices, and **linear**: Home dynamic 1.00 CHF/kW per month excl. VAT (1.08 incl. 8.1% VAT); the standard single tariff E9 1.50 (1.62 incl. VAT); ES10 0.50. So lowering the monthly peak by 2 kW saves about 2 CHF a month.
- **No price brackets** (user, 2026-10-02: they had assumed steps; the sheet has none). Every kW above the month's highest quarter hour costs the same, so the only line that matters is that highest quarter hour.
- Still to check: which product the household is on; whether the billed quarter hours are the fixed ones (:00, :15, :30, :45), like the price slots. Keep CHF amounts for the peak out of the code.

### Design (user, 2026-10-02)

- **Peak load needs a whatwatt.** No manual values, no appliance list, no estimate: the meter measures everything.
- **Setup guide:** help, Region, then Measurement (📟 whatwatt, address and **Test**, the result line; the Peak load switch once connected; **Done** or **Skip**). [built, reworked in the UI rework] Done switches the whatwatt on; Skip switches it and peak load off, so only prices show.
- **The whatwatt switch in Settings (user, 2026-10-02):** "whatwatt" with a switch. Off, that's all Settings shows, the whatwatt isn't read, and nothing about it appears on the main screen (no "not reachable"). On, the connection block (address, Test, result) and the Peak load switch appear below it; the explanation is in the ⓘ dialog. Saved as `whatwatt_enabled`; the address stays saved while it's off. [built]
- **Spot + whatwatt:** the colour plus the cost right now, "1.3 kW now · 0.34 CHF/h" (the measured draw × the current slot's price). [built] The draw stays in kW with one decimal, also for small draws (user, 2026-10-02). When the whatwatt can't be read (away from home, rebooting), one quiet line, "whatwatt not reachable", instead of a stale value (user, 2026-10-02).
- **Both prices count (Claude's suggestion, accepted by the user 2026-10-02):** on CKW Home dynamic every kWh has its time-dependent price *and* the month's peak is billed per kW. Spot says *when* to run something, peak *how much at once*. So peak load is the spot screen plus the peak scale, not a replacement. The user's original idea was the price right now ("the sun is out, running the washing machine feels better").
- **Peak load:** records quarter hours and works from the first reading: the alarm only needs the current quarter hour and the month's highest so far.
- **Quarter hours from the energy register:** the billed peak is the average over fixed quarter hours, so take it from the meter's cumulative energy register at the quarter-hour boundaries, not from instantaneous power. This quarter hour, projected = energy so far in it + the current draw for the time left, as an average kW.
  - **Built (phase 2):** `QuarterRecorder` interpolates the register at each boundary between the readings either side, using the **meter's clock** (`report.date_time_utc`). A quarter is recorded only when both boundaries were seen with readings at most 30 s apart (at 3 kW at most ±0.1 kW of the quarter's average); otherwise it's left out for phase 4. A register going backwards, or a new address, starts over. Recorded with peak load on or off while the whatwatt is switched on (Claude's choice, 2026-10-02: costs nothing and gives last month's peak for the goal).
- **Storage:** quarter-hour kWh, one small file per month in Europe/Zurich, `files/quarters/2026-10.json`, keyed by the local start: `{"2026-10-02T18:15+02:00":0.0278, ...}` (about 3000 values, ~100 KB), written atomically (`QuarterStore`). No database.
- **Goal (a switch since the UI rework):** `peak_goal_enabled` (default off) and a kW field below it when on, `peak_goal_kw` (a string), pre-filled with last month's highest seen (rounded to 0.1) the first time it's switched on, if there is one. Useful from the 1st of a month, when nothing is recorded yet. The line not to pass is the higher of the goal and the month's highest quarter hour: anything up to the month's highest is billed anyway (`UiState.peakLine`, `activeGoalKw`). Off or blank: the line is only this month's highest. With no line (nothing recorded, no goal), the window shows only the bars, with no "kW free" and no alarm. [built]
- **Alarm, in the app only (user, 2026-10-02):** when this quarter hour's projection reaches 90% of that line (a 10% margin, user, 2026-10-02), **the current bar turns red** (the whole bar; "kW free" keeps its colour), a red line says "Close to a new peak. Wait before switching more on." (above the line: "x kW over", "This quarter hour sets a new peak."), and the phone **vibrates once per quarter hour** (400 ms), only with peak load on while the app is open (user, 2026-10-02, after the mockup; the screen keeps its spot colour). [built] No background service and no notification permission for now; a background alarm is a possible later step (it needs a service polling the whatwatt, the notification permission and local network access in the background).
- **The screen [built, phase 3, changed in the UI rework]:** the spot colour stays the background; headline and price get smaller, and below them a **white window** (user, 2026-10-02) holds a vertical scale: bars for the **two previous recorded quarter hours** (grey, labelled with their start time) and this quarter hour's projection (dark, "now") on the right, so each new quarter moves the bars one step left and the oldest drops off (user, 2026-10-02; `PAST_BARS`, a missed quarter shows "–"). Across them the month's highest (solid, "0.8 highest seen", no date since the UI rework) and the goal (dashed, when switched on); then "0.7 kW free" (worked out from the values as drawn, rounded to 0.1, so a "0.9" line and a "0.8" bar always give "0.1 kW free"; the red bar and vibration use the exact values, user, 2026-10-02), "New quarter hour in 6 min" (only with the `peak_countdown` switch, default off) and "Highest seen only while GridLoad is open." (until phase 4). Mockup: https://claude.ai/artifact/3tWHE5VrfVvUcTpJStg6g5 (the alarm and past bars changed after it).

  ```
   kW
   3.8 ┤ ━━━━━━━━━━━━━━ 3.8 highest seen
   3.1 ┤ ▒▒  ▒▒  ███
   3.0 ┤ ┄┄┄┄┄┄┄┄┄┄┄┄┄┄ 3.0 goal
   0   ┴ 17:45 18:00 now
       0.7 kW free
       New quarter hour in 6 min
  ```

- **Without a reading (user, 2026-10-02):** a Settings switch "Show the scale without a reading" (`peak_scale_without_reading`, default on): on, the window stays with the goal, the highest and the past bars, no current bar, and "whatwatt not reachable" in it; off, the window is hidden and the quiet line is under the price, as without peak load.
- **Opening mid quarter (not complete until phase 4, user agreed 2026-10-02):** when the app didn't see the quarter's start, `QuarterRecorder.projection` assumes the draw before the first reading was the average since then (the current draw in the first minute) and the window says "Estimated: GridLoad opened during this quarter hour." From the next boundary on it's exact. Phase 4 can take the missing part from the SD card.

- **Claude's ideas, accepted (user, 2026-10-02):**
  - The alarm warns of a **new monthly peak**, not a price bracket, so it works with any linear tariff and needs no tariff data.
  - **"0.7 kW free"** as the main number: how much more can be switched on now without passing the line in this quarter hour.
  - **The quarter-hour countdown** ("new quarter hour in 6 min"): waiting a few minutes before the kettle or oven often avoids a peak.
  - **Check the meter's maximum demand register** (OBIS 1.6.0, the month's highest 15-minute average kept by many smart meters). If the whatwatt passes it on, the month's highest is exact and complete without any recording. Checked 2026-10-02: **not available** on the Kamstrup (see below).

### Recording while away

The phone reaches the whatwatt only on home Wi-Fi, and the app reads it only while open (no background work). To fill the gaps, in this order:
1. ~~The meter's maximum demand register~~: the Kamstrup over KMP doesn't report one (checked 2026-10-02).
2. **The whatwatt's SD-card log: works.** It logs the energy register every 15 s and the API serves it (see below). On reopening, the app can download the missing days and compute every quarter hour exactly. This is phase 4.
3. A small logger on the Pi, only if there's no other way. Not needed now. The app must still work without it.

Without any of them, "highest this month" is the highest the app has seen, and the screen says so.

### Hardware, CKW key and the REST API (verified on the device 2026-10-02)

Raw responses are in `private/` (gitignored; they contain the meter's id): `whatwatt_system_*.json`, `whatwatt_report_2026-10-02.txt`, `whatwatt_settings_2026-10-02.json`, `whatwatt_live_2026-10-02.txt`, `whatwatt_poll_2026-10-02.txt`, `whatwatt_sd_20261002.CSV`. Docs: https://documentation.whatwatt.ch.
- **The device:** whatwatt Go `WW_Go_1.3`, firmware 2.8.2, on the household meter, a Kamstrup OMNIPOWER rev. AF1 (`protocol` `"KMP"`, `interface` `"TTL"`), through the Kamstrup adapter. 2.4 GHz Wi-Fi, RSSI −64 to −72 at the meter. Powered by the meter alone (no socket there). Kamstrup meters supply little power, so polling stays gentle. It stayed up through 2 s polls for a minute and an hour of 15 s SD logging (check `device.last_reboot` in `/api/v1/system`). At home: `http://192.168.0.36` (DHCP reservation set, 2026-10-02), hostname `whatwatt-7C02F0`. `avahi-browse` didn't find it, so no DNS-SD discovery.
- **Bought:** whatwatt Go (CHF 90) + the Kamstrup Omnipower adapter (CHF 20) + the Plus licence (CHF 19, one-time), activated 2026-10-02. The licence sits **on the device** (`device.license.type` `"PLUS"` in `/api/v1/system`), so every phone on the home network can use it. No whatwatt account was made; GridLoad must not need one.
- **CKW key:** email `messtechnik@ckw.ch` with the meter number (on the meter face, after the barcode). CKW's maintenance on 2025-05-14 renewed all Kamstrup keys, so any older key is invalid. Entered in the whatwatt web UI (Meter page: Encryption on, Key 1); the app only shows a hint.
- **`meter.status`:** `"NOT CONNECTED"` with no meter (interface `"NONE"`), `"KEY REQUIRED"` before the key, `"OK"` after it (`enc_en: true`). Trust the values only when it's `"OK"`.
- **Auth:** Device Protection is off, and the API answers without credentials. With it on, every endpoint answers 401 with `WWW-Authenticate: Digest realm="whatwatt-<id>", algorithm=MD5-sess, qop=auth`, empty username. `HttpURLConnection` doesn't do Digest, so the app doesn't either: on 401, Test connection says to turn Device Protection off (user, 2026-10-02). A lockout is fixed by a factory reset (hold the button 10 s or more, then redo the Wi-Fi setup over AP mode).
- **`GET /api/v1/report`** (needs Plus, else `404` with body `License required`). The app reads it (user, 2026-10-02): unlike the stream, several readers can poll at once, e.g. two phones in the household. Real fields (all modelled nullable):
  - `report.instantaneous_power.active.positive.total`: kW, the current draw.
  - `report.energy.active.positive.total`: kWh imported, the register for the quarter hours; also `t1`/`t2` (tariff registers, which add up to the total). Resolution **0.001 kWh**, so a 0.2 kW quarter hour (50 Wh) is about ±2% off.
  - `report.id`: +1 with each meter reading, every ~4.2 s (`report.interval`), so an unchanged `id` means stale data. It restarts at boot.
  - Timestamps: `report.date_time_utc` / `system.date_time_utc` are real UTC. **`date_time` ends in `Z` but is local time** (firmware quirk), so never use it. `date_time_local` has the offset. `report.*` is the **meter's** clock (equal to `meter.date`/`meter.time` in `/api/v1/system`), `system.*` the whatwatt's: against the PC (NTP), the meter's was right to within its 4.2 s reading interval, the whatwatt's 22 s fast, though it says NTP synced (2026-10-02). The app uses the meter's.
  - `meter.status`, `meter.id` (the meter number), `system.boot_id`, `system.time_since_boot`.
- **`GET /api/v1/report/objects`** (Plus): empty for this meter. KMP isn't DLMS, so there are no OBIS objects and **no maximum demand register**.
- **`GET /api/v1/live`:** an SSE stream, `event: live` with flat JSON (`P_In` kW, `E_In` kWh, `E_In_T1/T2`, `Date`, `Time` local), one event per meter reading. It works **without** the Plus licence, but only one client at a time: a new one closes the previous stream. Not used, because of that limit; it's the fallback if a licence-free option is ever wanted.
- **SD card** (SDHC, installed): logging turned on 2026-10-02 via `PUT /api/v1/settings` `{"services":{"sd":{"enable":true}}}` (PUT merges partial settings). `services.sd.frequency` is in **seconds** (15 = a row every ~15–17 s, every 4th reading). `GET /sdcard/` lists the files (503 without a card; the listed `size` lagged at 0 while the file had rows), `GET /sdcard/YYYYMMDD.CSV` downloads one day: a header line, then ~250 bytes per row (~1.2 MB/day at 15 s). Columns include `RID` (= `report.id`), `TIME` (**local, despite the `Z`**, as above), `MSTAT`, `EAP_T`/`EAP_T1`/`EAP_T2` (the register, kWh), `IPAP_T` (kW), `RP` (report period, ms). `MDAP_*` (maximum demand) exist but are empty for this meter. Kept at 15 s (2026-10-02): the user worried that sparse rows plus readings of 0 could skew the quarter hours, but quarter hours come from the register's difference, which no momentary reading affects. The interval only sets how close a row is to the boundary (at 3 kW: about ±0.025 kW of the quarter-hour average at 15 s, ±0.1 kW at 60 s). Skip rows that aren't `MSTAT` `OK` or where the register goes backwards (none in the first 93 rows).
- **Android's local network permission:** Android 17 (API 37) adds `ACCESS_LOCAL_NETWORK`, a runtime permission in the `NEARBY_DEVICES` group. The app targets 37, so on Android 17 every LAN connection needs it. **Declared in the manifest** (user, 2026-10-02), and asked for in the whatwatt step on the first Test connection (`SDK_INT >= 37`). Without it, connections just time out, hence the check before testing. The picker exemption (`NsdManager` + `FLAG_SHOW_PICKER`) isn't used, since the whatwatt didn't show up over DNS-SD. The user's phone is on API 36, so the permission prompt is untested.
- **Cleartext:** `usesCleartextTraffic="true"` in the manifest (user, 2026-10-02). `<domain>` in a network security config can't express "192.168.x.x", and the compiled-in price URLs are all HTTPS anyway.
- The address is typed in (IP or hostname, URL keyboard) and saved as `whatwatt_address` in SharedPreferences, next to the switch `whatwatt_enabled`. While switched on, the app reads `report` every 5 s while visible (user, 2026-10-02), with 3 s timeouts.

### Phases

0. **Checks on the device [done 2026-10-02; findings above]** (the user, with Claude's commands; save raw responses to `private/`, they contain the meter's id):
   - The household meter's model and the adapter; Wi-Fi signal at the meter.
   - Where the key is entered (web UI or API); the exact `meter.status` before the key is set.
   - `curl -i http://<device>/api/v1/report` gives 200 (Plus licence).
   - The full JSON with and without the key: the fields above, the energy register's resolution (at 0.01 kWh steps a 0.2 kW quarter hour is ±20% off), any timestamp (meter or device time, UTC or local).
   - How often `report.id` and the register change (poll every 2 s for a minute).
   - A maximum demand field (OBIS 1.6.0) anywhere in the API.
   - The SD card: can the log be listed and downloaded over the API, what is logged and how often, does it need a card.
   - Other endpoints: the SSE stream's URL and rate, any history endpoint.
   - Network: the DNS-SD service type (`avahi-browse -art`), a DHCP reservation, the phone's API level (`adb shell getprop ro.build.version.sdk`; 37 means `ACCESS_LOCAL_NETWORK` applies now; the user's phone was 36 on 2026-10-02, so not yet there, but Android 17 users need it).
   - Auth: the web UI password off; if set, Digest or Basic.
1. **whatwatt step and Settings card [done 2026-10-02, tested on the phone in an R8 release build]:** address, Test connection, meter status, Skip. Then the cost line and the quiet "whatwatt not reachable" line. `core/.../Whatwatt.kt` (`fetchMeterReading`, `parseMeterReading`; the test uses a real, anonymized response).
2. **Live reading and recording [done 2026-10-02, tested on the phone in debug and R8 release builds]:** poll while visible (no background work), quarter hours from the register, the monthly files. `core/.../Quarters.kt`. Checked against the SD log, interpolated the same way: 18:15 quarter 0.0270 kWh in the app, 0.0265 on the SD card; 18:45 quarter 0.0852 against 0.0856, within the register's 0.001 kWh. So the SD log's `TIME` is on the meter's clock too.
3. **Peak load screen [done 2026-10-02, tested on the phone in debug and R8 release builds]:** the scale with the two past quarters, kW free, the countdown, the goal, the in-app alarm, the no-reading switch (`PeakWindow.kt`; `Projection`, `peakLine`, `isPeakWarning` in `Quarters.kt`).
4. **Gaps:** from the SD card's CSV (the maximum demand register isn't available); the Pi only if that fails.
5. Later, if wanted: a background alarm, remote access.

Settled 2026-10-02 (user): `ACCESS_LOCAL_NETWORK` declared, `VIBRATE` for the alarm (declared since phase 3), the alarm at 90% of the line, 📟 for the whatwatt step. SD log interval: 15 s. Open: which CKW product the household is on ("later").

## UI rework (user, 2026-10-02) [built, not released]

The user's feedback after trying phase 3 on the phone. Built in one pass on 2026-10-02 and tested on the phone (debug and R8): the migration on the user's real prefs, the main screen, Settings, the dialogs and the manual link, collapse and expand, Test, a wrong address, the setup guide from Settings and as a first start. Numbers are the user's.

Decided while building (user, 2026-10-02):
- Only the connection block collapses (address, Test, result); the peak switches stay visible.
- Keep all emoji: ⚡ on Region and the help's "Prices", 📟 on whatwatt, 📊 on Peak load.
- Tappable labels show a small ⓘ after them.
- **Open:** the user is rethinking the categorization now that the modes are gone (whether Measurement and whatwatt get one dialog or two, and the section layout). Until then "Measurement" is a plain title with no dialog; ask the user before changing it.
- "Connected" (`UiState.whatwattConnected`) is the last result of a Test or a reading. Editing the address keeps the block open until a Test succeeds, so it doesn't collapse while typing.

**Modes go away (14, decided 2026-10-02).** Peak load mode had become "spot + peak", so the choice was redundant: the spot price always shows, and **Peak load** is a switch under Measurement, shown only while the whatwatt is on. `Mode`, the mode cards and the mode step are gone. Migration (in `MainViewModel`'s first `init`): a saved `mode == "peak"` with `whatwatt_enabled` gives `peak_enabled = true`, a saved `peak_goal_kw` gives `peak_goal_enabled = true` (it was a goal the user had typed), then `mode` is dropped. The help's two parts are "⚡ Prices" and "📊 Peak load".

**Main screen**
- (4) Drop the hint lines ("Run your appliances now", "Only run what you need", "Wait if you can"). The headline (Good, Fair, Bad time) stays; the setup help and "?" explain the colours.
- (5) Replace "Next good time: tomorrow 10:00" with a small **green circle** (the app's `GREEN`; give it a thin outline in the text colour so it shows on any background) and just the time: "● 15:00" or "● tomorrow 10:00".
- (6) Tapping the region text in the top bar opens Settings with the region dropdown already open.
- (2) The peak window drops the date and time of the month's highest: the label is just "0.8 highest seen". (Claude's reason for showing it was to recognise which appliance run caused the peak. The user found it unnecessary; the history tile (16) is the better place for it.)
- (3) "New quarter hour in N min" is hidden by default; a Settings switch "Quarter-hour countdown" (`peak_countdown`, default off) shows it.
- (16) **Later, not in this pass; design it with the user first:** a history of the past month as a new tile below the peak window. With it, the part below the spot header scrolls while the spot part (colour, headline, price, cost line, next good time) stays fixed at the top.

**Settings** (Points 7–13 and 15)
- (8) The title row is "Settings" with a "Setup guide" button on its right; the "Open the setup guide" button at the bottom goes.
- (9, 15) The sections, in order, with exactly these titles: **Region**, then **Measurement**. Under Measurement: the **whatwatt** switch; when on, the connection block, then the **Peak load** switch; when peak load is on, **Goal** (switch), **Show the scale without a reading** (switch) and **Quarter-hour countdown** (switch).
- (1) **Goal** is a switch (`peak_goal_enabled`, default off) with the kW field below it when on (`peak_goal_kw`, pre-filled with last month's highest seen the first time it's switched on, if there is one). **Off: no goal; the line is only this month's highest** (user, 2026-10-02). With nothing recorded yet, there's no line and no warning: the window shows the bars only, and no "Set a goal" text. Drop the automatic fallback to last month (`effectiveGoalKw`).
- (7) The connection block: the address field with the **Test** button on its right in the same row, and the result line below. The explanation paragraph moves into the whatwatt dialog.
- (13) **Collapse when connected:** once the whatwatt is reachable (the last Test succeeded, or the app has a good reading), the connection block shrinks to one row, "192.168.0.36 · connected", with an expand button on the right. A successful Test collapses it right away; when it's not reachable, it stays open. The user said "until the Show the scale… setting". Confirmed: only the connection block collapses.
- (10) Tapping a setting's label (Region, Measurement, whatwatt, Peak load, Goal, Show the scale without a reading, Quarter-hour countdown) opens a short dialog with its help text.
- (11) Keep the text on the screens and in Settings minimal (labels, values, a result line); the dialogs explain in a few more sentences. Draft texts (shorten as needed):
  - Region: "GridLoad uses your utility's dynamic tariff. Only utilities that publish one are listed. Pick the one on your electricity bill."
  - Measurement / whatwatt: "A whatwatt Go reads your smart meter over your home Wi-Fi. GridLoad then shows what you use right now and what it costs, and can watch your monthly peak. It needs the whatwatt Plus licence and works only while your phone is on your home Wi-Fi. Reserve the device's address in your router so it stays the same." Then the link (12).
  - Peak load: "Some grid tariffs also charge for the month's highest quarter hour: your average kW over 15 minutes. The scale shows this quarter hour, the two before and the month's highest. Close to a new peak, the bar turns red and the phone vibrates. GridLoad only sees quarter hours while it's open."
  - Goal: "Off: the line not to pass is this month's highest quarter hour. On: your own value in kW. The line is then the higher of the two, since everything up to the month's highest is billed anyway."
  - Show the scale without a reading: "When the whatwatt can't be read, e.g. away from home: on keeps the scale with the recorded quarter hours, off hides it."
  - Quarter-hour countdown: "Shows the minutes until the next quarter hour. Waiting a few minutes before switching on a big appliance can keep it out of the current one."
- (12) The whatwatt dialog links to the **whatwatt Go Reference Manual**, https://whatwatt.ch/doc/whatwatt_Go_Reference_Manual_v1.0.pdf (checked 2026-10-02: 200, v1.0 of 27 Feb 2025, 46 pages, covers the setup and says the REST API needs a licence). Open it with Compose's `LocalUriHandler` (no new permission). The product page https://whatwatt.ch/en/product is a sales page and isn't used.

**Setup guide (9, 15):** help and Next (first start only), then **Region**, then **Measurement** (the whatwatt connection, and the Peak load switch once connected), with the same titles and the same minimal text as Settings. Skip turns the whatwatt and peak load off.

## UI text (reworked 2026-09-30)

The UI copy is inline Kotlin string literals in `MainActivity.kt`, `PeakWindow.kt`, `SettingsScreen.kt` and `MainViewModel.kt`. No `strings.xml`: the app is English-only, and the user chose to reword in place (2026-09-30); extracting the strings stays an optional later step.

Rules (user, 2026-09-30: the texts were too long, and some titles misleading):
- Short, one idea per line. Drop anything the screen already shows.
- Each concept is explained in one place: the colours and peak load in the help (`HelpContent`), each setting in its ⓘ dialog (`Info` in `SettingsScreen.kt`).
- From the UI rework (user, 2026-10-02): screens and Settings show only labels, values and result lines; the explanations move into the "?" help and the tap-a-label dialogs in Settings, which may go into a bit more detail.

## Conventions

- **Simplest approach that works.** Don't over-engineer and don't clutter the UI or the code: no extra screens, options, layers or abstractions until they are needed. When unsure, or when a simpler idea or a good idea comes up, ask the user instead of deciding alone.
- **No dead code** (user, 2026-10-02): remove what isn't used; git history keeps it.
- Commit only when asked; the user pushes.
