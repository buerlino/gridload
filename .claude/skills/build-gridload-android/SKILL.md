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
  unit-tested. `:app` holds `MainViewModel` (prices, settings, the peak alarm, the appliances and
  their measuring), `WhatwattMeter` (reading, the recorder's sync, checks and actions, the Test)
  and the screens: main (`MainActivity.kt`, `PeakWindow.kt`, `AppliancesPanel.kt`,
  `HistoryPanel.kt`, with the shared panel and chart drawing in `Charts.kt`), Settings and the
  setup guide (`SettingsScreen.kt`, `WhatwattGuide.kt`).
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
- Prefs and files of the debug build: `adb shell run-as io.github.buerlino.gridload cat files/recorder/GL261003.CSV`.
  To test states without touching the device, edit the prefs with the app stopped:
  `adb shell "run-as io.github.buerlino.gridload sed -i -e 's/A/B/' shared_prefs/settings.xml"`
  (the whole command in one quoted string, or adb's shell splits it); a wrong address gives
  "not reachable". Put the user's values back afterwards.
- The recorder's lines can be checked against the whatwatt's CSV log (`/sdcard/YYYYMMDD.CSV`,
  `EAP_T` at the boundaries); it's off since the validation, so turn it on first
  (`services.sd.enable`) and off again afterwards. For a long test with the app open,
  `adb shell svc power stayon usb` keeps the screen on; `svc power stayon false` afterwards.
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

### whatwatt [phases 0 to 3 released in v0.7.0; phase 4, the recorder, in v0.8.0; script frozen at v2]

The design, the verified API facts and the phases are in `CLAUDE.md` under "Peak load with
whatwatt" (the recorder: "The recorder (phase 4)"); the Berry tests, the script's history and the
download measurements are in `research/whatwatt_berry_script.md`. The whatwatt runs on meter
power: poll it gently (every 5 s while visible), and never download big SD files at full speed
(it rebooted twice; ≤ 8 KB/s held). Also open: the Measurement/whatwatt categorization (ask
before touching it).

Phase 4, the recorder on the whatwatt. Done 2026-10-03: script v2 (version marker, start lines,
no `print`) as a `:core` resource; `Recorder.kt` (day files, merge, gaps, `checkRecorder`, the
local copies, install/start/remove over HTTP) with tests against a fake whatwatt; the app's own
recording removed (user: no fallback, precise warnings instead); the Recorder row in Settings,
the red lines in the peak window, the help, ⓘ texts, README and store text; the step-by-step
whatwatt guide (`WhatwattGuide.kt`).
Open, in order:
1. ~~Validate overnight~~: done 2026-10-03, 32 quarters within 0.0012 kWh of the CSV log, only
   the two restart quarters missing (results in the research file); CSV log turned off. Still
   open: what `onreport` delivers when the meter isn't `OK`. The old CSV logs are deleted.
2. ~~On the phone~~: done 2026-10-03: the update v1 → v2 by the app, the waiting and gap lines,
   the auto-run warning → Fix, the R8 release build; Stopped (`run=false`; the state is `IDLE`,
   no longer shown in brackets) → Start, Remove (dialog, script deleted, auto-run off, day files
   kept) and Install on the real whatwatt, all within one quarter hour (08:15 lost). Gaps count
   only up to the last check, which is every 15 min while the recorder waits for its first
   quarter, so a fresh gap can take that long to show.
3. **The DST night** (25 Oct 2026, 02:00–03:00 twice): the lines are keyed by UTC, so the day
   file just has 100 lines; check it.
4. ~~Release v0.8.0~~: tagged 2026-10-03 (store screenshots 1 and 3 retaken).

Unknowns to check when they matter: whether Berry needs the Plus licence; the minimum firmware
(user, 2026-10-03: 2.0.0 does not run Berry, 2.8.2 does; a firmware check from `/api/v1/system`
in Test or the recorder check is a possible next step, ask the user); the values of
`execution_status.state` other than `RUNNING` and `IDLE` (shown verbatim).

### Panels and history [released in v0.9.0, 2026-10-03]

Making room for the history on the main screen (user, 2026-10-03; the design is in `CLAUDE.md`
under UI, "Power unit" and "History"). In order:
1. Settings: drop "Show the scale without a reading"; 📟 on the Measurement title; Country then
   region (setup guide and Settings, preselected from the phone); the power unit (kW | W).
2. Main screen: the spot part fixed at the top, the panels scrolling below; pull down to refresh
   with the "Updated 14:02 ↻" line, ↻ dimmed during the cooldown.
3. Peak window: collapsible with the "kW free" header and the countdown in it; values above the
   bars; the current bar 1.6× as wide; "Recorded since", the gaps and Remove into Settings →
   Recorder → Details.
4. History panel, first version (bars per day). Test on the phone: scrolling with both panels
   open, both collapsed, and without a reading.
5. Help, ⓘ texts, README and store text; then the user decides on a release.

Tested on the Fairphone 6 (2026-10-03): collapse, scrolling with both open (`adb shell wm size
1116x1500` to force it, `wm size reset` after), pull to refresh and the blocked pull, ↻ dimmed,
W, recorder details, first start with the SIM's country. Seen on the phone too (2026-10-03, after the
`Charts.kt` refactor): the peak window without a reading (wrong address in the prefs), the red
"kW over" header and warning, open and collapsed, and the guide's Region step back at its top
after Measurement.

History chart (user, 2026-10-03): the thin bars are fine for now; no tap-for-value (too thin to
hit). The month's highest day red, today dark (only seen with today as the highest so far).
Open: a full-screen view of the chart on tap (the user's idea; its content to be decided).

### Appliances panel [built 2026-10-03; not released]

The design is in `CLAUDE.md` under "Appliances", the reasoning in `research/appliances.md`; the
recorder stays at v2. Built and tested on the Fairphone 6 (2026-10-03): the measuring flow
("Cooking", "Kettle 1 L"), OK and WAIT ("Sets a new peak at any start"), the collapsed summary,
rename, delete, variants ("Cooking 60 min", "Kettle 1.5 L"), export and import, and an R8 release
build (a measurement survives a force-stop). Declutter pass afterwards: see
[declutter.md](declutter.md).

Release plan (proposed 2026-10-03, the user decides; committed up to `7498e45`):
1. ~~**Before the release:**~~ done 2026-10-03, 15:35–15:38: "Kettle 1L test" (saved) gave
   3 min · 0.10 kWh · 2.0 kW right after Done (jump +1.8 to +2.0 kW; Start and Done in the
   15:30 quarter). The plan was: install the current R8 build on the Fairphone 6 (it has the pass's
   fixes and 1.6, which the installed one lacks) and measure "Kettle 1 L" again. This tests the
   new Done (`doneAt`, `doneKwh`), which has never run on the phone. Expect the result right
   after Done, close to the first measurement (3 min · 0.09 kWh · 1.8 kW). Why first: once
   released, `appliances.json` (export/import) and the `measuring` pref are a format to keep.
   Before installing, check that GridLoad is in front with no measuring row.
2. **Then release v0.10.0:** versionCode 12, `changelogs/12.txt` (max 500 characters; a new
   feature, hence the minor bump), see Releasing; tag only when the user asks, the user pushes.
   Text for `12.txt` (469 characters, approved by the user 2026-10-03; the version isn't bumped yet):
   ```
   - Appliances: measure each one once with the whatwatt, then see OK or WAIT for starting it now, by peak and by price, with the time to wait or the delay to set.
   - Tap an appliance to see its run in the peak window; hold to edit.
   - Variants: another run time, e.g. a kettle with more water.
   - Export and import the appliances in Settings.
   - The peak window shows the last hour and the next three quarter hours.
   - Scrolling the panels shrinks the price part to one line.
   ```
3. **Not blocking, a v0.10.1 if needed:** the logic is covered by core tests, and what's left in
   the app is plain text and one rounding.
   - On the phone: a WAIT with a time ("· at 14:30" needs a heavy quarter with a low draw now,
     e.g. after the hob is switched off mid-quarter); a "Cheaper" row and, with a start delay,
     "Cheaper · Delay 3 h" (evening prices; checks 1.3's rounding and 1.4's separator); the
     row before tomorrow's prices are out (1.5, before 12:00 for CKW).
   - The dishwasher run ("Dishwasher 65°", ~1.5 h, Can wait on).
   - Store screenshots without the appliances panel: retake before F-Droid ships it.
4. Left to the user: the setup help's "The result shows up to 15 minutes later." is still true,
   though the result now usually comes at once (the user's text).

Preview (built 2026-10-03, uncommitted at first; design in `CLAUDE.md` under "OK or WAIT"): tap
a row → its run in the peak window, hold → edit; the peak window has 3 past | now | 3 coming
columns. Tested on the Fairphone 6 with an R8 build: "Cooking" (WAIT, a red 16:15 quarter),
closing with ×, the empty coming columns, a long press opening the sheet. Also seen (2026-10-03,
16:06): a run past the columns ("Cooking 60 min" at 16:06, "Then 1 more quarter hour, up to
0.2 kW.", its plural fixed), W (labels up to 3000 fit; "90 W free"), the preview staying open
through Settings. Not seen: the preview without a line (needs the goal off and nothing recorded
this month, so only after a `pm clear` or on a fresh phone; checked in the code: the header shows
"2.0 kW with …" in ink, no red, the scale fits the bars). A long name wrapping inside
"with Cooking 60 / min" in the header is fine (user, 2026-10-03).

To test a measurement's prefs by hand: edit `shared_prefs/settings.xml` with the app stopped by
pulling it, editing locally and `cat`-ing it back through `/data/local/tmp` (sed's `&` breaks
the `&quot;` entities).

### Regions outside Switzerland [researched 2026-09-30, not started]

Spot prices for Germany, Austria and Liechtenstein from the Energy-Charts day-ahead API (a
second price source and parser, no absolute CHF price, CC BY attribution). The findings, the
code changes and the user's open questions are in `research/neighbouring_countries.md`. Settle
the open questions with the user first.

## Conventions

The conventions are in `CLAUDE.md`. Keep to the roadmap phases, in order; ask about genuinely
open product decisions rather than guessing, but don't ask about anything settled there.
