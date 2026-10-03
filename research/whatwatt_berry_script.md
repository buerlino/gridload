# The GridLoad recorder on the whatwatt (Berry), and why not SD downloads (2026-10-02)

Tested on the user's whatwatt Go (`WW_Go_1.3`, firmware 2.8.2, Plus licence, Kamstrup over KMP,
meter power only) on 2026-10-02. It started as desk research by another session (Sonnet), whose
open points are answered below. The decision is in `CLAUDE.md` ("Recording while away"); the
open tasks are in the skill.

## The requirement

The typical use (user): open the app, look for 10 s, know what to do. So when the app opens, the
month's highest quarter hour and the current quarter's start must already be known, within a
second. Gaps from the time the app was closed can only make the line too low (a missed quarter
can only hide a higher peak), so an incomplete month never costs money, but it gives needless
warnings.

## Why not download the SD-card CSV log on open

Measured from the PC on home Wi-Fi:

- `GET /sdcard/YYYYMMDD.CSV` serves the whole day only: a `Range` header is ignored (200, the
  full file), and the docs list no time filter. Today's file has to be downloaded whole each time.
- Speed: ~12.7 KB/s (258 KB in 20 s). A row is ~250 bytes, so a day is ~1.4 MB at 15 s and
  ~720 KB at 30 s.
- **It drains the supercap.** `device.plug.v_scap` in `/api/v1/system` went from 3.76 V to 3.2 V
  over one 265 KB download at full speed. The second download right after stalled and the
  whatwatt rebooted (twice: 22:35 and 22:45). After a reboot it was back to ~3.71 V at once,
  then rose slowly (~0.04 V in 2 min).
- Throttled by the client (`curl --limit-rate`), it holds: 4 KB/s (267 KB in 65 s) and 8 KB/s
  (two files back to back, 538 KB in 65 s) kept 3.70–3.75 V. At 6 KB/s with `/api/v1/report`
  polled every 5 s alongside, all 8 reports answered (0.2–0.5 s) and the voltage stayed at
  3.71–3.73 V. So concurrent reading is fine; the speed is what matters.
- A day at 30 s at a safe 6 KB/s takes ~2 min, which a 10-second look never finishes, and there's
  no resuming. Hence the recorder on the device instead. Should a CSV download ever be needed
  (e.g. the background-job fallback), read at ≤ 6 KB/s. On Android, check that slow reads really
  slow the sender: the kernel's receive buffer may take a burst first.
- `RID` restarted without a reboot (3834 → 1 at 21:48), so order rows by `TIME`, not `RID`.

## Berry on the whatwatt: what was tested

Each test was a throwaway script: `POST /api/v1/berry`, `PUT /api/v1/berry?run=true`, output read
from `/api/v1/berry/console` (SSE; connect before starting, since it streams live only and allows
one client), then stopped and deleted. The slot was empty before (`GET` → 404), and it was left
that way, with `auto_run` off.

