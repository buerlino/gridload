"""Scores the colour on a month of CKW and market prices, as CLAUDE.md (Classification) did for CKW.

For every quarter hour of September 2026 (local time), the colour is what `classify` shows with
the data the app has then (today only, and today and tomorrow from 13:15 local for the market
prices, from 12:00 for CKW), and the saving is the price now minus the cheapest price in the
next 24 hours, i.e. what waiting up to 24 h would really have saved. Market prices in ct/kWh
without add-on or VAT; CKW's Home dynamic in Rp/kWh, the `integrated` price as the app shows it.

    python3 research/energy_charts/score.py
"""
import json
from datetime import datetime, time, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

ZONE = ZoneInfo("Europe/Zurich")  # CH, AT and DE-LU are all on CET/CEST
DIR = Path(__file__).parent
FROM = datetime(2026, 9, 1, tzinfo=ZONE)
TO = datetime(2026, 10, 1, tzinfo=ZONE)
DAY = timedelta(hours=24)


def load(name):
    """Energy-Charts' EUR/MWh as ct/kWh."""
    data = json.loads((DIR / name).read_text())
    return [(datetime.fromtimestamp(s, timezone.utc), p / 10) for s, p in zip(data["unix_seconds"], data["price"]) if p is not None]


def load_vse(name):
    """A VSE/AES answer's `integrated` CHF/kWh as Rp/kWh."""
    data = json.loads((DIR / name).read_text())
    return [(datetime.fromisoformat(p["start_timestamp"].replace("Z", "+00:00")), p["integrated"][0]["value"] * 100) for p in data["prices"]]


def known_end(now, tomorrow_from):
    """The end of the data the app has at [now]: tomorrow's prices from [tomorrow_from] local."""
    local = now.astimezone(ZONE)
    midnight = datetime.combine(local.date(), datetime.min.time(), ZONE)
    days = 2 if local.time() >= tomorrow_from else 1
    return (midnight + timedelta(days=days)).astimezone(timezone.utc)


def level(price, lo, hi, top):
    """GREEN, ORANGE or RED as in `classify`, with [top] in place of the window's max."""
    rng = top - lo
    if rng <= 0:
        return "ORANGE"
    if price <= lo + rng / 3:
        return "GREEN"
    if price >= lo + 2 * rng / 3:
        return "RED"
    return "ORANGE"


def percentile(values, q):
    s = sorted(values)
    return s[min(len(s) - 1, int(q * len(s)))]


def score(slots, tomorrow_from, top_of):
    starts = [t for t, _ in slots]
    out = []
    for i, (now, price) in enumerate(slots):
        if not FROM <= now < TO:
            continue
        end = known_end(now, tomorrow_from)
        last = max(t for t in starts if t < end) + timedelta(minutes=15)
        win_start = min(now, last - DAY)
        window = [p for t, p in slots if win_start <= t < win_start + DAY and t < end]
        lvl = level(price, min(window), max(window), top_of(window))
        future = [p for t, p in slots if now <= t < now + DAY]
        out.append((lvl, price - min(future)))
    return out


def report(name, scored):
    n = len(scored)
    print(f"  {name}: {n} quarters")
    for lvl in ("GREEN", "ORANGE", "RED"):
        s = [x for l, x in scored if l == lvl]
        if not s:
            continue
        extra = ""
        if lvl == "GREEN":
            extra = f", missed > 5: {sum(x > 5 for x in s) / len(s):.1%}, > 2: {sum(x > 2 for x in s) / len(s):.1%}"
        if lvl == "RED":
            extra = f", under 2 to gain: {sum(x < 2 for x in s) / len(s):.1%}"
        print(f"    {lvl:6} {len(s) / n:5.1%}  saving avg {sum(s) / len(s):5.2f}{extra}")


for name, slots, tomorrow_from in (
    ("CKW", load_vse("CKW_2026-09.json"), time(12, 0)),
    ("AT", load("AT_2026-09.json"), time(13, 15)),
    ("DE-LU", load("DE-LU_2026-09.json"), time(13, 15)),
):
    month = [p for t, p in slots if FROM <= t < TO]
    print(f"{name}: {min(month):.2f} to {max(month):.2f} per kWh, {sum(p < 0 for p in month)} negative quarters")
    report("max (the app)", score(slots, tomorrow_from, max))
    report("95th percentile", score(slots, tomorrow_from, lambda w: percentile(w, 0.95)))
    report("90th percentile", score(slots, tomorrow_from, lambda w: percentile(w, 0.90)))
