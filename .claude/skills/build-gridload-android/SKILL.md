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
  `HistoryPanel.kt`, with the shared panel and chart drawing in `Charts.kt`), the History
  screen (`HistoryScreen` in `HistoryPanel.kt`), Settings and the setup guide
  (`SettingsScreen.kt`, `WhatwattGuide.kt`; `Page` and `TitleRow` are shared).
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
`fastlane/.../images/featureGraphic.png` (1024 x 500, asked for by a tester on 2026-10-03) is the
foreground's `Artboard11` group at 5.6x on `#FFE537`, with "GridLoad" (Inter Bold 112 px) and
"When to run your appliances" (Inter Medium 36 px) in `INK` and the red, orange and green dots,
rendered the same way and flattened to RGB.
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
  test the URL with the app's request, add it to `REGIONS` with its `Country`, the table in
  `CLAUDE.md`, `README.md` and the store description. A region gets its country's time zone;
  pass `zone` only if it differs (e.g. the Canaries in Spain).
- **v0.4 / v0.5:** peak load with manual appliances and a CKW Excel import, dropped in v0.6.0
  (commit `71f954f`; reasons in `CLAUDE.md`).
- **v0.6:** back to the lean core.
- **v0.7:** the whatwatt (phases 1 to 3) and the UI rework: no modes, Settings as Region then
  Measurement, ⓘ dialogs. Details in `CLAUDE.md`.
- **Declutter passes:** see [declutter.md](declutter.md), which also holds the reusable
  checklist for the next pass.

### F-Droid [submitted 2026-09-29, in review]

