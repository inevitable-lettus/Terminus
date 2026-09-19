# Demo runbook — sandbox, then algorithm

A rehearsed path for showing the module to someone: the simulator first, in its
own UI, then the algorithm answering questions about exactly the state on screen.
Every command and output below was run against DSTNS `5f75fa5` on 2026-09-14.

## Before anyone is watching

The image is already built and the container already exists, so starting it
needs no source checkout:

```bash
docker start dstns_engine_5f75fa5
curl -s http://127.0.0.1:8090/health        # {"ok":true,...}
```

Use that container, not the older `dstns_simulation_engine`, which runs the
previous sandbox. If the container is ever deleted, rebuilding needs a `dstns`
checkout with the fix under "Known sandbox issue" applied.

Clear everything published during testing. **Stopping and starting a run is not
enough** — DSTNS's `start` clears weather, surges and news but keeps every
published bus route, so routes from the test suite would still be on the map.
Only `reset` removes them:

```bash
curl -s -X POST http://127.0.0.1:8090/api/v1/playback/stop
curl -s -X POST http://127.0.0.1:8090/api/v1/playback/reset
```

then start a run from the UI, seek to 18:00 and pause. Check the *Live
Bulletins* panel has no `Transit bus ... dispatched` entries. Do this *after*
running `liveTest`, which publishes routes of its own.

Warm the JVM so the first command is not slow in front of people:

```bash
./gradlew run -q --args="network --attach"
```

## Act 1 — the sandbox

Open `http://localhost:8090`.

- **What it is.** A deterministic traffic simulator, C++20, serving a real
  OpenStreetMap extract of Berlin Mitte: 279 junctions, 612 road segments.
- **Seed.** Bottom bar, `Active Seed`. Same seed and same virtual time give the
  same state, so any number reported can be reproduced.
- **Clock.** Top left. **Seek to `18:00:00` and pause.** 18:00 is the demand peak
  the network is designed for, and pausing matters: a moving clock changes the
  road speeds while the algorithm is reading them.
- **Where demand comes from.** Click a building junction — a school, office, mall
  or store. Its peak profile is the time-of-day window it generates trips in.
  Demand is synthetic, derived from these, not counted.

The line to land: the sandbox owns the city and its traffic. It plans no routes
for us.

## Act 2 — the algorithm

Use `--attach` for everything. It reads the run on screen without touching its
clock, and prints the run id, so the audience can see both halves are looking at
the same simulation.

```bash
./gradlew run -q --args="network --attach"
```
```text
Network at 18:00:00, seed 0x00000000000000000000000000002026 (attached to run_...)
  R0     6 stops,   2.7 min end to end, demand 16.77
  R1     2 stops,   0.9 min end to end, demand 5.64
2 routes, covering 100.0% of 22.41 total demand
```

Say what built it: greedy demand-covering construction, with our own Dijkstra on
our own indexed binary heap as the subroutine. Say what it is not: designing an
optimal bus network is NP-hard, and the usual methods — genetic algorithms,
simulated annealing — are outside the course toolkit. This is a heuristic with
no optimality claim, and it says so.

Then a journey:

```bash
./gradlew run -q --args="journey 5 217 --attach"
```
```text
Walk from junction 5 to stop 5 (0.0 min)
Ride R0 from stop 5 to stop 217, 4 stops (2.0 min)
Walk from stop 217 to junction 217 (0.0 min)
Total 2.0 min, 0 transfers
```

This is A* over the transit graph. The query is between two *junctions*, not two
stops — the walk at each end is priced as a real edge.

## Act 3 — the same trip, backwards

The strongest moment, because it shows a bug being caught and fixed rather than
a feature.

```bash
./gradlew run -q --args="journey 217 5 --attach"
```
```text
Walk from junction 217 to stop 205 (3.3 min)
Walk from stop 205 to junction 5 (2.9 min)
Total 6.2 min, 0 transfers
```

Same two places, three times as long, and no bus. R0 uses a one-way street, so it
cannot be driven back along its own path.

The story behind it: the sandbox publishes the road network with a reverse
direction for *every* street, including one-way ones, and does not mark which
reverses are fake. When the sandbox switched to this real map, our publishing
started being rejected. A sweep of all 612 roads found 139 refused — exactly the
139 flagged as fabricated on a different endpoint, and exactly the 139 with no
traffic demand. Three independent signals, one set. The search now excludes
them.

Then publish, and switch back to the UI:

```bash
./gradlew run -q --args="publish --attach"
```
```text
Published 2 directed routes from 2 lines. R0, R1 could not be driven back along
their own path — one-way streets — so they are published outbound only.
```

The lines render in the simulator's own view. The algorithm's output has become
the simulator's input.

**If you want a live-conditions beat**, trigger *Traffic Surge* or *Simulate Rain*
on a junction near R0's stops in the UI, then re-run the Act 2 journey with
`--attach`. Journey times should rise. Rehearse this first — how much they move
depends on where the event lands, and it has not been measured for this runbook.

## Act 4 — the evidence

```bash
./gradlew test       # 125 tests, saved sandbox responses, nothing needs to run
./gradlew liveTest   # 21 tests against the running simulator
```

Why two suites: the offline one cannot be broken by the simulator changing, and
the live one exists to notice when it does. That is not hypothetical — the map
changed this week, the offline suite stayed green against a map that no longer
existed, and the live suite is what caught it.

## Things to be ready for

- **"Why is walking often the answer?"** The map is 901 m by 947 m. For most
  pairs, walking is genuinely competitive with a bus. That is the map, not the
  algorithm.
- **"Isn't that 21-minute answer wrong?"** Junction 66 to 207 comes back as 21.5
  minutes with two changes, when walking takes about 11. It is wrong. The
  planner only compares against walking the whole way for short trips; see
  [limitations.md](limitations.md). Avoid this pair, or raise it yourself.
- **"Why does A* only save 6.8%?"** On a real map Manhattan distance is not
  admissible, so the heuristic falls back to Euclidean, which is looser, and the
  transit graph has only eight vertices. The saving was 15.9% on the old grid
  with Manhattan.
- **"What's left?"** The weighted-A* research sweep, and a map large enough to
  make it meaningful.

## If something breaks

| Symptom | Cause | Fix |
|---|---|---|
| `409 LIFECYCLE_CONFLICT` | should no longer happen | re-run; the CLI now stops the old run first |
| `--attach needs a run that is already going` | no run active | press Resume in the UI |
| `Could not reach the sandbox` | container down | `docker start dstns_engine_5f75fa5`, wait for `/health` |
| journey says "Walk the whole way" | the two junctions are close | use `5 217` and `217 5` |
| old routes still drawn on the map | a restarted run keeps published buses | `stop`, then `reset`, then start again |

## Known sandbox issue

DSTNS `5f75fa5` does not build as committed. `ui-engine/tests/mapFocus.test.tsx`
lines 34 and 36 pass `exact: true` to `getByRole`, which is not a valid
`ByRoleOptions` key, and `npm run build` type-checks tests, so the Docker build
fails with `TS2769`. Removing `exact: true` from both calls fixes it. Until that
lands upstream, build from a checkout with that change applied.
