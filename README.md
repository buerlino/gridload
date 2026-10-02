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

**Peak load** (a switch under Measurement, needs the whatwatt): some tariffs also charge for the month's highest 15-minute average draw (CKW Home dynamic: 1.00 CHF per kW and month). Below the price, a scale shows this quarter hour's projected draw, the two before, the month's highest and an optional goal, and how many kW are still free. Close to a new monthly peak the bar turns red and the phone vibrates.

The quarter hours come from the **GridLoad recorder**, a small Berry script that the app installs on the whatwatt (Settings → Peak load → Recorder → Install). It records every quarter hour on the whatwatt's SD card around the clock, also while the app is closed or the phone is away, and the app copies the new ones when it opens. There is no fallback: if the recorder stops or misses quarter hours, the app says exactly what is wrong (no SD card, not installed, another script in the slot, stopped, nothing saved since a time, quarter hours missing this month).

### What you need

| | What | Price (CHF, 2026) |
|---|---|---|
| 1 | [whatwatt Go](https://whatwatt.ch) on your smart meter | 90 |
| 2 | The whatwatt adapter for your meter (e.g. Kamstrup OMNIPOWER) | 20 |
| 3 | whatwatt **Plus** licence (on the device, no account needed) | 19 |
| 4 | Your meter's encryption key from your utility (CKW: email messtechnik@ckw.ch with the meter number) | free |
| 5 | For peak load: a microSD card in the whatwatt. Any size: the recorder uses about 1 MB a year | a few |
| 6 | For peak load: the whatwatt's one Berry script slot free (GridLoad never replaces another script) | |
| 7 | The whatwatt on your home Wi-Fi (2.4 GHz), with a fixed address (DHCP reservation) and Device Protection off | |
| 8 | Your phone on the same network (at home, or over a VPN such as Tailscale) to see live values | |

Tested with a whatwatt Go `WW_Go_1.3`, firmware 2.8.2, on a Kamstrup OMNIPOWER at CKW.

## Later ideas

- Colour by the grid's carbon intensity (gCO2e/kWh) instead of, or next to, the price. The data could come from Electricity Maps or the National Grid Carbon Intensity API.

## Tech stack

Native Android app (Kotlin + Jetpack Compose), distributed via F-Droid and Obtainium.

## Building

Needs JDK 21 (Gradle picks it through `gradle/gradle-daemon-jvm.properties`) and the Android SDK with platform 37.

```sh
ANDROID_HOME=~/Android/Sdk ./gradlew :core:test :app:assembleDebug
```

`./gradlew :app:assembleRelease` gives an unsigned, R8-shrunk release APK; signing is described in [CLAUDE.md](CLAUDE.md).

## License

See [LICENSE](LICENSE).
