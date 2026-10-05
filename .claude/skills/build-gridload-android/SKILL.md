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
  and the screens: main (`MainActivity.kt`, `PricePanel.kt`, `PeakWindow.kt`, `AppliancesPanel.kt`,
  `HistoryPanel.kt`, with the shared panel and chart drawing in `Charts.kt`), the History
  screen (`HistoryScreen` in `HistoryPanel.kt`), Settings and the setup guide
  (`SettingsScreen.kt`, `WhatwattGuide.kt`; `Page` and `TitleRow` are shared), and all the help,
  its texts and its dialogs, in `Info.kt`.
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
- JSON in the prefs (e.g. `measuring`): pull `shared_prefs/settings.xml` with the app stopped,
  edit it locally and `cat` it back through `/data/local/tmp` (sed's `&` breaks the `&quot;`
  entities). Back up what you stage and restore it byte-identical.
- Screenshots: `adb exec-out screencap -p > file.png`. For store screenshots, use SystemUI demo
  mode (`settings put global sysui_demo_allowed 1`, then `am broadcast -a
  com.android.systemui.demo -e command enter|clock|notifications|status|exit ...`) so the user's
  status bar icons don't show, with the clock set to match "Updated" (pull to refresh first).
  On the Fairphone 6, `network -e mobile hide -e sims 0` and `network -e wifi show -e level 4
  -e fully true` leave only Wi-Fi and the battery. Afterwards exit demo mode and set
  `sysui_demo_allowed` back to 0. The six store screenshots (v0.14.0, 2026-10-04, real state, no
  staging): the main screen with the price curve and the peak window; Cooking's preview; the
  appliances and the history with the base load; the History screen; the help; Settings → Mode
  with the Limit group.
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

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`, and `APP_VERSION` in
   `core/.../Io.kt` (the User-Agent; `:core:test` fails until they match).
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (max 500 characters).
3. Commit and tag `vX.Y.Z` (only when the user asks); the user pushes. The tag builds the signed
   GitHub Release for Obtainium.
4. F-Droid: once the merge request below is merged, auto update picks up new tags by itself. It
   rebuilds the tag and publishes the GitHub APK only if the two are byte-identical apart from
   the signature, so keep the build deterministic. For build-only changes, compare the unsigned
   release APK's sha256 before and after.

## Roadmap

Work phase by phase and update the status in brackets. Finished phases keep only their status,
what's still open and the how-tos later work needs; the history is in git.

### Done

- **v0.2 to v0.6:** Settings and first start, the icon; today + tomorrow, the 24-hour window and
  the seven Swiss regions (v0.3); peak load from manual appliances and a CKW Excel import (v0.4,
  v0.5), dropped in v0.6.0 (commit `71f954f`; reasons in `CLAUDE.md`).
- **v0.7 / v0.8:** the whatwatt (phases 1 to 3) and the recorder (phase 4), below.
- **v0.9 to v0.11:** the panels and the history, the appliances, the limit with its floor,
  Settings as sections, the neutral theme, a time zone per country, the full-screen history.
- **v0.12 to v0.14:** wave 1 of the regions outside Switzerland; the energy-planning steps 1 to 4
  (the price curve, the Limit setting), the 90th percentile, Draw ahead and Share.
- **Declutter passes:** see [declutter.md](declutter.md), which also holds the reusable
  checklist for the next pass.

### F-Droid [submitted 2026-09-29, in review]

Merge request https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50583, from the user's fork
`buerlino/fdroiddata`, branch `io.github.buerlino.gridload`, file
`metadata/io.github.buerlino.gridload.yml`. Recipe choices: `NonFreeNet` anti-feature, category
`Market & Price`, `GPL-3.0-only`, `Binaries` + `AllowedAPKSigningKeys` for reproducible builds.
The pipeline is green: `fdroid build` (Debian 13, JDK 21 by default, so no `sudo` block) and the
reproducible-build check. The MR description says the APK has AndroidX's
`libandroidx.graphics.path.so` (~10 KB per ABI); the reviewer's R8 request was answered with
v0.3.1.

Status (2026-10-04): the MR's head is 0.13.0 (`3ade4eee0`, versionCode 16, pipeline green).
Checked 2026-10-04: the GitHub APK of v0.14.0 (sha256 `2a38f0cd…5e59a20`) is signed with the
`AllowedAPKSigningKeys` certificate, and its 25 entries outside `META-INF/` equal an unsigned
build of the tag. The recipe skips it for v0.14.1 (versionCode 18, the same app with new store
screenshots and description, since F-Droid reads those from the built tag): its GitHub APK
(sha256 `26e26618…59343c3a`, signer as above) equals an unsigned build of the tag outside
`META-INF/` (checked 2026-10-04), and the recipe's move to it is committed in `../fdroiddata`
(`a8e76070c`, commit `199bdb65cacdee7aac71d1a757168704901bf1a0`); changelog 17 is deleted. The NonFreeNet text, unchanged since 0.12.0:
```
NonFreeNet:
  en-US: Loads the prices from the chosen utility's web API or Energy-Charts. The
    optional peak load reads a whatwatt Go meter reader on the local network, which
    needs its paid Plus licence.
