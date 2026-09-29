---
name: build-gridload-android
description: Execution brief for building the GridLoad Android app — a native Kotlin/Jetpack Compose app showing a red/orange/green "good time to run appliances" indicator, distributed via F-Droid and Obtainium. Use this skill whenever working in the gridload repo on the Android app itself: scaffolding the Gradle project, writing the API client or classification logic, building the Compose UI, setting up signing/release/CI, or preparing the F-Droid (fdroiddata) submission. The platform choice (native Android, no Flutter/React Native/KMP) is already decided here — do not re-open that question; just execute.
---

# Build GridLoad Android

This captures a plan already agreed with the user in a prior planning conversation. Your job is
to pick up execution where that conversation left off, in order — not to redesign the app or
re-litigate the platform choice.

`CLAUDE.md` at the repo root is the source of truth for the repo's stack line, the API schema,
the chosen thresholds, and commit conventions once those get recorded. Read it first. If anything
in this skill and `CLAUDE.md` disagree once `CLAUDE.md` has been updated by later work, `CLAUDE.md`
wins — update this skill to match rather than trusting a stale copy here.

## Goal

One-screen Android app. It shows a single colored field:

- **Red** = not good to run appliances
- **Orange** = run only if you must
- **Green** = run now!

The color is derived from the dynamic electricity price API:
`https://e-ckw-public-data.de-c1.eu1.cloudhub.io/api/v1/netzinformationen/energie/dynamische-preise`

Target distribution is F-Droid + Obtainium. Nothing else — no appliance management, no
scheduling/optimization, no accounts. Keep resisting scope creep; this is deliberately minimal.

## Platform decision (settled — do not relitigate)

Native Android: **Kotlin + Jetpack Compose**. Reasoning, for context if it's ever questioned:

- The whole app is "fetch one JSON endpoint, compare a number, show a color" — there's no shared
  business logic substantial enough to justify Flutter, React Native, or Kotlin Multiplatform.
- F-Droid builds everything from source, reproducibly, via Gradle. Plain Kotlin/Compose is the
  easiest thing for their build pipeline and reviewers to vet — small dependency tree, no bundled
  engine (Flutter), no npm dependency tree to license-audit (React Native).
- iOS is explicitly deferred. When it's greenlit, the default plan is a small native SwiftUI
  rewrite, not a cross-platform framework — but that call gets revisited then, based on how much
  logic actually exists to share at that point. Don't preemptively build for it now (e.g. don't
  reach for KMP "just in case").

## Architecture

- **Single-Activity Compose app**, one screen: the big colored field, a region switcher, a
  last-updated timestamp, and a manual refresh (pull-to-refresh or a button). Nothing more.
  The price data is utility-specific (CKW serves only Central Switzerland), so the user picks a
  region; add new ones to `REGIONS` in `core/.../Region.kt`.
- **A small, framework-free `core` package/module** holding:
  - the region list (`Region`, `REGIONS`)
  - the API client — plain `HttpURLConnection` or OkHttp (Apache-2, F-Droid-clean); no
    Retrofit/Ktor, there's only one endpoint
  - the response model + JSON parsing
  - the red/orange/green classification function

  Keep `core` free of Android framework/UI dependencies and unit-test it directly. This is also
  what would get ported first if a native iOS app ever happens — keep it small and readable for
  that reason too, not just for testability.
- **No background work for v1.** Fetch on app open/resume plus manual refresh only. This keeps
  the only required permission to `INTERNET`, and avoids WorkManager complexity that the "very
  very basic" scope doesn't call for. (Background refresh + notifications are a plausible later
  add-on, not now.)
- **No proprietary/non-free dependencies.** No Firebase, no Play Services, no ads, no
  analytics/crash SDKs. Any of these either breaks F-Droid inclusion outright or adds an
  anti-feature flag for zero benefit to a one-screen app.

## F-Droid + Obtainium distribution

