# Demo runbook — sandbox, then algorithm

A path for showing the module to someone: the simulator first, in its own UI,
then the algorithm answering questions about exactly the state on screen.

> **Status.** Rewritten for DSTNS 2.0 (`dstns-v2`) from its source. **It has not
> been rehearsed against a live 2.0 yet**, so the printed outputs below are from
> the saved Berlin fixtures (DSTNS `5f75fa5`, 2026-09-14) and will differ: 2.0
> serves a different city for every seed. Do a dry run and replace the samples
> with what you see. Steps that depend on 2.0 behaviour are marked **[2.0]**.

## Before anyone is watching

**[2.0]** Only the simulator's operator can start a run, so start it the way its
own README says (the operator CLI, `./launcher` in the sandbox repo), pick or
note the seed, and let it finish downloading the city. Then in the UI seek to
`18:00:00` and **pause** — 18:00 is the demand peak the network is designed for,
and a moving clock changes road speeds while the algorithm is reading them.

Check the module can see it, and warm the JVM so the first command is not slow in
front of people:

```bash
./bus help          # ends with "Simulator at http://127.0.0.1:8090: PAUSED"
./bus network
```

If the simulator is somewhere else, add `--sandbox http://host:port`.

## Act 1 — the sandbox

Open `http://localhost:8090`.

- **What it is.** A deterministic traffic simulator, C++20. **[2.0]** The seed
  picks a real city from 181 and a district inside it; the map is downloaded from
  OpenStreetMap on demand.
- **Seed.** Same seed and same virtual time give the same state, so any number
  reported can be reproduced.
- **Where demand comes from.** **[2.0]** Every place on the map — schools, shops,
  offices, bus stops — has a demand multiplier, and a stop's follows the places
  around it. The algorithm reads that figure per stop.

The line to land: the sandbox owns the city and its traffic. It plans no routes
for us.

## Act 2 — the algorithm

Nothing below touches the simulator's clock, and the header names the run, so the
audience can see both halves are looking at the same simulation.

```bash
./bus network
```
```text
DSTNS world
  run      run_ef7d6f6676c5  seed 0x00000000000000000000000000002026  time 18:00:00  (attached; clock untouched)
  map      3000 junctions, 6330 roads, graph 59116c6827ff
  one-way  566 roads excluded (flagged by the topology)
  stops    81 flagged, 72 after merging those within 60 m
           11 of them in one-way pockets off the main road network, where no line can call and come back
  demand   CATCHMENT — 2940 of 3246 active trip generators, each credited to its nearest stop
  budget   8 min per line (default), walk 75 m (derived), up to 6 lines (derived), target 90%

Lines
  line  stops  minutes   demand  share  first -> last   back
  R0       11      7.3  2324.08  47.6%    224 -> 1348   11.3 min
  R1       13      7.7  1232.16  25.2%   2470 -> 1682   10.9 min
  R2       14      7.5   426.82   8.7%    656 -> 803    16.3 min
  R3        8      7.7   206.51   4.2%   2288 -> 1704   9.4 min
  R4        9      7.3   194.48   4.0%    377 -> 1575   9.7 min
  R5       13      7.6    76.87   1.6%   1397 -> 2045   18.3 min

Summary
  6 lines cover 91.3% of demand (55 of 72 stops within reach)
  stopped because the 90% coverage target was met
  interchanges: 11 stops served by more than one line (34, 161, 170, 265, 1101, 1156, 1241, 1244, ...)
  passenger network: connected — a rider can reach any served stop from any other
```

Read the header aloud, because it is the honesty of the result. This is live
DSTNS 2.0 (Dar es Salaam, seed `0x2026`). The `demand` line: `CATCHMENT` means the
simulator's own trip generators were used, each credited to its nearest stop;
`BUILDINGS` or `UNIFORM` means they were not, and the note under it says why. The
line under `stops` counts the stops no bus can serve both ways, and the `back`
column is each line's return service.

Say what built it: greedy demand-covering construction, with our own Dijkstra on
our own indexed binary heap as the subroutine. Say what it is not: designing an
optimal bus network is NP-hard, and the usual methods — genetic algorithms,
simulated annealing — are outside the course toolkit. This is a heuristic with
no optimality claim, and it says so. The `stopped because` line says which limit
ended it, and which flag would change that.

Then pick real junction ids and plan a journey:

```bash
./bus stops
./bus journey 5 56
```
```text
Journey  junction 5 -> junction 56  at 18:00:00
  from 52.51446, 13.38480   to 52.51712, 13.39258   straight line 605 m

   1  Walk from junction 5 to stop 5                       0.0 min   at   0.0
   2  Ride R0, stop 5 -> stop 214 (2 stops)                0.8 min   at   0.8
   3  Walk from stop 214 to junction 56                    3.0 min   at   3.9

  Total 3.9 min, 0 transfers. Walking straight there would take about 7.2 min.
  The search looked at 8 of 8 transit vertices.
```

This is A* over the transit graph. The query is between two *junctions*, not two
stops — the walk at each end is priced as a real edge.

## Act 3 — the same trip, backwards

The strongest moment, because it shows a bug being caught and fixed rather than
a feature. Swap the two ids from Act 2:

```bash
./bus journey 56 5
```

On the Berlin map the answer is several times longer and has no bus: line R0 uses
a one-way street, so it cannot be driven back along its own path.

The story behind it: the simulator publishes the road network with a reverse
direction for *every* street, including one-way ones. Nothing on the old topology
said which reverses were fake, and the search was driving the wrong way down 139
of 612 roads. Three independent signals agreed on that set — the simulator's
route validator refused exactly those edges, a flag on a different endpoint
marked them, and none of them carried traffic demand. **[2.0]** The topology now
carries the flag itself, so the header reads `flagged by the topology`.

Then export, and show the result on a map:

```bash
./bus export
```
```text
Wrote 2 lines to:
  out/routes.json
  out/routes.geojson
  R0, R1 cannot be driven back along their own path (one-way streets), so they are written outbound only.
```

Drag `out/routes.geojson` onto any GeoJSON viewer (geojson.io works). **[2.0]**
The lines can no longer be sent back to the simulator to render there — it
retired that endpoint — which is why the output is a file.

**If you want a live-conditions beat**, trigger a traffic surge or rain on a
junction near a line's stops in the simulator, pause, then re-run the journey.
Times should rise. Rehearse this first — how much they move depends on where the
event lands, and it has not been measured.

## Act 4 — the evidence

```bash
./gradlew test       # 241 tests, saved sandbox responses, nothing needs to run
./gradlew liveTest   # against the running simulator; see docs/testing.md
```

Why two suites: the offline one cannot be broken by the simulator changing, and
the live one exists to notice when it does. That is not hypothetical — the map
changed once and the offline suite stayed green against a map that no longer
existed; the live suite is what caught it. The same applies now: the offline
suite has not seen a 2.0 response yet.

## Things to be ready for

- **"Why is walking often the answer?"** On a small district, walking is
  genuinely competitive with a bus. That is the map, not the algorithm.
- **"Isn't a long answer wrong?"** The planner compares against walking the whole
  way only for short trips; see [limitations.md](limitations.md). If a result
  looks worse than walking, that is the limitation, and worth raising yourself.
- **"Why does A* save so little?"** On a real map Manhattan distance is not
  admissible, so the heuristic falls back to Euclidean, which is looser, and the
  transit graph is small.
- **"Why did the budget change between cities?"** The walking radius and line cap
  are derived from that map's stop spacing and stop count, and the header says so.
  The 150 m / 8 lines the earlier version used were an argument about one small
  map, not a constant.
- **"What's left?"** A live run against 2.0, and the weighted-A* research sweep.

## If something breaks

| Symptom | Cause | Fix |
|---|---|---|
| `No run is going on ...` | nothing has been started | start a run in the simulator's CLI or UI |
| `--start needs the simulator's operator token` | 2.0 only starts runs for its operator | set `DSTNS_OPERATOR_TOKEN` or pass `--token-file <sandbox>/logs/operator.token`; nothing was stopped |
| `the simulator refused the operator token` | wrong token | the previous run **was** already stopped; start one in the simulator |
| `Could not read the simulator` | not running | `./launcher` in the sandbox repo |
| `the simulator was still PREPARING after ...` | a city download is slow or blocked | check the simulator's own log; it needs to reach the Overpass API |
| `Warning: the run is RUNNING` | the clock is moving | pause it, or pass `--time` |
| `stops ... 0 after merging` / `No bus stops in this world` | that district has no usable stops | pick another seed |
| journey says "Walk the whole way" | the two junctions are close | choose ids from `./bus stops` that are further apart |