```
Auto update (`UpdateCheckMode: Tags`, `AutoUpdateMode: Version`) builds later tags once merged.

Moving the recipe to a new version: check that the GitHub APK's signer matches
`AllowedAPKSigningKeys` and that its contents equal an unsigned build of the tag (all entries
outside `META-INF/`), then commit in the clone at `../fdroiddata`. Build the tag in a scratch git
worktree. A local build's `META-INF/version-control-info.textproto` can say
`NO_VALID_GIT_FOUND` instead of the commit (seen 2026-10-04 in the worktree and in the working
tree); CI's and F-Droid's checkouts of the tag write the commit, as the GitHub APK has, and
F-Droid's reproducible check passed with it. The recipe's `commit` is the tag's commit
(`git rev-parse vX.Y.Z^{commit}`), not the annotated tag object's hash. `fdroid rewritemeta` wraps
long text values at about 80 columns with continuation lines indented 2 more, so write them
wrapped that way. Pushing to the fork needs a GitLab token (`write_repository`) as the password;
the fork has no credential helper. The user pushes and posts on GitLab (it's public under their
name); Claude drafts the answers and recipe changes, and can check the MR and pipelines through
GitLab's public API (`/api/v4/projects/fdroid%2Ffdroiddata/merge_requests/50583`).

Next: on-device testing by the reviewer; answer further comments.

### whatwatt [phases 0 to 3 released in v0.7.0; phase 4, the recorder, in v0.8.0; script frozen at v2]

The design and the phases are in `CLAUDE.md` under "Peak load with whatwatt"; the device and
REST API facts in `research/whatwatt_api.md`; the Berry tests, the script's history, the overnight validation (32 quarters within
0.0012 kWh of the CSV log) and the download measurements are in
`research/whatwatt_berry_script.md`. The whatwatt runs on meter power: poll it gently (every 5 s
while visible), and never download big SD files at full speed (it rebooted twice; ≤ 8 KB/s
held). Gaps count only up to the last check, which is every 15 min while the recorder waits for
its first quarter, so a fresh gap can take that long to show.

Open:
1. **The DST night** (25 Oct 2026, 02:00–03:00 twice): the lines are keyed by UTC, so the day
   file just has 100 lines; check it on 26 Oct, with the price fixtures below.
2. What `onreport` delivers when the meter isn't `OK`.

Unknowns to check when they matter: whether Berry needs the Plus licence; the minimum firmware
(Berry since 2.0.0 per the docs and the device; tested only on 2.8.2; no firmware check, since no
version is known to fail); the values of `execution_status.state` other than `RUNNING` and
`IDLE` (shown verbatim).

### Panels and history [released in v0.9.0 and v0.11.1]

Design in `CLAUDE.md` (UI, Power unit, History). To force scrolling with both panels open:
`adb shell wm size 1116x1500`, `wm size reset` after. A fake day file (a copy of a real one
shifted by whole days, debug build) shows last month with data; delete it afterwards.

Still open: today dark while another day is the month's highest (from 4 Oct on); last month
with real data (from 1 Nov); the DST day's 100 slots (25 Oct).

### Appliances [released in v0.10.0; the limit's floor in v0.11.0]

Design in `CLAUDE.md` under "Appliances" and "Peak load" (The limit), the reasoning in
`research/appliances.md`. `appliances.json` (export/import) and the `measuring` pref are a
format to keep.

- Can wait on is saved by leaving the key out: `json` doesn't encode defaults, and
  `canWait`/`countsForLimit` default to true. Check the row or the sheet, not a grep for
  `"canWait":true` (a 2026-10-03 session took this for a bug).
- A red bar at the limit is easiest to see with the cookings' "Counts for the limit" off (the
  limit falls to the month's highest) and kettle + hob; switch them back afterwards.

Still open (not blocking; the logic is covered by core tests):
- On the phone: a WAIT with a peak time ("Sets a new peak · at 14:30" needs a heavy quarter with
  a low draw now, e.g. after the hob is switched off mid-quarter; catch it after real cooking
  rather than staging it, user 2026-10-03); a WAIT before 12:00 (a red morning, CKW).
- The dishwasher run ("Dishwasher 65°", ~1.5 h, Can wait on).
- The preview without a line (the goal off and nothing recorded this month: only after a
  `pm clear` or on a fresh phone).

### Regions outside Switzerland [wave 1 released in v0.12.0, 2026-10-04]

Decisions in `CLAUDE.md` under "Regions outside Switzerland"; facts, sources and APIs in
`research/neighbouring_countries.md`; the colour's scores in `research/classification.md`.

Open:
1. On 26 Oct 2026, save 25 Oct 2026 (100 quarters) for CKW and AT and add them as tests, the same
   day as the recorder's DST check. Save every response, don't re-request (Energy-Charts answers
   429 after ~3 quick requests). A patch release only if they turn something up.
2. On the phone: tomorrow's market prices (after 13:15); the 90th percentile in a market-price
   region (more red than before, the next good time later); the saving with an add-on
   (`ownSaving` is covered by a core test); the help's general version (needs a phone whose SIM
   and locale aren't in the list).
   Tried 2026-10-04 19:43 on the debug build (Austria and a 15 ct add-on staged, a Can-wait
   "Test 1 h" in `appliances.json`): Energy-Charts answered 503 (curl too), so only the failure
   showed: "No prices", "The price server answered with error 503.", the row "–". Restored after.
   The v0.14.0 release test (2026-10-04 20:20) skipped this: Energy-Charts still answered 503.

A new region: test the request with the app's code (a request of up to 31 days, past days too,
gives plenty of data to analyse in one call; mind CKW's 4 per window), add it to `REGIONS` with
its `Country` and `source` (`PriceSource.Vse(url)` for a utility, `market(...)` for a market
price), the table in `CLAUDE.md`, `README.md` and the store description. Set `minimumKw` where it
bills a peak (0.0 with no minimum, null where none is billed), `peakFrom` where that billing
starts later, and `priceNote`/`peakNote` only for what the help must say about it alone. Pass
`zone` only if it differs from the country's (e.g. the Canaries in Spain).

A new country: add a `Country` (ISO code as the phone reports it, name, flag emoji, zone,
currency, `vat`: the standard rate in %, the own price's default; null where no region is a
market price) to `COUNTRIES` and its regions to `REGIONS`; the dropdowns and the help need no
change. `tomorrowFrom` is local to the region: the day-ahead auction's ~12:55 CET is 14:15 in
Finland and the Baltics. A source that is neither VSE/AES nor Energy-Charts needs a new
`PriceSource` (its request in `pricesRequestUrl`, its parser in `pricesFrom`); a currency other
than CHF and EUR needs a new `Currency` (see `research/neighbouring_countries.md`).

### Energy planning [steps 1 and 2 released in v0.13.0, 3 and 4 and the base load in v0.14.0]

Claude's proposal, confirmed by the user 2026-10-04, before German, so the new strings are
translated once and wave 1's users, mostly without a whatwatt, get more than one colour; the
reasoning in `research/feature_ideas.md` (Review). Each step was settled with the user before it
started; the designs are in `CLAUDE.md`. All of them were seen on the Fairphone 6 and in the R8
release build (the v0.14.0 release test, 2026-10-04, the signed GitHub APK).
1. ~~A timer on WAIT rows~~ and 2. ~~the saving on WAIT rows~~ (`CLAUDE.md`, OK or WAIT). To
   stage a "Cheaper at" row: a `prices.json` with a few dear quarters now and today copied as
   tomorrow, so nothing is fetched; restore it byte-identical afterwards.
3. ~~The price curve panel~~ (`CLAUDE.md`, Price curve). To stage negative prices: copy a
   fetched `prices.json` and set some of tomorrow's `integrated` values below 0. To stage the
   header: a `prices.json` ending at tomorrow 00:15 local (so `wantsFetch` stays false), dear
   before now and cheap from now. With tomorrow's whole day the window starts at now, so a green
   run to its end makes the range 0: "Green for now" only shows while the window starts before
   now (before tomorrow's prices, or after midnight). Restore the file afterwards.
4. ~~The Limit setting~~ (`CLAUDE.md`, The limit), with "sets a new peak" only above the month's
   highest (`isNewPeak`). To stage each "No limit" reason: only the floor on with the appliances
   hidden (none on); the copied day files removed and the address at `192.0.2.1`, so nothing
   syncs (nothing recorded); every `countsForLimit` false in a staged `appliances.json` (no
   appliance counts); the goal empty. A goal below the draw gives a red bar and "reaches the
   limit". Still open: a row "Reaches the limit · at hh:mm" (a goal below the base draw can't give
   one); last month's History without a line (September has nothing recorded).
   **Vibrate at the limit** (both choices as ALARM, Unless silent checks the ringer mode itself):
   check with `dumpsys vibrator_manager`, "Recent vibrations", grouped by usage (`finished`,
   `ignored_for_ringer_mode`, `ignored_for_settings`). As NOTIFICATION it depended on Android's
   notification vibration, which is off on the user's Fairphone 6
   (`settings get system notification_vibration_intensity` = 0). Ringer mode over adb:
   `cmd media_session volume` doesn't change it; open the volume panel and use its chooser in one
   go, `adb shell "input keyevent KEYCODE_VOLUME_DOWN; sleep 0.4; input tap 1008 900; sleep 0.6;
   input tap 1008 <y>"` (y: 648 vibrate, 775 silent, 898 sound; the key also lowers the media
   volume by a step), check with `dumpsys audio | grep -A2 '^Ringer mode'`.
