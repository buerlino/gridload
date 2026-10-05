# The draw ahead (2026-10-05)

What the peak window, the alarm and every appliance's OK/WAIT assume the house draws for the rest
of this quarter hour and in the quarters after it. Audited and studied after a WAIT on 5 Oct that
looked wrong; the decisions are in `CLAUDE.md` (Projection, the whatwatt switch, Preview).

## The case

5 Oct 2026, 19:13:49, CKW, about 70 s left in the 19:00 quarter. The peak window showed 480 W now
and a limit of 2870 W; previewing "Kettle 1 L" put the 19:15 column at 2160 W (house 1930 W +
kettle 230 W, "House, 2-min average"). Cooking and Dishwasher 65°C said "Sets a new peak right
now". The recorder saved 18:45 at 1060 W, 19:00 at 372 W (0.093 kWh) and 19:15 at 461 W.

It was a real load, not a defect. A trace of 85 W, then about 1.95 kW from 19:11:47 to 19:13:48,
then 0.55 kW gives every number shown (`theObservedCaseWasABurstTheAverageCarriedIntoTheNextQuarters`).
The 85 W before it is the night's base, which the fit didn't aim for. No trace without such a load
gives that draw ahead (`noTraceWithoutABurstGivesThatDrawAhead`): 0.064 kWh in two minutes, about
half a litre in the kettle, or the microwave. The 2-minute average carried it into the next quarter
and every later one, so Cooking waited from 19:12:35 to 19:15:30.

## The audit

All in `core/src/test/.../DrawAheadTest.kt`, on `MeterTrace` (a meter read every 4.25 s, time in
whole seconds, register in 0.001 kWh steps, the recorder's lines interpolated as
`gridload_recorder.be` does, polled through the app's code every 5 s).

- `DrawAverage` is the true average of its window within the register's step and a second of time
  stamp: 0.06 kW + 1.7% at its 1-minute minimum, 0.03 kW + 0.8% at 2 minutes. On the real readings
  (5 Oct, 20:34–20:51, 149 readings) it matched the mean of the meter's power readings: mean
  difference 0.000 kW, ±0.04.
- A pause of up to 2 minutes is averaged over, as the register counted it; after that the latest
  reading stands in for a minute. A frozen report freezes it. The meter's clock jumping ahead
  halves it for up to 2 minutes; going back, or the register going back, starts it over.
- The projection is exact from the first reading after the recorder's line: the line interpolates
  between the readings either side and the register only rises, so a later reading is never below
  it. `used` is within 4 Wh of the truth. On the real readings the projection stayed within
  0.06 kW of the recorded 1.054 kW and ended at 1.056 (`realReadingsProjectTheRecordedQuarter`).
- The phone's clock (an appliance's start) and the meter's (the projection) can sit in different
  quarters for up to ~10 s after a boundary: readings were 1.3–6.7 s old when polled. Each quarter
  still gets the house once.
- The preview, the chip, the header and the alarm read the same numbers.
- Found: nothing told a frozen report from a current one, and back from the background the last
  reading was advised from until the next arrived. Fixed with `Freshness` (30 s).
- Found: the app polls only while open, so in a glance there's no 2-minute average and the latest
  reading stood in, the jumpy behaviour the average was meant to fix. Fixed: this quarter so far.

## The study

`draw_ahead/DrawAheadStudy.kt` (how to run it is at its top). Nine synthetic household events,
each started at 30 points across a quarter, through the app's own `DrawAverage`,
`QuarterProjector` and `quarterLoads`, scored every 5 s from the event's start to an hour after it
ends. The question asked: can Kettle 1 L, Cooking or Dishwasher 65°C (the household's measured
curves) start now, against a 2.87 kW limit? Each cell is **missed / false**: minutes per event,
summed over the three appliances. Kettle 1 L never decides anything (it adds at most 0.36 kW to a
quarter). "Glance": the app opened seconds before. Synthetic events decide the mix, so the totals
are a guide, not a verdict.

