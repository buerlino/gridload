# GridLoad outside Switzerland (researched 2026-09-30, revised 2026-10-03)

Which countries can GridLoad cover now that it has peak load, the recorder, the history and the
appliances, and what does each need? APIs were tested with live requests (2026-09-30 and
2026-10-03); regulation comes from the web sources listed at the end. Items marked
**unverified** come from one secondary source or none; check them before building on them.

## Summary

GridLoad has three layers, and each needs different things from a country:

| Layer | Needs | Where |
|---|---|---|
| **Prices**: the colour, next good time, the appliances' "Cheaper at" | a free, licensed day-ahead or tariff API | most of the EU (below) |
| **whatwatt**: cost line, live draw, appliance measuring | a meter port the whatwatt reads (P1, M-Bus, Kamstrup HAN) and a whatwatt to buy | NL, BE, LU, AT (most grids), DK, SI; likely NO, SE, FI |
| **Peak load**: the line, the alarm, the history, the appliances' "Sets a new peak" | households billed on a power peak | CH (CKW and others), **Belgium (Flanders)** since 2023, **Austria from 1 Jan 2027**, Norway (hourly variant), parts of Sweden |

Recommended order:

1. **Austria and Flanders first.** They are the only places outside Switzerland where all three
   layers fit with almost no new concepts. Both bill the **highest quarter hour of the calendar
   month**, as CKW does. Austria starts on **1 Jan 2027**, so a release before then lands
   exactly when Austrians start caring. Their meters are on the whatwatt's datasheet, and the
   spot zones `AT` and `BE` are CC BY on Energy-Charts. New: one spot-price parser, a currency,
   and a **minimum billed peak** per region (2 kW in AT, 2.5 kW in Flanders).
2. **The rest of the Energy-Charts CC BY zones, prices only**, in the same release: Germany,
   Luxembourg, Netherlands, Liechtenstein (zone `CH`), and if wanted Czechia, Poland, Hungary,
   Slovenia. No extra code beyond step 1.
3. **Spain**, prices only: the regulated PVPC is an hourly household price that about a third of
   households pay, and the free REE API gives the **final** price (the time-of-use grid tolls
   included), so the colour is exact. A second small parser. No whatwatt there.
4. **Denmark**: spot plus the grid operator's time-of-use tariff, both from Energi Data Service
   (CC BY). Spot alone gave the wrong colour in **46 of 96** quarters on 2026-10-03. Kamstrup HAN
   meters, the same family as the user's.
5. **Nordics and Baltics**, prices only at first: Finland, Estonia, Latvia, Lithuania (Elering),
   Sweden and Norway. **Norway's peak model is different**: the average of the month's three
   highest *hours* on three different days, in price steps. It's worth its own phase, not a
   tweak.
6. **Not a fit**: France (Tempo day colours, not hourly), Italy (fixed time bands), Germany's
   whatwatt layer (its meters have no port the whatwatt reads), the UK (no local meter port; not
   EU).

**Biggest open point: can people outside Switzerland buy a whatwatt?** whatwatt's own shop sells
in CHF and ships by Swiss Post; no EU shop or retailer turned up (only one eBay.de listing). The
site says "Made for Europe". Without an EU source, layers 2 and 3 stay Swiss in practice. Ask
whatwatt (info@whatwatt.ch) before investing in Austria's peak load. See the open questions.

## What changed since 2026-09-30

- **The app:** peak load (v0.7–v0.8), the recorder, the panels and history (v0.9), the
  appliances (v0.10), country before region (v0.9), and a time zone per country (2026-10-03,
  not released). The appliances' price advice is relative like the colour, so it works on any
  price source. Their peak advice needs a line, so it needs a peak tariff or a goal.
- **Austria:** E-Control's new grid-fee framework (SNE-G-V) bills households on their monthly
  highest quarter hour from 2027, and adds a winter low-price window (WiNAP) to the summer one
  (SNAP).
- **Sweden:** the national power-tariff mandate for 2027 was **paused** on 13 Mar 2026.
- **Netherlands:** time-dependent grid tariffs for households are only a proposal, for 2029 at
  the earliest. Net metering (saldering) ends 1 Jan 2027, which pushes solar owners towards
  dynamic contracts.
- **Wallonia** has had time-of-use grid tariffs since 1 Jan 2026.
- **Norway:** the state fixed price (Norgespris) covered about 58% of household consumption in
  mid-2026; it runs to 31 Dec 2026 and must be re-ordered for 2027.