Merge request https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50583, from the user's fork
`buerlino/fdroiddata`, branch `io.github.buerlino.gridload`, file
`metadata/io.github.buerlino.gridload.yml`. Recipe choices: `NonFreeNet` anti-feature (the
utilities' APIs), category `Market & Price`, `GPL-3.0-only`, `Binaries` +
`AllowedAPKSigningKeys` for reproducible builds. The pipeline is green: `fdroid build` (Debian
13, JDK 21 by default, so no `sudo` block) and the reproducible-build check.

The recipe is at v0.10.0 (versionCode 12, the user's fork commits `cb4172fe1` and `87bc0a2d7`,
2026-10-03: the first failed only `fdroid rewritemeta`, which wraps long text values at about 80
columns with continuation lines indented 2 more, so write them wrapped that way); the reviewer's
R8 request was answered with v0.3.1. The second review (2026-10-03) asked for a current version
and corrected "no native code" (the APK has AndroidX's `libandroidx.graphics.path.so`, ~10 KB
per ABI; the MR description now says so). The NonFreeNet text now also names the whatwatt and
its paid Plus licence. Auto update (`UpdateCheckMode: Tags`, `AutoUpdateMode: Version`) builds
later tags once merged. Before moving the recipe to a new version:
check that the GitHub APK's signer matches `AllowedAPKSigningKeys` and that its contents equal
an unsigned build of the tag (all entries outside `META-INF/`), then commit in the clone at
`../fdroiddata`. Pushing to the fork needs a GitLab token (`write_repository`) as the password;
the fork has no credential helper.

Next: on-device testing by the reviewer; answer further comments. The user posts on GitLab (it's public under their name);
Claude drafts the answers and any recipe changes, and can check the merge request and
pipelines through GitLab's public API (`/api/v4/projects/fdroid%2Ffdroiddata/merge_requests/50583`).

### whatwatt [phases 0 to 3 released in v0.7.0; phase 4, the recorder, in v0.8.0; script frozen at v2]

The design, the verified API facts and the phases are in `CLAUDE.md` under "Peak load with
whatwatt" (the recorder: "The recorder (phase 4)"); the Berry tests, the script's history and the
download measurements are in `research/whatwatt_berry_script.md`. The whatwatt runs on meter
power: poll it gently (every 5 s while visible), and never download big SD files at full speed
(it rebooted twice; ≤ 8 KB/s held). The Settings sections are settled
(2026-10-03: Region, Measurement with the recorder, Mode with peak load; see `CLAUDE.md`).

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
(the docs say Berry since 2.0.0, and the device reported `services.berry` on 2.0.0; tested only
on 2.8.2. A firmware check was dropped on 2026-10-03: no version is known to fail); the values of
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
Full screen on tap: built 2026-10-03 (`HistoryScreen` in `HistoryPanel.kt`; the user chose a
day's quarter hours and last month, opened by tapping the chart, as its own screen; design in
`CLAUDE.md` under History). Seen on the Fairphone 6 (2026-10-03, R8 build): opening by tapping
the chart, back by ← and by the system back; this month (3 Oct, today, as the highest, red; 2 Oct
grey; the day of the highest shaded and shown below); tapping days, also above a short bar
(the shade and the quarter bars follow); taps on days without a bar, the axis labels and the limit's
label do nothing; ‹ › to an empty September and back, the day reset to the month's highest; the
four gaps of 2 and 3 Oct (2 Oct 23:30; 3 Oct 00:15, 08:15, 20:15) as empty slots, matching
Settings → Recorder ("4 quarter hours missing this month"); W ("2560 W", the axis to 4000,
"2650 limit"); process death with the screen open (back on History with the tapped day). Last
month with data, seen with a temporary fake `GL260915.CSV` (3 Oct's lines 18 days earlier,
debug build, deleted afterwards): no limit line, its own highest red, the scale to 3 kW, the
day reset to 15 Sep. Fixed then (user): the shade now starts at the axis top (it stuck out above
the top tick), an empty month says "No quarter hours recorded." (not "this month" under
"September 2026"), and the main screen keeps its scroll and strip after History and Settings
(they were reset to the top). After the declutter, the Settings and setup-guide title rows
(`TitleRow`) and the WAIT rows ("Cheaper tomorrow 10:00", `comingTime`) look as before. Still
open: today dark while another day is the month's highest (from 4 Oct on); last month with real
data (from 1 Nov); the DST day's 100 slots (25 Oct).

### Appliances panel [released in v0.10.0, 2026-10-03]

The design is in `CLAUDE.md` under "Appliances", the reasoning in `research/appliances.md`; the
recorder stays at v2. Built and tested on the Fairphone 6 (2026-10-03): the measuring flow
("Cooking", "Kettle 1 L"), OK and WAIT ("Sets a new peak right now"), the collapsed summary,
rename, delete, variants ("Cooking 60 min", "Kettle 1.5 L"), export and import, and an R8 release
build (a measurement survives a force-stop). Declutter pass afterwards: see
[declutter.md](declutter.md).

Released as v0.10.0 (versionCode 12, 2026-10-03), after re-measuring "Kettle 1 L" on the R8
build (3 min · 0.10 kWh · 2.0 kW right after Done, which tested `doneAt`/`doneKwh`). From now on
`appliances.json` (export/import) and the `measuring` pref are a format to keep.

Still open:
1. **Not blocking, a patch release if needed:** the logic is covered by core tests, and what's left in
   the app is plain text and one rounding.
   - On the phone: a WAIT with a time ("· at 14:30" needs a heavy quarter with a low draw now,
     e.g. after the hob is switched off mid-quarter; with the limit at 2.1 and a 0.85 kW base it
     takes ~0.35 kWh in the quarter's first 8 min, so catch it after real cooking rather than
     staging it, user 2026-10-03); the row before tomorrow's prices are out
     (1.5, before 12:00 for CKW). Seen 2026-10-03 19:35 (debug build, "Kettle 1 L" with Can wait
     on): "Cheaper at tomorrow 10:00" (wording asked, below) and, with a 1 h delay, "Cheaper ·
     Delay 15 h".
   - ~~Wording~~: done 2026-10-03 (user): no "at" before "tomorrow" ("Cheaper tomorrow 10:00"),
     seen on the phone.
   - "Kettle 1 L" has Can wait on for the before-12:00 check on 2026-10-04 (the user sends a
     screenshot); switch it back off afterwards, as in the user's export.
   - Can wait on is saved by leaving the key out: `json` doesn't encode defaults, and
     `canWait`/`countsForLimit` default to true. Check the row or the sheet, not a grep for
     `"canWait":true` (a 2026-10-03 session took this for a bug).
   - The dishwasher run ("Dishwasher 65°", ~1.5 h, Can wait on).
   - ~~Store screenshots~~: done 2026-10-03 on the Fairphone 6, demo mode, "Kettle 1L test"
     hidden for the shots: 1 main screen with the appliances, 2 the "Cooking" preview, 3
     scrolled (strip, appliances, history), 4 help and 5 Settings kept from v0.9. F-Droid takes
     them from the built tag, so they show there from the first tag after v0.10.0. All retaken
     for v0.11.1 (2026-10-03 23:14, R8 build, neutral theme): 1 main screen, 2 the "Cooking"
     preview, 3 scrolled (strip, appliances, history), 4 the History screen (new), 5 help,
     6 Settings.
2. ~~The setup help's result line~~: done 2026-10-03 (user): "The result usually shows right
   after Done." and "Switch nothing else on or off until you tap Done."

Preview (built 2026-10-03; design in `CLAUDE.md` under "OK or WAIT"): tap
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

### The limit and its floor [released in v0.11.0, 2026-10-03]

Design in `CLAUDE.md` under "Peak load" (The limit, Alarm) and "Appliances"; the reasoning in
`research/appliances.md` ("The floor"). One line, the limit = max(goal, floor, month's highest),
floor = the biggest appliance with "Counts for the limit" on × 1.2, red at the limit (no 90%).
Core tests cover the floor, the heaviest quarter and the old JSON. Seen on the Fairphone 6 in an
R8 build: "2.6 limit" in the peak window and the history, "2.2 kW free" at 0.4 kW, all five rows
OK (Cooking included), the switch in the edit sheet; the switch turned off on both cookings
(2026-10-03, debug build: the limit fell from 2.6 to 2.1, the month's highest, above the kettles'
~0.65 floor). A red bar at the limit, seen 2026-10-03 19:49–19:52 (limit lowered to 2.1 that
way, kettle + hob): "2.1" right at the line already red, then 2.8 with the scale stretched to
4 kW, "This quarter hour sets a new peak.", all rows "Sets a new peak right now" (later quarters
at the high draw now); after the hob, 1.3, matching the register (0.23 kWh at 8.7 min + 0.85 kW
for the rest). The user's hob plate draws ~1.1 kW. The vibration was requested (19:49:08, 400 ms)
but not played: `adb shell dumpsys vibrator_manager` lists it `ignored_for_settings`, usage TOUCH,
since `vibrate()` without attributes counts as touch feedback, which is OFF in silent mode (as are
notification vibrations; alarm and ringtone stay on). Every earlier alarm that day was dropped too.
Built then (user: "let the user decide"): Settings → Mode → **Vibrate at the limit**,
Unless silent (notification, default) | Always (alarm), `peak_vibrate_always`; seen in Settings
with its ⓘ. Not seen vibrating yet: a rainy-day task (below).

### Settings and setup guide rework [released in v0.11.0, 2026-10-03]

The user's list of 2026-10-03; the design is in `CLAUDE.md` under UI ("Settings", "Sections",
"Setup guide"). Done and seen on the Fairphone 6: the sections as light grey cards that fold to
a summary (`settings_closed`), ⓘ on every section title, the recorder folded into the
Measurement card, Mode (📊) with peak load, goal, countdown and the appliances switch
(`appliances_enabled`), no dividers, the connection block folding by tap with a green Test
result, the setup guide on the same cards with ← and "n of 2", and the appliances panel's title
without 🔌, bold. A recorder warning, seen 2026-10-03 20:15 (the user agreed to stop it with
`PUT /api/v1/berry?run=false` right after the 20:00 line; 20:15 lost): "The recorder is stopped. ›"
in the peak window, Start beside the folded Recorder row, the red line under the folded Measurement
summary; Start → "Recorder started. First quarter hour at 20:45.". Fixed then: for
one reading after a successful action the old check ("stopped", with Start) came back; `act` now
drops it on success.
Disconnect (2026-10-03 ~19:57, the user unplugged the Wi-Fi repeater): the peak window's
"whatwatt not reachable" header, past bars and limit, "–" for now and every row, no preview on
tap, + blocked with the reason. Fixed then: the open Measurement card kept the
first start's "Connected. 0.8 kW now." (and a failed Test's text would have stayed, green, after
the readings reconnected); now a reading whose connected state differs from the Test's clears the
Test result, and the line shows `meter.problem` without one. Seen both ways on the phone: a failed
Test, then the whatwatt back (20:05:22, "· connected", the old text gone), then gone again a
minute later ("whatwatt not reachable"). The 19:45 quarter, recorded while it was offline, was
copied afterwards with no gap.
Open, in order:
1. ~~A theme~~: done 2026-10-03, the user chose "neutral ink", always light (`NEUTRAL` in
   `MainActivity.kt`); seen in Settings, a dialog and the appliance edit sheet (ink switches).
2. ~~Redo the help~~: done 2026-10-03 (declutter pass "whole app"): one idea per line, the
   Settings paths, the limit with its floor, 3 · now · 3, the help keeps 🔌; Mode and Goal ⓘ
   shortened to point at it. Seen on the phone (welcome page, Mode and Goal ⓘ), with the first
   start redone and a malformed address ("192.168.0.36 x") answered without a crash.

### Countries and time zones [released in v0.11.0, 2026-10-03]

Country before region shipped in v0.9.0 (`Country`, `COUNTRIES`, the Country dropdown in the
setup guide and Settings, preselected from the SIM, then the locale). On 2026-10-03 the single
`TARIFF_ZONE` (`Europe/Zurich`) was replaced by a zone per country, so a country in another
zone works (user: "make it scalable"):
- `Country(code, name, flag, zone)`; `Region(..., zone = country.zone)`, overridden only where a
  country spans several zones.
- The selected region's zone sets the tariff day: `fetchPeriod(now, zone)` (the request's
  midnights) and `wantsFetch(slots, now, region)` (`tomorrowFrom` in the region's local time).
- It also sets the peak's month and days, since the utility bills by its own calendar month:
  `missingQuarters`, `recordedSince` and `dailyHighest` take a `zone`; `WhatwattMeter` gets
  `{ region.zone }` from `MainViewModel`; `HistoryPanel`'s "today" uses `state.region.zone`.
- Times shown on screen ("Updated 14:02", "● tomorrow 10:00") stay in the phone's zone.
- Tests: London's midnights across the October DST change in the request, `tomorrowFrom` in the
  region's zone, every current region on its country's zone.
- For Switzerland nothing changes. Seen on the phone for CKW (2026-10-03, debug build of
  `d45df99`): prices with tomorrow, the peak window, the history.
- The recorder's day files are still named by the whatwatt's own clock zone; the app only uses
  the names to choose which files to copy, so a whatwatt set ahead of the region's zone copies
  each day's last quarters late (details in `CLAUDE.md`, Day files).

A new country: add a `Country` (ISO code as the phone reports it, name, flag emoji, zone) to
`COUNTRIES` and its regions to `REGIONS`; the dropdowns need no change. Then the steps for a
new region above. `tomorrowFrom` is local to the region: the day-ahead auction's ~12:55 CET is
14:15 in Finland and the Baltics (the zone per candidate country is in
`research/neighbouring_countries.md`). A country whose utilities don't follow VSE/AES needs its own parser, and a
non-CHF currency needs the price and cost-line texts changed (see
`research/neighbouring_countries.md`).

### Regions outside Switzerland [decided 2026-10-03, plan approved by the user; step 1 done]

The decisions are in `CLAUDE.md` under "Regions outside Switzerland"; the facts, sources and
APIs in `research/neighbouring_countries.md`. Wave 1 is Austria and Flanders with peak load,
plus Wallonia and Brussels, Germany, Luxembourg, the Netherlands and Liechtenstein with prices
only, all from Energy-Charts (CC BY 4.0), released before 1 Jan 2027 (Austria's peak tariff
starts then). In English; German is the next phase. No app code before the user approves this
plan: approved 2026-10-03, start when the user says so. Each step ends green on the verify
command above.

1. ~~**A price source per region**~~ (`:core` only, nothing visible changes): done 2026-10-03.
   `PriceSource` in `Region.kt`, `parseEnergyCharts` in `Prices.kt`, fixtures
   `ec-at-2026-10-03-to-04.json` (192 quarters) and `ec-at-2026-03-29.json` (93: Energy-Charts
   includes the slot at `end` when there is a price for it, like the VSE APIs). A missing
   (`null`) price drops only that slot. Seen on the Fairphone 6: CKW from the cache and a pull to
   refresh, as before. The 25 Oct fixtures are still to come (26 Oct).
   - `Region.pricesUrl` becomes `Region.source`, a small sealed type: `Vse(url)` (today's
     regions, unchanged requests) and `EnergyCharts(bzn)`. `fetchPrices(region)` builds the
     request from `fetchPeriod(now, region.zone)` (instants, not dates) and parses by source.
   - Energy-Charts: `GET https://api.energy-charts.info/price?bzn=AT&start=<UTC>&end=<UTC>`;
     zip `unix_seconds` and `price` (EUR/MWh, ÷1000 → per kWh); a slot ends where the next
     starts, the last after the same length; drop a slot starting at `end` (the existing filter).
     `deprecated: true` or an empty list counts as unreadable.
   - `PriceCache` stays as it is (internal; its `"CHF_kWh"` label just means "per kWh"), so no
     migration.
   - Tests: a saved Energy-Charts response for AT (a normal day with tomorrow) and the spring DST
     day (29 Mar 2026, 92 quarters, saved now since past days work) in `core/src/test/resources/`;
     the request URL for each source across a DST change, as the VSE test already does.
     On 26 Oct 2026, save 25 Oct 2026 (100 quarters) for both CKW and AT and add them as tests
     (the same day as the recorder's DST check). Request politely: Energy-Charts answers 429
     after ~3 quick requests; save every response, don't re-request.
   - Phone: none (CKW must look exactly as before; a quick look suffices).
2. **Currency** (`:core` + the two price texts).
   - `Country.currency`: `Currency(symbol, small)`, CHF = ("CHF", "Rp"), EUR = ("€", "ct").
     The price line and the cost line use it (`MainActivity.kt:343`, `:345`); Swiss text
     unchanged ("22.7 Rp/kWh", "0.34 CHF/h").
   - Tests: formatting for both currencies, negative prices ("−1.2 ct/kWh").
3. **The own price in spot regions** (user, 2026-10-03).
   - `Region.isSpot` (from the source). Own price = (spot + add-on) × (1 + VAT), a `:core`
     function. Prefs `price_addon` (ct/kWh excl. VAT, a string like `peak_goal_kw`) and
     `price_vat` (%, blank = the country's `Country.vat`: AT 20, BE 6, DE 19, LU 8, NL 21,
     LI 8.1, CH null).
   - `classify` and the appliances' price advice keep using the raw price (an affine change
     with a positive factor gives the same colour and the same "Cheaper at").
   - Main screen, spot region without an add-on: "Market price 11.3 ct/kWh", no cost line, and a
     "Set your price ›" line that opens Settings with Region open at the fields. With an
     add-on: the normal price line and the cost line, from the own price.
   - Settings → Region, spot regions only: "Add-on" (ct/kWh excl. VAT) and "VAT" (%) fields,
     ⓘ: what goes into the add-on (supplier markup, grid fee per kWh, levies, from the bill),
     and that the colour doesn't depend on it. Summary: "Austria · + 18.5 ct".
   - Tests: the formula (negative spot, VAT 0 and blank), the default VAT per country.
   - Phone: an AT region (temporarily, while CKW's peak stays recorded) without and with an
     add-on; the link lands on the fields; the cost line with the whatwatt.
4. **The minimum billed peak** (user, 2026-10-03: "floor" keeps its meaning, the biggest
   appliance × 1.2; the tariff's value is the "minimum").
   - `Region.minimumKw: Double?`: null = no peak billing, 0.0 = billed with no minimum (CKW),
     2.0 Austria, 2.5 Flanders. The other six Swiss regions get null (checked 2026-10-03; see
     the research file; Groupe E unconfirmed, treated as none). It's never a restriction
     (user, 2026-10-03): users there keep peak load as they set it and can switch it on.
   - `peakLine(goal, floor, minimum, highest)`: the highest of the four. The minimum counts with
     the appliances panel hidden too (it's the tariff, not an appliance). The history draws the
     same line.
   - Peak load stays off by default everywhere (it already is). With it on in a region whose
     `minimumKw` is null: a muted line under the switch in Settings → Mode and the setup guide,
     "Your region doesn't bill a peak.", and the Mode ⓘ says that the limit is then a personal
     cap that nothing is billed for. Nothing is locked.
   - Tests: `peakLine` with each combination, each region's `minimumKw`.
   - Phone: Flanders (2.5) above the user's month's highest (~2.4) shows "2.5 limit" and moves
     "kW free"; Wallonia shows the line under the switch.
5. **The regions** (`Region.kt`, README, store text).
   - Countries: AT `Europe/Vienna`, BE `Europe/Brussels`, DE `Europe/Berlin`, LU
     `Europe/Luxembourg`, NL `Europe/Amsterdam`, LI `Europe/Vaduz`, each with currency and VAT.
   - Regions (label "<name> (market price)", `tomorrowFrom` 13:15 local): Austria (`AT`, 2.0),
     Flanders (`BE`, 2.5), Wallonia and Brussels (`BE`), Germany (`DE-LU`), Luxembourg
     (`DE-LU`), Netherlands (`NL`), Liechtenstein (`CH`, hourly). Energy-Charts gives every zone
     in EUR, so Liechtenstein (where households pay CHF) shows € too (user, 2026-10-03: fine;
     CHF there is a rainy-day task).
   - Tests: every region's country, zone, source and minimum; the country dropdown from the
     phone's country (AT, BE, …).
   - Phone: first start with the setup guide, Country → region for AT and BE; prices with
     tomorrow after 13:15.
6. **Texts** (one idea per line, each concept in one place).
   - Help, Prices: "your utility's price" becomes "your tariff's price, or the market price";
     one line that the colour saves money only on a dynamic tariff, and on a fixed price still
     shows when the grid has power to spare (user, 2026-10-03: inform, don't exclude); Wallonia
     and Brussels: the time-of-use grid fee isn't included.
   - Help, Peak load: what's billed per region in one line ("At least 2 kW is billed" in
     Austria, "each month counts at least 2.5 kW, billed on the average of 12 months" in
     Flanders), and where nothing is billed.
   - Region ⓘ (`SettingsScreen.kt:80`): utilities with a dynamic tariff, or the market price
     where none publishes one.
   - The whatwatt guide's key line (`WhatwattGuide.kt:37`): "from your grid operator", the CKW
     address only for CKW.
   - The fetch errors (`priceError` in `MainViewModel.kt`) say "the utility's server"; in spot
     regions it's the market price's ("the price server").
   - Attribution in the help: "Market prices: Bundesnetzagentur | SMARD.de, via
     energy-charts.info (CC BY 4.0)".
   - README and `full_description.txt`: which layers work where (prices everywhere listed, the
     whatwatt and peak load where the meter has a port and a peak is billed).
7. **Release v0.12.0**, before 1 Jan 2027 (Austria's start), with the 25 Oct tests in.
   - F-Droid: the recipe's NonFreeNet text names the utilities' APIs; Energy-Charts is a new
     network service, so draft an update of the text for the user to post (only once the merge
     request is merged, or in it while still open).
   - Phone, release build with R8: switch CH → AT → CH and back; the cache per region; offline
     start in AT.

Later phases, in order (each settled with the user before it starts):
- **German** (user, 2026-10-03: right after wave 1): move the inline UI strings to
  `strings.xml`, add `values-de`, and `fastlane/metadata/android/de-DE/`. Dutch and French
  afterwards if wanted.
- **Spain**: REE PVPC (final price, tolls included, so no add-on), a second parser, the
  Canaries as a region with its own zone, no whatwatt.
- **Denmark**: spot plus each grid operator's tariff from Energi Data Service (two requests,
  the tariff cached for weeks), a region per grid operator.
- **An EU meter reader** (user, 2026-10-03: a future task): HomeWizard P1 or a similar device
  sold in the EU. It has no recorder, so how the month's highest is kept complete is the first
  question (in Belgium the meter's own 1-0:1.6.0 may stand in).
- **Norway**, once Norgespris' 2027 terms are known: its hourly top-three-days peak model.
- Not planned: Czechia, Poland, Hungary, Slovenia (local currencies, uptake unknown); France,
  Italy, Portugal (see the research file).

### Rainy day

Little tasks for when there's nothing else (user, 2026-10-03: skipped for now).
- **Liechtenstein in CHF** (after wave 1): its market price comes in EUR from Energy-Charts,
  while households pay CHF; show it in CHF (an exchange rate, or a CHF source for zone `CH`).
- **Vibrate at the limit on the phone:** after a real limit alarm, check
  `adb shell dumpsys vibrator_manager` for GridLoad's 400 ms vibration with usage NOTIFICATION
  (Unless silent) or ALARM (Always) and that it was played, not `ignored_for_settings`. Try both
  settings, in silent mode too.

## Conventions

The conventions are in `CLAUDE.md`. Keep to the roadmap phases, in order; ask about genuinely
open product decisions rather than guessing, but don't ask about anything settled there.
