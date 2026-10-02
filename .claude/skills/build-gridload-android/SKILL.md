---
name: build-gridload-android
description: Execution brief for building the GridLoad Android app — a native Kotlin/Jetpack Compose app showing a red/orange/green "good time to run appliances" indicator, with a peak load mode to be rebuilt on a whatwatt Go smart meter reader, distributed via F-Droid and Obtainium. Use this skill whenever working in the gridload repo on the Android app itself: the API client or classification logic, the Compose UI, settings, the cleanup, the whatwatt integration, peak load mode, building/installing on the phone, releases, or the F-Droid (fdroiddata) merge request. The platform choice (native Android, no Flutter/React Native/KMP) is already decided here — do not re-open that question; just execute.
---

# Build GridLoad Android

Roadmap, status and practical know-how for the app. `CLAUDE.md` at the repo root is the source
of truth for the stack, versions, API schema, thresholds, UI wording, release/signing and the
whatwatt design. Read it first. If this skill and `CLAUDE.md` disagree, `CLAUDE.md` wins;
update this skill to match. Finished work is kept here only as far as later work needs it; the
history is in git.

## Settled (don't relitigate)

- **Native Android, Kotlin + Jetpack Compose.** The app is "fetch one JSON endpoint, compare a
  number, show a colour": nothing that justifies Flutter, React Native or KMP, and plain
  Kotlin/Compose is the easiest for F-Droid to build and review. iOS is deferred; if it comes,
  the default is a small native SwiftUI rewrite. Don't build for it now.
- **Architecture:** single Activity. `:core` (plain Kotlin/JVM, no Android) holds regions, the
  API client, parsing and classification, all unit-tested. `:app` holds the screens: main
  (`MainActivity.kt`), Settings and the setup guide (`SettingsScreen.kt`).
- **No background work:** fetch on open/resume and manual refresh only. The whatwatt is read
  only while the app is visible; the peak alarm is in the app only (user, 2026-10-02).
- **No proprietary dependencies** (Firebase, Play Services, ads, analytics, crash SDKs).
- **No dead code** (user, 2026-10-02): remove what isn't used; git history keeps it.
- **Beyond the roadmap:** no accounts, no cloud, no optimization engine.

## Working on the phone

- Build and test: `ANDROID_HOME=~/Android/Sdk ./gradlew :core:test :app:assembleDebug`.
- The user's phone is a Fairphone 6 over USB, no emulator. `adb` isn't on PATH in Claude's
  shell: use `~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk`.
  The debug build is signed with a different key than releases, so switching between the two
  needs an uninstall. `adb shell pm clear io.github.buerlino.gridload` shows the first start
  again.
- R8 release build for testing: `assembleRelease`, then `zipalign -p 4` and `apksigner` with
  `~/.android/debug.keystore`; it installs over debug builds.
- Screenshots: `adb exec-out screencap -p > file.png`. For store screenshots, use SystemUI demo
  mode (`settings put global sysui_demo_allowed 1`, then `am broadcast -a
  com.android.systemui.demo -e command enter|clock|notifications|status|exit ...`) so the user's
  status bar icons don't show. Afterwards exit demo mode and set `sysui_demo_allowed` back to 0.
- A helper that taps the first node containing a text can hit a label instead of a button;
  match whole texts for buttons.
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

## Roadmap

No modes since the UI rework (user, 2026-10-02): the spot price always shows (the colour says
run everything or wait), and **peak load** (rebuilt on the whatwatt) is a switch under
Measurement in Settings. Work phase by phase and update the status in brackets.

### Done

- **v0.2:** Settings and first start (mode, region, "first start done" saved), app icon.
- **v0.3:** today + tomorrow and the 24-hour window with the next good time; seven regions from
  four utilities (all VSE/AES). Findings in `CLAUDE.md` under Data source and Classification.
  To analyse prices again, a request of up to 31 days (past days work) gives plenty of data in
  one call; mind CKW's rate limit of 4 per window. A new region: test the URL with the app's
  request, add it to `REGIONS`, the table in `CLAUDE.md`, `README.md` and the store description.
- **v0.4 / v0.5:** peak load mode with manual appliances and a CKW Excel import. **Dropped
  2026-10-02** (reasons in `CLAUDE.md` under Current task scope).
- **v0.6:** the cleanup, back to spot/peak + region with no dead code (commit `71f954f`). On
  start, `peak.json` and its `.bak` are deleted. Tested on the phone: an update over v0.5.0 with
  a `peak.json`, a fresh start, both modes, a region switch, the R8 build (0.99 MB). Lint's
  remaining warnings (`DataExtractionRules`, `MonochromeLauncherIcon`, `UseKtx` on prefs edits,
  where core-ktx is only transitive) predate the cleanup.

### F-Droid [submitted 2026-09-29, in review]

