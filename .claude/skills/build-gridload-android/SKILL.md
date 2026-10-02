---
name: build-gridload-android
description: Execution brief for building the GridLoad Android app — a native Kotlin/Jetpack Compose app showing a red/orange/green "good time to run appliances" indicator from Swiss dynamic electricity tariffs, plus peak load monitoring with a whatwatt Go smart meter reader, distributed via F-Droid and Obtainium. Use this skill whenever working in the gridload repo on the Android app itself: the API client or classification logic, the Compose UI, settings, the whatwatt integration, peak load, building/installing on the phone, releases, the F-Droid (fdroiddata) merge request, or a declutter pass. The platform choice (native Android, no Flutter/React Native/KMP) is already decided here — do not re-open that question; just execute.
---

# Build GridLoad Android

Roadmap, status and practical know-how for the app. `CLAUDE.md` at the repo root is the source
of truth for the stack, versions, API schema, thresholds, UI, release/signing and the whatwatt
design. Read it first. If this skill and `CLAUDE.md` disagree, `CLAUDE.md` wins; update this
skill to match. Finished work is kept here only as far as later work needs it; the history is
in git.

## Settled (don't relitigate)

- **Native Android, Kotlin + Jetpack Compose.** The app is "fetch one JSON endpoint, compare a
  number, show a colour": nothing that justifies Flutter, React Native or KMP, and plain
  Kotlin/Compose is the easiest for F-Droid to build and review. iOS is deferred; if it comes,
  the default is a small native SwiftUI rewrite. Don't build for it now.
- **Architecture:** single Activity. `:core` (plain Kotlin/JVM, no Android) holds the logic, all
  unit-tested. `:app` holds `MainViewModel` (prices, settings, the peak alarm),
  `WhatwattMeter` (reading, recording, the Test) and the screens: main (`MainActivity.kt`,
  `PeakWindow.kt`), Settings and the setup guide (`SettingsScreen.kt`).
- **No background work** and no accounts, cloud or optimization engine.

## Working on the phone

- Verify: `ANDROID_HOME=~/Android/Sdk ./gradlew :core:test :app:lintDebug :app:assembleDebug :app:assembleRelease`.
  Lint's up-to-date check can miss manifest edits and repeat an old report; add
  `:app:lintAnalyzeDebug --rerun` when a warning looks stale.
- The user's phones are a Fairphone 6 (the main one) and a Fairphone 4, over USB, no emulator.
  Both can be connected at once: check `adb devices` and pass `-s <serial>`. `adb` isn't on PATH
  in Claude's shell: use `~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk`.
  The debug build is signed with a different key than releases, so switching between the two
  needs an uninstall. `adb shell pm clear io.github.buerlino.gridload` shows the first start
  again, but deletes the recorded quarters; to keep them, set `first_start_done` to false in the
  prefs (below) instead.
- R8 release build for testing: `assembleRelease`, then `zipalign -p 4` and `apksigner` with
  `~/.android/debug.keystore`; it installs over debug builds.
- Prefs and files of the debug build: `adb shell run-as io.github.buerlino.gridload cat files/quarters/2026-10.json`.
  To test states without touching the device, edit the prefs with the app stopped:
  `adb shell "run-as io.github.buerlino.gridload sed -i -e 's/A/B/' shared_prefs/settings.xml"`
  (the whole command in one quoted string, or adb's shell splits it); a wrong address gives
  "not reachable". Put the user's values back afterwards.
- Recorded quarters can be checked against the whatwatt's SD log (`/sdcard/YYYYMMDD.CSV`,
  `EAP_T` at the boundaries), which needs no extra polling of the device. For a long test with
  the app open, `adb shell svc power stayon usb` keeps the screen on; `svc power stayon false`
  afterwards.
- Screenshots: `adb exec-out screencap -p > file.png`. For store screenshots, use SystemUI demo
  mode (`settings put global sysui_demo_allowed 1`, then `am broadcast -a
  com.android.systemui.demo -e command enter|clock|notifications|status|exit ...`) so the user's
  status bar icons don't show, with the clock set to match "Updated". Afterwards exit demo mode
  and set `sysui_demo_allowed` back to 0.
- A helper that taps the first node containing a text can hit a label instead of a button;
  match whole texts for buttons.
- If the phone is locked, don't try to unlock it; ask the user.

## Icon

