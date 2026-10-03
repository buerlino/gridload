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
- **Build/CI:** `./gradlew :core:test :app:lintDebug` (warnings), `--warning-mode all`
  (deprecations), action versions in `.github/workflows/`, redundant `gradle.properties`.

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
