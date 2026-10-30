# Limitations, and what is not done

Written before anyone asks. Knowing where a result is weak is part of the result.

## DSTNS 2.0 — verified live on 2026-09-25

The module was adapted to DSTNS 2.0 from source on 2026-09-21 and first run
against a live 2.0 (`origin/dstns-v2` `bc6e517`, built from source, seed `0x2026`,
Dar es Salaam) on 2026-09-25. The live suite passes 20/20 and the offline suite
now carries real 2.0 captures (`src/test/resources/fixtures/v2/`). Two of the
source-reading bets were wrong, and both are fixed rather than tolerated — see
the [build log](build-log.md):

- **The `bus_stop` places were not a demand field.** Only 11 exist against 81
  flagged stops, one anchored on a flagged junction, so 71 of 72 stops scored the
  same baseline. Demand now comes from the simulator's trip generators (`CATCHMENT`).
- **Reading the outbound path backwards gave no line a return.** Real maps model
  dual carriageways as paired one-way ways; the return is now routed on its own.

What remains a reading, not an observation: `COMPLETED` (a run that played to the
end of its day) was never reached live, and the handling of it is by analogy with
`STOPPED`, which was.

**Demand is relative, not a head count.** A generator's multiplier is an
intensity (around 1.0–3.0), and a stop's demand is the sum over the generators
whose nearest stop it is. "Coverage" is therefore a share of generator pull
within reach, not of passengers. When the source falls back to `UNIFORM`, the
network is designed purely for reach. The report header names the source either way.

**Some stops cannot be served both ways, and are left unserved.** A district is
cut out of a larger city, so one-way streets run off its edge: on the 2.0
capture 13 of 81 stops sit in pockets a bus can enter and never leave, or leave
and never re-enter. A line only reaches for stops it can come back from, so those
stops stay uncovered. The header counts them. Serving them with a one-way loop is
possible and not built.

**Lines can no longer be shown in the simulator.** The publishing endpoint is
retired, so the algorithm's output reaches a map only through `routes.geojson` in
a separate viewer, not inside the simulator's own UI. That weakens the
"algorithm's output becomes the simulator's input" demonstration.

**Starting a run from here can leave nothing running.** `--start` must stop the
current run first (the simulator answers a second start with 409), and only the
*absence* of a token can be detected beforehand. A token that is present but
wrong is refused after the stop; the message says so. Reading an existing run,
the default, has no such risk.

## About the data

**Demand is synthetic.** The sandbox generates it from building types and
hardcoded time-of-day windows; nobody counted passengers. That is one step further
from reality than a count of real shops and schools would be.

**The network is designed for one time slice.** 18:00, at seed `0x2026`. A network
optimal at 18:00 is not optimal at 08:30, and the demand table in the
[build log](build-log.md) shows how far the picture moves across a day.

**The Berlin fixtures are real, but tiny.** *(DSTNS 2.0 serves a larger district
per seed — 3000 junctions on the 2.0 capture — and that is now the normal case;
this paragraph describes the pre-2.0 regression fixtures.)* Since DSTNS `5f75fa5` it is an OpenStreetMap
extract of Berlin Mitte: 279 junctions, 612 directed road segments, 8 bus stops,
901 m by 947 m. The farthest two junctions are 951 m apart, so for most pairs
walking is competitive with any bus, and a network of two lines covers all of the
demand. That makes the route builder's output thin, and it means the
"algorithm at scale" research question cannot be answered on this map. The
bundled `real_network.osm.xml` is larger, but the sandbox's Docker image does not
ship it.

When the map changed from the synthetic 12 x 10 grid, the Manhattan heuristic
stopped being admissible. The code enforced that precondition rather than
assuming it, so the change produced a refusal and a fall-back to Euclidean, not a
wrong answer — which is the case for writing the check.

