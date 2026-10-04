# Scoring the colour

The rule and the decisions are in `CLAUDE.md`, Classification. This file keeps the numbers
behind them. Each quarter hour was scored by what waiting up to 24 h would really have saved,
with the data the app had at that moment (tomorrow only once published).

## CKW's prices (1 Aug to 30 Sep 2026)

- CKW's stated range is 0.1 to 32 Rp/kWh for the dynamic part. Observed `integrated`: the daily
  minimum was 0.1513 on nearly every day (a floor), the maximum 0.25 to 0.33.
- Every day has the same shape: one cheap valley from about 11:00 to 16:00 (PV). The night is
  **not** cheap (0.21 to 0.27). On weekdays the morning 06:00 to 09:00 is the peak; on weekends
  the dearest hour is usually midnight. The same hour varies only about ±1.5 Rp across
  weekdays. Exceptions happen: on 2026-09-09 the morning was 0.31 to 0.33 and the cheapest hour
  was 04:00.

## The 24-hour window (CKW, 1 Aug to 30 Sep 2026, checked 2026-09-29)

You can't run an appliance in the past, so "wait if you can" should point to a cheaper time that
is still coming. The old window, the calendar day, compared the evening with a noon that was
over. That worked for CKW only because tomorrow's noon looks the same.

- With the 24-hour window, green was 33% of the time, orange 22%, red 45%. Waiting would have
  saved on average 1.1 Rp/kWh at green, 6.9 at orange and 9.8 at red. Only 1.1% of greens missed
  a saving of more than 5 Rp, and no red had less than 2 Rp to gain.
- The calendar day scored almost the same (2.1% of greens missed more than 5 Rp) and differed in
  about 9% of hours, always orange against red. The main gain of the window is the next good
  time.
- Rejected: "the rest of today". Late in the evening it compares a few expensive hours with each
  other and shows green at 0.23 while tomorrow's noon is 0.15; 21% of its greens missed more than
  5 Rp.
- Known weak point, left as is for the Swiss regions: the maximum is often one expensive hour (a
  spike to 0.33 moves the red line up and turns that evening orange). A percentile instead of
  `max` would fix it, but the effect is only orange against red.

## Market prices (September 2026, AT, DE-LU and CKW, checked 2026-10-04)

The prices and the script are in `energy_charts/`: run `python3 research/energy_charts/score.py`,
don't re-request (Energy-Charts answers 429 after about 3 quick requests).

- The thirds still order the colours, but one spike squeezes them more often. With `max`, AT and
  DE-LU show green 35% and 39% of the time, orange 33% and 32%, red 32% and 29%; waiting would
  have saved on average 4.3 ct at green, about 12 at orange and 19 at red, and no red had less
  than 2 ct to gain.
- But a day's range is about 23 ct (CKW's 10 to 18 Rp), and 36 to 37% of greens missed a saving
  of more than 5 ct (CKW in the same month: 0.4%). E.g. on 14 Sep in DE-LU one spike stretched
  the range to 71 ct and turned 16:45 to 18:00 green while 18 to 23 ct cheaper was coming.
- A 90th percentile in place of `max` cut those misses to 22% and 19%, but on CKW it only turned
  orange into red (red 39% → 53%), since CKW's dear hours are a broad morning plateau, not a
  spike. v0.12.0 kept `max`.
- The user's criterion (2026-10-04): never tricked into thinking a moment is good or bad when it
  isn't. Green while the app's own data already showed a price more than 10 ct cheaper coming
  fell from 2.4% / 3.7% of the time (AT / DE-LU) with `max` to 0.0% / 0.1% with the 90th
  percentile (95th: 1.1% / 1.9%), and no red, with any top, had less than 5 ct to gain. The cost
  is red more often (AT 32% → 47%, DE-LU 29% → 41%), each of them worth waiting. The remaining
  green misses (~5%) come from prices not yet published. So the 90th percentile for market-price
  regions, shipped in v0.13.0; the Swiss regions keep `max`.
- The percentile is the sorted value at index ⌊0.9 n⌋ (`colourTop` and `score.py` alike), one
  rank above the textbook nearest rank when n is a multiple of 10. The scores above use it.
