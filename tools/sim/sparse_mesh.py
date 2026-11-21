#!/usr/bin/env python3
"""Sparse-group mesh simulator for MoshGrid.

Question it answers: when only a handful of MoshGrid phones are spread over a
festival ground, how often can friends reach each other, how stale is each
friend's location, and how often does a Help alert get through in time?

Model (deliberately simple, see docs/superpowers/plans/2026-11-22-moshgrid-fork-v1.md, Task 0b):
- Phones stand with their group near a home spot (a stage), take trips to the
  bar or toilet, and some wander off alone for an hour.
- A link exists when two phones are within range; each step a link can be
  blocked (bodies). A link must persist for discovery_s before it is usable.
- Messages flood over usable links up to ttl hops per step and wait at
  holders (store-and-forward) until a path appears.
- A phone with internet that step can hand a message to the cloud, and a
  phone with internet can receive from it.

All coordinates are synthetic.
"""

import argparse
import json
import math
import random
import statistics
from dataclasses import asdict, dataclass

WALK_MPS = 1.2


@dataclass(frozen=True)
class Config:
    width: float = 300.0
    height: float = 200.0
    n: int = 6
    groups: int = 2
    hours: float = 6.0
    step_s: int = 5
    seed: int = 1
    range_m: float = 20.0
    p_drop: float = 0.3
    discovery_s: int = 10
    p_internet: float = 0.0
    p_lost: float = 0.2
    ttl: int = 7
    location_every_s: int = 120


class _Person:
    def __init__(self, rng, home, cfg, total_s):
        self.rng = rng
        self.cfg = cfg
        ang = rng.uniform(0, 2 * math.pi)
        off = rng.uniform(0, 10)
        self.home = (home[0] + off * math.cos(ang), home[1] + off * math.sin(ang))
        self.pos = self.home
        self.goal = self.home
        self.next_trip = rng.uniform(20, 40) * 60
        self.trip_end = None
        self.lost_start = None
        self.next_lost_goal = 0.0
        if rng.random() < cfg.p_lost and total_s > 3600:
            self.lost_start = rng.uniform(0, total_s - 3600)

    def _random_spot(self):
        return (self.rng.uniform(0, self.cfg.width), self.rng.uniform(0, self.cfg.height))

    def is_lost(self, t):
        return self.lost_start is not None and self.lost_start <= t < self.lost_start + 3600

    def step(self, t):
        if self.is_lost(t):
            if t >= self.next_lost_goal:
                self.goal = self._random_spot()
                self.next_lost_goal = t + 600
        elif self.trip_end is not None:
            if t >= self.trip_end:
                self.goal = self.home
                self.trip_end = None
                self.next_trip = t + self.rng.uniform(20, 40) * 60
        elif t >= self.next_trip:
            self.goal = self._random_spot()
            self.trip_end = t + self.rng.uniform(10, 20) * 60
        elif self.lost_start is not None and t >= self.lost_start + 3600:
            self.goal = self.home
        dx, dy = self.goal[0] - self.pos[0], self.goal[1] - self.pos[1]
        d = math.hypot(dx, dy)
        move = WALK_MPS * self.cfg.step_s
        if d <= move:
            self.pos = self.goal
        else:
            self.pos = (self.pos[0] + dx / d * move, self.pos[1] + dy / d * move)
        return self.pos


def _spread(sources, adj, ttl):
    """Every phone within ttl hops of any source, sources included."""
    seen = set(sources)
    frontier = list(sources)
    for _ in range(ttl):
        nxt = []
        for u in frontier:
            for v in adj[u]:
                if v not in seen:
                    seen.add(v)
                    nxt.append(v)
        if not nxt:
            break
        frontier = nxt
    return seen


def _pct(values, q):
    if not values:
        return None
    s = sorted(values)
    k = min(len(s) - 1, max(0, math.ceil(q * len(s)) - 1))
    return s[k]


