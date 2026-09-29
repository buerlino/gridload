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
  API client, parsing, classification and later the peak load logic, all unit-tested. `:app`
  holds the screens: main, Settings, first start (`SettingsScreen.kt`), later appliances.
- **No background work:** fetch on open/resume and manual refresh only, so `INTERNET` stays the
  only permission. Peak load mode keeps this by computing everything from saved start/stop times
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
Work phase by phase and update the status in brackets.

### Phase 1: v0.2, Settings and first start [done, released as v0.2.0]

Settings behind ⚙ (mode cards, region picker), first start (help, Next, mode choice with peak
load "coming soon"), region and "first start done" saved, app icon. The mode isn't saved yet,
since spot is the only choice; save it when peak mode exists. The mode logos are emoji
placeholders (⚡, 📊).

### Phase 2: F-Droid [submitted 2026-09-29, waiting for review]

Merge request https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50583, from the user's fork
`buerlino/fdroiddata`, branch `io.github.buerlino.gridload`, file
`metadata/io.github.buerlino.gridload.yml`. The pipeline is green: `fdroid build` (Debian 13,
JDK 21 by default, so no `sudo` block) and the reproducible-build check. Recipe choices:
`NonFreeNet` anti-feature (CKW's API), category `Market & Price`, `GPL-3.0-only`, `Binaries` +
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

### Phase 4: peak load mode [discussion started 2026-09-29; next: load data import]

The design is in `CLAUDE.md` under "Peak load mode". The CKW price sheet confirms the peak
charge (1.00 CHF/kW per month on Home dynamic). Still open: the household's product and the
portal details (the user is getting access), and the open points listed there.

1. **Load data import (next task, user's idea 2026-09-29).** The user has 3 years of smart meter
   data, one Excel sheet per year. Import it (Excel or CSV) and derive monthly peaks, base load
   and heating per month; see "Load data import" in `CLAUDE.md`. It must work with any amount
   of data (a few months with a fresh contract) and be repeatable (each year, import the
   previous year). Start by asking the user for a
   file (or its first rows) and settling format, Excel vs CSV and which numbers to derive. Don't
   commit the user's real data. The results may settle open points 1 and 2 in `CLAUDE.md`.
2. **Appliance list:** name, watts, optional run time, flexible or not, plus the baseline.
   Saved on the phone as JSON. This adds a second screen.
3. **Switching appliances on and off** in the app, with automatic off when the run time ends.
   `:core` computes the average draw per quarter hour and the month's peak from the saved runs.
4. **Advice** as in `CLAUDE.md`: the peak is the hard limit, the price decides within it.
5. **Mode choice** in first start and Settings becomes real; save the mode. Open: the user
   designs the mode logos, or Claude uses simple built-in symbols. Ask the user.

## Conventions

- Commit only when the user asks; the user pushes themselves — don't `git push`.
- Simplest approach that works (user, 2026-09-29): don't over-engineer, don't clutter the UI
  or the code. When unsure, or when you see a simpler or better idea, ask the user first.
- Keep to the roadmap phases, in order. Ask about genuinely open product decisions rather than
  guessing, but don't ask about anything settled above or in `CLAUDE.md`.