**The coverage walking radius was pulled below the planning standard.** *(Now
derived per map — half the median stop spacing, 75–400 m — with 150 m being what
that gives for the Berlin fixtures' spacing, roughly.)* 150 m
instead of the usual 400 m, because the closest pair of stops is 176 m apart and
at anything above that a stop counts as covered by a bus that never goes there.
That is a concession to the map's size, not a claim about pedestrians.

## About the algorithms

**Algorithm 1 is a heuristic with no approximation bound.** Greedy never
reconsiders an earlier choice, and nothing here says the result is within any
factor of the best possible network. That is deliberate — the alternatives are
outside the course toolkit — but it is a real limitation, not a formality.

**One-way streets are handled only because a second endpoint says which they
are.** This map has 139 one-way streets. The transit graph prices each direction
of a line from its own roads and drops a return ride wherever a road cannot be
driven that way, and publishing sends a return leg only when the whole line can
be driven back. All of that rests on `source.synthetic_reverse` from
`/view/edges`: `/view/topology`, which the graph is built from, cannot tell a
one-way street from a two-way one. If the sandbox stopped sending that flag, the
module would quietly go back to planning the wrong way down them, and no test
currently asserts the flag is present.

**The transfer wait is a flat five minutes.** The sandbox publishes no timetable
or headway, so there is nothing to derive a real wait from.

**The first boarding is free unless asked otherwise.** With no timetable there is
no wait to derive, so the default is none, and a journey's cost is in-vehicle
plus transfer time. `--board-wait MIN` adds a chosen wait before the first bus,
shown as its own leg; it is a stand-in the user picks, not a figure the simulator
gives. *(Fixed 2026-09-25: walking the whole way used to be compared only within
400 m, so junction 66 to 207 came back as 21.5 minutes by bus against about 11 on
foot. A bus answer now never costs more than walking straight there, at any
distance.)*

**Searches run against a snapshot at most 2 seconds old.** `./bus serve` re-reads the
speeds every 2 seconds, but the lines are planned once per run: a road that closes
later is priced as gridlock (0.1 m/s) for the bus, which cannot re-route. The
one-shot commands still answer for the single moment they read.

**A passenger starts at a junction, not an address.** Access and egress legs
are modelled — a query takes two road junctions and the walks at either end are
priced as real edges — but a junction is still not a doorway. Sub-junction
precision would need a map with addresses on it, which this one does not have.

## About the testing

**The cost model's proof is weaker than planned.** The previous sandbox published
a per-edge weight to check against, giving an exact match on every edge. It was removed in
the rewrite, so what remains is an invariant that must hold, three hand-computed
values, the clamp, and the closure rule.

**The sandbox pathfinder cross-check is dropped.** No HTTP endpoint exists for it,
re-checked at the current HEAD. Correctness rests instead on the Floyd–Warshall
brute-force comparison and the Dijkstra-versus-A* agreement.

**The default suite runs against saved fixtures.** That is the right default — a
test that needs another service running is a bad test. A separate live suite
(`./gradlew liveTest`, 20 tests) now covers the end-to-end path against a running
simulator, including a real road closure. What it deliberately does not assert is
exact numbers: the simulator's rate-limited speeds settle differently depending on
how the clock reached 18:00, so live results differ slightly from the fixtures and
the tests check properties instead. See [testing.md](testing.md).

**All 21 live tests passed** against DSTNS at `origin/dstns` (`0fb6307`),
run 2026-09-12, including publishing every line and a test asserting the server
rejected a gapped route — which showed that carrying the full
junction-by-junction traversal was *necessary* rather than merely tidy. Both
tests are gone: DSTNS 2.0 retired the endpoint, and the rule is now checked
locally before export. That evidence stands as history and can no longer be
re-run.

**The wait for the first bus is zero by default, and it shows.** A one-block bus
ride can beat a one-block walk. Pass `--board-wait` to see the answer with a
realistic wait; the planner guarantees an answer is never *worse* than walking.

**A passenger is assumed to walk at most 400 m to or from a stop.** That bounds
the access and egress walks, and walking the whole way when no bus serves the
trip. Beyond it a destination with no stop nearby is reported unreachable, which is a modelling
choice rather than a fact about the world — a real person would walk further
rather than not travel. It is what keeps "unreachable" a meaningful answer
instead of every query returning a two-hour trudge.

**Walking distances are straight lines, not road distances.** A passenger
walking 200 m does not follow the carriageway. At this radius the error is small
and computing a walking path to every candidate stop would cost far more than
the answer is worth, but it does mean a stop across an unbridged barrier would
be treated as reachable.

**A\*'s 15.9% saving is modest because the graph is small.** 61 vertices is a size
where any search finishes instantly, and the transfer and walk costs are large flat
numbers geometry cannot predict, so the guess is weak relative to the true costs.
The gap would widen on a larger network, which is exactly what the research
component should show.

## Not built yet

**The research deliverable.** The course explicitly requires comparing
shortest-path algorithms **across different network sizes**. Outstanding pieces:

- A determinism harness proving that the same seed and virtual time give
  byte-identical edge speeds, without which no benchmark number means anything.
- Instrumentation: nodes expanded as the primary machine-independent metric,
  wall-clock timing with warm-up runs discarded, peak queue size.
- The weighted A* sweep, `f = g + ε·h`, with its bounded-suboptimality guarantee
  that the returned path is never worse than ε times optimal.
- Networks of different sizes. DSTNS 2.0 answers this: every seed is a real
  city, and a start accepts `map.district_nodes` (200–50000), so the same city
  can be served at several sizes. The 279-junction Berlin fixture and the
  3000-junction 2.0 capture are already two points.

The module is at least ready for that map in one respect: `JourneyPlanner` picks
its heuristic by asking whether the map is axis-aligned rather than assuming it,
so Manhattan will refuse itself on real road geometry and Euclidean will take
over without an edit. That path has only been exercised on a hand-built skewed
graph, never on a real map.

**Integration is done, and verified on `5f75fa5` only.** A command-line entry
point (`./bus`) covers the network, the stops, a journey and an export; routes are
written to files, since publishing them back is retired in 2.0. See the "Using
it" section of the [README](../README.md).
