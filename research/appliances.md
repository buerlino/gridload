# Appliances: the reasoning (2026-10-03)

The decided design is in `CLAUDE.md` under "Appliances", which wins where the two differ, and
the code is in `Appliances.kt` and `AppliancesPanel.kt`. This file keeps why it is so: the
measured numbers behind the choices, and the rejected options.

The list reverses v0.6's "no appliance list". That decision was against *typed-in* appliances
whose values were guesses (a dishwasher draws most of its power in its first ~30 minutes, so a
typed power and run time say little). Here the whatwatt measures each appliance once, curve and
all, and nothing is typed in (user, 2026-10-03).

## Measuring a run

From the recorder's quarter hours as they are, plus the live draw read every 5 s while the app
is open (user, 2026-10-03). The app is needed only at the start and after the run. 15-minute
resolution is enough: the billed peak is a 15-minute average, and prices come in 15-minute or
hourly slots.

- **The jump** (the live draw minus the reading just before Start) only shows that the appliance
  was caught: an instant against an instant. It isn't part of the result.
- **The base draw** subtracted from the quarters is an average, since it's subtracted from
  averages: a fridge on or off at the moment of a live reading would put it ±0.1 kW off, i.e.
  ±0.3 kWh over 3 h. It's the start's own quarter before Start when Start is 5+ minutes into it.
  Why not the quarter before: the kettle test started at 13:11, after a 12:45 quarter at 0.58 kW
  from lunch, while the house drew 0.29 kW from 13:00 to the start. The quarter before would have
  eaten most of the kettle's 0.1 kWh.
- **Spread within each quarter, between Start and Done:** a run started at :07 then lines up when
  it's projected onto other start times. A kettle from :07 with Done at :10 is 3 minutes at
  2.2 kW; Done tapped later spreads the same energy further, which leaves each quarter's energy
  (what the peak is billed on) unchanged.
- **Not the jump at the end:** the first cut ran the last quarter at the run's highest jump from
  the quarter's start. With several appliances that jump is all of them at once, so cooking (rice
  cooker, two plates, the vent) had its last quarter, 21 Wh from the big plate on a low setting,
  as 15 seconds at 5 kW: "28 min · 5.0 kW" for a 33-minute run that never averaged more than
  2.3 kW in a quarter. Spreading up to Done gives "34 min · 0.89 kWh · 2.3 kW".
- **Done's quarter from the energy at Done** (declutter pass 2026-10-03): with the recorder's
  whole quarter, everything the house drew after Done landed in the run's last piece. Done 20 s
  after 10:15 with 0.02 kWh of fridge later in that quarter gave 20 s at 4.5 kW, and a variant
  stretched it. The register at Done is exact, like the base at Start, and the result no longer
  waits for Done's quarter.
- **Quiet quarters at the end** are trimmed below 0.01 kWh extra, a 40 W average: a fridge cycle
  is about 0.008 kWh. During the run a fridge adds a few Wh per quarter; the heat pump and boiler
  are on the other meter.
- **The end is tapped, not detected:** eco dishwasher programmes sit at the base draw for long
  stretches mid-run, and detection would cut the run there.

### Rejected: a minute log in the recorder

The draft proposed recorder script v3: the last 24 hours at minute resolution in a ring of 24
hour files (`/sdcard/GMhh.CSV`), so short runs would keep their shape. Rejected (user,
2026-10-03): the recorder stays at v2. A write every minute draws more from the meter's weak
supply, a whatwatt restart loses quarters, every install would update itself at once, and the
billed peak depends on the recorder. The quarters plus the live jump give the same "app only at
start and end", at the billed peak's resolution.

### Rejected: measuring in the app only

No script change, but the app reads only while it's on screen, so it would have to stay open for
the whole run: fine for a kettle, clumsy for a dishwasher.

## OK or WAIT

- **The household in later quarters is the draw now** (user, 2026-10-03): the house keeps drawing
  what it draws, and this agrees with the peak window's bar. This quarter uses the bar's own
  projection, so the row and the bar always agree (both at the limit; at 90% of the line until
  the floor, below).
- **A short run adds little to a quarter:** power × minutes / 15. So starting at the next quarter
  hour often fits when now doesn't: a kettle in a fresh quarter adds 2.2 kW × 3/15. A long run
  adds its full power to every quarter it covers. Seen on the phone: a kettle at 14:15 drawing
  2.1 kW made every row "Sets a new peak at any start", since later quarters assume the draw now. That text claimed more than the app knows (a running appliance ends at some point), so it now says "Sets a new peak right now".
