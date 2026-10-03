# Appliances panel (design draft, 2026-10-03)

The appliance list planned under "Later" in `CLAUDE.md` (UI), designed with the user on
2026-10-03. It reverses the v0.6 decision "no appliance list". That decision was against
*typed-in* appliances whose values were guesses. This list holds **measured** appliances: the
whatwatt measures each one once, and the app then says per appliance whether it's a good moment to
switch it on. The decisions so far are marked "(user, date)". Everything else is a proposal, listed
under [Open questions](#open-questions); all were settled with the user on 2026-10-03.

It replaces two ideas in [feature_ideas.md](feature_ideas.md): the delay-start helper (here it's
per appliance, with the measured load curve instead of a typed run length) and "Measure an
appliance" (here it's how an appliance gets its values).

## What the user asked for (user, 2026-10-03)

- **A panel on the main screen** with a list of appliances and a **+** to add one.
- **Adding one:** a name, the power (not typed in: measured by a controlled start, as the
  difference in the draw before and after), and the run time. The user isn't happy with guessing
  the run time (a dishwasher draws most of its power in its first ~30 minutes), so **measure the
  run time and the load curve too**, assuming nothing else is switched on during the measurement.
  More fields if they're relevant (proposed below).
- **Each row:** the name and a status, a green **OK** when it's fine to switch it on now or a red
  **WAIT**. This is for planning.
- **Recommendations from the spot price** for later in the day ("best at 11:00").

## Decided (user, 2026-10-03)

- **A. The recorder script stays exactly as it is (v2).** No v3, no minute log, no other change
  to `gridload_recorder.be`, and nothing new sent to the whatwatt. It was tested many times and
  is at the limit of what Berry and the meter's power supply handle stably, and the billed peak
  depends on it. (Also noted in `CLAUDE.md` under "The recorder (phase 4)".) The minute log is
  [rejected](#rejected-a-minute-log-in-the-recorder).
- **B. Measuring uses the recorder's quarter hours as they are, plus the jump in the live draw.**
  The app is needed only at the start and again after the run, not during it. It needs peak load
  on with the recorder recording, which the panel needs anyway. 15-minute resolution is enough:
  the billed peak is a 15-minute average, and prices come in 15-minute or hourly slots. Details in
  [Measuring a run](#measuring-a-run).
- **C. A measurement setup help** opens when you tap **+**: by itself the first time, afterwards
  from a ? or ⓘ. Draft in [The setup help](#the-setup-help).

## The appliance

| Field | How it's set | Why |
|---|---|---|
| Name | typed | the row label |
| Load curve | **measured** (see [Measuring a run](#measuring-a-run)): the extra draw over the run, as pieces of constant kW, with the household's base draw subtracted | gives the power, the run time, the energy and where in the run the energy falls, so nothing is guessed |
| Can wait | switch, default on | dishwasher, washing machine, dryer: yes, the price counts. Kettle, oven, hob: no, you use them when you need them, so only the peak counts |
| Start delay | none · 30 min · 1 h, default none | many dishwashers and washing machines have "start in X h". With it set, the advice is the number to set on the appliance ("Delay 3 h") |

Derived from the curve, shown in the edit sheet and not stored separately: the run time, the
energy (kWh) and the power. Name, Can wait and Start delay are the only fields (user, 2026-10-03).

Considered and left out, for now:
- **Programmes:** an eco and an intensive programme have different curves. Add them as two
  appliances ("Dishwasher eco", "Dishwasher 65°"). There's no programme field.
- **Variable run time** (oven, hob, kettle: depends on what you cook): measure a typical use
  ("Oven 30 min"), or add two. There's no run-time slider.
- **Emoji per appliance:** later, not in the first version (user, 2026-10-03). A name can hold
  an emoji typed from the keyboard anyway.
- **Typed watts:** no (user, 2026-10-03). Only measured appliances; a typed value is the guess
  this design avoids. Instead, measured appliances can be exported and imported, see below.

Storage: `files/appliances.json` (a small list; `CLAUDE.md`: small files, no database). The curve
is a list of pieces, back to back from the start (`min` minutes at `kw` extra), so a 3-hour run is
about 13 pieces. The name is the key (unique; import replaces by name):

```json
[{ "name": "Dishwasher eco", "canWait": true, "delayMinutes": 60,
   "curve": [{ "min": 8, "kw": 1.95 }, { "min": 15, "kw": 1.2 }, { "min": 15, "kw": 0.08 },
             { "min": 4.5, "kw": 2.0 }] }]
```

Prefs: `appliances_open` (the panel, like `peak_open`), `appliance_help_seen` (the setup help
was shown once), and a running measurement (`measuring`: the appliance, the start time, the live
draw before it, for the jump shown while it runs, the base draw, and Done's time). Cloud backup is already off for everything.

**Export and import** (user, 2026-10-03): measuring takes time, so the measured appliances must
survive a reinstall, and be easy to move to another phone (e.g. a family member's). Settings → a
row **Appliances** with **Export** and **Import**. Export saves `appliances.json` where the user
picks (Downloads, a cloud folder, or sent on) through Android's file picker (Storage Access
Framework: `ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`), so no permission and no cloud in
the app. The file is the same JSON as `files/appliances.json`. Import adds the file's appliances;
one with the same name as an existing one replaces it. A file that isn't readable changes
nothing and says so.

## Measuring a run

From the recorder's quarter hours, which the app already copies (`RecorderFiles`), plus the live
draw it reads every 5 s while open (user, 2026-10-03, decision B):

- **The jump:** while the app is open, the measuring row shows the live draw minus the reading
  just before the start ("+2.1 kW now"), so the user sees the appliance was caught. It isn't
  part of the result (see the run's end).
- **The base draw** (user, 2026-10-03: both): the jump uses the live reading just before the
  start (an instant against an instant). The base subtracted from the quarters is an average,
  since it's subtracted from averages; a fridge on or off at the moment of the live reading would
  put it ±0.1 kW off, i.e. ±0.3 kWh over 3 h. It's the average in the start's own quarter hour
  before Start (`Projection.baseKw`, exact from the register, saved at Start as `baseKw`) when
  Start is at least 5 minutes into the quarter; else the recorder's quarter hour before; else
  the live reading. (Changed 2026-10-03: the kettle test started at 13:11, after a 12:45 quarter
  at 0.58 kW from lunch, while the house drew 0.29 kW from 13:00 to the start. The quarter before
  would have eaten most of the kettle's 0.1 kWh.)
- **The energy per quarter:** each recorded quarter during the run minus the base draw
  (`base kW × 0.25 h`); below 0 counts as 0.
- **Spreading within quarters:** each quarter's extra energy is spread evenly over the part of
  the quarter between Start and Done: from the start in the first quarter, up to Done in Done's
  quarter, the whole quarter in between. A run started at :07 then lines up correctly when it's
  projected onto other start times. A kettle from :07, Done at :10 when it clicks off, is
  3 minutes at 2.2 kW; Done tapped later spreads the same energy further, which leaves each
  quarter's energy (what the peak is billed on) unchanged. The power shown is the highest piece's
  kW.
- **Not the jump at the end** (changed 2026-10-03 after the first real measurement, cooking with
  a rice cooker, two plates and the vent): the first cut ran the last quarter at the highest jump
  of the whole run from the quarter's start. With several appliances that jump is all of them at
  once, so the cooking's last quarter (21 Wh, the big plate on a low setting) became 15 seconds
  at 5 kW: "28 min · 5.0 kW" for a 33-minute run that never averaged more than 2.3 kW in a
  quarter. Spreading up to Done gives "34 min · 0.89 kWh · 2.3 kW".
- **Quiet quarters at the end** (extra under 0.01 kWh, i.e. a 40 W average: a fridge cycle is
  about 0.008) are trimmed.
- **The end** (user, 2026-10-03): the user taps **Done** once the appliance has finished (it
  beeps). The run covers the quarters from the start's to Done's, with the quiet ones at the end
  trimmed. Not detected: eco dishwasher programmes sit at the base draw for long stretches
  mid-run, and detection would cut the run there.
- **Result:** ready once the recorder has saved every quarter from the start's to Done's, up to
  15 minutes after Done. A quarter missing in between (a gap) means measure again.

Noise: a fridge cycling during the run adds ~0.1 kW for a few minutes, i.e. a few Wh per quarter.
The heat pump and boiler are on the other meter, so they don't interfere.

### The flow

1. **+** → the setup help (by itself the first time) → a sheet with Name, Can wait and Start
   delay, and **Measure**.
2. Measure: "Tap Start, then switch the appliance on." The app saves the start time and the live
   draw just before it (`measuring`). While the app is open it shows the jump live ("+2.1 kW now").
3. You can leave the app once the jump shows. On return it still shows the running measurement.
4. **End:** tap **Done** when the appliance has finished. Until Done's quarter is saved, the
   app says when the result shows ("Result at 21:30").
5. The app builds the curve from the copied quarters, as above, and shows the run time, the kWh,
   the power and the curve. **Save** stores it, **Discard** drops it (built so on 2026-10-03:
   "Measure again" would restart at once, while the appliance isn't about to start; + or the
   edit sheet's Measure again start a new one).
6. Re-measuring an appliance later replaces its curve.

### The setup help

Shown when you tap **+**, by itself the first time; afterwards a ? (or ⓘ, like Settings) reopens
it (user, 2026-10-03). Points the user listed: the recorder recording (no red line); the phone on
the home network with GridLoad open at the switch-on until the jump shows (about a minute);
nothing else switched on or off during the run (no kettle, oven, hob, washing machine or dryer;
lights and small devices are fine; a fridge cycling adds a little); a quiet time, not while
cooking; the usual programme, to its end (another programme is another appliance); open GridLoad
again after the run, the result is ready once the recorder has saved the quarter hour in which the
run ended, up to 15 minutes later; only appliances on the whatwatt's meter count (a heat pump on a
second meter doesn't show).

The text (user, 2026-10-03: as drafted), a dialog opened by **+**, by itself the first time
(`appliance_help_seen`); afterwards an ⓘ next to the add sheet's title reopens it:

> **Measuring an appliance**
>
> No red line: the recorder must be recording.
> Be at home, with GridLoad open, when you switch it on.
> Keep it open until the jump shows, about a minute.
> Switch nothing else on or off until the result shows.
> Lights and small devices are fine.
> Start at a quiet time, not while cooking.
> Run the programme you always use, to its end.
> Another programme is another appliance.
> Tap Done as soon as it has finished.
> The result shows up to 15 minutes later.
> Only appliances on the whatwatt's meter count.

### Rejected: a minute log in the recorder

(user, 2026-10-03, decision A) The draft proposed recorder script v3: the last 24 hours at minute
resolution in a ring of 24 hour files (`/sdcard/GMhh.CSV`), so a measurement would need the app
only at the start and the end and short runs would keep their shape. Rejected: the recorder stays
at v2. A write every minute draws more from the meter's weak supply, a whatwatt restart loses
quarters, every install would update itself at once, and the billed peak depends on the recorder.
The quarters plus the live jump give the same "app only at start and end", with 15-minute
resolution, which matches the billed peak.

### Rejected: measure in the app only

No script change, but the app records its 5 s readings only while it's on screen, so the app must
stay open for the whole run: fine for a kettle, clumsy for a dishwasher.

## OK or WAIT

Recomputed with each reading (5 s) and each minute, for each appliance, for **starting it now**.

### Peak: would it set a new monthly peak?

For every quarter hour the run touches, starting now:

- **Household:** for this quarter, the projection the peak window already shows
  (`QuarterProjector`); for later quarters, the draw now (user, 2026-10-03: the house keeps
  drawing what it draws, and this agrees with the bar).
- **Appliance:** its curve's energy falling in that quarter, as a quarter's average kW
  (energy × 4). This is what `CLAUDE.md` describes: a short run adds only
  power × minutes / 15 to a quarter, while a long one adds its full power to every quarter it
  covers.
- **The run fits** if no quarter reaches 90% of the line (`WARN_SHARE`, `peakLine`). This is the
  point where the peak window's bar would turn red, so the appliance list and the bar always
  agree.
- No line (nothing recorded, no goal): the peak never blocks.
- Starting at the next quarter hour often fits when now doesn't, since a kettle in a fresh
  quarter adds only 2.2 kW × 3/15.

### Price: is a later start clearly cheaper? (only when Can wait is on)

- **The run's price** for a start time: the energy-weighted average over its curve,
  Σ kWh(slot) · price(slot) / Σ kWh. So the dishwasher's heavy first 30 minutes count most, not
  its quiet drying phase.
- **Candidates:** starting now, then every quarter hour boundary (:00, :15, :30, :45), or with a
  start delay now + 1, 2, … delay steps, as long as the whole run ends within the known prices and
  starts within the 24-hour window.
- **The rule** (user, 2026-10-03): the same thirds as the main colour (`classify`), applied to
  the run prices of all candidates: OK if starting now is in the cheapest third of their range. If
  all candidates cost the same, OK. So orange means WAIT for an appliance that can wait.
- **Prices not out yet** (user, 2026-10-03): only candidates that end within the known prices
  compete. If even starting now doesn't fit (a 3 h run at 22:00 before tomorrow's prices are out;
  rare, since every utility publishes by 18:00), the price isn't judged: the row shows the peak
  result with "Prices after 24:00 not out yet".

### The row

- **OK** (green) when the peak fits and, if it can wait, the price is OK.
- **WAIT** (red) otherwise, with when: the first candidate where both pass.
  - Peak only: "WAIT · new peak · at 14:15" (the next quarter where it fits).
  - Price: "WAIT · cheaper at 11:00" or, with a start delay, "WAIT · Delay 3 h".
  - Nothing fits in the window: "WAIT · new peak in every quarter" (rare; the line can't grow
    that much in a day).
- **Without a reading** (whatwatt not reachable): no status, "–" (user, 2026-10-03). The peak
  needs the live draw, and away from home the appliance can't be switched on anyway.
- **Measurement running:** that row shows "Measuring…" instead.

This is a what-if per row, not tracking: the app doesn't know which appliances are running. If you
start the dishwasher, the draw goes up, and the other rows react through the projection.

## The panel

- A third panel, between the peak window and the history (user, 2026-10-03), built on `Panel` (`Charts.kt`),
  collapsible like the others (`appliances_open`).
- **Header** (user, 2026-10-03): "🔌 Appliances", collapsed with a summary ("2 OK · 1 wait").
- **Rows:** name · status chip · the "when" line under WAIT. Tap a row: the edit sheet (rename,
  Can wait, Start delay, Measure again, the curve, Delete with a confirmation).
- **+** at the end of the list. Empty list: one line, "Add an appliance to see when it fits."
- **Needs peak load on** (the whatwatt and the recorder), like the other panels.
- **Help:** a short section; each concept explained once (UI text rules in `CLAUDE.md`). It
  includes one line on the main colour and a row disagreeing (user, 2026-10-03): "A row looks at
  the whole run, so it can differ from the colour." (E.g. "Good time" on screen, and the
  dishwasher "WAIT · cheaper at 13:00" because its 3 h run reaches into dearer hours.)

## Build order

1. **`:core`, no phone, no device** (done 2026-10-03, `Appliances.kt`, `AppliancesTest.kt`): the `Appliance` model and its JSON; the curve from the live
   jump and the recorder's quarters (base draw, subtraction, spreading within quarters, run time
   for short runs, trimming); the per-quarter peak fit; the run price and candidate starts; the
   OK/WAIT status with its "when". All pure functions with unit tests, in a new `Appliances.kt`.
   It stays unused until step 2, so both ship in the same release ("no dead code").
2. **`:app`:** the panel, the add/edit sheet, the setup help, the measuring flow,
   `appliances.json`, export and import in Settings, the help and ⓘ texts, README and store text. Test on the phone (a kettle,
   then a real dishwasher run) and in an R8 release build.
3. Move the decided design from this file into `CLAUDE.md` and the roadmap into the skill, as for
   the earlier phases.

## Open questions

All settled with the user on 2026-10-03; the answers are in the sections above.

1. Measuring: the recorder's quarters plus the live jump (decision B).
2. Fields: Can wait and Start delay, nothing more.
3. Price rule: OK only in the cheapest third of the run prices.
4. Household draw for later quarters: the draw now.
5. Without a reading: "–".
6. Panel: "🔌 Appliances", between the peak window and the history, collapsed "2 OK · 1 wait".
7. Typed watts: no; export and import of measured appliances instead (in Settings, as a file).
8. Per-appliance emoji: later.
9. Base draw: both (the live reading for the jump, the quarter before for the subtraction).
10. End: tap Done; the quiet tail is trimmed.
11. Prices not out yet: the peak result only, with a note.
12. Colour and row disagreeing: one line in the help.
13. Setup help: as drafted, on +, by itself the first time, then from an ⓘ
    (`appliance_help_seen`).

New, open (2026-10-03, after the first cut):

14. **Kettle variants** (settled with the user, 2026-10-03): measure the usual amount once
    (1 L to 100 °C). A measured appliance with Can wait off has **Other amount of water** in its
    edit sheet: Measured with (L, to 100 °C, default 1), Water (L), Temperature (100 · 90 · 80 ·
    70 °C). It adds an ordinary appliance ("Kettle 1.5 L" from "Kettle 1 L"; rename it in its edit sheet)
    whose curve is the measured one with each piece's minutes × `waterShare` = litres / measured
    litres × (T − 15 °C) / 85, at the same kW: heating water takes energy in proportion to the
    amount and the temperature rise, and the element's power is fixed. Tap water is taken as
    15 °C; the kettle's own heat-up is ignored, so very small amounts come out a little short.
    Nothing new is stored, and export and import carry the variants like any appliance.

15. **Start times** (user, 2026-10-03): candidates stay **now, then :00, :15, :30, :45**; no
    minute-level starts. Cleaner code and plainer advice.
16. **Variants instead of categories** (Claude's proposal 2026-10-03, the user's goal: scalable,
    not hardcoded, robust; build next): the user listed fixed appliances, linear dynamic ones
    (kettle), non-linear dynamic ones, composite actions (cooking) and dynamic actions. The
    engine only needs a curve per row, so they differ only in how the curve is made:
    - Fixed (dishwasher programme) and composite actions (cooking): measured once, as a whole.
    - Dynamic, linear or not (kettle, oven 30 vs 60 min, a longer simmer, a bigger meal): a
      **variant** of a measured appliance with another **run time**: the curve's start stays
      as measured, its **last phase** is stretched or cut. For an oven the preheat stays and the
      cycling grows; a kettle is one phase at fixed power, so this is the linear rule.
    - UI: the edit sheet of every measured appliance gets **Add a variant** (replacing "Other
      amount of water"): Run time (min), plus an optional helper "From water" (litres,
      temperature, `waterShare`) that fills the run time in. No category field, nothing tied to
      names.
    - Variants are saved as plain curves (baked), not links: renaming or deleting the original
      can't break them, export and import stay as they are; re-measuring the original means
      adding its variants again (rare).
    - Core: one function, a curve with a new total run time (extend the last piece, or cut
      pieces from the end), replacing `Appliance.scaled`. `advise` and the peak fit don't change.
    - Left out until needed: power levels (hob 4.5/9: measure the action as cooked) and
      combining appliances ("Kettle + Toaster").

The household's dishwasher (user, 2026-10-03): only the short hot programme is used, about
1.5 h, up to 65 °C. So one appliance, e.g. "Dishwasher 65°".