## When the spot price gives the right colour

`classify` is relative to the window's min and max. If the customer's price is
`a·spot + b` with `a > 0` (VAT scales, flat fees, margins and taxes add), the colour is exactly
the spot price's colour, so no per-supplier data is needed.

That breaks when an add-on **varies with time**. Then the colour needs spot **plus** that
add-on (in the same currency, before VAT, since VAT only scales):

| Country | Time-varying add-on | Size | Source | Verdict |
|---|---|---|---|---|
| Denmark | grid operator's tariff, e.g. Radius C from 1 Oct 2026: 0.11 DKK/kWh 00–06, 0.32 by day, **0.96 from 17 to 21** | DK1 spot that day ranged 0.38–1.82 DKK/kWh. **46 of 96 quarters changed colour** (spot alone: red mornings and nights; with the tariff: red 17–21, green at night) | Energi Data Service `DatahubPricelist`, per operator | **required** |
| Norway | grid energy fee by day/night, e.g. Elvia weekdays 16 øre 22–06, 26 by day (excl. taxes) | NO1 spot that day ranged 0.84 NOK; the 10 øre step is ~12% of it | NVE API, per operator and county | leave out at first |
| Austria | SNAP: grid energy price −20% Apr–Sep 10–16; WiNAP (from 2027): −20% Oct–Mar 22–04 | a fraction of a daily spot range; it deepens valleys the spot already shows | fixed national rule | later refinement |
| Belgium, Wallonia | grid tariff bihoraire (off-peak 11–17 and nights) or "IMPACT" (5 periods, 3 levels), per household | depends on the household's choice | fixed rule, per household | ask the household, or Flanders only first |
| Spain | 2.0TD tolls P1/P2/P3 | large | **already in PVPC** | none needed |
| Slovenia | grid charges in 5 time blocks by season | unverified | fixed rule | later |
| Germany | §14a Module 3 grid fees | only for controllable devices (heat pump, wallbox) | — | ignore |
| Netherlands | none until 2029 (proposal) | — | — | none |
| Flanders, Luxembourg, Liechtenstein, Finland, Baltics | none known | — | — | none |

## The whatwatt by country

The whatwatt Go datasheet (v16, 02/2025) lists the interfaces **P1** (DSMR 4.x/5.x), **M-Bus**
(DLMS/COSEM, HDLC), **UART/TTL** (Kamstrup Omnipower HAN, Kamstrup pull mode) and **pMEP**, and
these meters:

- P1: Ensor eRS301/eRS801, Iskraemeco AM550 (with CII module AC140-K8), Kamstrup Omnipower (P1
  adapter) and Omnia, Landis+Gyr E360, Meter+Control Flexy, NES 83335-3 (Gen 5), Sagemcom
  S211/T211, Semax/Elster AS3000 (CII module).
- M-Bus 34/22 V (sometimes needs USB-C power): Kaifa MA309M, Landis+Gyr E450 S4/S5 and E570,
  Sagemcom S210/T210-D.
