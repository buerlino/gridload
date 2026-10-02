# Remote access to the whatwatt over Tailscale (verified 2026-10-02)

The whatwatt's REST API is on the home LAN only (`192.168.0.36`), and GridLoad has no
background service — it reads the whatwatt only while open, on whatever network the phone is
on. Tailscale subnet routing lets GridLoad reach the whatwatt from outside the home Wi-Fi too,
without any app change.

## Setup

- A Raspberry Pi on the home network runs Tailscale as a subnet router, advertising just the
  whatwatt's address as a /32: `192.168.0.36/32`.
- The route is approved for the tailnet in the Tailscale admin console
  (login.tailscale.com/admin/machines → the Pi → Subnet routes). Advertising alone isn't enough;
  it must show **Approved**.
- The phone has the Tailscale app installed and signed into the same tailnet. No extra setting
  is needed on the client: once a route is approved server-side, it shows up automatically.
- The Pi needs IP forwarding on (`net.ipv4.ip_forward=1`), or an approved route still won't
  forward traffic.

## Verification (phone connected over USB/adb, Wi-Fi off)

Checked directly on the device, not just assumed from the admin console:

1. **Wi-Fi actually off:** `adb shell settings get global wifi_on` → `0`.
2. **Route accepted by the phone:** `adb shell ip route` lists `192.168.0.36 dev tun0` next to
   the Tailscale `tun0` interface — the subnet route is live on the client, not just approved
   server-side.
3. **Reachable over the tunnel, on mobile data:**
   - `ping 192.168.0.36` → 3/3, RTT 143–374 ms (likely a DERP relay rather than a direct peer
     connection, but well inside GridLoad's 3 s read timeout).
   - `curl http://192.168.0.36/api/v1/report` → `200 OK` with a real reading.
4. **GridLoad itself:** already showing a live reading on the main screen ("0.8 kW now ·
   0.18 CHF/h", a live peak-load bar) with Wi-Fi off, before any manual action. Settings →
   whatwatt showed "192.168.0.36 · connected", and tapping **Test** succeeded (the block
   collapsed immediately, which only happens on success).

## How to tell routing, Tailscale and app problems apart

If "Test connection" ever fails while away from home, check in this order:
1. `adb shell ip route | grep <whatwatt-ip>` — if the route isn't listed, it's Tailscale
   (route not approved, or the Pi isn't forwarding), not GridLoad.
2. `curl` the whatwatt's `/api/v1/report` directly from the phone (adb shell or browser). If
   that fails too, it's still network-level, not the app.
3. If curl works but GridLoad doesn't: check the `ACCESS_LOCAL_NETWORK` permission is granted
   (Android gates connections to private/RFC1918 destination addresses by IP, regardless of
   whether the real interface is Wi-Fi or a Tailscale tun0), and consider the 3 s timeout if the
   path is relayed and slow.

No code changes were needed for this — GridLoad already works unmodified as long as the phone
can route to the whatwatt's address somehow.
