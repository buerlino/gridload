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

The colour depends on the dynamic tariff of the electricity utility that supplies your grid, so the app has a **region switcher** at the top of the screen.

| Region | Utility | Data source |
|--------|---------|-------------|
| Central Switzerland (Zentralschweiz) | CKW (Centralschweizerische Kraftwerke AG) | `https://e-ckw-public-data.de-c1.eu1.cloudhub.io/api/v1/netzinformationen/energie/dynamische-preise` |

CKW is the only region for now. It serves only Central Switzerland, so outside that area the colour says nothing about your tariff. More regions (other utilities) are planned.

## Later ideas

- Users add their appliances and get a recommended start time.
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
