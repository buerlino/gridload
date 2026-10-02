# CLAUDE.md

Guidance for working in this repo. Project background and scope live in [README.md](README.md).

## Project

GridLoad: an Android app that shows whether now is a good time to run household appliances, based on dynamic electricity prices.

## Stack

- Native Android: Kotlin + Jetpack Compose, single Activity. One main screen, plus Settings and the setup guide. Distributed via F-Droid and Obtainium (GitHub Releases).
- No Flutter / React Native / KMP. No proprietary dependencies (Firebase, Play Services, analytics, ads). Only the `INTERNET` permission (the whatwatt may add `ACCESS_LOCAL_NETWORK`; see [Peak load mode with whatwatt](#peak-load-mode-with-whatwatt-planned-2026-10-02)).
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
- UI: a "?" button at the top right opens a help dialog: a short general part, then one short part per mode (spot: the colours, the 24-hour comparison and when tomorrow's prices come out; peak load: written when it is rebuilt on the whatwatt). The same text is the first page of the setup guide. Keep it in sync with the wording above.
- Settings and setup guide: a ⚙ at the top left opens Settings; the main screen shows the region as plain text. The **setup guide** is shown on first start and from "Open the setup guide" in Settings: the help and **Next** (first start only), the mode, the region (later the whatwatt step, see [Peak load mode with whatwatt](#peak-load-mode-with-whatwatt-planned-2026-10-02)). **Settings:** the mode cards and the region. Region, mode (`"spot"`/`"peak"`) and "first start done" are saved in SharedPreferences. The mode logos are emoji: ⚡ spot, 📊 peak (the user likes them, 2026-09-29). The mode cards say "Spot price" and "Peak load".
- Refresh policy (because of the rate limit): one response covers today and, once published, tomorrow. So on resume and every minute while visible, recompute the colour from the cached slots, and fetch only when no slot covers now, when it's after the region's `tomorrowFrom` and the cache doesn't reach tomorrow yet (`wantsFetch`), or when the user taps refresh. That's about two fetches a day. Fetches the user didn't ask for always wait the full 5-minute cooldown. Never fetch more often than a cooldown: 5 minutes after any attempt (successful or not), or 30 seconds when nothing is shown, so tapping refresh repeatedly can't hit the rate limit (`Cooldown.kt`). A blocked refresh shows a short notice instead. Switching region resets the cooldown. Keep the slots in a `ViewModel` so rotation doesn't refetch. Recomputing about every minute makes the colour change at slot boundaries.

## Current task scope

Released: **v0.6.0** (tag `v0.6.0`, versionCode 8) on GitHub Releases/Obtainium. F-Droid: merge request https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50583, in review; the recipe moves from v0.3.1 (the first version with R8, which the reviewer asked for) straight to v0.6.0, skipping v0.5.0 (user, 2026-10-02), and auto update picks up later tags once merged. The roadmap and each phase's status are in the skill.

**Reset (user, 2026-10-02).** v0.4.0 and v0.5.0 built peak load mode on manually tracked appliances (start/stop, an estimated draw per quarter hour, a goal) and an import of CKW's Excel exports. Both are dropped: the import mixes the household's two meters (see [Household and tariff](#household-and-tariff)) and only works for CKW, and a whatwatt measures what the appliance model could only estimate. The app goes back to its lean core, and peak load mode is rebuilt on the whatwatt. **No dead code:** what isn't used goes; git history keeps it.

The order:
1. **Cleanup [done, v0.6.0]:** back to spot/peak + utility. The setup guide is help, mode, region; the main screen is the colour, the price, the next good time and refresh. Peak load mode can still be chosen and shows the spot screen until the whatwatt is built. The removed code is in commit `71f954f`; the old whatwatt client in `0f9b17b`, to reuse. Migration for v0.5.0 installs: on start, `files/peak.json` (personal imported hourly data, appliances, runs) and its backup are deleted; the saved mode stays.
2. **whatwatt [next task]:** see [Peak load mode with whatwatt](#peak-load-mode-with-whatwatt-planned-2026-10-02), starting with phase 0, the checks on the device.

The app has two modes, chosen on first start and switchable in Settings (⚙ top left):
- **Spot price mode:** the screen is one colour, because at any moment you either run everything or wait.
  - Red = not good to run appliances (shown as "Bad time", "Wait if you can")
  - Orange = run only if you must (shown as "Fair time", "Only run what you need")
  - Green = run now! (shown as "Good time", "Run your appliances now")
  - With a whatwatt (planned): also the cost right now, "1.3 kW now · 0.34 CHF/h".
- **Peak load mode (planned, needs a whatwatt):** the same colour, plus the month's peak on a vertical scale and an in-app alarm before a new monthly peak.

Small saved settings (mode, region, first start done) in SharedPreferences; no database. iOS is out of scope for now. The user will give further instructions step by step.

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

## Peak load mode with whatwatt (planned 2026-10-02)

Step by step, as the user decides.

### Household and tariff

- The household has **two meters, billed separately** (user, 2026-10-02): heat pump + boiler, and everything else. The peak tariff is billed per meter, so GridLoad only cares about the **household meter**; heat pump and boiler peaks are ignored. The whatwatt Go goes on the household meter. An always-on Raspberry Pi is at home, but it is a last resort (see Recording).
- CKW's 2026 price sheet for grid products (Preisinformation Netzprodukte 2026, CKW Netz E/ES/Home dynamic) has a **Leistungstarif**: "Bei der Leistung wird die höchste während 15 Minuten beanspruchte mittlere Leistung (kW) im Monat gemessen und in Rechnung gestellt." It's per kW of the month's peak on top of the kWh prices, and **linear**: Home dynamic 1.00 CHF/kW per month excl. VAT (1.08 incl. 8.1% VAT); the standard single tariff E9 1.50 (1.62 incl. VAT); ES10 0.50. So lowering the monthly peak by 2 kW saves about 2 CHF a month.
- **No price brackets** (user, 2026-10-02: they had assumed steps; the sheet has none). Every kW above the month's highest quarter hour costs the same, so the only line that matters is that highest quarter hour.
- Still to check: which product the household is on; whether the billed quarter hours are the fixed ones (:00, :15, :30, :45), like the price slots. Keep CHF amounts for the peak out of the code.

### Design (user, 2026-10-02)

- **Peak load mode needs a whatwatt.** No manual values, no appliance list, no estimate: the meter measures everything.
- **Setup guide:** help, mode, utility, then the whatwatt step (address, **Test connection**, the meter status; **Skip**). Skip means spot price mode, prices only.
- **Spot + whatwatt:** the colour plus the cost right now, "1.3 kW now · 0.34 CHF/h" (the measured draw × the current slot's price).
- **Peak load:** records quarter hours and works from the first reading: the alarm only needs the current quarter hour and the month's highest so far.
- **Quarter hours from the energy register:** the billed peak is the average over fixed quarter hours, so take it from the meter's cumulative energy register at the quarter-hour boundaries, not from instantaneous power. This quarter hour, projected = energy so far in it + the current draw for the time left, as an average kW.
- **Storage:** quarter-hour kWh, one small file per month (e.g. `files/quarters/2026-10.json`, about 3000 values), no database.
- **Goal:** one number in Settings (kW), pre-filled with last month's recorded peak once there is one. It's needed from the 1st of a month, when nothing is recorded yet. The line not to pass is the higher of the goal and the month's highest quarter hour: anything up to the month's highest is billed anyway.
- **Alarm, in the app only (user, 2026-10-02):** when this quarter hour's projection is about to pass that line, the screen changes colour and the phone vibrates while the app is open. No background service and no notification permission for now; a background alarm is a possible later step (it needs a service polling the whatwatt, the notification permission and local network access in the background).
- **The screen:** a vertical scale with the goal, the month's highest quarter hour (with when) and this quarter hour's projection as a bar:

  ```
   kW
   3.8 ┤ ━━━ highest this month (29 Sep 17:15)
   3.1 ┤ ███ this quarter hour, projected
   3.0 ┤ ┄┄┄ goal
   0   ┴
       0.7 kW free · new quarter hour in 6 min
  ```

- **Claude's ideas, accepted (user, 2026-10-02):**
  - The alarm warns of a **new monthly peak**, not a price bracket, so it works with any linear tariff and needs no tariff data.
  - **"0.7 kW free"** as the main number: how much more can be switched on now without passing the line in this quarter hour.
  - **The quarter-hour countdown** ("new quarter hour in 6 min"): waiting a few minutes before the kettle or oven often avoids a peak.
  - **Check the meter's maximum demand register** (OBIS 1.6.0, the month's highest 15-minute average kept by many smart meters). If the whatwatt passes it on, the month's highest is exact and complete without any recording.

### Recording while away

The phone reaches the whatwatt only on home Wi-Fi, and the app reads it only while open (no background work). To fill the gaps, in this order:
1. The meter's maximum demand register, if the whatwatt reports it (see above).
2. The whatwatt's SD-card log, if its API serves it.
3. A small logger on the Pi, only if there's no other way. The app must still work without it.

Without any of them, "highest this month" is the highest the app has seen, and the screen says so.

### Hardware, CKW key and the REST API (verified 2026-09-30)

Checked against whatwatt's docs and pricing page, the smart-me wiki and Android's docs. Anything marked *unverified* comes only from the user's research notes (`private/smart_meter_research.md`, gitignored); confirm it on the device.
- **Bought:** whatwatt Go (CHF 90) + the Kamstrup Omnipower adapter (CHF 20, for Kamstrup meters) + the Plus licence (CHF 19, one-time) that unlocks the REST API. Without Plus, `/api/v1/report` answers `404 License required`.
- **CKW key:** email `messtechnik@ckw.ch` with the meter number (on the meter face, after the barcode) and CKW sends the key (smart-me wiki). For the household meter, not the heat pump's. CKW's maintenance on 2025-05-14 renewed all Kamstrup keys, so any key from before that date is invalid. Probably entered in the whatwatt's own web UI, in which case the app has no part in it beyond a hint. *Unverified:* the address `smartmeter@ckw.ch`, no fee, 1–2 business days.
- **The REST API:** `GET http://<device>/api/v1/report`. There's no auth unless the device's Web UI password is set; then it's HTTP Digest (MD5-sess, firmware 1.10+) or Basic (older firmware). Skip auth until someone hits a 401. Confirmed fields:
  - `report.instantaneous_power.active.positive.total`: kW, the current draw.
  - `report.energy.active.positive.total`: kWh imported since install, the register for the quarter hours.
  - `report.id`: counts up with each reading, so an unchanged `id` means stale data.
  - `meter.status`: e.g. `"OK"`, `"NO DATA"`, `"NOT CONNECTED"`. Trust the values only when it's `"OK"`. *Unverified:* the exact string when the key is missing ("Key required" in the manual, `"ENCRYPTION KEY"` in the notes).
  - The docs list `protocol` `"KMP"` and `interface` `"TTL"`, so Kamstrup is supported, but model every field as nullable and check the real response.
  - Also there: a REST streaming (SSE) endpoint, and SD-card CSV logging. Whether that log can be read over the API is unclear.
- **Android's local network permission:** Android 17 (API 37) adds `ACCESS_LOCAL_NETWORK`, a runtime (dangerous) permission in the `NEARBY_DEVICES` group. This app targets 37, so every LAN connection needs it, and so does resolving `.local` names.
  - It has to be declared in the manifest of every install, so **INTERNET would no longer be the only permission**. That's the user's call.
  - When the permission is missing, TCP connections just time out. So "Test connection" should check the permission first; otherwise "denied" looks the same as "wrong address".
  - **Exemption:** if the app finds devices with `NsdManager` and `DiscoveryRequest.FLAG_SHOW_PICKER`, the user picks the device in a system picker, and the app can connect to its addresses without the permission. The whatwatt announces itself as `whatwatt-XXXXXX.local` (last 6 hex digits of its id); its DNS-SD service type isn't documented.
- **Cleartext:** the manifest sets `usesCleartextTraffic="false"`. `<domain>` in the network security config takes exact hostnames or IPs, **not CIDR ranges**, so "allow cleartext on 192.168.x.x" can't be written. Either `<domain includeSubdomains="true">local</domain>` for the mDNS name (test it), or `base-config cleartextTrafficPermitted="true"` for an IP the user types in, acceptable because the compiled-in price URLs are all HTTPS.
- The whatwatt address will be saved as `whatwatt_address` in SharedPreferences.

### Phases

0. **Checks on the device** (the user, with Claude's commands; save raw responses to `private/`, they contain the meter's id):
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
1. **whatwatt step and Settings card:** address, Test connection, meter status, Skip. Cleartext and the permission settled by phase 0. Then spot mode's cost line.
2. **Live reading and recording:** poll while visible (no background work), quarter hours from the register, the monthly files.
3. **Peak load screen:** the scale, kW free, the countdown, the goal, the in-app alarm.
4. **Gaps:** the maximum demand register or the SD card; the Pi only if neither works.
5. Later, if wanted: a background alarm, remote access.

Open questions: `ACCESS_LOCAL_NETWORK` as a second permission, or the picker exemption only? How early the alarm warns (a margin in kW, or minutes left in the quarter hour)? Icon for the whatwatt step (📟 or 🔌)?

## UI text (reworked 2026-09-30)

The UI copy is inline Kotlin string literals in `MainActivity.kt`, `SettingsScreen.kt` and `MainViewModel.kt`. No `strings.xml`: the app is English-only, and the user chose to reword in place (2026-09-30); extracting the strings stays an optional later step.

Rules (user, 2026-09-30: the texts were too long, and some titles misleading):
- Short, one idea per line. Drop anything the screen already shows.
- Each concept is explained in one place: the colours and modes in the help (`HelpContent`) and the mode cards (one line each).

## Conventions

- **Simplest approach that works.** Don't over-engineer and don't clutter the UI or the code: no extra screens, options, layers or abstractions until they are needed. When unsure, or when a simpler idea or a good idea comes up, ask the user instead of deciding alone.
- **No dead code** (user, 2026-10-02): remove what isn't used; git history keeps it.
- Commit only when asked; the user pushes.
