# Feature ideas (collected 2026-10-03)

Ideas for new panels, tools and settings, found by reading the code, the docs, the whatwatt's raw
API responses and the household meter's September 2026 quarter-hour export. Nothing here is
decided. Each idea lists the questions to settle with the user before building it (see the
"Simplest approach that works" convention in `CLAUDE.md`). Phase 5 (a background alarm) is
already on the roadmap, and the appliances panel is built (`CLAUDE.md`, "Appliances"). They come
up below only where an idea touches them.

## What the data says

From the September 2026 export of the household meter (heat pump and boiler are on the other
meter), 2788 quarter hours:

- **The month's peak was one quarter hour:** 5.4 kW on 23 Sep at 12:00. The next highest was
  4.5 kW, and only 2 quarter hours came within 80% of the peak. On a typical day the highest
  quarter hour was 2.1 kW.
- **Peaks happen in the green valley.** 7 of the 30 highest quarter hours started at 12:00, and
  most of the rest were between 10:00 and 13:00. The spot colour says "run things at noon", and
  running them all at once sets the peak. The two prices pull in different directions right in
  the valley.
- **Base load is about 80 W** (median between 02:00 and 05:00; the 10th percentile of all quarters
  is 68 W). That's about 700 kWh a year, roughly a quarter of the household's use.
- **Energy by time of day:** the evening (17–22) used 35%, the noon valley (11–16) 26%, the
  morning (06–09) 8%. There's still energy to shift.
- **The money involved is small on both sides.** Avoiding the 23 Sep quarter hour would have
  saved about 0.9 kW × 1.08 CHF ≈ 1 CHF that month. Moving a third of the evening energy into the
  valley would save a couple of francs a month. So features that shift *when* energy is used are
  worth at least as much as more peak detail.

## Review of the planning features (Claude, 2026-10-04)

Asked by the user for an opinion on the energy-planning feature set (not the countries). The
plan built from it is the "Energy planning" phase in the skill's roadmap.

- **Strong:** the price signal (scored against real savings), the recorder, one set of numbers
  behind the peak window, the chips and the preview, and measured rather than typed appliances.
- **Value against complexity:** in Switzerland the peak is worth about 1 CHF a month and
  shifting a couple of francs (see above), while the limit already has four sources (goal,
  floor, minimum, highest). New peak features need a high bar; Austria and Flanders are where
  the machinery pays off.