- ~~The base load line~~ (`CLAUDE.md`, History). Checked against the day files: the median of the
  quarters in the hours matches the line, past midnight too. Still open: the line settling over
  7 nights; the "no ¼ hours" line (every hour has quarters in the last 7 days now).
- **Decide before German:** appliances by run time without a whatwatt (price-only rows from a
  flat curve), which touches the "measured only" rule. Only the user can decide.

Draw ahead (v0.13.0), studied 2026-10-05 (`research/draw_ahead.md`: the audit, the policy study
and its harness, `DrawAheadTest` and `MeterTrace` in core's tests). Done in the working tree, not
released: the glance fallback (this ¼ hour so far) with the legend naming the average, and
stale readings dropped after 30 s. Open:
1. The quarters after the next: keep the 2-min average or a longer one, decided on real readings.
   `research/draw_ahead/capture.py` polls `/api/v1/report` every 5 s (the app's rate, nothing
   sent to the whatwatt) 17:00–21:00 on 6–8 Oct 2026, into `../gridload-captures/` (outside the
   repo: household data). Then score the policies on them (the harness's policies on real
   readings, the quarters from the day files).
2. On the phone: the legend's three texts, "whatwatt: no new reading" (needs a frozen report; not
   staged), a glance at a cycling hob (no red bar).

