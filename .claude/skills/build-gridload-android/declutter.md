# Declutter

The user wants a lean repo and a periodic declutter pass (2026-10-02). Each pass: scan
everything, report what could be removed, rewritten or is deprecated, plus architectural
flaws; the user decides; then work through the checklist below and tick items off.

## What to check in a pass

- **Code:** dead or duplicated code, logic repeated between `:core` and `:app`, magic numbers
  that core already defines, classes taking on too many jobs, raw errors shown to the user.
- **Migrations:** remove migration code two releases after F-Droid has shipped past the version
  that needed it (user, 2026-10-02).
- **Docs:** `CLAUDE.md` loads into every session, so keep it to current facts and decisions;
  finished-phase narratives go (git keeps them). Look for stale lines (removed features, "planned"
  for built things, links to headings that moved), duplicates between `CLAUDE.md`, this skill
  and the research notes, and UI text copied into docs (it drifts from the code).
- **Repo:** files nothing uses, fastlane changelogs for versions F-Droid never built, `.gitignore`
  comments, loose git objects (`git gc`).
- **Build/CI:** `./gradlew :core:test :app:lintDebug` (warnings; add `:app:lintAnalyzeDebug
  --rerun`, since lint can repeat a stale report), Kotlin compiler warnings (lines starting
  `w:` in the build output), `--warning-mode all` (deprecations), two clean unsigned
  `assembleRelease` builds with the same sha256 (reproducible for F-Droid), action versions in
  `.github/workflows/`, redundant `gradle.properties`.

## Pass 2026-10-02

Report: lint 0 errors / 12 warnings, tests green, no unused code or dependencies. Clutter was
mostly in the docs.

### Design flaws and bugs
- [x] 1.1 Save the last price response and the last fetch attempt, so the cooldown survives
  the app being closed (and the colour shows at once, offline too).
- [x] 1.2 Move the whatwatt, quarter recording and peak alarm out of `MainViewModel` into a
  plain class it owns (before phase 4).
- [x] 1.3 Move the polling loops out of the Compose tree into `lifecycleScope` +
  `repeatOnLifecycle` in `onCreate`.
- [x] 1.4 Put the whatwatt test result into `UiState` instead of a separate StateFlow.
- [x] 1.5 Past bars show "–" for the first 30 min of a month: load last month's quarters into
  `recent` too.
- [x] 1.6 Friendly price errors instead of raw exception text.
- [x] 1.7 Don't poll half-typed whatwatt addresses.

### Code simplifications
- [x] 2.1 One HTTP GET helper in core for prices and the whatwatt; `HttpException` with it.
- [x] 2.2 One shared lenient `Json` instance.
- [x] 2.3 `PriceSlot.covers(now)` instead of the predicate in `classify` and `wantsFetch`.
- [x] 2.4 Peak: no `900` in the UI (`Projection.start`), `Scale` takes `state.peakLine`, one
  `UiState.peakWarning`.
- [x] 2.5 One `HH:mm` formatter, one red, `Region.label`, one goal kW parser.
- [x] 2.6 Settings saving through KTX `prefs.edit { }` (10 lint warnings).
- [x] 2.7 Test result in kW with one decimal, like everywhere else.

### Migrations
- [x] 3.1 Note the removal rule in `CLAUDE.md`; keep the `peak.json` and `mode` migrations for now.