- The repo is already GPLv3-licensed — F-Droid-compatible, nothing to change there.
- Two independent tracks, both worth setting up:
  1. **GitHub Releases** with a self-signed release APK, tagged `vX.Y.Z`. Obtainium can track
     this directly with no review wait — this is the fast path to "the user can actually install
     it."
  2. **F-Droid's `fdroiddata` repo**, submitted once there's a stable tagged release. Their
     review/build can take weeks — start this in parallel once step 6 below is solid, don't block
     everything else on it.
- Needs a release keystore, generated once, **kept out of git** (verify `.gitignore` covers
  keystore files before generating one).
- Standard `versionCode`/`versionName` bump per release. An optional GitHub Actions workflow can
  build and attach the APK to a GitHub Release on tag push — worth doing since it removes manual
  signing-and-uploading toil from every release.
- `minSdk` around 24–26. Only the `INTERNET` permission. Avoid cleartext (non-HTTPS) traffic
  unless the API forces it — check when you inspect the real response.

## Before writing any classification code: inspect the real API

Do this first, before scaffolding anything else. Writing the color-threshold logic against
assumptions instead of the real response is the most likely place this goes wrong.

1. Call the API and record in `CLAUDE.md`: the JSON shape, field names and units, the timezone
   the timestamps use, and whether prices are already per-kWh or need conversion.
2. Decide the red/orange/green thresholds — e.g. relative to that day's min/max price vs. fixed
   price bands — and record the decision **and the reasoning** in `CLAUDE.md`, not just the
   numbers. If the right thresholds aren't obvious from the data alone, that's a case to surface
   to the user rather than guess silently, since it directly defines what the app tells people to
   do.

## Order of work

1. Hit the real API; record schema + thresholds in `CLAUDE.md` (see above).
2. Scaffold the Gradle/Compose project. Confirm package id and app name with the user if they
   haven't already been given — don't invent a package id unilaterally, it's awkward to change
   later.
3. Implement `core` (fetch + classify) with unit tests.
4. Implement the one-screen UI.
5. Local build/run check.
6. Set up signing + a GitHub Actions release workflow.
7. Only once 1–6 are solid: prepare the `fdroiddata` submission.

## Progress (update this as steps finish)

Last updated 2026-09-29.

1. **Done.** API inspected; schema, thresholds (day-range thirds on `integrated`) and reasoning are in `CLAUDE.md`.
2. **Done.** `:app` is in `settings.gradle.kts`; `app/build.gradle.kts` and `AndroidManifest.xml` (INTERNET only, no cleartext) exist. No launcher icon yet (system default); ask the user whether to add one.
3. **Done.** `core/`: `Prices.kt` (model + `parsePrices`), `Classify.kt` (`classify(slots, now): Status?`), `PriceApi.kt` (`fetchPrices()`), and `CoreTest.kt`, which uses the real response in `core/src/test/resources/prices-2026-09-28.json`. `./gradlew :core:test` passes (7 tests, daemon on JDK 21).
4. **Done.** `MainActivity.kt` (one screen: colour field, price, updated time, Refresh) and `MainViewModel.kt` (cache, refresh policy from `CLAUDE.md`).
5. **Done.** `ANDROID_HOME=~/Android/Sdk ./gradlew :core:test :app:assembleDebug` succeeds; installed on the user's phone with `~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk` and confirmed showing a colour (orange on 2026-09-28 evening). `adb` isn't on PATH in Claude's shell.
5b. **Done.** Region switcher (2026-09-28): `Region.kt` with CKW as the only region, `fetchPrices(region)`, dropdown in the UI. Selection is not persisted yet.
5c. **Done.** Fetch cooldown (`Cooldown.kt`), status wording "Good/Fair/Bad time" and a "?" help dialog.
6. **Done.** Signing + `.github/workflows/release.yml`. The user created the release keystore and the four GitHub secrets. **v0.1.0 was released on 2026-09-28** (tag `v0.1.0` at `7694594`) and installs via Obtainium.
7. Superseded by Phase 2 of the roadmap below.