### Household alerts [(a) released in v0.14.0]

Telling the household when a quarter hour heads for a new peak. Research, design and sources in
`research/household_alerts.md`; the design in `CLAUDE.md` (the peak window).
1. ~~(a) Share~~: the button beside the warning, open and collapsed, and the share sheet's text,
   seen in the release build (closed without sending). Open: a message actually sent to a
   messenger group (the user sends one when wanted); the Share text with "sets a new peak" in the
   release build (no real peak happened).
2. ~~(b) ntfy~~: dropped 2026-10-04 (it sends only while GridLoad is open somewhere).
3. Later, not planned: (b2) a background sender on a hub phone (phase 5) with ntfy, the only
   way an automatic alert would be useful; see the research file.

### Later phases, in order (each settled with the user before it starts)

- **German** (user, 2026-10-03): move the inline UI strings to `strings.xml`, add `values-de`,
  and `fastlane/metadata/android/de-DE/`. Dutch and French afterwards if wanted. Some copy is in
  `:core`, which can't use `strings.xml`: the help's lines per country (`Help.kt`) and the
  regions' `priceNote`/`peakNote`. Make core return the facts (which regions bill, from when, the
  minimum, the publication hours, a note's kind) and the app word them; `HelpTest` then checks
  the facts.
- **Spain**: REE PVPC (final price, tolls included, so no add-on), a second parser, the Canaries
  as a region with its own zone, no whatwatt.
- **Denmark**: spot plus each grid operator's tariff from Energi Data Service (two requests, the
  tariff cached for weeks), a region per grid operator.
- **An EU meter reader** (user, 2026-10-03: a future task): HomeWizard P1 or a similar device
  sold in the EU. It has no recorder, so how the month's highest is kept complete is the first
  question (in Belgium the meter's own 1-0:1.6.0 may stand in).
- **Norway**, once Norgespris' 2027 terms are known: its hourly top-three-days peak model.
- Not planned: Czechia, Poland, Hungary, Slovenia (local currencies, uptake unknown); France,
  Italy, Portugal (see the research file).

### Rainy day

Little tasks for when there's nothing else (user, 2026-10-03: skipped for now).
- **Liechtenstein in CHF**: its market price comes in EUR from Energy-Charts, while households
  pay CHF; show it in CHF (an exchange rate, or a CHF source for zone `CH`).

## Conventions

The conventions are in `CLAUDE.md`. Keep to the roadmap phases, in order; ask about genuinely
open product decisions rather than guessing, but don't ask about anything settled there.