- **The advice comes at the wrong time:** "Cheaper at 11:00" only helps if someone remembers at
  11:00. The [timer or alarm button](#set-a-timer-or-alarm-for-a-wait-row) is the cheapest fix
  (no permission, no background work).
- **Users without a whatwatt get one colour and the next good time.** That's most users, and
  most of wave 1's. The [price curve](#1-price-curve-for-the-next-24-hours) shows how long green
  lasts, which they need to plan a long run.
- **Appliances by run time, without a whatwatt** (open, the user's decision): a run time read
  off the appliance's display ("2 h 30") isn't a guess the way typed watts were. With a flat
  curve it gives price-only OK/WAIT rows. It touches the "measured only" rule (`CLAUDE.md`,
  Appliances), so only the user can say whether that rule is about watts or about appliances.
  Decide before the German phase, since it changes what the appliances panel is for.
- **[Where the limit comes from](#where-the-limit-comes-from):** with four sources, a Cooking
  row told to wait for a floor Cooking set itself looks like a bug.
- **The classification on spot prices:** the known weak point (one spike sets `max`, `CLAUDE.md`,
  Classification) was only orange against red on CKW's smooth hourly curve. Market prices have
  sharper spikes and negative hours. Score a month of Energy-Charts prices the same way before
  wave 1 is released; if the thirds hold, nothing changes, else a percentile for `max`.
- Smaller: the [saving on WAIT rows](#savings-in-francs-on-each-wait-row) (in spot regions from
  the own price with VAT); the [base load](#2-base-load) is the biggest number in the data
  (~700 kWh a year, far above the peak), one line in the history panel; the
  [widget](#home-screen-widget-or-a-quick-settings-tile) can wait, since without a background
  fetch it runs dry by evening and the timer button covers most of the need.

Suggested order: the timer or alarm and the saving on WAIT rows, then the price curve, then
where the limit comes from.

## Panels for the main screen

### 1. Price curve for the next 24 hours

**What:** a white panel (the existing `Panel` in `Charts.kt`) with one bar per slot or hour over
the classification window, each in its colour (green/orange/red), a marker for now, and time
labels every few hours. The collapsed header could say how long the current colour lasts:
"Green until 16:00" or "Green from 11:00 to 15:00".

**Why:** today the screen says when green *starts* ("● tomorrow 10:00") but not how long it
lasts, nor whether it's 1 hour or 5. With the curve you can plan a long run (washing machine,
then dryer) without trial and error.

**Data:** already there. These are the cached slots and the window from `classify`
(`Classify.kt`). The levels need `level(price)` for every slot, which today is a local function
inside `classify`. Moving it out of `classify` (e.g. a `levels(slots, now)` returning
slot → level for the window) is a small `:core` change with unit tests.

**Open questions:**
- Show it without peak load too? Right now the panels exist only with peak load on, and
  without peak load the spot part is centred with larger text. A price panel would make the
  panel layout the default for everyone.
- Hourly bars (24) or slots (96 for the 15-min utilities)? Bars per hour, averaged, read better
  on a phone. EKZ and Groupe E are 15-min, CKW and Primeo hourly.
- Show prices on the axis (Rp/kWh), or only the colours? Fewer numbers fits the UI rules.
- Where: above the peak window or below the history?

### 2. Base load

**What:** in the history panel or a small panel of its own: "Base load 80 W · about 700 kWh a
year". It could also compare with last month.

**Why:** a quarter of the use, and it runs every hour of the year. Most households don't know
theirs, and it's the easiest saving (old fridge, network gear, standby).

**Data:** the recorder's day files the app already copies (`RecorderFiles`, `parseRecording`).
A `:core` function, e.g. the median of the quarters between 02:00 and 05:00 over the last 7 days.
Use the median, not the minimum: a fridge cycles, so the minimum underestimates.

**Open questions:** CHF per year too? (`CLAUDE.md` keeps peak CHF amounts out of the code, but
this is kWh × the average price, which the app knows.) Is "02:00–05:00" right for a household
whose night isn't quiet (e.g. a dishwasher on a timer)? Then the lowest hour of each night is
safer.

### 3. Month so far

**What:** "This month 186 kWh · last month 230 kWh at this date". It could sit in the history
panel's header or under its bars.

**Data:** the recorder's files (sum of the quarters' kWh). A CHF figure would need the past price
slots, which the app doesn't keep (`PriceCache` holds only the last response). Storing them is a
few KB a day (one file per day like the recorder's), or a 31-day request, which costs one of
CKW's 4 requests per window.

**Open question:** kWh only, or store prices for CHF?

### 4. Shift score (later)

**What:** the share of this month's kWh used in green, orange and red time, e.g. a three-colour
bar: "42% green · 20% orange · 38% red". It could show the CHF saved against the day's average
price.

**Why:** motivating, and it shows whether the colour changes behaviour, which is the app's
purpose.

**Data:** needs the past price slots (see 3) plus the recorder's quarters. It's only possible
with a whatwatt.

## Tools

The delay-start helper, measuring an appliance and "one at a time in the valley" are all part
of the appliances panel now (start delay per appliance, measured curves, OK/WAIT per row): see
`CLAUDE.md`, "Appliances", and [appliances.md](appliances.md).

### Savings in francs on each WAIT row

**What:** the WAIT line gives the saving with the time: "Cheaper at 11:00 · saves 0.09 CHF", or
"Cheaper tomorrow 10:00 · saves 0.31 CHF".

**Why:** "wait" is easier to act on when it has a price. Most people asking whether to run the
dishwasher now want to know what it's worth.

**How:** `advise()` (`Appliances.kt`) already prices every candidate start with `runPrice`. The
saving is (price now − price of the chosen start) × the appliance's kWh, which the appliance
already derives from its curve. It's `:core` arithmetic with a unit test. The prices are already
CHF/kWh from the API, so no tariff constants go into the code (`CLAUDE.md`).

**Open questions:** does the spot price already include VAT? The app shows the API's price as it
is, so the saving should match that. (Since wave 1's own price, 2026-10-04: in spot regions the
saving is the market-price difference × (1 + VAT), the add-on cancels out; without an add-on,
show no saving, like the cost line.) Hide the saving below a minimum (e.g. under 0.01 CHF, which
reads as "0.00")? Spot part only: the peak saving stays separate, as in the peak window.

### Set a timer or alarm for a WAIT row

**What:** a button on a WAIT row that sets the phone's own timer for the wait ("Set timer 1 h 20
min") or alarm for the cheaper start ("Set alarm 10:00" for "Cheaper tomorrow 10:00"). With a start
delay, the timer is the delay.

**Why:** it turns the advice into an action, and the reminder comes from the phone's clock app,
which is already trusted to ring.

**How:** `AlarmClock.ACTION_SET_TIMER` (`EXTRA_LENGTH` in seconds) and `ACTION_SET_ALARM`
(`EXTRA_HOUR`, `EXTRA_MINUTES`, `EXTRA_MESSAGE` for the appliance's name). No permission, no
notification code, no background work, so it covers much of the "Green from 11:00 notification"
below without `POST_NOTIFICATIONS`. The app sets it once, when tapped; it doesn't track it. To
show the button only when a clock app exists, the manifest needs a `<queries>` entry (Android 11+
hides other apps otherwise). That's not a permission.

**Open questions:** timer or alarm for a delay, or both? Should the button also appear on OK rows
("Start in 1 h" is not useful, so probably not)? Does the timer's name carry the appliance
("Dishwasher")?

### Where the limit comes from

**What:** the peak window's limit line can come from three places: the floor (the biggest
appliance × 1.2), the goal, or the month's highest quarter hour. Right now the screen says only
"2.6 limit". A tap on the limit label would say which one sets it: "Floor from Cooking (2.21 kW
× 1.2)", "Goal", or "Highest this month, 2 Oct 18:30".

**Why:** when the peak window tells Cooking to wait for a limit it set itself, nothing on screen
explains why. Someone who doesn't know the floor would think the app is broken, which is what the
floor was meant to fix.

**How:** `peakLine` (`Quarters.kt`) returns only the maximum of the three, so it would need to
return the winning source with the value (a small pair or a label), and its tests would cover
which one wins. The explanation goes in a dialog, like the ⓘ ones (`Info` in `SettingsScreen.kt`),
so the peak window keeps one short line (`CLAUDE.md`: each concept in one place).

**Open questions:** a dialog on tap, or a short suffix in the scale ("2.6 limit · floor")? Show it
in the help too?

## Outside the main screen

### Home-screen widget (or a Quick Settings tile)

**What:** a small widget in the spot colour with the headline and the next good time ("Good time"
/ "● 11:00"). Tapping it opens the app. A Quick Settings tile is the smaller alternative: just
the colour and the headline.

**Why:** "open the app, look for 10 s" becomes "look at the home screen".

**How:** Jetpack Glance (`androidx.glance:glance-appwidget`, AndroidX, Apache 2.0, so F-Droid is
fine with it). The colour comes from the cached slots in `files/prices.json`, so no network is
needed to update it, only a recompute at each slot boundary. `updatePeriodMillis` is 30 min at
the shortest, so a precise widget needs an inexact `AlarmManager` alarm per boundary, or a
WorkManager job (another dependency). Fetching tomorrow's prices in the background would break
"no background work". Without it, the widget can say "Open the app for tomorrow's prices" once
the cache runs out.

**Open questions:** fetch in the background (the cooldown and rate limit still apply), or only
from the cache? The whatwatt stays out of it (no LAN access in the background).

### "Green from 11:00" notification

**What:** one notification when the next good time starts, or one in the morning about today's
valley.

**How:** one inexact alarm a day, the `POST_NOTIFICATIONS` permission (a new permission, which
the user decides on; `CLAUDE.md` lists the allowed ones). It's a step toward phase 5's
background alarm.

### German and French

**Why:** GridLoad's regions are German-speaking, except Groupe E (Fribourg, Neuchâtel), which is
mostly French-speaking. It's the biggest reach gain.

**How:** extract the strings into `strings.xml` (`CLAUDE.md` calls this an optional later step),
then `values-de` and `values-fr`. The fastlane store text per language too
(`fastlane/metadata/android/de-DE/...`). It's a lot of strings, and the UI rules (short, one idea
per line) apply in each language.

**Order, decided (user, 2026-10-03):** after wave 1, as planned. Wave 1 ships in English first,
then the strings move to `strings.xml` with German.

## Settings and diagnostics

- ~~Firmware check in Test~~: dropped 2026-10-03. `device.firmware` is in `/api/v1/system`, but no
  version is known to fail: the docs date Berry from 2.0.0, and the device reported
  `services.berry` on 2.0.0. Revisit if someone's recorder fails on an older firmware.
- **whatwatt health in Settings → Recorder (opened):** `wifi.rssi` (−64 at the meter here),
  `device.last_reboot`, and maybe `device.plug.v_scap` (supercap voltage; 3.2 V came before a
  reboot during the download tests), all in `/api/v1/system`, which the sync already fetches.
  These help explain gaps, since the whatwatt runs on weak meter power. One line: "Wi-Fi −64 dBm
  · restarted 2 Oct 17:01".
- **Share the recorder's files** (Settings → Measurement → Recorder): the copies in
  `files/recorder/` are already CSV. A Share action (the system share sheet, or one
  `CreateDocument` per month, as the appliance export does) lets someone check a quarter against
  the bill, or give the utility the data when a peak is disputed.

**How:** the share sheet needs a `FileProvider` in the manifest (not a permission). The files
hold UTC epoch seconds, so they're hard to read by hand; a readable export would write local
times in the region's zone, one line per quarter hour, with the register.

**Open questions:** one file per month or the day files as they are? Only the recorder's files,
or the appliances too?

## Accessibility

### A TalkBack pass

**What:** spoken labels for what the app shows only as colour or icon. `contentDescription`
appears nowhere in the app. The colour always comes with the headline text, which is good, but
the peak bars, the ↻ refresh (and its dimmed state), the ▴/▾ toggles, the OK/WAIT chips and the
history bars have no spoken labels.

**Why:** the app is on F-Droid, where screen-reader users look for accessible apps, and it's a
small pass for a lot of gain.

**How:** `contentDescription` or `Modifier.semantics` on each control, `stateDescription` for
the dimmed ↻ ("Refresh blocked for 3 min"), and a spoken line for each bar ("Now, 1.6 kW, 0.4 kW
free"). Check on the phone with TalkBack on; this list comes from reading the code, not from
running it.

**Open questions:** do it now, or with the German phase, which touches every string anyway?

## Considered and skipped

- **Per-phase power** (L1/L2/L3 in `report`): niche, and nothing to act on for a household.
- **Reactive power and power factor:** not billed for households.
- **A solar export view:** the export register reads 0 in this household. Worth reopening if
  someone with PV asks.
- **CHF amounts for the peak:** `CLAUDE.md` keeps them out of the code.
- **Carbon intensity:** already in the README's "Later ideas". It's a different signal (and
  data source) from the price; separate decision.
