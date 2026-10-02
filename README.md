# GridLoad

A smart energy planner that tells you the best times to run home appliances (washing machine, dishwasher, EV charger, ...) based on real-time and forecast electricity prices.

## Purpose

Shift everyday loads to the grid's most desirable time, e.g. noon when PV production peaks. This reduces demand at peak hours (evening), stabilizes the grid, lowers overall costs borne by society, and cuts CO2 emissions.

**Audience:** households who want to use energy effectively and reduce CO2 now; grid operators who want better grid stability.

## Current scope (MVP)

One field showing a single traffic-light status:

| Colour | Meaning |
|--------|---------|
| Red    | Bad time: electricity is expensive and busy, wait if you can |
| Orange | Fair time: average price, only run what you need |
| Green  | Good time: cheap, run your appliances now |

The price now is compared with the next 24 hours (until tomorrow's prices come out, between noon and 18:00 depending on the utility, with today), and the screen shows when the next good time starts.

The colour depends on the dynamic tariff of the electricity utility that supplies your grid, so the app has a **region switcher** at the top of the screen.

| Region | Utility | Data source |
|--------|---------|-------------|
| Central Switzerland (Zentralschweiz) | CKW (Centralschweizerische Kraftwerke AG), Home Dynamic | `https://e-ckw-public-data.de-c1.eu1.cloudhub.io/api/v1/netzinformationen/energie/dynamische-preise` |
| Canton of Zurich | EKZ (Elektrizitätswerke des Kantons Zürich), Energie Dynamisch + Netz 400D | `https://api.tariffs.ekz.ch/v1/tariffs` |
| Einsiedeln | EKZ Einsiedeln, Energie Dynamisch + Netz 400D | `https://api.tariffs.ekz.ch/v1/tariffs` |
| Fribourg and Neuchâtel | Groupe E, Vario | `https://api.tariffs.groupe-e.ch/v2/tariffs` |
| Northwestern Switzerland | Primeo Energie, NetzDynamisch | `https://tarife.primeo-energie.ch/api/v1/tariffs` |
| Olten area | AVAG (Aare Versorgungs AG), NetzDynamisch | `https://tarife.primeo-energie.ch/api/v1/tariffs` |
| Gretzenbach | ELAG (Elektra Gretzenbach AG), NetzDynamisch | `https://tarife.primeo-energie.ch/api/v1/tariffs` |

All of them follow the VSE/AES standard for dynamic tariffs. Each utility serves only its own grid area, and the colour follows its dynamic tariff, so outside those areas it says nothing about your price.

## Measurement

With a whatwatt Go on the smart meter (home Wi-Fi, Plus licence), switched on in Settings under Measurement, the screen also shows what the home draws right now and what that costs per hour.

**Peak load** (a switch under Measurement, needs the whatwatt): some tariffs also charge for the month's highest 15-minute average draw (CKW Home dynamic: 1.00 CHF per kW and month). Below the price, a scale shows this quarter hour's projected draw, the two before, the month's highest and an optional goal, and how many kW are still free. Close to a new monthly peak the bar turns red and the phone vibrates. The app reads the whatwatt only while it's open, so the month's highest is the highest it has seen.

## Later ideas

- A recommended start time per appliance.
- Optimize the start time by minimizing the integral of emissions (or cost) over the appliance's runtime: `P(t)` (appliance power demand) x `CI(t)` (forecast carbon intensity) over the cycle duration `d`. Carbon Intensity `CI = gCO2e / kWh`.
- Standard appliance profiles:

| Appliance       | Avg. kW   | Flexibility |
|-----------------|-----------|-------------|
| EV Charger      | 7.0       | High        |
| Dishwasher      | 1.2       | High        |
| Washing Machine | 0.5 - 2.0 | High        |
| HVAC / AC       | 3.5       | Moderate    |

- Carbon-intensity data could come from Electricity Maps or the National Grid Carbon Intensity API.

## Tech stack

Native Android app (Kotlin + Jetpack Compose), distributed via F-Droid and Obtainium.

## License

See [LICENSE](LICENSE).