- **The run price is weighted by energy,** so a dishwasher's heavy first 30 minutes count most,
  not its quiet drying phase.
- **The rule is the main colour's thirds** over the candidates' run prices (user, 2026-10-03), so
  orange means WAIT for an appliance that can wait. Candidates are quarter-hour boundaries, not
  minutes (user: cleaner code, plainer advice).
- **Prices not out yet** is rare: a 3 h run at 22:00 before tomorrow's prices; every utility
  publishes by 18:00.
- **Without a reading, "–"** (user): the peak needs the live draw, and away from home the
  appliance can't be switched on anyway.
- **A what-if per row, not tracking:** the app doesn't know what runs. Starting the dishwasher
  raises the draw, and the other rows react through the projection. A row can disagree with the
  main colour ("Good time" on screen, the dishwasher WAIT because its 3 h run reaches into dearer
  hours); the help says so in one line.

## The floor (2026-10-03)

The problem, seen on the phone: early in October the month's highest was 2.1 kW, set by
"Cooking" itself. With the line at 2.1, 90% of it at 1.9 and the house at 0.2–0.4 kW, Cooking's
heaviest quarter (2.21 kW on its own) reached the warning at every start, so the appliance that
had set the peak was told to wait for it, all month. The app felt broken.

The user's idea: peaks happen, but stacking them should not; the biggest appliance sets the
peak to stay under. On a linear tariff (CKW Home dynamic, 1.08 CHF/kW a month incl. VAT) only
the month's final highest quarter is billed, so a peak the biggest appliance sets anyway costs
nothing extra, and a stack on top of it does.

Decided with the user:
- **One line, the limit** = max(goal when on, floor, month's highest). The user didn't want
  several lines on the columns ("it will only confuse"); the goal stays as an override that can
  only raise it.
- **The floor** = the biggest appliance's heaviest quarter hour on its own × 1.2, over the
  appliances with "Counts for the limit" on (a switch per appliance, so a rarely used one can't
  raise it). Deterministic, from the measured curves only. The heaviest quarter is the most the
  curve draws in any 15 minutes (the worst start within a quarter), found exactly at the piece
  boundaries rather than by trying start minutes. Cooking: (12.62 × 2.335 + 2.38 × 1.529) / 15 =
  2.21 kW → 2.65.
- **No 90% margin:** with both, Cooking alone (2.21 + the house) would come within 8% of the
  floor and still wait. The 20% is the margin now, red is at the limit, and "kW free" and the red
  bar agree. The warning "Close to a new peak. Wait a bit." went with it.
- The floor counts only while the appliances panel is shown, so a hidden panel can't raise the
  limit unseen.

Rejected: a floor plus the highest as two lines (confusing), a floor from the highest alone (the
problem above), and the floor as a typed value (that's the goal).

## Variants instead of categories

Claude's proposal, 2026-10-03, for the user's goal: scalable, not hardcoded, robust. The user
listed fixed appliances, linear dynamic ones (a kettle), non-linear dynamic ones (an oven for
30 or 60 minutes), composite actions (cooking) and dynamic actions (a longer simmer, a bigger
meal). The engine only needs a curve per row, so they differ only in how the curve is made:
- Fixed (a dishwasher programme) and composite actions (cooking): measured once, as a whole.
- Dynamic: a variant of a measured appliance with another run time. The curve's start stays as
  measured and its last phase is stretched or cut: an oven keeps its preheat and cycles longer; a
  kettle is one phase at fixed power, so this is the linear rule.
- The kettle's **From water** helper: heating water takes energy in proportion to the amount and
  the temperature rise, at the element's fixed power, so only the run time changes. Tap water is
  taken as 15 °C, and the kettle's own heat-up is ignored, so very small amounts come out a
  little short.
- Variants are plain curves, not links: renaming or deleting the original can't break them, and
  export and import stay simple. The price: re-measuring the original means adding its variants
  again, and a variant of a cut variant stretches what's left, not the original's last phase
  (user: leave it).
- Left out until needed: power levels (hob 4.5 / 9: measure the action as cooked), combining
  appliances ("Kettle + Toaster"), a programme field (another programme is another appliance),
  emoji (a name can hold one from the keyboard).

## The household's dishwasher

Only the short hot programme is used, about 1.5 h, up to 65 °C (user, 2026-10-03). So one
appliance, e.g. "Dishwasher 65°", with Can wait on.
