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
- [ ] 2.1 `Scale` (`PeakWindow.kt`) and `DayBars` (`HistoryPanel.kt`) repeat the y axis (maxKw
  and step, ticks, unit label, axis lines) and the "0.9 highest" / "goal" labels, ~25 lines.
  Extract a shared `drawAxis` and `lineLabel` while the history chart is reworked; the shared
  panel pieces (`Panel`, colours, `tickLabel`, `drawLabels`) could then move from
  `PeakWindow.kt` to a small `Charts.kt`.
- [ ] 2.2 Store screenshots show the Refresh button and the old peak window: retake before the
  next release.
- Accepted: `peak_scale_without_reading` stays in existing prefs, unread (removing it would need
  migration code for nothing).