Review so far: linsui asked for R8 (2026-09-29). Answer: v0.3.1 enables it.
2026-10-01: the recipe moves to v0.5.0 (versionCode 7, commit `309df86`), committed in a shallow
clone of the fork at `../fdroiddata` for the user to push. Checked first: the GitHub APK's
signer matches `AllowedAPKSigningKeys`, and an unsigned local build of the tag has identical
contents (all entries outside `META-INF/`).
2026-10-02: the user skips v0.5.0 (its import and manual peak mode are gone in v0.6.0), so the
recipe moves straight to v0.6.0 (versionCode 8). Same checks once the release workflow has
built the tag's APK, then a new commit in `../fdroiddata`. Done: the GitHub APK's signer
matches and its contents equal an unsigned build of the tag; the user pushed `6aeacf167` and
added a comment to the merge request (2026-10-02). Pushing to the fork needs a GitLab token
(`write_repository`) as the password; the fork has no credential helper.

Merge request https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50583, from the user's fork
`buerlino/fdroiddata`, branch `io.github.buerlino.gridload`, file
`metadata/io.github.buerlino.gridload.yml`. The pipeline is green: `fdroid build` (Debian 13,
JDK 21 by default, so no `sudo` block) and the reproducible-build check. Recipe choices:
`NonFreeNet` anti-feature (the utilities' APIs), category `Market & Price`, `GPL-3.0-only`,
`Binaries` + `AllowedAPKSigningKeys` for reproducible builds.

v0.7.0 (2026-10-02) is not added to the merge request: the recipe stays at v0.6.0, and auto
update (`UpdateCheckMode: Tags`, `AutoUpdateMode: Version`) builds v0.7.0 once it's merged.

Next: answer reviewer comments in the merge request. The user posts on GitLab (it's public
under their name); Claude drafts the answers and any recipe changes, and can check the merge
request and pipelines through GitLab's public API (`/api/v4/projects/fdroid%2Ffdroiddata/merge_requests/50583`).

### whatwatt [phases 0 to 3 done 2026-10-02, released in v0.7.0; next: phase 4]

Peak load mode needs a whatwatt Go on the household meter; spot mode uses it for the cost
right now. The design (quarter hours from the energy register, monthly files, goal, vertical
scale, kW free, countdown, in-app alarm), the verified API facts and phases 0 to 5 are in
`CLAUDE.md` under "Peak load mode with whatwatt". Phase 0's findings (the real report fields,
the local-time-labelled-`Z` quirk, the SD log every 15 s, no maximum demand register) are there
too. Phase 1 restored and adapted `Whatwatt.kt` and added the whatwatt step, Settings card and
cost line. The whatwatt runs on meter power: poll it gently (every 5 s while visible).
Phase 2 records quarter hours (`Quarters.kt`) into `files/quarters/YYYY-MM.json`; read them on
the phone with `adb shell run-as io.github.buerlino.gridload cat files/quarters/2026-10.json`
(debug build) and check them against the SD card log (`/sdcard/YYYYMMDD.CSV`, `EAP_T` at the
boundaries), which needs no extra polling of the device. For a long test with the app open,
`adb shell svc power stayon usb` keeps the screen on; set it back with `svc power stayon false`.
Phase 3 added the peak window (`PeakWindow.kt`, a `Canvas` with `rememberTextMeasurer`; labels
on the right are pushed apart when goal and highest are close), the goal and the no-reading
switch in Settings, and the vibration in `MainViewModel.warnIfClose`. To show the first start
without losing the recorded quarters, set `first_start_done` to false in the prefs (below)
rather than `pm clear`. To test states without
touching the device, edit the debug build's prefs with the app stopped:
`adb shell "run-as io.github.buerlino.gridload sed -i -e 's/A/B/' shared_prefs/settings.xml"`
(the whole command in one quoted string, or adb's shell splits it); a wrong address gives
"not reachable". Put the user's values back afterwards.

### UI rework [done, v0.7.0]

The user's 16 points after trying phase 3, with the decisions (no modes, the goal as a
switch, Settings as Region then Measurement, tap-a-label ⓘ dialogs, collapse once connected,
the whatwatt manual link), are in `CLAUDE.md` under "UI rework". `SettingsScreen` and
`SetupGuide` take the `MainViewModel` directly instead of a dozen callbacks. Open: the user is
rethinking the Measurement/whatwatt categorization (ask before touching it). Point 16, the
history tile, is later and needs designing with the user first.
Released in v0.7.0 (versionCode 9) with new store screenshots (demo mode, the clock set to
match "Updated").

### Regions outside Switzerland [researched 2026-09-30, not started]

Spot price mode for Germany, Austria and Liechtenstein from the Energy-Charts day-ahead API
(a second price source and parser, spot mode only, no absolute CHF price, CC BY attribution).
The findings, the code changes and the user's open questions are in
`research/neighbouring_countries.md`. Settle the open questions with the user first.

## Conventions

- Commit only when the user asks; the user pushes themselves.
- Simplest approach that works (user, 2026-09-29): don't over-engineer, don't clutter the UI
  or the code. When unsure, or when you see a simpler or better idea, ask the user first.
- Keep to the roadmap phases, in order. Ask about genuinely open product decisions rather than
  guessing, but don't ask about anything settled above or in `CLAUDE.md`.
