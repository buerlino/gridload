# Regions outside Switzerland (researched 2026-09-30)

Can GridLoad cover the countries around Switzerland, and how much work is it? The APIs below
were tested with live requests on 2026-09-30; the regulation comes from web sources (listed at
the end).

## Summary

- **Spot prices: easy for Germany, Austria and Liechtenstein.** One free API (Energy-Charts)
  covers all three, and one new parser handles it. About one small release.
- **France and Italy: technically possible, but they don't fit the app's model.** France's
  common signal is EDF Tempo (whole-day colours), which would be a feature of its own. Italian
  households mostly pay fixed time bands, not hourly prices.
- **Peak load stays Swiss only.** It is for tariffs that bill the month's highest quarter hour
  (like CKW's), and neither Germany nor Austria bills households for their monthly peak in 2026.
  The whatwatt's cost line would still work there, with the caveat in item 3 below.

## Why the EU is different from Switzerland

In Switzerland, each utility publishes its own final household tariff (VSE/AES API), so a
region is one utility. In the EU, a dynamic tariff is the **day-ahead spot price of the bidding
zone plus fixed add-ons** (grid fee, taxes, levies, supplier margin, VAT). So one price series
covers a whole country.

`classify` is relative to the window's min and max. The customer's price is `a·spot + b` with
`a > 0` (VAT scales, everything else adds), and that shifts and scales min, max and both
thresholds the same way. **So the spot price alone gives exactly the colour of the customer's
real tariff.** No per-utility data is needed. Only a time-varying add-on would change the
colour; see Austria's SNAP and Germany's §14a Module 3 below.

## The data source: Energy-Charts

`GET https://api.energy-charts.info/price?bzn=<zone>&start=<ISO>&end=<ISO>`
(Fraunhofer ISE; OpenAPI at `https://api.energy-charts.info/openapi.json`)

```json
{
  "license_info": "CC BY 4.0 (creativecommons.org/licenses/by/4.0) from Bundesnetzagentur | SMARD.de",
  "unix_seconds": [1790719200, 1790720100, ...],
  "price": [112.61, 101.44, ...],
  "unit": "EUR / MWh",
  "deprecated": false
}
```

- **No key.** HTTPS.
- **Licence:** CC BY 4.0 (Bundesnetzagentur | SMARD.de) for AT, BE, CH, CZ, DE-LU, DK1, DK2,
  FR, HU, IT-North, NL, NO2, PL, SE4, SI. **The app must show an attribution**, e.g.
  "Market prices: Bundesnetzagentur | SMARD.de, CC BY 4.0" in Help or Settings. Data for the
  other zones is "private and internal use only", so don't use them.
- **Slot length:** `start=2026-09-30&end=2026-10-01` returned **192 values (15 min)** for DE-LU,
  AT, FR and IT-North, because the EU day-ahead market has used 15-minute slots since
  1 Oct 2025. The **CH** zone returned **48 (hourly)**, since Switzerland isn't in the EU market
  coupling. There is no end timestamp per slot: a slot ends where the next begins, and the last
  one after its own length.
- **Timestamps:** `start`/`end` take ISO 8601 (`2026-01-01T17:00Z`), a date (the zone's local
  midnight) or UNIX seconds. `end` given as a date means the last minute of that day. The
  response is UNIX seconds.
- **Rate limit:** the 4th request within a few seconds got `429` with `Retry-After: 21`, and
  there were no other rate-limit headers. That's harmless at the app's ~2 fetches a day with its
  5-minute cooldown, but a 429 must be handled like any failed fetch.
- **Tomorrow:** the day-ahead auction publishes about **12:55 CET** (13:08 on the first 15-min
  day). Use `tomorrowFrom = 13:15`.
- **Unit:** EUR/MWh, a **wholesale** price. It can be negative.

Fallbacks, both tested, no key:
- aWATTar `https://api.awattar.de/v1/marketdata` and `https://api.awattar.at/v1/marketdata`:
  DE/AT only, **hourly** averages (24 values), ms timestamps, from now to +24 h by default.
- SMARD direct `https://www.smard.de/app/chart_data/4169/DE/index_quarterhour.json` (index;
  data files are per week): the upstream of Energy-Charts, a clumsier format.

ENTSO-E Transparency also has it, but needs a token.

## Per country

### Germany: easy

- Since **1 Jan 2025 every supplier must offer at least one dynamic tariff** (§41a EnWG), based
  on the day-ahead spot price. It needs a smart meter.
- **§14a EnWG Module 3** (since 1 Apr 2025): time-variable **grid fees** in three levels (high,
  standard, low), with windows set by each grid operator and published ahead (usually
  quarterly), no API. They only apply to **controllable devices** (heat pump, wallbox, battery)
  under §14a, not to a washing machine. Ignore them.
- Zone `DE-LU` (this also gives **Luxembourg** for free).
- Most German households are still on fixed-price contracts. For them the colour means "the grid
  is cheap/green now", not money saved. A product decision for the user (see the open questions).

### Austria: easy

- About 7 suppliers offer dynamic tariffs (June 2026): aWATTar (HOURLY), Verbund (HOURLY),
  smartENERGY, oekostrom (Spot+), Salzburg AG (FlexSpot), VKW, Energie AG. Zone `AT`.
- **SNAP (Sommer-Nieder-Arbeitspreis), new from 1 Apr 2026:** the grid's energy price is **20%
  lower from 10:00 to 16:00, 1 Apr to 30 Sep**, for households (network level 7) with a smart
  meter reading quarter hours. It's a fixed rule, so a later refinement could add it to the spot
  price. It only deepens the midday valley the spot price already shows, so leave it out at
  first. E-Control plans broader time-variable grid fees in later years; recheck each December,
  when the next year's fees are set.
- No power-based grid charge for households in 2026.

### Liechtenstein: easiest, small audience

- LKW offers **LKWfree**: "Stündlicher Tarif (Rp./kWh): EPEX Spot CH" plus a flat **1.40 Rp/kWh**
  processing fee from 1 Jul 2026. The grid fee is fixed. So the Energy-Charts `CH` zone (hourly)
  gives the exact colour.
- LKW's price is in Rp, and EPEX CH is in EUR/MWh, so the conversion would be needed only to
  show an absolute price (see "Price display" below).
- LKWflex (monthly, with fixed low/normal/peak bands) isn't dynamic per hour.

### France: a different model, not a region entry

- Dynamic spot tariffs exist (EU rules make large suppliers offer one), but uptake is tiny.
- The widespread signal is **EDF Tempo**: each day is blue, white (≈40/year) or red (≈20/year),
  published the day before around 11:00, and each day has peak/off-peak hours
  (**HP/HC**). Free API, tested:
  `https://www.api-couleur-tempo.fr/api/jourTempo/today` (and `/tomorrow`) →
  `{"dateJour":"2026-09-30","codeJour":1,"periode":"2026-2027","libCouleur":"Bleu"}`.
  The official RTE API needs an account.
- The **off-peak hours are set per household by Enedis** (per municipality). The TURPE 7 reform
  is moving some of them to 11:00–17:00 in summer, rolled out between Nov 2025 and end of 2027.
  So the app would need the user's own HC hours as input.
- Verdict: its own feature (day colour + the user's HC hours), not an entry in `REGIONS`. The
  `FR` spot zone would only help the few people on a spot tariff.

### Italy: low value

- The `IT-North` zone works (15 min, CC BY). The single national price (PUN) is being replaced
  by zonal prices.
- Households mostly pay a flat price or the **F1/F2/F3** time bands (ARERA, the same for every
  supplier: F1 weekdays 8–19; F2 weekdays 7–8 and 19–23, Saturday 7–23; F3 nights, Sundays and
  holidays). "PUN-indexed" offers are usually averaged per band or per month, and true hourly
  billing is rare. The colour would be a market signal, not their bill.
- Aside: Italy (3 kW contracts that trip) and France (subscribed kVA) have a **hard power
  limit** per household, which is close to peak load mode's "stay under the goal". It's not
  billed per peak, though. Not for now.

## What changes in the code

The code is described as of 2026-09-30 (items 3 and 4 updated 2026-10-02 for v0.7.0); check it
before implementing.

1. **A price source per region.** `pricesRequestUrl` (`PriceApi.kt`) appends the VSE
   `start_timestamp`/`end_timestamp` parameters, and `parsePrices` (`Prices.kt`) reads the VSE
   JSON. Add a source type to `Region`, e.g. `enum class PriceSource { VSE, ENERGY_CHARTS }`, or
   store the bidding zone and build the URL from it. Then:
   - The request is `https://api.energy-charts.info/price?bzn=<zone>&start=<start>&end=<end>`
     with the same `fetchPeriod` instants (ISO `Z` works).
   - Add a parser `parseEnergyCharts(body)` that zips `unix_seconds` and `price` into
     `PriceSlot`s. `end` is the next start; the last slot gets the same length as the one before
     it. Use `Instant` → `OffsetDateTime` in `TARIFF_ZONE`. Convert EUR/MWh to per kWh (÷1000)
     so the numbers are in the same range as the Swiss ones.
   - Keep the existing "drop slots starting at or after `end`" filter, and check whether
     Energy-Charts includes the slot at `end`.
   - Unit-test with a saved response in `core/src/test/resources/` (capture one DE-LU and one CH
     day; include a DST day if possible).
2. **`PriceSlot.price` doc:** it says "CHF/kWh". It becomes "per kWh, in the region's currency".
   Add a currency or "is wholesale" flag to `Region`.
3. **Price display.** The main screen shows the price as "… Rp/kWh", and with a whatwatt the cost
   right now as "… CHF/h" (in `MainActivity.kt`). For spot-only regions that number is the
   wholesale price, about a third of what customers pay. Recommended: don't show an absolute
   price for them, or label it "Market price 11.3 ct/kWh". Hide the CHF/h cost rate.
4. **Peak load.** There are no modes any more: peak load is a switch under Measurement in
   Settings. It needs no tariff data (the line is the month's highest quarter hour), so it can
   stay available everywhere; whether to hide it for regions without a peak tariff is a question
   for the user.
5. **`TARIFF_ZONE`** (`Europe/Zurich`) can stay: DE, AT, LI, LU, FR and IT have the same offsets
   and DST dates. Rename it or make it per region only if a region outside CET is ever added.
6. **Regions.** Add `Region("de", "Germany", …, zone "DE-LU", 13:15)`, `"at"` Austria (`AT`),
   and `"li"` Liechtenstein (`CH`, hourly, `tomorrowFrom` 13:15). Maybe "Luxembourg" (`DE-LU`).
   The flat dropdown sorted by name still works with ~10 entries. The `utility` field could read
   "Day-ahead market" for these.
7. **Attribution** for CC BY 4.0 (see above), in Help or Settings.
8. **Refresh policy:** unchanged. The 5-minute cooldown and ~2 fetches a day are well within
   Energy-Charts' limit.
9. **Store listing:** the F-Droid/fastlane description says Switzerland; update it for the
   release. No new permission, no new dependency.

## Open questions for the user

- **Audience:** in Germany most households are on fixed prices. Show the colour as a "grid is
  cheap/green" signal for everyone, or say clearly that it pays only on a dynamic tariff?
- **Absolute price** for spot regions: hide it, or show "market price" with a note?
- **Which countries** in the first release: DE + AT + LI (+ LU for free)?
- **Austria's SNAP:** leave it out at first (recommended), or add it right away?
- **France/Tempo:** worth it as a later feature of its own?

## Sources

- Energy-Charts API: https://api.energy-charts.info/ (licence text in `/openapi.json`)
- Germany, §41a EnWG: https://checkalle.de/dynamic-electricity-tariffs-2026/
- Germany, §14a Module 3: https://energiemarie.de/strompreisvergleich/ratgeber/14a-enwg/modul-3
- Austria, grid fees 2026 (SNAP): https://www.wko.at/noe/wirtschaft/netzentgelte-neue-tarifelemente-fuer-2026
- Austria, suppliers: https://www.smartmeter-portal.at/dynamischer-stromtarif/anbieter-vergleich/
- Liechtenstein, LKW tariffs: https://www.lkw.li/angebot-und-leistungen/strom-und-waerme/stromtarife.html
- 15-minute day-ahead: https://www.epexspot.com/en/news/successful-implementation-15-minute-market-time-unit-mtu-sdac
- Publication time: https://www.nordpoolgroup.com/en/trading/transition-to-15-minute-market-time-unit-mtu/faq/faq-premium-container/will-the-publication-time-of-results-change-after-the-sdac-15-minute-mtu-go-live/
- Tempo: https://www.api-couleur-tempo.fr/ and https://data.rte-france.com/catalog/-/api/doc/user-guide/Tempo+Like+Supply+Contract/1.1
- France, off-peak reform: https://www.enedis.fr/presse/enedis-met-en-oeuvre-la-reforme-des-heures-creuses-pour-accompagner-les-nouveaux-usages-lies
- Italy, time bands: https://selectra.net/energia/guida/contatore/luce/fasce-orarie
