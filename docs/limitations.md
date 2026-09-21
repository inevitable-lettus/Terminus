# Limitations, and what is not done

Written before anyone asks. Knowing where a result is weak is part of the result.

## DSTNS 2.0 — the biggest open gap

The module has been adapted to DSTNS 2.0 (`dstns-v2`) **from its source code, not
from a live run**, so every 2.0-specific behaviour is a reading, not an
observation. The offline suite has not seen a 2.0 response and the live suite has
not run against one; fixtures were deliberately not hand-written, because that
would encode what was *expected* rather than what is sent. The specific bets, each
written to degrade rather than fail if wrong:

- that `/view/places` has the shape the source suggests, and that a stop place's
  `anchor_node` lands on a junction the topology flags as a stop — if not, those
  stops count 1.0 and the demand source falls back to buildings, then uniform;
- that the lifecycle strings are as documented (compared case-insensitively);
- that real-city extracts tag few buildings on road junctions, which is why the
  demand chain exists at all.

Two limits of the new demand source, stated now rather than found later. The
simulator's per-stop figure is a *relative intensity* (1.0 unmodulated, capped at
3.0), not a passenger count, and every stop starts from 1.0 — so "coverage" on
`PLACES` is closer to a share of stops, weighted a little, than to a share of
passengers. And when the source is `UNIFORM`, the network is designed purely for
reach, which says nothing about where people are. Both are why the report header
names the demand source.

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

**The map is real, but tiny — on `5f75fa5`.** *(2.0 serves a different, larger
district per seed, which this module has not yet seen; this paragraph describes
the map the saved fixtures are from.)* Since DSTNS `5f75fa5` it is an OpenStreetMap
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

**Walking the whole way is only offered for short trips.** The planner compares
a bus journey against walking directly only when the two junctions are within the
stop-access radius. For longer trips it returns the best bus journey even when
walking would be quicker: junction 66 to 207 comes back as 21.5 minutes with two
changes, against roughly 11 minutes on foot. That is a real defect in the answer,
not a modelling nicety.

**The first boarding is free.** The initial wait is not modelled, so a journey's
cost is in-vehicle plus transfer time, not door-to-door time.

**Searches run against a frozen snapshot.** They answer "what was best at 18:00",
not "what is best right now".

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
(`./gradlew liveTest`, 21 tests) now covers the end-to-end path against a running
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

**The wait for the first bus is not modelled, and it now shows.** Boarding is
free, so a one-block bus ride can beat a one-block walk. That was always a
stated simplification of the transit graph; door-to-door queries are where it
becomes visible, because a passenger asking for a short hop may be told to take
a bus that in reality they would wait five minutes for. The planner does
guarantee an answer is never *worse* than walking when walking is possible.

**A passenger is assumed to walk at most 400 m in one go.** That bounds the
access walk, the egress walk, and the whole-way walk alike. Beyond it a
destination with no stop nearby is reported unreachable, which is a modelling
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
- A second, larger network. The repository ships `real_network.osm.xml`
  (8.6 MB, real road topology), which would give grid-versus-real and
  small-versus-large in one sweep. It has not been tested for load time or
  whether it loads at all, and **no endpoint for loading it has been found** —
  the claim that it is "loadable through the playback API" is an assumption
  nobody has checked against a running server. That has to be established before
  the larger-map comparison can be planned, not assumed while planning it.

The module is at least ready for that map in one respect: `JourneyPlanner` picks
its heuristic by asking whether the map is axis-aligned rather than assuming it,
so Manhattan will refuse itself on real road geometry and Euclidean will take
over without an edit. That path has only been exercised on a hand-built skewed
graph, never on a real map.

**Integration is done, and verified on `5f75fa5` only.** A command-line entry
point (`./bus`) covers the network, the stops, a journey and an export; routes are
written to files, since publishing them back is retired in 2.0. See the "Using
it" section of the [README](../README.md).