def simulate(cfg, positions=None, help_events=None):
    rng = random.Random(cfg.seed)
    total_s = int(cfg.hours * 3600)
    n = cfg.n

    people = None
    if positions is None:
        homes = [(rng.uniform(30, cfg.width - 30), rng.uniform(30, cfg.height - 30)) for _ in range(cfg.groups)]
        people = [_Person(rng, homes[i % cfg.groups], cfg, total_s) for i in range(n)]
        if help_events is None:
            help_events = []
            for i, p in enumerate(people):
                help_events.append((rng.uniform(0, total_s), i))
                if p.lost_start is not None:
                    help_events.append((p.lost_start + 600, i))
    help_events = sorted(help_events or [])

    pairs = [(i, j) for i in range(n) for j in range(i + 1, n)]
    link_age = {pr: 0 for pr in pairs}
    reachable_steps = {pr: 0 for pr in pairs}
    steps = 0

    # Latest-wins location messages per ordered pair.
    pending = {}  # (src, dst) -> [created_t, holders:set, in_cloud:bool]
    delivered_at = {}  # (src, dst) -> created_t of newest delivered
    ages = []

    helps = []  # [t0, src, holders, in_cloud, delivered_t]
    next_help = 0

    silent_since = [0] * n
    longest_silence = [0] * n

    for t in range(0, total_s, cfg.step_s):
        steps += 1
        pos = [positions(t, i) for i in range(n)] if positions else [p.step(t) for p in people]
        online = [rng.random() < cfg.p_internet for _ in range(n)]

        adj = [[] for _ in range(n)]
        for (i, j) in pairs:
            in_range = math.hypot(pos[i][0] - pos[j][0], pos[i][1] - pos[j][1]) <= cfg.range_m
            link_age[(i, j)] = link_age[(i, j)] + cfg.step_s if in_range else 0
            usable = in_range and link_age[(i, j)] >= cfg.discovery_s and rng.random() >= cfg.p_drop
            if usable:
                adj[i].append(j)
                adj[j].append(i)

        reach = [_spread([i], adj, cfg.ttl) for i in range(n)]
        for (i, j) in pairs:
            if j in reach[i] or (online[i] and online[j]):
                reachable_steps[(i, j)] += 1

        for i in range(n):
            connected = len(reach[i]) > 1 or (online[i] and sum(online) > 1)
            if connected:
                silent_since[i] = t + cfg.step_s
            longest_silence[i] = max(longest_silence[i], t + cfg.step_s - silent_since[i])

        if t % cfg.location_every_s == 0:
            for s in range(n):
                for d in range(n):
                    if s != d:
                        pending[(s, d)] = [t, {s}, False]

        for key, msg in list(pending.items()):
            _, dst = key
            msg[1] = _spread(msg[1], adj, cfg.ttl)
            if any(online[h] for h in msg[1]):
                msg[2] = True
            if dst in msg[1] or (msg[2] and online[dst]):
                delivered_at[key] = msg[0]
                del pending[key]

        while next_help < len(help_events) and help_events[next_help][0] <= t:
            t0, src = help_events[next_help]
            helps.append([t0, src, {src}, False, None])
            next_help += 1
        for h in helps:
            if h[4] is not None:
                continue
            h[2] = _spread(h[2], adj, cfg.ttl)
            if any(online[x] for x in h[2]):
                h[3] = True
            others_have = len(h[2]) > 1 or (h[3] and any(online[x] for x in range(n) if x != h[1]))
            if others_have:
                h[4] = t

        for s in range(n):
            for d in range(n):
                if s != d:
                    ages.append(t - delivered_at.get((s, d), 0))

    delays = [h[4] - h[0] for h in helps if h[4] is not None]
    count = len(helps)

    def share_within(limit):
        return round(sum(1 for d in delays if d < limit) / count, 4) if count else None

    return {
        "config": asdict(cfg),
        "pair_reachable_share": {f"{i}-{j}": round(reachable_steps[(i, j)] / steps, 4) for (i, j) in pairs},
        "location_age_s": {"median": statistics.median(ages) if ages else None, "p90": _pct(ages, 0.9)},
        "help": {
            "count": count,
            "delivered": len(delays),
            "within_120s": share_within(120),
            "within_600s": share_within(600),
            "delays_s": [round(d) for d in delays],
        },
        "longest_silence_s": {str(i): longest_silence[i] for i in range(n)},
    }


def _summary(results):
    shares = [v for r in results for v in r["pair_reachable_share"].values()]
    med_age = statistics.median([r["location_age_s"]["median"] for r in results])
    p90_age = statistics.median([r["location_age_s"]["p90"] for r in results])
    helps = [r["help"] for r in results if r["help"]["count"]]
    w120 = statistics.mean([h["within_120s"] for h in helps]) if helps else float("nan")
    w600 = statistics.mean([h["within_600s"] for h in helps]) if helps else float("nan")
    silence = statistics.median([max(r["longest_silence_s"].values()) for r in results])
    return statistics.mean(shares), med_age, p90_age, w120, w600, silence


def grid(seeds, hours):
    rows = ["| phones | range m | internet | pair reachable | location age median | location age p90 | Help < 2 min | Help < 10 min | worst silence |",
            "|---|---|---|---|---|---|---|---|---|"]
    for n in (3, 6, 10):
        for rng_m in (10, 20, 30):
            for p_net in (0.0, 0.05):
                res = [simulate(Config(n=n, range_m=rng_m, p_internet=p_net, seed=s, hours=hours)) for s in seeds]
                share, med, p90, w120, w600, sil = _summary(res)
                rows.append(f"| {n} | {rng_m} | {p_net:.0%} | {share:.0%} | {med/60:.0f} min | {p90/60:.0f} min | {w120:.0%} | {w600:.0%} | {sil/60:.0f} min |")
    return "\n".join(rows)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    for f in Config.__dataclass_fields__.values():
        ap.add_argument("--" + f.name.replace("_", "-"), type=type(f.default), default=f.default)
    ap.add_argument("--grid", action="store_true", help="run the standard grid over 5 seeds and print a markdown table")
    args = ap.parse_args()
    if args.grid:
        print(grid(seeds=range(1, 6), hours=args.hours))
        return
    cfg = Config(**{k: getattr(args, k) for k in Config.__dataclass_fields__})
    print(json.dumps(simulate(cfg), indent=2))


if __name__ == "__main__":
    main()
