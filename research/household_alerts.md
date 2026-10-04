# Household alerts (researched 2026-10-04)

Telling the rest of the household when a quarter hour is heading for a new peak, so nobody
switches on another big appliance until it ends. Researched with the user on 2026-10-04.
**Decision (user, 2026-10-04):** (a) a share link only. (b) ntfy was planned as an alternative
and dropped the same day: GridLoad sends only while it's open on some phone, which makes an
automatic alert useless (the user: "if the app has to be open then it will be useless"). Kept
below as the record of why. (a) is built (2026-10-04, commit `0c85de7`), not released; the design
is in `CLAUDE.md` (the peak window), the status in the skill ("Household alerts").

## What it can and can't do

- **Late by nature.** The billed peak is one quarter hour, and the projection only crosses the
  limit some minutes into it. An alert helps with the *next* appliance ("don't start the
  dishwasher now"), less with the one already running.
- **No cause.** The meter sees the household's total. The message can say "3.1 kW this quarter
  hour, limit 2.6, wait until 12:15", not "oven + dishwasher".
- **Small money.** CKW Home dynamic: 1.08 CHF per kW and month incl. VAT. September 2026's peak
  was one quarter hour, 5.4 kW against 4.5 kW for the next highest: about 1 CHF. Worth it as a
  habit, not as a big saving, so it must stay quiet (once per quarter hour at most).
- **Sending needs GridLoad open somewhere.** GridLoad reads the whatwatt only while it's open
  (no background work). So with (a) and (b) the alert goes out when the warning starts on a
  phone that has GridLoad open. With (b), the *receivers* get it with GridLoad closed, since the
  ntfy app delivers it. Alerting with every GridLoad closed needs a background sender, (b2).

## Messengers

| | Sending automatically | Verdict |
|---|---|---|
| WhatsApp | No API for personal accounts. Unofficial libraries (Baileys, whatsmeow) break the terms; bans are reported even at low volume. The Cloud API's Groups API needs an Official Business Account, max 8 participants who join by invite link, paid per message, templates. | Share sheet only |
| Signal | No bot API. signal-cli (GPL, unofficial) needs its own phone number; the terms forbid "auto-messaging". Too heavy to embed (libsignal native libraries). | Share sheet only (or signal-cli on the Pi, outside GridLoad) |
| Telegram | Official Bot API, free, one HTTPS POST to a group's chat id. Proprietary server, bot messages not end-to-end encrypted. | Possible later as another target; not planned |
| Matrix | Open, self-hostable, one HTTPS PUT per message. Everyone needs a Matrix client. | Not planned |
| ntfy | Free software (Apache 2.0 / GPLv2), app on F-Droid, Play and the App Store; one HTTP POST to a topic; self-hostable. | Dropped with (b); worth it only with a background sender (b2) |
| Share sheet | `ACTION_SEND` with the text: the user picks the family chat and taps Send. Any messenger; can't preselect a group. | **(a)** |

## Open source and F-Droid

- GPLv3: calling an HTTP API is fine; no SDK is needed (`HttpURLConnection`, as for the prices).
- F-Droid's NonFreeNet and Tethered Network Services anti-features are for apps that depend
  *entirely* on such a service, and Tethered doesn't apply when the app can be pointed at a
  self-hosted server. (a) uses no service; (b) is optional and points at the user's own ntfy
  server. Neither should add an anti-feature.
- No new permission for (a) or (b): `INTERNET` is there, and the Pi is on the LAN, which
  `ACCESS_LOCAL_NETWORK` (already declared for the whatwatt) covers on Android 17.
- Unofficial WhatsApp or Signal clients inside GridLoad: no (terms, bans, F-Droid).

## (a) Share button (built)

- While the peak window's warning shows, a **Share** button right of it (not a link below it, so
  the panel keeps its height) opens Android's share sheet (`Intent.createChooser` with
  `ACTION_SEND`, `text/plain`; `TellHousehold` in `PeakWindow.kt`).
