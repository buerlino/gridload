#!/usr/bin/env python3
"""Polls the whatwatt's /api/v1/report every 5 s (the app's own rate) on the given evenings and
writes one CSV per day: the PC's UTC time, report.id, the meter's time, the power (kW) and the
register (kWh). Nothing is sent to the whatwatt and none of its settings change.

    capture.py ADDRESS OUT_DIR 2026-10-06,2026-10-07,2026-10-08 [17:00] [21:00]

The times are the PC's local time. Start it detached (setsid nohup ...); OUT_DIR/capture.log
says when each evening starts and stops.
"""
import datetime as dt
import json
import os
import sys
import time
import urllib.request

address, out_dir, days = sys.argv[1], sys.argv[2], sys.argv[3].split(',')
start, stop = (sys.argv[4] if len(sys.argv) > 4 else '17:00'), (sys.argv[5] if len(sys.argv) > 5 else '21:00')
os.makedirs(out_dir, exist_ok=True)


def log(text):
    with open(os.path.join(out_dir, 'capture.log'), 'a') as f:
        f.write(f'{dt.datetime.now().isoformat(timespec="seconds")} {text}\n')


def at(day, hhmm):
    return dt.datetime.fromisoformat(f'{day}T{hhmm}:00').timestamp()


log(f'scheduled {days} {start}-{stop}')
for day in days:
    begin, end = at(day, start), at(day, stop)
    if time.time() >= end:
        continue
    # In steps, by the wall clock: a single long sleep stops counting while the PC is suspended.
    while time.time() < begin:
        time.sleep(min(60.0, begin - time.time()))
    log(f'{day} start')
    path = os.path.join(out_dir, f'report-{day}.csv')
    with open(path, 'a') as out:
        if out.tell() == 0:
            out.write('pc_utc,id,meter_utc,kw,kwh,status\n')
        while time.time() < end:
            t0 = time.time()
            now = dt.datetime.now(dt.timezone.utc).isoformat(timespec='milliseconds')
            try:
                r = json.load(urllib.request.urlopen(f'http://{address}/api/v1/report', timeout=3))
                rep = r['report']
                out.write(f"{now},{rep['id']},{rep['date_time_utc']},{rep['instantaneous_power']['active']['positive']['total']},"
                          f"{rep['energy']['active']['positive']['total']},{r['meter']['status']}\n")
            except Exception as e:
                out.write(f'{now},ERR,{type(e).__name__}\n')
            out.flush()
            time.sleep(max(0.0, 5 - (time.time() - t0)))
    log(f'{day} stop')
log('done')
