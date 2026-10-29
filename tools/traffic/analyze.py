"""Congestion by weekday and hour: each ride's actual duration over its line's uncongested duration (10th percentile)."""
import json, os, sys, statistics, collections
from datetime import datetime
from zoneinfo import ZoneInfo

HERE = os.path.dirname(os.path.abspath(__file__))
IL = ZoneInfo("Asia/Jerusalem")
rows = json.load(open(os.path.join(HERE, "rides.json"), encoding="utf-8"))

by_line = collections.defaultdict(list)
for r in rows:
    d = r["duration_minutes"]
    if not d or not r["gtfs_ride__end_time"] or not r["scheduled_start_time"]:
        continue
    sched = (datetime.fromisoformat(r["gtfs_ride__end_time"]) - datetime.fromisoformat(r["gtfs_ride__start_time"])).total_seconds() / 60
    # Partial tracks (a ride seen for only part of its way) and stuck reports.
    if sched < 20 or d < 0.6 * sched or d > 2.5 * sched:
        continue
    t = datetime.fromisoformat(r["scheduled_start_time"]).astimezone(IL)
    by_line[(r["gtfs_route__line_ref"], r["kind"])].append((t, d))

cells = {"inter": collections.defaultdict(list), "urban": collections.defaultdict(list)}
for (ref, kind), rides in by_line.items():
    if len(rides) < 60:
        continue
    base = sorted(d for _, d in rides)[len(rides) // 10]
    k = "inter" if kind == "inter" else "urban"
    for t, d in rides:
        # Weekday: Sunday = 0 ... Saturday = 6. Mid-ride hour would be better; the start is close enough for the table.
        wd = (t.weekday() + 1) % 7
        cells[k][(wd, t.hour)].append(d / base)

days = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]
out = {}
for k, c in cells.items():
    print(f"\n{k}: lines {sum(1 for (r, kk) in by_line if (kk == 'inter') == (k == 'inter'))}")
    print("     " + " ".join(f"{h:>4}" for h in range(24)))
    table = []
    for wd in range(7):
        row = []
        for h in range(24):
            v = c.get((wd, h), [])
            row.append(round(statistics.median(v), 2) if len(v) >= 15 else None)
        table.append(row)
        print(f"{days[wd]}  " + " ".join(f"{x:4.2f}" if x else "   -" for x in row))
    out[k] = table
json.dump(out, open(os.path.join(HERE, "factors.json"), "w"), indent=1)