The user's SVGs in `logo/` (108 x 108, yellow background, tower with "GL" as foreground) are
the source. `res/drawable/ic_launcher_{background,foreground}.xml` are vector drawables
converted from them, combined in `res/mipmap-anydpi/ic_launcher.xml` (adaptive icon), and
`fastlane/.../images/icon.png` is a 512 px render of both (`rsvg-convert` + `magick`).
The themed (monochrome) layer, `logo/gl_icon_monochrome.svg` → `ic_launcher_monochrome.xml`
(user's choice, 2026-10-02), is computed from the foreground with shapely: the `Grid_bright`
mast lines at 3.2 px, minus the GL letters grown by 0.5 px (their outline) plus a 1.6 px gap,
pieces under 4 px² dropped, plus the letters; simplified at 0.15 px with one decimal, one
path per piece, so each stays under lint's 800-character `VectorPath` limit. Reusing the foreground as the
mask merges letters, cables and mast into one blob. If the SVGs change, regenerate all four.

## Releasing

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (max 500 characters).
3. Commit and tag `vX.Y.Z` (only when the user asks); the user pushes. The tag builds the signed
   GitHub Release for Obtainium.
4. F-Droid: once the merge request below is merged, auto update picks up new tags by itself. It
   rebuilds the tag and publishes the GitHub APK only if the two are byte-identical apart from
   the signature, so keep the build deterministic. For build-only changes, compare the unsigned
   release APK's sha256 before and after.

## Roadmap

Work phase by phase and update the status in brackets.

### Done

- **v0.2:** Settings and first start, app icon.
- **v0.3:** today + tomorrow and the 24-hour window with the next good time; seven regions from
  four utilities (all VSE/AES). To analyse prices again, a request of up to 31 days (past days
  work) gives plenty of data in one call; mind CKW's rate limit of 4 per window. A new region:
  test the URL with the app's request, add it to `REGIONS`, the table in `CLAUDE.md`,
  `README.md` and the store description.
- **v0.4 / v0.5:** peak load with manual appliances and a CKW Excel import, dropped in v0.6.0
  (commit `71f954f`; reasons in `CLAUDE.md`).
- **v0.6:** back to the lean core.
- **v0.7:** the whatwatt (phases 1 to 3) and the UI rework: no modes, Settings as Region then
  Measurement, ⓘ dialogs. Details in `CLAUDE.md`.
- **Declutter 2026-10-02:** see [declutter.md](declutter.md), which also holds the reusable
  checklist for the next pass.

### F-Droid [submitted 2026-09-29, in review]

Merge request https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50583, from the user's fork
`buerlino/fdroiddata`, branch `io.github.buerlino.gridload`, file
`metadata/io.github.buerlino.gridload.yml`. Recipe choices: `NonFreeNet` anti-feature (the
utilities' APIs), category `Market & Price`, `GPL-3.0-only`, `Binaries` +
`AllowedAPKSigningKeys` for reproducible builds. The pipeline is green: `fdroid build` (Debian
13, JDK 21 by default, so no `sudo` block) and the reproducible-build check.

The recipe is at v0.6.0 (versionCode 8, the user's fork commit `6aeacf167`); the reviewer's
R8 request was answered with v0.3.1. Auto update (`UpdateCheckMode: Tags`, `AutoUpdateMode:
Version`) builds v0.7.0 and later once merged. Before moving the recipe to a new version:
check that the GitHub APK's signer matches `AllowedAPKSigningKeys` and that its contents equal
an unsigned build of the tag (all entries outside `META-INF/`), then commit in the clone at
`../fdroiddata`. Pushing to the fork needs a GitLab token (`write_repository`) as the password;
the fork has no credential helper.

Next: answer reviewer comments. The user posts on GitLab (it's public under their name);
Claude drafts the answers and any recipe changes, and can check the merge request and
pipelines through GitLab's public API (`/api/v4/projects/fdroid%2Ffdroiddata/merge_requests/50583`).

### whatwatt [phases 0 to 3 done, released in v0.7.0; next: phase 4]

The design, the verified API facts and the phases are in `CLAUDE.md` under "Peak load with
whatwatt". The whatwatt runs on meter power: poll it gently (every 5 s while visible). Phase 4
fills the gaps from the SD card log; open: the history tile (design it with the user first)
and the Measurement/whatwatt categorization (ask before touching it).

### Regions outside Switzerland [researched 2026-09-30, not started]

Spot prices for Germany, Austria and Liechtenstein from the Energy-Charts day-ahead API (a
second price source and parser, no absolute CHF price, CC BY attribution). The findings, the
code changes and the user's open questions are in `research/neighbouring_countries.md`. Settle
the open questions with the user first.

## Conventions

The conventions are in `CLAUDE.md`. Keep to the roadmap phases, in order; ask about genuinely
open product decisions rather than guessing, but don't ask about anything settled there.