| Question | Result |
|---|---|
| Can a script write files? | **Yes:** `open('/sdcard/GLTEST.TXT', 'w')`, write, close, read back. `'a'` appends. `/sd/...` and relative paths fail (`io_error`). |
| Can the app read them? | **Yes:** listed by `GET /sdcard/` and served by `GET /sdcard/GLTEST.TXT`, like the CSV logs. `DELETE /sdcard/<name>` removes it (204). |
| Which clock does `ww.onreport` carry? | `r['timestamp']` (int, UTC epoch seconds) is the **meter's clock**: equal to `report.date_time_utc` from `ww.apiget('report')`. `r['date_time']` is local time with a `Z` (the known quirk). The whatwatt's own clock (`system.date_time_utc`) was 11 s ahead. |
| Precision | Reals are double (`1.0 + 1e-10 != 1.0`), ints 64-bit. The register comes through exactly (`10529.5040`). `str()` prints only 6 significant digits (`10529.5`), so format with `string.format`. |
| Modules | `json`, `time`, `string`, `math`, `gc` (8 KB allocated for a small script) exist; `path` doesn't. `persist` (Tasmota) wasn't tried: files on the SD card survive reboots, which was its purpose. |
| `time.dump(epoch)` | Local time (the device's timezone, +2 on 2026-10-02): `time.dump(1790975100)` → 23:05 for 21:05 UTC. Used for the day files' names. |
| Event rate | One `onreport` per meter reading, every ~4.2 s (ids 275, 276, 277 at :31, :35, :39). |

From the docs (and Sonnet's notes): the script may be at most 8191 bytes; up to 10 timers;
`ww.httpreq`, `ww.apiget`/`apiset`/`apidel` and `ww.modreq` block the interpreter until done;
auto-start is `services.berry.auto_run` with `run_delay` **60 to 86400 s** (default 300),
persists across reboots and firmware updates; `DELETE /api/v1/berry` deletes the script but
doesn't stop it (stop with `run=false` first). `ww.httpreq` could push each finished quarter to
a phone, but the SD files make that unnecessary. Not known yet: whether Berry needs the Plus
licence. Firmware: whatwatt's docs date Berry from 2.0.0, and `/api/v1/system` on 2.0.0 already had
`services.berry.execution_status`; the script is tested only on 2.8.2 (an earlier note that 2.0.0
doesn't run Berry was from memory, 2026-10-03).

## The draft script

Installed for a live test at 23:08 on 2026-10-02 (no auto-start). 1.6 KB.

```berry
# GridLoad recorder 1
# Records the meter's quarter hours (:00, :15, :30, :45) on the SD card for the GridLoad app.
# One file per local day, /sdcard/GLyymmdd.CSV, one line per quarter: its start (UTC, epoch
# seconds), the kWh drawn in it and the import register at its end. The register at each
# boundary is interpolated between the readings either side, by the meter's clock.
import ww
import string
import time

gl_t = nil   # the last reading: meter time and register
gl_e = nil
gl_qt = nil  # the current quarter's start and the register there, once a boundary was seen
gl_qe = nil

def gl_write(start, kwh, e)
  var d = time.dump(start)
  var name = string.format('/sdcard/GL%02d%02d%02d.CSV', d['year'] % 100, d['month'], d['day'])
  try
    var f = open(name, 'a')
    f.write(string.format('%d,%.4f,%.4f\n', start, kwh, e))
    f.close()
  except .. as err, msg
    print('write failed: ' + str(msg))
  end
end

def gl_report(r)
  var t = r['timestamp']
  var e = r['energy']['active']['positive']['total']
  if t == nil || e == nil || e <= 0 return end
  if gl_t != nil && t <= gl_t return end
  var pt = gl_t
  var pe = gl_e
  gl_t = t
  gl_e = e
  if pt == nil return end
  if e < pe gl_qt = nil return end
  var b = (pt / 900 + 1) * 900
  if b > t return end
  if t - pt > 30 gl_qt = nil return end
  var eb = pe + (e - pe) * real(b - pt) / real(t - pt)
  if gl_qt != nil && gl_qt == b - 900 gl_write(gl_qt, eb - gl_qe, eb) end
  gl_qt = b
  gl_qe = eb
end

ww.onreport('gl_report')
```

(The test version also printed each quarter to the console.) Same rules as v0.7's recording in the app: a
quarter needs both boundaries, with readings at most 30 s apart; a register going backwards starts
over; repeated readings are ignored. After a reboot, the quarter under way and the one in which the
script starts (`run_delay` 60 s) are lost.

The day file: `GL261002.CSV` (an 8.3 name, to be safe on FAT), no header, lines like
`1790975700,0.0523,10529.5040`: about 28 bytes, 96 lines, ~2.7 KB a day, so fetching it takes
~0.2 s. The UTC epoch keys make the DST night need nothing special: its quarters just land in the
local day's file.

## Live test against the CSV log

The script started at 23:08:31. The 23:15 boundary started the first quarter, and at 23:30 it
wrote `GL261002.CSV` (29 bytes, listed and served at once):

```
1790975700,0.2132,10529.8292
```

The CSV log (30 s setting, rows actually ~33.5 s apart, every 8th reading), interpolated the same
way: 10529.6157 at 23:15:00 (between 23:14:47 = 10529.613 and 23:15:21 = 10529.620) and
10529.8294 at 23:30:00 (between 23:29:51 = 10529.827 and 23:30:25 = 10529.836), so 0.2137 kWh.
The recorder: 0.2132 kWh, end register 10529.8292. **0.0005 kWh apart (0.002 kW), within the
register's 0.001 kWh steps**, and the recorder's readings are 8× closer to the boundary than the
log's. No reboot during the test (`last_reboot` still 22:45:41, `v_scap` 3.76 V).

At 23:44 the draft above (without the test's console output) replaced it, with auto-start on
(`auto_run: true`, `run_delay: 60`, user's go-ahead), as the start of the day-long validation in
the skill; the CSV log keeps running at 30 s as the reference. The restart lost the 23:30
quarter; the first line after it is the 23:45 quarter, written at 00:00 into `GL261002.CSV`, not
a new day's file: a line goes into the file of its own time's local date (the quarter's start).

## Version 2 (2026-10-03, the one the app ships)

`core/src/main/resources/gridload_recorder.be`. Changes from the draft above: the first line is the
version marker `# GridLoad recorder 2` (the app updates older ones of its own and never touches a
script without the marker); a line `<time>,start` at each start of the script (its first
reading), so the app can tell a restart from a failure and when the first quarter is due; the
report's fields are read with `map.find` (a report without `energy` can't raise an error); a failed
write is ignored silently (no `print`). Found on the device on 2026-10-03:

- A day file that doesn't exist answers **500** (`text/plain`), not 404.
- The listed `size` of a `GL*.CSV` was right at once (58 bytes after the second line); the CSV
  log's lagged because the firmware keeps it open.
- `/api/v1/system` reports `services.berry.execution_status.state` (`"RUNNING"`, `"IDLE"` after
  `run=false` and after `DELETE`, seen 2026-10-03) and `sd_card`
  (`installed`, `type` `"SDHC/SDXC"`, `size` 488960, `speed`); no free space. The docs' OpenAPI
  file lists no other states.
- `PUT /api/v1/berry` with no query answers `{"run":true}`.


## Overnight validation (2026-10-02 23:44 to 2026-10-03 07:45)

v1 ran from 23:44, and the app replaced it with v2 at 00:18:29 (its first `start` line). Every
recorder quarter was compared with the CSV log (30 s setting, downloaded at 6 KB/s), its register
interpolated at the boundaries the same way:

- **32 quarters, all within 0.0012 kWh (0.005 kW), mean difference +0.00001 kWh**; 26 of them
  within 0.0005. The end registers agree to 0.0006 kWh. The CSV log's rows are ~33 s apart (gaps up
  to 204 s), so most of the difference is the reference's.
- Missing: only 23:30 (the v1 install at 23:44) and 00:15 (the v2 update at 00:18), as expected
  after a restart: the quarter in which the script starts is lost. Nothing else is missing.
- No reboot all night (`last_reboot` still 2026-10-02 22:45:41, 9.1 h up), `v_scap` 3.76–3.80 V.
- The meter stayed `OK` all night, so what `onreport` delivers without a meter reading is still
  untested; the script ignores a report without a register either way.
- The listed `size` of the open CSV log lagged (17 284 bytes listed, 208 027 downloaded).

Then the CSV log was turned off (`services.sd.enable: false`, 07:52, user's go-ahead from
2026-10-02). The recorder keeps running. The old logs `20261002.CSV` and `20261003.CSV` were then
deleted (`DELETE /sdcard/<name>`, 204; user's go-ahead), so only the `GL*.CSV` day files are left.
The web UI's SD logging switch is the same `services.sd.enable`, so it shows off too; `services.sd.recorder_mode`
is the whatwatt's own option, unrelated to the GridLoad recorder, and stays off.