Toolchain verified installed on 2026-09-28: JDK 21 at `/usr/lib/jvm/java-21-openjdk`, SDK at
`~/Android/Sdk` (platform-tools, platforms;android-37.0, build-tools;37.0.0, licenses accepted),
and `ANDROID_HOME`/`PATH` set in `~/.bashrc`. No phone was connected over adb at the time.

## Roadmap after v0.1.0 (agreed with the user 2026-09-29)

The app is split into two **modes**, chosen on first start and changeable in Settings:

- **Spot price mode:** the app as in v0.1.0. The colour says run everything or wait, so it
  needs no appliance list.
- **Peak load mode:** built around the user's appliances and staying under the month's peak.

Work phase by phase and update the status in brackets as things finish. iOS is out of scope
for now; don't plan or start it.

### Phase 1: v0.2, settings and first start (spot mode only) [not started]

1. **Saved settings:** the chosen mode, the region and "first start done", in
   SharedPreferences (Android's built-in key-value storage). No database.
2. **Settings screen** behind an icon at the top left. It holds the mode switch and the region
   picker. Open: move the region dropdown off the main screen into Settings (Claude's
   suggestion), or keep it on the main screen. Ask the user.
3. **Shorter help:** a short general part plus a short section per mode.
4. **First-start flow:** help screen, then **Next**, then the mode choice (spot price or peak
   load, each with its own logo). Open: ship v0.2 without the choice step and add it with peak
   mode in Phase 4 (Claude's suggestion), or show peak mode as "coming soon" now. Ask the user.
5. **App icon:** the user designs it and hands it over. Until then the system default stays.

### Phase 2: publish on F-Droid [not started]

Right after v0.2, once the icon is in. Needs store texts and screenshots in the repo
(fastlane metadata) and possibly build tweaks the reviewers ask for. Their review takes weeks,
so the later phases run while waiting.

### Phase 3: research, no code [not started]

6. **Early vs late in the day:** the colour currently compares now against the whole day,
   including hours that are over, so late in the evening "wait if you can" can point to a cheap
   period that has passed. Check whether the API can return tomorrow's prices (published around
   11:00). If yes, compare against the next 24 hours; if not, against the rest of today.
   Record the result and the decision in `CLAUDE.md`.
7. **Other Swiss providers:** which utilities publish dynamic prices with a public API, and how
   much of Switzerland they would cover. Output: a short list in `CLAUDE.md`. Each new utility
   is then a separate small task added to `REGIONS`.

### Phase 4: peak load mode [not started]

8. **Peak load discussion with the user first.** It settles where the monthly peak comes from
   (the app only knows what the user enters; it cannot read the smart meter), whether CKW
   charges households for peak, and whether peak mode also uses the price. Record the outcome
   in `CLAUDE.md` before writing code.
9. **Appliance list:** name, watts, optional run time (e.g. washing machine 2000 W for 1 h),
   and always on (fridge) or flexible. Saved on the phone. This adds a second screen.
10. **Switching appliances on and off** in the app, with automatic off when the run time ends.
    The app sums the current estimated draw.
11. **Advice:** under the month's peak, adding more is fine. Over it, suggest the smallest
    *flexible* appliance that brings the draw back under the peak (not simply the smallest
    one), and never an always-on appliance.
12. **Mode choice in first start and Settings,** with the two mode logos. Open: the user
    designs the logos, or Claude uses simple built-in icons (e.g. a lightning bolt and a
    gauge). Ask the user.

## Conventions

- Commit only when the user asks; the user pushes themselves — don't `git push`.
- Keep scope to the MVP above plus the roadmap phases, in order. If a step surfaces a genuinely open product decision (thresholds,
  package id, app name, whether to add an icon now vs. later), ask rather than guessing — but
  don't ask about anything this skill has already settled (platform, architecture, distribution
  approach).
