"""Measured factors -> Kotlin tables, filling the hours buses don't run (or run only in a few towns)."""
import json, os

HERE = os.path.dirname(os.path.abspath(__file__))
f = json.load(open(os.path.join(HERE, "factors.json")))
SUN, FRI, SAT = 0, 5, 6

# Shabbat daytime: buses run only in a few towns, so their times say little. Quiet morning, leisure traffic from midday.
SHABBAT = {h: 1.0 for h in range(0, 9)} | {9: 1.05} | {h: 1.12 for h in range(10, 17)} | {17: 1.18}

for name, kind in (("INTERCITY", "inter"), ("CITY", "urban")):
    t = f[kind]
    for d in range(7):
        for h in range(24):
            v = t[d][h]
            if d == SAT and h <= 17:
                v = SHABBAT[h]
            elif d == FRI and h >= 17:
                v = {17: 1.06, 18: 1.03}.get(h, 1.0)
            elif d == SAT and h == 18 and v is None:
                # Shabbat ends and traffic picks up.
                v = 1.22
            elif v is None:
                v = 1.0
            # Empty roads are as fast as Transitous has them; buses at night are no faster.
            t[d][h] = max(1.0, v)
    print(f"    private val {name} = arrayOf(")
    for d, row in enumerate(t):
        print("        doubleArrayOf(" + ", ".join(f"{v:.2f}" for v in row) + f"),  // {['Sun','Mon','Tue','Wed','Thu','Fri','Sat'][d]}")
    print("    )")