- The text: the projection, the limit and the quarter's end, in the power unit, the time in the
  phone's zone; the wording is in the code.
- Visible collapsed too, like the warning. No setting, no permission, no state.

## (b) ntfy on the user's server (dropped 2026-10-04)

Dropped: it sends only while GridLoad is open on some phone. The design, in case (b2) is ever
built:

- **Setup (outside the app):** ntfy server on the Pi, e.g. `http://192.168.0.x/gridload-home`.
  Everyone installs the ntfy app and subscribes to that topic on that server. Android's ntfy app
  keeps its own connection ("instant delivery"), so it rings with GridLoad closed. iPhones can't
  keep a connection in the background: the server needs `upstream-base-url: "https://ntfy.sh"`,
  which sends ntfy.sh only a poll request with the message id (not the text), and ntfy.sh wakes
  the iPhone through Apple; the iPhone then fetches the text from the Pi, so it must reach the
  Pi (home Wi-Fi, or Tailscale away). The Pi needs internet access for that.
- **Settings:** under Mode, with peak load on: a **Household alert** switch and the topic's URL
  (one field, like the whatwatt's address; it stays saved while off), with **Test**, which
  sends a test message and shows the result. ⓘ explains it in a few lines; the setup of the
  server is in the README, not in the app.
- **Sending:** when the in-app alarm fires (`warnIfClose`, once per quarter hour), POST the same
  text as (a) to the topic URL: UTF-8 body, ASCII headers only (`X-Title: GridLoad`,
  `X-Priority: high`, `X-Tags: zap`, which ntfy shows as ⚡). Non-ASCII headers would need
  RFC 2047, so the emoji goes in the tag.
- **One message per quarter hour across phones:** before sending, poll the topic,
  `GET <url>/json?poll=1&since=<quarter start, unix s>&tags=zap`; if it returns a message, a
  phone already sent it, so skip. Two phones within the same second may both send: accepted.
  The Test message carries no `zap` tag, so it doesn't suppress a real alert. Not ntfy's
  sequence ids (newer, not needed).
- **With (b) on:** after a successful send (or when another phone already sent), the share link
  makes way for a muted "Household notified." On failure, the share link stays, so (a) is the
  fallback. Settle with the user when building.
- The sending phone's own ntfy app rings too. Accepted.
- Saved: `household_alert_enabled`, `household_alert_url` (names to confirm).

## (b2) Background sender (later, phase 5)

To alert while no GridLoad is open, one phone (a "hub", e.g. an old phone plugged in at home)
must read the whatwatt in the background: a foreground service of type `specialUse` (`dataSync`
is capped at 6 h a day from Android 15; `connectedDevice` needs a network-change permission that
doesn't fit), a permanent notification, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_SPECIAL_USE`, probably `RECEIVE_BOOT_COMPLETED`, and local network access in
the background (untested on Android 17). It's phase 5's background alarm with an ntfy POST, and
breaks "no background work", so it's the user's decision. Alternative: a script on the Pi
polling `/api/v1/report` (the whatwatt handles several readers), but the limit (floor, goal,
month's highest) lives in the app and would have to be copied. Not planned.

## Sources

- Meta, Cloud API Groups: https://developers.facebook.com/docs/whatsapp/cloud-api/groups/
- whatsmeow ban warnings: https://github.com/tulir/whatsmeow/issues/810
- Signal terms: https://signal.org/legal/ ; no bot API: https://github.com/signalapp/libsignal/issues/694
- Telegram Bot API: https://core.telegram.org/bots/api
- ntfy publishing: https://docs.ntfy.sh/publish/ ; polling and filters:
  https://docs.ntfy.sh/subscribe/api/ ; iOS and `upstream-base-url`: https://docs.ntfy.sh/config/
- F-Droid anti-features: https://f-droid.org/docs/Anti-Features/
- Android foreground service types and timeouts:
  https://developer.android.com/develop/background-work/services/fgs/service-types ,
  https://developer.android.com/develop/background-work/services/fgs/timeout