### Docs
- [x] 4.1 Trim `CLAUDE.md` to current facts: drop the UI rework narrative and its draft texts,
  the reset story, the done phase 0 checklist; fix the stale lines (modes, `0f9b17b`, "later
  the whatwatt client", "cache responses locally", "(planned …)" heading and its anchors).
- [x] 4.2 Link the Tailscale note from phase 5 (remote access already works without app changes).
- [x] 4.3 `SKILL.md`: fix the description and the mode wording, the broken heading reference,
  shorten the F-Droid narrative, drop what duplicates `CLAUDE.md`.
- [x] 4.4 `research/neighbouring_countries.md`: update the summary and code items 3–4 (no modes,
  no Excel import).
- [x] 4.5 README: drop the appliance parts of "Later ideas" (user, 2026-10-02), add build lines.

### Repo, build and CI
- [x] 5.1 Delete fastlane changelogs 3, 4, 6, 7 (never built by F-Droid); keep 5, 8, 9.
- [x] 5.2 Monochrome launcher icon layer.
- [x] 5.3 Backup: device transfer only, no cloud backup (user, 2026-10-02); replaces the
  deprecated `allowBackup`.
- [x] 5.4 Release workflow on `checkout@v5` / `setup-java@v5`; core tests on every push
  (user, 2026-10-02).
- [x] 5.5 Remove `android.useAndroidX=true` if the build passes without it.
- [x] 5.6 Reword the `*.xlsx` comment in `.gitignore`; `git gc`.
- [x] 5.7 Verify: tests, lint, debug and R8 release builds; the phone test is for the user.

### Done (2026-10-02)

All items done; verified with tests (34), lint (0 warnings), debug and R8 release builds. The
phone test is the user's. Where the work differed from the checklist or the notes:
- **1.1:** the cache file isn't cleared on a region switch: it carries the region id and is used
  only when it matches and still covers now. The last attempt is cleared.
- **1.7:** a 10 s pause after the last edit of the address.
- **2.1:** one timeout per request (prices now 15 s for connect too, was 10 s).
- **2.5:** also merged `roundKw` and `shown` into one `roundKw` (the goal pre-fill now rounds
  exactly like the scale's labels). `parseKw` is in core, with a test.
- **5.2:** option C (user): the mast with GL cut out, computed with shapely (see the Icon section
  of `SKILL.md`).
- **5.3:** lint still asked for `fullBackupContent`; suppressed with `tools:ignore`, since
  `allowBackup="false"` already covers Android 8 to 11.
- **5.4:** `checkout@v7` and `setup-java@v6`, the current majors (past the v5 named above).
- **Also:** the `compileSdk 37` reason in `CLAUDE.md` was stale (it's Compose and lifecycle
  2.11, not core-ktx 1.19). Lint's up-to-date check missed a manifest edit once; `--rerun` on
  `lintAnalyzeDebug` fixed it (noted in `SKILL.md`). Gradle's only deprecation
  (`Configuration.setVisible`) comes from a plugin, not this build.

### Notes for whoever works through this (2026-10-02)

Suggestions, not decisions; check them against the code first.
- **1.1:** the cache can reuse the VSE classes in `Prices.kt` (write the slots back in the same
  JSON, plus the region id and fetch time), written atomically like `QuarterStore`; share that
  tmp-and-move code. Keep the last attempt in SharedPreferences, add the key to the list in
  `CLAUDE.md`, and clear both on a region switch.
- **1.2:** the alarm joins settings (goal, peak switch) with meter data, so it may stay in the
  ViewModel. The new class can then hold reading, recording, projection and the Test. Read its
  state directly rather than through a combined flow, which can lag a tick.
- **1.7:** for example, skip polling while the address was edited in the last few seconds. Keep
  "the block stays open until a Test succeeds".
- **2.6:** core-ktx is only on the classpath transitively (`androidx.core:core` resolves to
  1.18.0). Declare it in `libs.versions.toml` at the resolved version so the APK doesn't change.
- **5.2:** a monochrome layer is an alpha mask. The foreground's white "GL" with its black outline
  would merge into the mast's silhouette, so reusing `ic_launcher_foreground` likely loses
  the letters. It probably needs its own simple drawable; show the user a render before deciding.
- **5.3:** correction to the report: for apps targeting Android 12+, `allowBackup="false"` only
  turns off cloud backup, and phone-to-phone transfer already happens. So
  `dataExtractionRules` (cloud excludes every domain, transfer takes everything), with
  `allowBackup="false"` kept for Android 8–11, mainly makes today's behaviour explicit and
  silences lint.
- **5.4:** `gh` isn't installed, so check the current major versions of `actions/checkout` and
  `actions/setup-java` on GitHub (checkout may be past v5). Limit the test workflow to branch
  pushes and PRs, so tags don't run it twice next to `release.yml`.

## Pass 2026-10-03

Report (before v0.8.0): lint 0 issues, tests green, the only Gradle deprecation is still a
plugin's. No unused code or dependencies. Migrations unchanged (F-Droid hasn't shipped yet).
The user said go; all done, with 1.1 accepted as is (below).

### Design
- [x] 1.1 A fresh gap shows up to 15 min late: while the recorder waits for its first quarter,
  `nextLineDue` points at that quarter's end, so the app syncs only every 15 min in between
  (seen 2026-10-03: the 08:15 gap appeared at the 08:39 sync). Accept, or also sync 5 s after
  the next boundary following a start.
- [x] 1.2 Store screenshots are v0.7's: "highest seen", "Highest seen only while GridLoad is
  open", no Recorder row. Retake for v0.8 (demo mode, see `SKILL.md`).

### Code
- [x] 2.1 `checkRecorder` computes "first quarter after a start" again; use `nextLineDue`.
- [x] 2.2 `PeakWindow`: the recorder's `val line` shadows the peak `line`; rename it.
- [x] 2.3 Stale wording in comments: "In peak load mode" (`MainActivity`), "last month's highest
  seen" (`setGoalEnabled`).

### Docs
- [x] 4.1 `SKILL.md`, Working on the phone: the `files/quarters/2026-10.json` example and the
  bullet on checking recorded quarters against the SD log are v0.7's; now `files/recorder/GL*.CSV`,
  and the CSV log is off.
- [x] 4.2 `research/whatwatt_berry_script.md` names `QuarterRecorder`, which is gone.
- [x] 4.3 Optional: `CLAUDE.md` "Recording while away" keeps five struck-out options; shorten
  to one line each (the reasons still stop them from being reopened).

### Repo
- [x] 5.1 200 loose objects (936 KiB): `git gc`.

### Done (2026-10-03)

- **1.1:** accepted as is. A sync at the next boundary wouldn't help: the missing quarter only
  counts a minute after it ends (`GRACE`), so it would need a sync tuned to that for a few
  minutes' gain.
- **1.2:** retaken with v0.8.0 (main screen with the peak window, help, Settings with the Recorder row).
- **2.1:** `pendingStart()` shared by `checkRecorder` and `nextLineDue`; behaviour unchanged (tests).

## Pass 2026-10-03 (after the panels)

Quick check of the uncommitted panels work: lint 0 issues, tests green, the only Gradle
deprecation is still a plugin's. No leftovers of the dropped scale switch or `roundKw`.
- [x] 1.1 `Note` was made `internal` without need; private again.
- [x] 1.2 `CLAUDE.md` UI text: the file list was missing `HistoryPanel.kt` and `WhatwattGuide.kt`.
- [x] 2.1 `Scale` (`PeakWindow.kt`) and `DayBars` (`HistoryPanel.kt`) repeat the y axis (maxKw
  and step, ticks, unit label, axis lines) and the "0.9 highest" / "goal" labels, ~25 lines.
  Extract a shared `drawAxis` and `lineLabel` while the history chart is reworked; the shared
  panel pieces (`Panel`, colours, `tickLabel`, `drawLabels`) could then move from
  `PeakWindow.kt` to a small `Charts.kt`.
- [x] 2.2 Store screenshots show the Refresh button and the old peak window: retake before the
  next release. Done 2026-10-03: all three retaken (main screen with both panels, help, Settings).
- Done 2.1: `Charts.kt` holds `Panel`, the colours, `SMALL`, `Axis` (ticks, unit, axis lines,
  `y()`), `drawLines`, `lineLabel` and `drawLabels` (which now sorts by itself); `tickLabel` is
  folded into `Axis`. The history's highest line is now 2.5 dp like the peak window's (was 2).
- Accepted: `peak_scale_without_reading` stays in existing prefs, unread (removing it would need
  migration code for nothing).

## Pass 2026-10-03 (after the appliances)

Report: 59 tests green, lint 0, the only Gradle deprecation is still the plugin's
`Configuration.setVisible`. The user decided each item (below).

### Design and bugs
- [x] 1.1 Done's quarter squeezes the whole quarter's household draw into the run's last piece.
  (a): save the energy used so far in Done's quarter at Done, exact from the register, like
  `baseKw` at Start; the extra is that minus base × (Done − quarter start), spread from
  max(Start, quarter start) to Done. The result then needs only the quarters before Done's
  (Pending, "Result at"). Nullable: older measurements and estimated projections fall back.
- [x] 1.2 Crash on Save: Done on a boundary or in Start's second gives a 0-minute piece at
  Infinity kW. Done's quarter is `quarterStart(done − 1 s)`, skip zero-length pieces, catch
  `Exception` when saving.
- [x] 1.3 "Delay 2 h 59 min": round to the nearest minute.
- [x] 1.4 "Sets a new peak · · Delay 1 h": one separator.
- [x] 1.5 "Prices after tomorrow 00:00 not out yet" → "Tomorrow's prices aren't out yet."
- [x] 1.6 Can wait with no prices at all shows a plain OK (low): propose the smallest fix, ask
  before adding UI text.
- [x] 1.7 `appliances.json` saves one at a time (`Dispatchers.IO.limitedParallelism(1)`).
- [x] 1.8 Import: unique names and a last piece above 0 kW.
- [x] 1.9 Variant name of a variant: also strip "· 80 °C". A cut-then-stretched variant losing
  its shape follows from plain curves: leave it.
- [x] 1.10 Confirm before Discard, like Delete.

### Tests (core)
- [x] measure: Done on a boundary and in Start's second; Done seconds into a quarter with
  household draw; Start and Done in one quarter; across midnight and a month end from day
  files (GL260930, GL261001) through the parser; the DST night (01:50–03:20, 25 Oct 2026).
- [x] advise: the DST night's 100 slots; a start delay when the prices end early; no prices at
  all; Can wait with no line.
- [x] parseAppliances/mergeAppliances: duplicate names, a last piece at 0 kW.
- [x] withRunTime: cut, then stretched again.

### Code
- [x] 2.1 `parseKw` → `parsePositive` (kW, W, minutes, litres).
- [x] 2.2 `minutes`, `kwh`, `kw` on the curve (`List<Piece>`).
- [x] 2.3 One "14:15 / tomorrow 10:00" formatter; one "N appliance(s)" helper.
- [x] 2.4 No file split (user).
- [x] 2.5 Stale comments: `Projection`'s two doc comments, "OK at 14:15" on `whenToStart`, the
  `MainViewModel` class comment, `UiState` naming two panels.

### Docs
- [x] 3 `CLAUDE.md` "## Appliances" matches the code after the fixes.
- [x] 4.1 Trim `research/appliances.md` to about 120 lines.
- [x] 4.2 The skill's appliances section: status, open items, the prefs tip; fix its
  architecture line.
- [x] 4.3 `CLAUDE.md` "## Appliances": drop quoted UI text that carries no decision.
- [x] 4.4 `research/feature_ideas.md`: the "planned appliance calculator" points to `CLAUDE.md`.

### Repo
- [x] 5.2 `git gc` (243 loose objects).
- [x] 5.3 `changelogs/12.txt` isn't written: the user decides on a release (noted in `SKILL.md`).

### Done (2026-10-03)

All done; verified with 69 tests (10 new), lint 0, debug and R8 release builds, and the R8 build
on the Fairphone 6 (installed over v0.9.0's data: the four appliances load and advise). Where
the work differed from the checklist:
- **1.1:** `Projection` now carries the reading's `time` and `usedKwh` (exact energy so far);
  `estimated` and `baseKw` are derived from them. `doneAt` uses it only from a fresh reading
  (under 15 s: on return from the background the last projection can be minutes old, and its
  energy would miss the rest of the run). A fresh reading from before the boundary (Done in a
  quarter's first seconds) puts Done at the boundary, so that case doesn't fall back to the
  squeeze either. The result is now usually there right after Done.
- **1.2:** also `parsePositive` rejects "Infinity" and "NaN" (they parse as doubles), and
  `parseAppliances` non-finite values.
- **1.5:** `Advice.Ok.pricesEnd` (an `Instant`) became `pricesMissing` (a `Boolean`).
- **1.6:** the user's choice: with no prices at all, a Can-wait appliance shows "–" (no chip,
  like without a reading; `advise` returns null), unless starting now sets a new peak, which
  still shows WAIT with its time. No new text.
- **1.9:** as decided; the test pins a cut-then-stretched variant.
- **1.10:** "Discard this measurement?" / "Its result is lost. To get one, measure it again." /
  Cancel, Discard. Seen on the phone: Cancel keeps the measurement, Discard removes it.
- **2.2:** `minutes`/`kwh`/`kw` moved from `Appliance` to `List<Piece>` (no copies left); the
  private `minutes(from, to)` is now `minutesBetween`.
- **2.3:** `comingTime` in `MainActivity.kt`.
- **5.2:** 287 loose objects by then; 0 after `git gc`.
- **Phone:** 1.3, 1.4 and 1.5 couldn't be seen at 15:00 (no WAIT with a time on an appliance
  with a start delay; tomorrow's prices out). 1.4 and 1.5 are plain strings; 1.3 rounds in the
  app, untested by core.

## Pass 2026-10-03 (whole app)

Report (at `aaf0e01`): 72 tests green, lint 0, debug and R8 release builds fine. The user
approved the whole list.

### Bug
- [x] 1.1 Test crashed on a malformed address ("192.168.0.36 x"): `URI(url)` throws
  `URISyntaxException`, which nothing caught. `URI.create` (an `IllegalArgumentException`,
  which Test already turns into "That doesn't look like an address."); `act()` catches it too;
  a core test.

### Code
- [x] 2.1 `PriceCache.clear()` was used only by its test: removed.
- [x] 2.2 `recorderAction?.startsWith("Couldn't")` in two places → `MeterState.recorderFailed`,
  set by `act()`.
- [x] 2.3 `MainActivity.kt` imports sorted; `HistoryPanel`'s unreachable `?: highest.kw`.

### Docs
- [x] 3.1 README: peak load under Mode, the scale (3 · now · 3) and the limit with its floor,
  red at the limit, the panel order, the Settings paths, "Counts for the limit".
- [x] 3.2 Store text: warns at the limit, not "before a new monthly peak".
- [x] 3.3 `CLAUDE.md`: "Without a reading", "No price brackets", "7 min left", the goal's
  rounding, "Ideas accepted" folded into Design, history git keeps, the Remove dialog described
  instead of quoted, Phases one line each.
- [x] 3.4 `research/feature_ideas.md`: the Recorder path; Tools shrunk to a pointer.
- [x] 3.5 Redo the help and check the ⓘ texts against it.

### Repo
- [x] 4.1 Changelogs 5, 8, 9, 10, 11 deleted (the recipe builds only versionCode 12).
- [x] 4.2 `git gc` (246 loose objects → 0).

### Done (2026-10-03)

Verified with 73 tests (1 new), lint 0, debug and R8 release builds. The phone test is the
user's. Where the work differed from the checklist:
- **1.1:** `act()` says "Couldn't install the recorder: the address isn't valid." A saved bad
  address in the polling loops was already caught (`catch (_: Exception)`).
- **2.3:** the history keeps a null check on the limit (smart cast, no fallback value) rather
  than `!!`.
- **3.1:** `CLAUDE.md` also called the appliances "the third panel"; now the second. The README
  gives the history its own short paragraph.
- **3.5:** drafted and approved by the user: the help keeps 🔌 on its heading; Mode and Goal ⓘ
  no longer repeat the limit's reasoning (help only); the other five ⓘ unchanged. MeasureHelp:
  "The result usually shows right after Done." and "until you tap Done" (user).
- **3.4:** "One at a time" is covered by OK/WAIT, so it went too; "Recommended first" dropped
  the delay-start helper and is now two ideas.

## Pass 2026-10-03 (history screen)

Report (at `371ae77`), checked by reading the code: the full-screen history's tap mapping, DST
days, default day, last month and colours were right apart from the items below. The user asked
for everything to be fixed, and for 1.2 the cleanest option.

### Done (2026-10-03)

Verified with 74 tests (1 new), lint 0 (`--rerun`), no compiler warnings, two clean unsigned
release builds with the same sha256. Not seen on the phone yet.
- [x] 1.1 Taps outside the day bars' plot (the axis, the limit's label) did pick day 1 or the
  last day; now they do nothing, and a tap before the first draw too.
- [x] 1.2 The history mixed zones; now all its days and times are the region's, so a time
  never lands on another day than its bar. The one exception to "times in the phone's zone",
  in `CLAUDE.md`. (`timeFormat` has the phone's zone built in, so the history has its own
  `clockFormat`.)
- [x] 1.3 After the process is killed, "No quarter hours recorded this month." shows until the
  files load: accepted, noted in `CLAUDE.md`.
- [x] 2.1 `DayQuarters` in core (slots, `slot`, `hourSlot`, the day's quarters and highest)
  and `Quarter.day(zone)`, shared with `dailyHighest`; tested on 29 Mar and 25 Oct 2026.
- [x] 3.1 The day's highest comes from `DayQuarters`, once. 3.2 `Plot` holds the limit and
  draws it. 3.3 `highestText`. 3.4 `TitleRow` next to `Page`. 3.5 `comingTime(at, today =
  "at ")`. 3.6 The `Charts.kt` comment.
- [x] 4.1 to 4.8 The docs; 4.7 is in the checklist above. 5.1 `git gc`.

## Pass 2026-10-04

Report (at `01ebe3e`, v0.13.0; range `058980a..HEAD`, 19 commits, then the whole app): 103 tests
green, lint "No issues found" (`lintAnalyzeDebug --rerun`), the only Gradle deprecation is still
the plugin's `Configuration.setVisible`, no unused top-level symbols, workflows on the current
majors (`checkout@v7`, `setup-java@v6`). No bugs found in the range; the clutter is a few app
helpers that belong in core, leftovers from Draw ahead, and mostly the docs (`CLAUDE.md` is
288 lines but ~81 KB: 12 lines are over 1500 characters). Nothing below changes what the user
sees, so it can go into v0.14.0 rather than a v0.13.1 (the user decides).

v0.13.0 for F-Droid (step 4, done during the report, since the GitHub Release was up): the
release APK's signer is `0b07da4b…a07a113` = `AllowedAPKSigningKeys`; all 25 entries outside
`META-INF/` equal the unsigned build of the tag (`01ebe3e`, the tag on GitHub points there).
The recipe commit is prepared in `../fdroiddata` (`3ade4eee0` "Update GridLoad to 0.13.0",
versionCode 16, not pushed). The NonFreeNet text needs no change: it already names
Energy-Charts since the 0.12.0 commit `8a9053d72`, which is pushed and the MR's head.

### Design and bugs
- [x] 1.1 **Question:** release this as v0.13.1 or fold it into v0.14.0 with the price curve?
  Suggest v0.14.0: no user-visible change below.
- [x] 1.2 **Question:** the store text (`full_description.txt`) doesn't mention the timer or the
  saving (the README does). Add "with a timer for the later start and what waiting saves" to
  its Appliances line, or leave the store text as is?

### Code
- [x] 2.1 `UiState.yourPrice` and `UiState.saving` (`MainViewModel.kt:129-136`) hold the spot /
  add-on / VAT decision and the 0.005 threshold in the app, untested; `ownPrice`/`ownSaving`
  in core only do the arithmetic. Move the decision to core next to them (e.g.
  `yourPrice(price, region, addOn, vat): Double?` and `yourSaving(saving, region, addOn, vat):
  Double?`, null in a spot region without an add-on and for a saving under 0.005), with tests;
  `UiState` keeps one-line wrappers.
- [x] 2.2 `adviceLine(appliance, advice, Instant.now(), state.saving(advice))` is assembled in
  two places (`AppliancesPanel.kt:174`, `PeakWindow.kt:119`). One `UiState.adviceLine(name)`
  (or a top-level `adviceLine(state, appliance)`) that adds the saving; `ApplianceRow` then takes
  the line instead of `saves` (its 7 parameters are otherwise fine for a composable; no bundling).
- [x] 2.3 `timerAt` (`AppliancesPanel.kt` ~150) re-lists which advices carry a time. Put
  `val at: Instant?` on the `Advice` interface (`Ok` → null), so it's
  `advice?.at?.takeIf { appliance.delayMinutes == 0 }`.
- [x] 2.4 Leftover from `PeakNow.drawKw`: `derive()` still requires `s.meter.kw != null`
  (`MainViewModel.kt:262`); `read()` sets the projection to null whenever kw is null. Drop it:
  `projection?.let { PeakNow(it, s.peakLine) }`.
- [x] 2.5 `colourTop`'s KDoc (`Classify.kt:46`) says "nearest rank", but index ⌊0.9 n⌋ is one rank
  higher when n is a multiple of 10 (the test pins 91 of 1..100). It's the same definition as
  `research/energy_charts/score.py`, so the scores hold: fix the comment ("the value at index
  ⌊0.9 n⌋, as in score.py"), not the formula.
- [x] 2.6 `SetupGuide`: `REGIONS.first { it.id == chosenRegion }` three times
  (`SettingsScreen.kt:195, 197, 203`); one `val region`.
- [x] 2.7 `Currency.perHour` repeats `amount` (`Currency.kt:15`): `amount(cost, locale) + "/h"`.
- [x] 2.8 `SettingsScreen.kt:526` has a private `amount()` (a typed field's text) next to
  `Currency.amount` (money): rename it `typed()`.
- [x] 2.9 Stale comment: `PeakWindow`'s KDoc (`PeakWindow.kt:45-46`), "Without one (no goal, no
  appliance, nothing recorded yet)", misses the region's minimum.

### Migrations
- [x] 3.1 Confirmed: all three stay (v0.6.0 `peak.json`, v0.7.0 `mode`, v0.8.0 `quarters/`), since
  F-Droid hasn't shipped anything yet.

### Docs
- [x] 4.1 Stale F-Droid status: `CLAUDE.md` Current state says "the recipe at v0.10.0" and "once
  the tag is pushed, the recipe moves to v0.12.0"; the skill's F-Droid section and wave-1 step 7
  say the same. The recipe is at 0.12.0 (pushed) and, with `3ade4eee0`, 0.13.0 (to push).
- [x] 4.2 Stale `max`: `CLAUDE.md:268` ("step 6c … `max` unchanged"), the skill's step 6c ("The
  user kept `max`"), `research/feature_ideas.md:58-60` ("`max` kept … a percentile comes next").
  The 90th percentile shipped in v0.13.0.
- [x] 4.3 `CLAUDE.md` Classification (118-141): "Why the next 24 hours" (5 bullets of CKW
  scoring) and "On market prices" (one 2050-character paragraph, the decision at its end).
  Keep the rule and the decisions with a one-line reason each; move the numbers to a new
  `research/classification.md` next to `research/energy_charts/score.py`. About −20 lines,
  −4 KB.
- [x] 4.4 `CLAUDE.md` Decided → Regions (line 28, 2866 characters): drop the constructor
  signatures (`Country(…)`, `Region(…)`, `Currency` examples: the code) and keep the decisions
  (country first, saved ids, zones and the history's exception, no default region, the CKW
  fallback, a switch drops the cache, 429 accepted).
- [x] 4.5 `CLAUDE.md` Decided → Releases (line 27) and the skill's Releasing + F-Droid
  sections repeat each other. `CLAUDE.md` keeps the constraints (tag = `versionName` =
  `APP_VERSION`, changelog ≤ 500, reproducible, unsigned for F-Droid, `dependenciesInfo` off,
  the keystore and its backup, the secrets' names); the skill keeps the steps and the recipe.
- [x] 4.6 `CLAUDE.md` UI → Help (line 38) is an inventory of the help that has drifted (no Draw
  ahead line, no ⏱, no "red is what goes over the limit", no saving). Replace it with the rules
  (lines per country from `countryHelp`, `HelpTest`; one idea per line; the three sections; the
  limit's reasons only there; the fixed-price line for every country); `HelpContent` is the
  text. Same for the quoted summaries in the Settings bullet (line 39).
- [x] 4.7 `CLAUDE.md` Regions outside Switzerland: the step narrative in its intro → "Wave 1,
  released in v0.12.0"; "Next phases, in order" repeats the skill's "Later phases": keep the
  one-line order here, the details in the skill.
- [x] 4.8 `CLAUDE.md` Current state (line 280, 2332 characters): six releases with their
  contents (git and the changelogs keep them) → the latest release, F-Droid's status, next.
- [x] 4.9 **Question:** move `CLAUDE.md`'s "Hardware, CKW key and the REST API" (200-221) and
  the CKW price-shape observations under "Response schema" (the valley, the night, 9 Sep) to
  research (`research/whatwatt_api.md`, `research/classification.md`)? The whatwatt is frozen,
  so they're rarely needed. `CLAUDE.md` would keep what the code relies on: the `report` fields
  read, `date_time`'s fake `Z`, the meter's clock, 401 → Device Protection off, cleartext,
  `ACCESS_LOCAL_NETWORK`, the 5 s poll and 3 s timeouts. About −20 lines, −8 KB.
- [x] 4.10 The skill (502 lines): finished-phase narratives and phone-test logs (whatwatt phase
  4, Panels and history, Appliances, The limit, Settings rework, Countries, wave-1 steps 1-7,
  energy planning steps 1-2, Accurate preview). Keep per phase a status line, the open items
  and the reusable how-tos (a new region or country, the phone tips, editing prefs, demo
  mode); target ~300 lines.
- [x] 4.11 `research/feature_ideas.md`: the timer and saving sections are built (shrink each to a
  line pointing to `CLAUDE.md`, OK or WAIT); the Review's suggested order is half done;
  TalkBack's "contentDescription appears nowhere" (the timer icon has one).
- [x] 4.12 `research/neighbouring_countries.md`: the summary table's "CH (CKW and others)" bills a
  peak (only CKW, per `CLAUDE.md`); line 79 "min and max" → "min and the colour's top" (the
  affine argument holds for the percentile too).

### Repo, build and CI
- [x] 5.1 Changelogs 12, 13, 14, 15: F-Droid never built them and, with the recipe at 16, never
  will. Delete them once the recipe commit is pushed; keep 16.
- [x] 5.2 `git gc` (229 loose objects).
- [x] 5.3 Verify after the changes: tests, lint 0, no `w:`, `--warning-mode all`, two clean
  unsigned release builds with the same sha256, and the R8 build on the Fairphone 6 (main
  screen, a preview, Settings, History, the help) if 2.1-2.4 went in.

### Done (2026-10-04)

Worked through by a second session while the user was away, then finished with the user's
go-ahead ("proceed with everything"). Where it differed from the suggestions:
- **1.1:** v0.14.0, as suggested; no release now. **1.2:** the store text's Appliances line now
  ends "A waiting row sets a timer for the later start and says what waiting saves."
- **Code (2.1-2.9):** `yourPrice`/`yourSaving(…, region, addOn, vat)` in `Currency.kt`, tested
  in `yourPriceNeedsTheAddOnOnlyInSpotRegions` and `yourSavingHidesWhatWouldShowAsZero` (105
  tests); `UiState` keeps one-line wrappers. `adviceLine` is a `UiState` extension that adds the
  saving itself, so `UiState.saving` is gone and `ApplianceRow` takes the line. `timerAt` is
  inline at the call site (`advice?.at`). `colourTop`'s comment points to
  `research/classification.md`.
- **Docs:** new `research/classification.md` (the scoring and CKW's price shape from
  `CLAUDE.md`) and `research/whatwatt_api.md` (4.9: the hardware and REST API section verbatim;
  `CLAUDE.md` keeps a short "what the code relies on" section). `CLAUDE.md` 288 → 273 lines,
  81 → 70 KB; the skill 502 → 279 lines.
- **5.2:** `git gc` (229 loose objects → 0).
- **5.3:** `:core:test :app:lintDebug :app:lintAnalyzeDebug --rerun :app:assembleDebug
  :app:assembleRelease --warning-mode all`: green, lint "No issues found", no `w:` lines, the
  only deprecation the plugin's `Configuration.setVisible`. Two clean unsigned
  `assembleRelease` builds: both `f5e8e8d01b5ecfb067af5afa57dfdd6624733c2d44f43adf540fa6a1c06edbfe`.
  On the Fairphone 6 (12:50-12:58), R8 build signed with the debug key: main screen, the
  Dishwasher preview ("OK to start now."), Settings, History and the help, no crash; the
  user's region is Germany, where Energy-Charts answered 503 (to `curl` too), so the test
  switched to CKW. The release build isn't debuggable (`run-as` fails), so staging needs the
  debug build: with it, a `prices.json` with 12:45-13:45 at 0.40 gave "Cheaper at 13:45 · saves
  0.15 CHF" with the timer button, in the row and the preview. The backup (app stopped,
  `run-as … tar`) was restored byte-identical (contents and modes; the release build's extra
  `profileinstaller_…` file removed); the phone runs the debug build of this tree.
- **5.1:** changelogs 12-15 deleted once the user had pushed the recipe commit `3ade4eee0`.

## Pass 2026-10-04 (after the price curve and the Limit setting)

Report (at `46c35c1`; range `76c03d9..46c35c1`: the ¼-hour rename and Share, the household
alerts research, the price curve, the Limit setting): 112 tests green, lint "No issues found", no
`w:` lines, no unused imports, two clean unsigned release builds identical (`6ab39484…`),
workflows on `checkout@v7` / `setup-java@v6`. Worked through on top of `6ef4220` ("new peak"
only above the month's highest, from another session), whose strings (the warning, the WAIT
rows, the Share text) this pass left alone.

### Bug
- [x] 1. The history chart vanished with no limit: `HistoryPanel` drew `DayBars` only when
  `line != null`, and its comment said the limit is never null while there's a highest, which the
  Limit setting made false (Month's highest off and the goal empty, or only the floor on and the
  appliances hidden). The chart is also the tap target for `HistoryScreen`, so that was
  unreachable too. Now drawn with a null line, the scale `maxOf(line ?: 0.0, highest.kw)`, the
  comment corrected. `HistoryScreen` and `QuarterBars` already handled a null line.

### Deprecation
- [x] 2. `Configuration.setVisible(boolean)` (removal in Gradle 11) comes from AGP itself:
  `-Dorg.gradle.deprecation.trace=true` shows `BasePlugin.createAndroidJdkImageConfiguration`
  and `SourceSetManager.createConfiguration` (`com.android.build.gradle.internal`). AGP 9.4.1 is
  the newest stable on Google's Maven; only 9.5.0 alphas exist. No fixed version to propose:
  check again when 9.5.0 is stable.

### Docs
- [x] 3. Share was undocumented: a line in `CLAUDE.md`'s peak window bullet (and "the warning
  (with Share)" collapsed), the skill's Household alerts entry marked built, and
  `research/household_alerts.md`'s status line and section (a) (a button right of the warning,
  not a link below it).
- [x] 4. `CLAUDE.md` quoted UI text from before the ¼-hour rename (the countdown switch twice,
  "First ¼ hour at …" twice, "Estimated: … this ¼ hour.", "No ¼ hours recorded (yet)", "hasn't
  saved a ¼ hour", "Then n more ¼ hours"): matched to the code. The rule is in "UI text": the UI
  says "¼ hour", prose may keep "quarter hour". The skill and research had no stale quotes
  besides `household_alerts.md` (item 3).
- [x] 5. F-Droid status: the MR's head is `3ade4eee0` (0.13.0, pushed, pipeline green, checked
  through GitLab's API); `CLAUDE.md` Current state and the skill's F-Droid section updated.
  Current state now says the price curve, the Limit setting and Share are built, not released,
  and what's next.
- [x] 6. README: the price curve (the first panel, its switch), the limit's default and its
  switches (Settings → Mode → Limit), Share; the panels renumbered (the curve first, four in
  all). Store description: one sentence for the curve, the limit's default and switches, Share
  (3019 → 3202 characters).

### Small
- [x] 7. "Green until tomorrow 00:00" when the green run reaches the last known slot, before
  tomorrow's prices are out (the green may go on); the same after tomorrow is out when the run
  reaches the 24-hour window's end. The user chose "Green for now": `greenUntil` is null when the
  run reaches the window's last slot, and the header says "Green for now" while green. Test:
  `greenToTheWindowsEndHasNoUntil` (116 tests). Not seen on the phone (it needs a green
  evening before tomorrow's prices, or prices staged to end in a green run).
- [x] 8. `MainActivity`'s `PricePanel` call uses `UiState.showCurve` instead of repeating it.
- [x] 9. `LimitGroup` computed `peakFloor` a second time: `UiState.floor` now, used by `limit`
  and the Biggest appliance switch's value (`SettingsScreen` no longer imports `peakFloor`).
- [x] 10. `git gc` (243 loose objects, 1.31 MiB → 0).

### No change
- The Limit group's lock can't stop every way to no limit: a region change or hiding the
  appliances can leave only hidden parts on. The result line says why; `CLAUDE.md` records it
  as the user's "no fallback" decision.

### Done (2026-10-04)
- Verified: `--warning-mode all :core:test :app:lintDebug :app:lintAnalyzeDebug --rerun
  :app:assembleDebug :app:assembleRelease` green, 116 tests, lint "No issues found", the only
  deprecation AGP's `setVisible`; `:core:compileKotlin --rerun :app:compileDebugKotlin --rerun`
  without `w:` lines; two clean unsigned `assembleRelease` builds both
  `20ea98fdd5aaecae922349ce1d8b587170bb9c27b72c263b983d87a8e36905be` (with item 7).
- On the Fairphone 6 (16:23-16:25, debug build of this tree, CKW), staged in the prefs with
  `limit_highest` and `limit_floor` off: the history panel shows the day bars without a line,
  and tapping it opens the History screen (day and quarter bars, no line). Then a goal of
  0.05 kW (below the 0.1 kW draw): "This ¼ hour reaches the limit." with Share beside it, open
  and collapsed; Share opens the share sheet with the text (closed without sending); the rows say
  "Reaches the limit right now". The app's data (backed up with `run-as … tar`, app stopped) was
  restored byte-identical, contents and modes, no extra files.

## Pass 2026-10-04 (after v0.14.0)

Report (at `758b0f3`, the v0.14.0 tag; range `ae4c5fe..758b0f3`: the vibration fix and the base
load line): 118 tests green, lint "No issues found", no `w:` lines, no unused declarations, the
only deprecation AGP's `setVisible` (AGP 9.5.0 still alpha08), workflows on `checkout@v7` /
`setup-java@v6`. The code was lean; the clutter was in the docs. The user approved all items.

### Code
- [x] C1. `BASE_LOAD_INFO` said "last 7 days" as a literal while `BaseLoadLine` uses
  `$BASE_LOAD_DAYS`: both use the constant now (same text).

### Docs
- [x] D1. `research/feature_ideas.md`: the built price curve, base load and "where the limit
  comes from" sections shrunk to a line each pointing to `CLAUDE.md`; the Review's order marked
  built (−69 lines).
- [x] D2. The skill's Energy planning and Household alerts (374 → 316 lines): the
  phone and release-test logs of steps 3 and 4, Vibrate at the limit, the base load and Share
  condensed to status, open items and the how-tos (staging, `dumpsys vibrator_manager`, ringer
  mode over adb).
- [x] D3. `CLAUDE.md` Current state: the F-Droid recipe's move to 0.14.0 prepared.
- [x] D4. The base load was in changelog 17 but not in the README (History) or the store
  description (3202 → 3289 characters).

### Repo
- [x] R1. Changelog 16 deleted: with the recipe moving to 17, F-Droid never builds 16 (the
  v0.13.0 tag keeps its copy). Assumes the 0.14.0 recipe commit is pushed.
- [x] R2. `git gc` (141 loose objects, 808 KiB → 0).

### No change
- The base load's defaults (2 and 5) appear in `UiState` and the prefs read, as for every pref.
- `hourText`'s `"%02d:00"` beside the `HH:mm` formatters: the same idiom as the hour labels.

### Done (2026-10-04)
- F-Droid: the GitHub APK of v0.14.0 equals an unsigned build of the tag outside `META-INF/`
  (25 entries); the recipe's move to 0.14.0 prepared in `../fdroiddata` (see the skill).
- Verified: `:core:test :app:lintDebug :app:assembleDebug :app:assembleRelease` green, 118
  tests, lint "No issues found". The unsigned release APK went from `ec54b697…` to
  `4efe5266…` with C1: `dexdump` of both is identical apart from R8's `r8-map-id` (the added
  import moves source lines), which also changes `baseline.prof`; no code change. Two clean
  `assembleRelease` builds both `4efe5266423f980744300d39ba78b821b2b6e47fbd812879b151dce25b71a2c3`.
- New store screenshots (v0.14.0, on the Fairphone 6, 20:45): the price curve, the base load and
  the Limit group show; the old ones were from v0.11.1.