- UART/TTL: Kamstrup Omnipower (whatwatt Kamstrup adapter, the user's setup).
- pMEP: NES 83332-3 (Gen 3), 83334-3 (Gen 4).

"A standard interface alone does not guarantee that every meter variant or configuration is
supported" (whatwatt docs). Mapped to countries:

| Country | Meter port | whatwatt | Key from the grid operator |
|---|---|---|---|
| Netherlands | P1, DSMR 5, RJ12 | yes (P1 is the reference case) | no |
| Belgium | P1 (eMUCS), Sagemcom S211/T211 at Fluvius; P1 firmware 1.4.3+ also sends the **current quarter's average (1-0:1.4.0) and the month's highest quarter hour (1-0:1.6.0)** | yes, these models are on the datasheet | no |
| Luxembourg | P1 (Smarty), encrypted | unverified | yes |
| Austria | **M-Bus**: Netz NÖ (Kaifa MA309M, Sagemcom T210-D), Salzburg, TINETZ, Vorarlberg, Innsbruck (Kaifa, Honeywell), Steiermark and Graz (L+G E450, Sagemcom P1), Klagenfurt (E450/E570), Kärnten and Wels (P1: Iskra AM550, Siemens IM350). **Not**: Wiener Netze and Netz OÖ (infrared), Linz (wireless M-Bus) | yes for the M-Bus/P1 grids (Kaifa, E450, T210-D, AM550 are on the datasheet); Honeywell and Siemens unverified | yes (Netz NÖ: smartmeter@netz-noe.at; Salzburg: activated in its portal) |
| Denmark | Kamstrup Omnipower HAN (encrypted), NES/Echelon at some operators | likely: the Kamstrup adapter and the NES meters are on the datasheet; Danish HAN pushes DLMS, while the user's Swiss meter is read in KMP pull mode, so check | yes (Kamstrup) |
| Norway | HAN on RJ45, M-Bus, DLMS/COSEM (Aidon, Kaifa, Kamstrup), unencrypted | unverified: M-Bus and DLMS match, the connector is RJ45 against the whatwatt's RJ12 | no |
| Sweden | HAN/P1 on RJ12, mandatory on every meter since 1 Jan 2025 | unverified, likely | no |
| Finland | HAN on RJ12 on second-generation meters | unverified | no |
| Slovenia | Iskraemeco AM550, P1 | yes (with the CII module) | unverified |
| Germany | optical SML, or the smart meter gateway | **no** | — |
| France, Spain, Portugal, Italy | Linky TIC, or no usable customer port | **no** | — |

The recorder (`gridload_recorder.be`, frozen at v2) uses only `ww.onreport`'s energy register and
the SD card, so it is meter-independent in principle. On a first DSMR or DLMS meter, check that
`report.energy.active.positive.total` is filled and that `meter.status` reads `"OK"`.

**Belgium bonus:** the meter itself computes the billed monthly peak (1-0:1.6.0). The whatwatt's
`MDAP_*` (maximum demand) columns were empty on the Kamstrup; on a Belgian meter they are probably
filled (unverified). If so, the app could compare its month's highest with the meter's, without
any change to the script.

## Price sources (tested 2026-10-03)

| Source | Zones | Slots | Key | Licence | Tomorrow |
|---|---|---|---|---|---|
| **Energy-Charts** `api.energy-charts.info/price?bzn=&start=&end=` | CC BY 4.0 only for **AT, BE, CH, CZ, DE-LU, DK1, DK2, FR, HU, IT-North, NL, NO2, PL, SE4, SI**; every other zone is "private and internal use only" | 15 min (CH hourly) | no | CC BY 4.0, "Bundesnetzagentur \| SMARD.de" | 96 quarters of 2026-10-04 were there at ~17:30; publication ~12:55 CET, so `tomorrowFrom` 13:15 |
| **Energi Data Service** `api.energidataservice.dk/dataset/DayAheadPrices` | DK1, DK2 (also DE, NO2, SE3, SE4) | 15 min, EUR and DKK | no | **CC BY 4.0**, "Source: Energinet", commercial use allowed | 2 days returned |
| **Energi Data Service** `…/dataset/DatahubPricelist` | every Danish grid operator's tariffs (`ChargeOwner`, `ChargeTypeCode`, `Price1`–`Price24` per hour, `ValidFrom`/`ValidTo`) | hourly | no | CC BY 4.0 | changes twice a year (1 Apr, 1 Oct) |
| **Elering** `dashboard.elering.ee/api/nps/price?start=&end=` | EE, FI, LV, LT in one response | 15 min, UNIX seconds | no | not stated (Elering is Estonia's grid operator) | yes; it includes the slot at `end` (97 values), like the VSE APIs, so the existing filter applies |
| **elprisetjustnu.se** `/api/v1/prices/YYYY/MM-DD_SE3.json` | SE1–SE4 | 15 min, SEK and EUR | no | free, attribution asked, data from ENTSO-E | one static file per day |
| **hvakosterstrommen.no** `/api/v1/prices/YYYY/MM-DD_NO1.json` | NO1–NO5 | **hourly only** | no | "refer to us as a source" | from 13:00 |
| **REE apidatos** `apidatos.ree.es/es/datos/mercados/precios-mercados-tiempo-real?start_date=&end_date=&time_trunc=hour` | Spain: **PVPC** (the 2.0TD household price, €/MWh, tolls included; taxes probably not, unverified) and the spot price | PVPC hourly, spot 15 min | no | reuse allowed with attribution (REE legal notice) | PVPC for tomorrow wasn't out at ~17:20; today's data said last updated 20:46 the evening before, so `tomorrowFrom` ≈ 21:00 (one observation) |
| **NVE** `nettleietariffer.dataplattform.nve.no/v1/NettleiePerOmradePrTimeHusholdningFritidEffekttariffer?ValgtDato=&Tariffgruppe=Husholdning&Kundegruppe=Husholdning&FylkeNr=&OrganisasjonsNr=` | every Norwegian grid operator: energy fee per hour, and the capacity steps (`effekttrinnFraKw`/`TilKw`, `fastleddEks` per month) | hourly | no | Norwegian open data (licence unverified) | daily |
| ENTSO-E Transparency | all zones | 15 min | **token** (401 without) | per-user registration | — |
| EnergyZero (NL) | NL, all-in incl. VAT | hourly | no | not stated | — |

Notes:
- Energy-Charts answered `429` after about three quick requests (a `Retry-After` of ~20 s); one
  request every 25 s always worked. At ~2 fetches a day per phone that's harmless, and the app
  already shows "busy" for it.
- elprisetjustnu, hvakosterstrommen and Elering pass on Nord Pool/ENTSO-E data without an explicit
  licence, while Energy-Charts calls the same zones private-use only. Prefer the CC BY sources
  where they cover a zone: Energi Data Service for DK1, DK2, SE3, SE4 and NO2; Energy-Charts for
  the rest of its list. Use the others only for SE1, SE2, NO1, NO3–NO5 and FI/EE/LV/LT, and only
  after checking their terms (or asking them).
- No free, licensed source turned up for Portugal, Italy's other zones, Greece, Croatia or
  Romania.

## Peak tariffs by country

| Country | What is billed | Fits the current peak load? |
|---|---|---|
| Switzerland (CKW and others) | the month's highest quarter hour, linear per kW | yes, built for it |
| **Belgium, Flanders** | the highest quarter hour of each month; the bill uses the **average of the last 12 monthly peaks**, each at least **2.5 kW**; 2026 about €53/kW per year excl. VAT | yes. The line becomes max(2.5 kW, the month's highest, the goal). The 12-month average changes the money, not the line: a new monthly peak still costs for a year |
| **Austria, from 1 Jan 2027** | the highest quarter hour of each calendar month; at least 2 kW (or 20% of the contracted capacity); a higher rate per kW above 10 kW; phased in from ~30% of the grid fee to ~50% over three years. SNE-G-V issued in September 2026 (stakeholder sources); the amounts (SNE-T-V) were due in October 2026 | yes. Line = max(2 kW, the month's highest, the goal). It needs a quarter-hour smart meter, which whatwatt users have anyway |
| Belgium, Wallonia and Brussels | Wallonia: no capacity tariff, time-of-use grid fees since 2026. Brussels: not checked | no peak; prices only |
| **Norway** | *kapasitetsledd*: the average of the **three highest hours on three different days** in the month, billed in steps (Elvia: 0–2, 2–5, 5–10, 10–15, … kW) | not as is. The recorder's quarters add up to hours, so no script change, but the app needs a second model: daily highest hours, the top three, the step they fall in, and the alarm when an hour would raise the step |
| Sweden | power tariffs (often the average of the 3 highest hours a month) at some grid operators; the national 2027 mandate was paused on 13 Mar 2026, with a new proposal due by 12 Apr 2027 | per operator; wait for the new rules |
| Slovenia | since 1 Oct 2024 an agreed power per time block (5 blocks) with a surcharge for exceeding it, on 15-min values; the households' transition and the 2025 parliamentary changes are unclear | possibly (line = the agreed power of the current block); verify first |
| Netherlands, Germany, Denmark, Finland, Spain, Italy, France | no household peak billing (Spain, Italy and France have a hard contracted-power limit that trips, a different thing) | prices only, or peak as a goal only |

## Per country

### Austria: the best fit (prices, whatwatt, peak)

- Prices: zone `AT` (Energy-Charts, CC BY). About 7 suppliers offer dynamic tariffs (aWATTar,
  Verbund, smartENERGY, oekostrom, Salzburg AG, VKW, Energie AG).
- whatwatt: M-Bus/P1 meters in most grids (table above); not Vienna or Upper Austria.
- Peak: from 1 Jan 2027, the monthly highest quarter hour (table above).
- SNAP/WiNAP: fixed national windows, −20% on the grid's energy price. Leave them out at first;
  add them as a fixed rule once the 2027 amounts are known.
- No CHF: EUR, cents.

### Belgium: Flanders now, Wallonia prices only

- Prices: zone `BE`. More than ten dynamic suppliers in Flanders (ENGIE, Eneco, Luminus, Bolt,
  Frank Energie, OCTA+, Ecopower and others), some with quarter-hour prices since Oct 2025; fewer
  in Wallonia.
- Flanders: the capacity tariff since 2023 (table above), P1 on every digital meter. Grid fees
  per kWh are flat, so spot gives the exact colour.
- Wallonia: the grid fee varies by time for bihoraire and IMPACT customers, so the spot-only
  colour can be off there. Offer only Flanders first, or a "Wallonia (spot only)" region with a
  note.
- One country, two regions: "Flanders" (peak) and "Wallonia and Brussels" (prices only).

### Netherlands: prices and whatwatt, no peak

- Zone `NL`. More than 900,000 dynamic contracts at the end of 2025 (+30% in a year), still under
  10% of households. Saldering ends 1 Jan 2027, which makes timing consumption matter more.
- P1 everywhere. No household peak billing; time-dependent grid tariffs proposed for 2029.
- The Dutch market for P1 readers is dominated by others (HomeWizard and friends); the whatwatt is
  unknown there.

### Germany, Luxembourg, Liechtenstein: prices only

- Germany: every supplier must offer a dynamic tariff since 2025 (§41a EnWG); most households are
  still on fixed prices. Zone `DE-LU`. §14a Module 3 only affects controllable devices. No
  whatwatt (meters).
- Luxembourg: zone `DE-LU`; few dynamic offers (unverified). P1 meters, encrypted.
- Liechtenstein: LKWfree = EPEX Spot CH per hour + a flat 1.40 Rp/kWh, so zone `CH` gives the
  exact colour, and the currency is CHF. Small audience. Swiss whatwatt shop.

### Spain: prices only, a large audience

- PVPC, the regulated hourly tariff, covers roughly a third of households (sources say 30–35%).
  Each day's 24 prices are published the evening before.
- REE's PVPC series already includes the time-of-use tolls (P1/P2/P3), so the colour matches what
  households pay; no add-on logic. One parser for REE's JSON (`included[].attributes.values[]`
  with `value` in €/MWh and `datetime` with an offset).
- Mainland and Balearics are `Europe/Madrid`; the Canaries need `Atlantic/Canary` (a region with
  its own `zone`, already supported).
- No whatwatt (no usable meter port).

### Denmark: prices with the grid tariff

- Zones DK1 (west), DK2 (east). Spot-based (variable) contracts are the norm.
- The colour needs the grid operator's tariff (see above), so a region is a **grid operator**
  (Radius, N1, Cerius, TREFOR El-net, Konstant, Dinel, …), as in Switzerland. Fetch the spot price
  and the operator's tariff (two requests, both CC BY), add them, then classify.
- Kamstrup Omnipower with an encrypted HAN port at most operators; the key comes from the grid
  operator. No household peak billing.

### Norway: big, but later

- Nearly all households (97.6%) have spot-type contracts, but Norgespris (a fixed 40 øre/kWh excl.
  VAT, chosen per household at Elhub) covered ~58% of consumption in mid-2026. It ends
  31 Dec 2026 and must be re-ordered; 2027's terms weren't set. A household on Norgespris has no
  reason to shift.
- Prices: NO2 is CC BY (Energy-Charts, Energi Data Service, 15 min); NO1, NO3–NO5 only hourly from
  hvakosterstrommen.
- Peak: the hourly top-three-days steps above, with tariffs from the NVE API. The most interesting
  peak market after Austria and Flanders, but a second model.
- whatwatt: HAN is M-Bus on RJ45; unverified.

### Sweden, Finland, Estonia, Latvia, Lithuania: prices only for now

- Sweden: quarter-hour prices from 2026 at many suppliers; zones SE1–SE4 (SE3/SE4 CC BY via
  Energi Data Service). Power tariffs per operator (see above). HAN/P1 on every meter.
- Finland and the Baltics: spot contracts are common (unverified share); Elering gives all four in
  one response, in `Europe/Helsinki`, `Europe/Tallinn`, `Europe/Riga`, `Europe/Vilnius` (all the
  same offset).

### Slovenia, Czechia, Poland, Hungary: prices only, low priority

- All four are CC BY zones on Energy-Charts. Slovenia's block tariff and AM550 meters could make it
  a peak market later (verify the 2025–2026 changes first). Czechia, Poland and Hungary: dynamic
  household uptake not checked.

### France and Italy: not a fit (unchanged from 2026-09-30)

- France: the common signal is EDF Tempo (whole-day colours, published around 11:00 the day
  before; free API `https://www.api-couleur-tempo.fr/api/jourTempo/today`), plus off-peak hours
  set per household by Enedis. A feature of its own (day colour + the user's off-peak hours), not
  a region. The `FR` zone helps only the few on a spot tariff. Linky's TIC port isn't on the
  whatwatt's list.
- Italy: households mostly pay flat prices or the F1/F2/F3 bands; `IT-North` is CC BY but is a
  market signal, not their bill.

## Time zones (done 2026-10-03, not released)

Each `Country` has a `zone`, and a `Region` takes it unless it overrides it (commit `39fb3cc`).
The selected region's zone sets the tariff day (`fetchPeriod(now, zone)`: the request's
midnights), `tomorrowFrom` (in the region's local time, `wantsFetch`) and the peak's month and
days (`missingQuarters`, `recordedSince`, `dailyHighest`, the history's "today"). Times on screen
stay in the phone's zone. So a new country needs only its zone; nothing else in the code depends
on CET.

| Country | `Country.zone` | Regions with their own zone | `tomorrowFrom` (local) |
|---|---|---|---|
| Austria | `Europe/Vienna` | — | 13:15 (spot) |
| Belgium | `Europe/Brussels` | — | 13:15 |
| Netherlands | `Europe/Amsterdam` | — | 13:15 |
| Germany | `Europe/Berlin` | — | 13:15 |
| Luxembourg | `Europe/Luxembourg` | — | 13:15 |
| Liechtenstein | `Europe/Vaduz` | — | 13:15 (zone `CH`, hourly) |
| Denmark | `Europe/Copenhagen` | — | 13:15 |
| Norway | `Europe/Oslo` | — | 13:15 |
| Sweden | `Europe/Stockholm` | — | 13:15 |
| Slovenia, Czechia, Poland, Hungary | `Europe/Ljubljana`, `Europe/Prague`, `Europe/Warsaw`, `Europe/Budapest` | — | 13:15 |
| Finland, Estonia, Latvia, Lithuania | `Europe/Helsinki`, `Europe/Tallinn`, `Europe/Riga`, `Europe/Vilnius` (EET, one hour ahead) | — | **14:15**: the same auction, ~12:55 CET = 13:55 EET |
| Spain | `Europe/Madrid` | Canaries: `Atlantic/Canary` (one hour behind) | PVPC ~21:00 in Madrid, so **20:00** on the Canaries |
| Portugal (no source yet) | `Europe/Lisbon` | Azores: `Atlantic/Azores` | — |

Notes:
- All of these zones change to and from summer time on the same EU dates, so the day-ahead day
  (midnight to midnight, local) always has 92, 96 or 100 quarters.
- Pass the source the instants from `fetchPeriod(now, region.zone)`, not dates. Energy-Charts
  reads a bare date as the bidding zone's own midnight, which happens to match here, but
  instants keep every source on the region's day.
- The Canaries are on the Iberian market, so their PVPC hours are Madrid's hours shifted by one.
  Check on a saved response that REE's `datetime` offsets are the peninsula's (`+02:00`/`+01:00`);
  the app then shows them in the phone's zone as usual.
- **The whatwatt's own clock zone** names the recorder's day files (`time.dump`). The app reads
  the quarters by UTC and only uses the names to choose which files to copy, up to "today" in the
  region's zone. A whatwatt set to a zone *ahead* of the region's (e.g. EET in Austria) would have
  each day's last quarters copied only after midnight, so check its zone in the whatwatt's web UI
  when setting it up abroad. Behind or equal is fine.

## What the code needs (as of 2026-10-03)

The current code: `Region(id, name, utility, country, pricesUrl, tomorrowFrom, zone)`,
`Country(code, name, flag, zone)`, `fetchPrices` appends the VSE `start_timestamp`/
`end_timestamp`, `parsePrices` reads the VSE JSON, `PriceSlot.price` is "CHF/kWh", and the main
screen shows "Rp/kWh" and "CHF/h" (`MainActivity.kt:306`, `:308`). Time zones are done (above).
Steps, smallest first:

1. **A price source per region.** Replace `pricesUrl` with a small sealed type, e.g.
   `VsePrices(url)`, `SpotPrices(bzn)` (Energy-Charts), later `Pvpc`, `EnergiNet(area, operator,
   chargeCode)`, `Elering(area)`. Each builds its request from `fetchPeriod(now, zone)` and parses
   into `PriceSlot`s.
   - Energy-Charts: zip `unix_seconds` and `price`; a slot ends where the next starts, the last
     after the same length; ÷1000 for per kWh. Check whether it includes the slot at `end`
     (Elering does, like the VSE APIs; the existing filter handles both).
   - Unit-test each parser with a saved response in `core/src/test/resources/`, including a DST
     day (25 Oct 2026).
   - `PriceCache` writes slots in the VSE shape with the unit `"CHF_kWh"`. It's internal, so it
     can stay, but renaming it to `"kWh"` with a fallback read is cleaner.
2. **Currency and price kind.** `Country.currency` (CHF, EUR, DKK, NOK, SEK) with its small unit
   (Rp, ct, øre, öre), and `Region.final: Boolean`. Final prices (VSE, PVPC, Denmark with its
   tariff) show as today, in the right units. Spot-only regions: hide the absolute price, or label
   it "Market price 11.3 ct/kWh", and hide the cost per hour (it would be a third of the real
   cost). The colour, the next good time and "Cheaper at" are relative and stay correct.
3. **A minimum billed peak per region** (`Region.peakFloorKw`: CKW 0, Austria 2.0, Flanders 2.5;
   null where nothing is billed). `peakLine` becomes the highest of the floor, the month's highest
   and the goal, so "kW free" counts up to the floor. Small `:core` change, unit-tested.
4. **Peak load where nothing is billed** (NL, DE, DK, …): hide the Peak load switch, or keep it
   for the goal only. A product decision (open questions).
5. **Time-varying add-ons** (Denmark first): add the operator's hourly tariff to the spot price
   before `classify`, in the same currency. For Denmark that's a second request per fetch (the
   tariff changes twice a year, so it could be cached for weeks).
6. **Norway's peak model** (later): from the recorder's quarters, each hour's kWh, each day's
   highest hour, the month's top three days, their average and its step (NVE). The line is the
   next step's lower bound; the alarm warns when this hour would push the average over it.
7. **Texts:**
   - The whatwatt guide names CKW's key address (`WhatwattGuide.kt:37`); make it "from your grid
     operator", with the CKW line only for Swiss regions.
   - The help says peak load is billed per month's highest quarter hour; per region it should say
     what's billed, or nothing.
   - Attribution in the help: "Market prices: Bundesnetzagentur | SMARD.de (CC BY 4.0)",
     "Energinet", "Red Eléctrica", "NVE", as used.
   - Store listing and README: say which layers work where.
8. **Language:** the UI is English only. For Austria, Germany, Flanders and the Netherlands that's
   a barrier; extracting the strings (an optional step in `CLAUDE.md`) would become worthwhile.
9. **Unchanged:** time zones (done), refresh policy and cooldown, the recorder script (frozen at
   v2), permissions, dependencies.

## Open questions for the user

- **whatwatt outside Switzerland:** ask whatwatt whether they ship to the EU (Austria, Belgium)
  and at what price. If not, is another meter reader acceptable for those countries? For example,
  the HomeWizard P1 meter, common in NL and BE, has a documented local API, but no SD card or
  scripts, so no recorder; in Belgium the meter's own monthly peak (1-0:1.6.0) could stand in.
  Or stay whatwatt-only and offer prices only outside Switzerland.
- **First wave:** Austria + Flanders (with peak) and the other CC BY zones (prices only), before
  1 Jan 2027?
- **Absolute price** in spot-only regions: hide it, or "Market price …"?
- **Peak load without peak billing:** hide the switch, or keep it with the goal only?
- **Wallonia:** leave it out, or offer it as spot only with a note?
- **Translations:** German first (AT, DE, LI, LU), then Dutch and French?
- **Spain** (prices only, large audience) and **Denmark** (two requests per fetch): worth it after
  the first wave?
- **Norway:** worth a second peak model, given Norgespris?
- Still open from 2026-09-30: in Germany most households are on fixed prices. Show the colour as
  a "grid is cheap now" signal for everyone, or say it pays only on a dynamic tariff?

## Sources

whatwatt
- Datasheet v16 (02/2025): https://whatwatt.ch/doc/whatwatt_Go_Datasheet_v16.pdf
- Documentation: https://documentation.whatwatt.ch
- Shop: https://whatwatt.ch/de/shop/whatwatt-go

Prices
- Energy-Charts: https://api.energy-charts.info/ (licences in `/openapi.json`)
- Energi Data Service: https://www.energidataservice.dk/terms-and-conditions
- Elering: https://dashboard.elering.ee/assets/api-doc.html
- elprisetjustnu.se: https://www.elprisetjustnu.se/elpris-api
- hvakosterstrommen.no: https://www.hvakosterstrommen.no/strompris-api
- REE REData API: https://www.ree.es/en/datos/apidata
- NVE grid tariffs: https://nettleietariffer.dataplattform.nve.no (Swagger at the old
  `biapi.nve.no/nettleietariffer/swagger/v1/swagger.json`)
- 15-minute day-ahead: https://www.epexspot.com/en/news/successful-implementation-15-minute-market-time-unit-mtu-sdac

Austria
- SNE-G-V draft: https://www.e-control.at/documents/1785851/0/V+SNE+01_26+SNE-G-V+Begutachtungsentwurf+samt+Erl%C3%A4uterungen.pdf/1425ce2f-897d-bea7-b8e6-e84f67f978ab?t=1782735970283
- Wiener Stadtwerke summary: https://positionen.wienerstadtwerke.at/aktuelles/systemnutzungsentgelte-grundsatz-verordnung-begutachtung
- https://www.checkeverything.at/en/blog/electricity-grid-fees-austria-2027
- https://www.bestconnect.info/blog/leistungstarif-2027-oesterreich/
- SNAP (2026): https://www.wko.at/noe/wirtschaft/netzentgelte-neue-tarifelemente-fuer-2026
- Meters per grid: https://smartmeteradapter.at/de/kompatibilitaet,
  https://www.michaelreitbauer.at/kundenschnittstelle-der-osterreichischen-smart-meter/
- Suppliers: https://www.smartmeter-portal.at/dynamischer-stromtarif/anbieter-vergleich/

Belgium
- Fluvius capacity tariff: https://www.fluvius.be/nl/factuur-en-tarieven/capaciteitstarief/gezinnen-en-kleine-ondernemingen/aangerekend
- VREG: https://www.vreg.be/en/faq/capaciteitstarief
- P1 port and 1.6.0: https://partner.fluvius.be/nl/technische-documenten/gebruikerspoort-specificaties
- Dynamic suppliers: https://callmepower.be/nl/energie/gids/begrijpen/energiecontract/dynamisch
- Wallonia 2026: https://www.wallonie.be/fr/actualites/le-1er-janvier-2026-le-bihoraire-change-dhoraire,
  https://www.cwape.be/publications/document/6649

Netherlands
- ACM proposal: https://www.acm.nl/nl/publicaties/voorstel-codewijziging-volume-en-tijdsafhankelijke-transporttarieven-voor-kleinverbruikers
- Dynamic contracts and saldering: https://eerlijkverbruik.nl/blog/salderen-of-dynamisch-contract-zonnepanelen-2026/

Nordics
- Sweden, mandate paused: https://www.regeringen.se/pressmeddelanden/2026/03/krav-pa-inforande-av-effektavgifter-stoppas/
- Sweden, meter interface: https://konsumentforum.ei.se/org/energimarknadsinspektionen/d/funktionskrav-for-elmatare-1-jan-2025/
- Norway, kapasitetsledd: https://lede.no/ofte-stilte-sporsmal/hvordan-beregnes-kapasitetsleddet
- Norway, Norgespris: https://www.regnestykket.no/norgespris,
  https://www.regjeringen.no/no/aktuelt/39-milliarder-kroner-spart-med-norgespris-i-andre-kvartal-2026/id3172989/
- HAN ports per country: https://github.com/ArnieO/SmartMeterDocumentation

Others
- Slovenia: https://www.mdpi.com/1996-1073/19/2/567, https://sloveniatimes.com/42421/parliament-intervenes-in-electricity-network-charges
- Spain PVPC: https://fsr.eui.eu/the-spanish-experience-with-dynamic-tariffs/
- Germany §41a EnWG: https://checkalle.de/dynamic-electricity-tariffs-2026/
- Germany §14a Module 3: https://energiemarie.de/strompreisvergleich/ratgeber/14a-enwg/modul-3
- Liechtenstein, LKW: https://www.lkw.li/angebot-und-leistungen/strom-und-waerme/stromtarife.html
- France, Tempo: https://www.api-couleur-tempo.fr/ ; off-peak reform: https://www.enedis.fr/presse/enedis-met-en-oeuvre-la-reforme-des-heures-creuses-pour-accompagner-les-nouveaux-usages-lies
- Italy, time bands: https://selectra.net/energia/guida/contatore/luce/fasce-orarie
