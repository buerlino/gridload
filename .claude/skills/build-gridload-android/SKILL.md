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

- **Single-Activity Compose app**, one screen: the big colored field, a last-updated timestamp,
  and a manual refresh (pull-to-refresh or a button). Nothing more.
- **A small, framework-free `core` package/module** holding:
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

Last updated 2026-09-28.

1. **Done.** API inspected; schema, thresholds (day-range thirds on `integrated`) and reasoning are in `CLAUDE.md`.
2. **Partly done.** Package id `io.github.buerlino.gridload` and app name `GridLoad` are confirmed (see `CLAUDE.md` → Decided). Written so far: Gradle wrapper, `settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/gradle-daemon-jvm.properties`, `core/build.gradle.kts`. Web/TypeScript leftovers were removed from README, CLAUDE.md and `.gitignore`.
   - **Still to do:** add `":app"` back to `include(...)` in `settings.gradle.kts`, then `app/build.gradle.kts` and `AndroidManifest.xml` (INTERNET only, no cleartext), plus a launcher theme/strings. For the icon, ask the user whether a placeholder is fine for now.
3. **Done.** `core/`: `Prices.kt` (model + `parsePrices`), `Classify.kt` (`classify(slots, now): Status?`), `PriceApi.kt` (`fetchPrices()`), and `CoreTest.kt`, which uses the real response in `core/src/test/resources/prices-2026-09-28.json`. `./gradlew :core:test` passes (7 tests, daemon on JDK 21).
4. **Not started.** The UI follows the refresh policy in `CLAUDE.md`.
5. **Not started.** Build check: `./gradlew :core:test :app:assembleDebug`, then `adb install` on the user's phone.
6. **Not started.** Signing + GitHub Actions release workflow.
7. **Not started.** fdroiddata submission.

Toolchain verified installed on 2026-09-28: JDK 21 at `/usr/lib/jvm/java-21-openjdk`, SDK at
`~/Android/Sdk` (platform-tools, platforms;android-37.0, build-tools;37.0.0, licenses accepted),
and `ANDROID_HOME`/`PATH` set in `~/.bashrc`. No phone was connected over adb at the time.

## Conventions

- Commit only when the user asks; the user pushes themselves — don't `git push`.
- Keep scope to the MVP above. If a step surfaces a genuinely open product decision (thresholds,
  package id, app name, whether to add an icon now vs. later), ask rather than guessing — but
  don't ask about anything this skill has already settled (platform, architecture, distribution
  approach).
