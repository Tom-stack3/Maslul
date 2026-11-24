"""Samples bus lines from one weekday rush hour and pulls their actual ride durations for ~3 weeks from Open Bus Stride.

    python -X utf8 -I fetch_rides.py SAMPLE_UTC FROM_UTC TO_UTC
    e.g. 2026-10-27T05:00:00Z 2026-10-04T21:00:00Z 2026-10-28T21:00:00Z  (pick weeks without holidays)

Writes rides.json next to it (tens of MB; keep it out of git). Takes about an hour: gently, one line at a time.
"""
import json, os, sys, time, random, collections, urllib.request, urllib.parse
from datetime import datetime, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
API = "https://open-bus-stride-api.hasadna.org.il/siri_rides/list"
SAMPLE, FROM, TO = sys.argv[1:4]
FIELDS = ("scheduled_start_time", "duration_minutes", "gtfs_ride__start_time", "gtfs_ride__end_time",
          "gtfs_route__route_long_name", "gtfs_route__route_short_name", "gtfs_route__line_ref")


def cities(name):
    if not name or "<->" not in name:
        return None
    a, b = name.split("<->")
    ca = a.rsplit("-", 1)[-1].strip()
    parts = b.split("-")
    cb = parts[-2].strip() if len(parts) >= 3 else parts[-1].strip()
    return ca, cb


def get(params):
    url = API + "?" + urllib.parse.urlencode(params)
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Maslul traffic calibration"}), timeout=180) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception as e:
            print("retry", attempt, e, file=sys.stderr)
            time.sleep(10 * (attempt + 1))
    return []


def main():
    # Every ride starting in 20 minutes of a weekday rush hour (the API returns at most 15000 at once).
    end = (datetime.fromisoformat(SAMPLE.replace("Z", "+00:00")) + timedelta(minutes=20)).strftime("%Y-%m-%dT%H:%M:%SZ")
    sample = get({"scheduled_start_time_from": SAMPLE, "scheduled_start_time_to": end, "limit": 15000})
    by_line = {}
    for r in sample:
        c = cities(r["gtfs_route__route_long_name"])
        if not c or not r["duration_minutes"] or not r["gtfs_ride__end_time"]:
            continue
        sched = (time.mktime(time.strptime(r["gtfs_ride__end_time"][:19], "%Y-%m-%dT%H:%M:%S"))
                 - time.mktime(time.strptime(r["gtfs_ride__start_time"][:19], "%Y-%m-%dT%H:%M:%S"))) / 60
        if sched < 25:
            continue
        by_line[r["gtfs_route__line_ref"]] = ("inter" if c[0] != c[1] else "urban:" + c[0], c, sched)
    random.seed(7)
    picked, seen = [], collections.Counter()
    lines = list(by_line.items())
    random.shuffle(lines)
    for ref, (kind, c, _) in lines:
        key = kind if kind == "inter" else "urban"
        if seen[key] >= 45 or seen[c] >= 2:
            continue
        seen[key] += 1
        seen[c] += 1
        picked.append((ref, kind))
    print(len(picked), seen["inter"], seen["urban"], file=sys.stderr)
    out = []
    for i, (ref, kind) in enumerate(picked):
        rows = get({"gtfs_route__line_refs": ref, "scheduled_start_time_from": FROM, "scheduled_start_time_to": TO, "limit": 5000})
        for r in rows:
            out.append({k: r.get(k) for k in FIELDS} | {"kind": kind})
        print(i, ref, kind, len(rows), file=sys.stderr)
        time.sleep(1)
    json.dump(out, open(os.path.join(HERE, "rides.json"), "w", encoding="utf-8"), ensure_ascii=False)


main()