The policies: (a) the 2-minute average, as in v0.14 (the latest reading until the readings span a
minute, so in a glance it's the latest reading); (b) the last recorded quarter; (c) the lower of
the average and the latest reading; (d) this quarter so far; (e) this quarter's projection; (f)
the lower of the average and the average since the previous quarter's start; the base alone.

This quarter, an appliance started now:

| Event | (a) | (a) in a glance | (c) | (d) | higher of avg, latest |
|---|---|---|---|---|---|
| Kettle 2 kW, 3 min | 0 / 1.5 | 0 / 1.7 | 0 / 1.0 | 0 / 1.3 | 0 / 2.1 |
| Microwave 1.2 kW, 2 min | 0 / 0.3 | 0 / 0.5 | 0 / 0.2 | 0 / 0.3 | 0 / 0.6 |
| Hob 2 kW cycling 15–30 s, 20 min | 0.4 / 2.5 | 1.1 / 4.6 | 1.3 / 1.2 | 0.4 / 2.6 | 0.2 / 5.9 |
| Oven: preheat, then cycling | 1.8 / 11.8 | 3.4 / 18.6 | 4.6 / 9.2 | 2.5 / 12.4 | 0.6 / 21.3 |
| Cooking (like 5 Oct 18:15–18:45) | 2.6 / 10.7 | 5.3 / 11.5 | 6.8 / 5.3 | 2.8 / 10.3 | 1.0 / 16.9 |
| Dishwasher heating phases | 3.1 / 10.5 | 3.0 / 10.6 | 3.5 / 8.7 | 3.1 / 9.7 | 2.5 / 12.4 |
| Tumble dryer 2.3 kW, 80 min | 3.1 / 8.0 | 8.3 / 18.6 | 10.7 / 6.1 | 3.4 / 7.4 | 0.7 / 20.5 |
| Oven + hob + kettle (a real new peak) | 4.0 / 7.0 | 6.8 / 13.2 | 9.2 / 3.3 | 7.0 / 8.4 | 1.6 / 16.9 |
| **All** | **14.9 / 52.4** | **27.8 / 79.3** | **36.1 / 35.0** | **19.2 / 52.3** | **6.6 / 96.7** |
| The alarm alone, all | 1.3 / 1.5 | 1.8 / 5.7 | 2.5 / 0.9 | 1.8 / 2.2 | 0.5 / 6.2 |

A quiet base with a fridge is 0 / 0 everywhere. The glance's extra false alarms are cooking: the
latest reading catching a plate on (3.4 minutes per cooking).

The next quarter:

| Event | (a) | (a) in a glance | (b) | (c) | (d) | (e) | (f) | base |
|---|---|---|---|---|---|---|---|---|
| Kettle | 0 / 4.8 | 0 / 6.0 | 0 / 0 | 0 / 3.4 | 0 / 1.2 | 0 / 2.2 | 0 / 0.1 | 0 / 0 |
| Microwave | 0 / 0.8 | 0 / 1.5 | 0 / 0 | 0 / 0.4 | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| Hob | 0.4 / 7.9 | 1.1 / 19.1 | 2.3 / 6.0 | 1.4 / 3.9 | 2.2 / 7.8 | 2.2 / 7.7 | 2.3 / 0.5 | 2.3 / 0 |
| Oven | 2.8 / 26.2 | 5.3 / 41.2 | 6.7 / 29.2 | 7.1 / 18.0 | 2.9 / 26.4 | 2.1 / 26.3 | 5.8 / 8.7 | 12.5 / 0 |
| Cooking | 2.8 / 32.2 | 4.3 / 31.3 | 24.6 / 51.2 | 6.6 / 25.6 | 9.4 / 37.9 | 7.2 / 36.1 | 20.0 / 26.1 | 26.7 / 0 |
| Dishwasher | 4.8 / 38.3 | 4.6 / 42.6 | 7.2 / 17.6 | 5.2 / 33.0 | 5.9 / 31.5 | 5.1 / 32.0 | 8.5 / 6.6 | 9.1 / 0 |
| Dryer | 6.2 / 34.4 | 19.0 / 28.6 | 36.1 / 60.6 | 23.7 / 27.4 | 12.0 / 39.4 | 9.8 / 37.6 | 32.3 / 34.3 | 105 / 0 |
| Oven + hob + kettle | 2.9 / 33.8 | 5.0 / 41.2 | 13.2 / 47.9 | 6.8 / 22.0 | 5.3 / 37.4 | 4.0 / 37.3 | 10.2 / 24.8 | 20.0 / 0 |
| **All** | **20.0 / 178** | **39.3 / 211** | **90.1 / 213** | **50.7 / 134** | **37.6 / 182** | **30.4 / 179** | **79.1 / 101** | **176 / 0** |

(d) in a glance is the same as warm, since it needs no history: 37.6 / 182.

The quarters after the next, all events: (a) 5.7 / 108, in a glance 3.5 / 213; (b) 14.3 / 80;
(c) 8.0 / 89; (d) 7.8 / 84; (e) 7.9 / 86; the lower of (a) and (e) 9.7 / 73; the average since the
previous quarter's start 13.4 / 64; (f) 14.4 / 24; the base 19.4 / 0. The extra misses are almost
all the dryer, a long load just started.

Most false warnings during sustained loads can't be foreseen by any policy: a hob or an oven
changes within 15 to 30 minutes. After a kettle the next quarter's house is up to 2.1 kW too high
(19:13 was 1.47 kW).

## Decided (user, 2026-10-05)

- This quarter and the next keep the 2-minute average: the fewest missed warnings. The few minutes
  of WAIT after a short burst late in a quarter are its price; every option that removes them
  adds 10 to 60 minutes of misses above.
- In a glance, this quarter so far instead of the latest reading (`drawAhead`), with the preview's
  legend naming the average in use. In the study's glance: the alarm 1.8 / 5.7 → 1.8 / 2.2, this
  quarter 27.8 / 79 → 19.2 / 52, the next 39.3 / 211 → 37.6 / 182, after the next
  3.5 / 213 → 7.8 / 84.
- A reading the meter hasn't moved on from for 30 s is old (`Freshness`).
- The quarters after the next: open, decided on real readings. `draw_ahead/capture.py` polls
  `/api/v1/report` every 5 s from the PC, 17:00–21:00 on 6, 7 and 8 Oct 2026, into
  `../gridload-captures/` (outside the repo: household data). Then score the policies on those
  readings, with the quarters from the day files.
