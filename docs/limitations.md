# Limitations, and what is not done

Written before anyone asks. Knowing where a result is weak is part of the result.

## About the data

**Demand is synthetic.** The sandbox generates it from building types and
hardcoded time-of-day windows; nobody counted passengers. That is one step further
from reality than a count of real shops and schools would be.

**The network is designed for one time slice.** 18:00, at seed `0x2026`. A network
optimal at 18:00 is not optimal at 08:30, and the demand table in the
[build log](build-log.md) shows how far the picture moves across a day.

**The map is a 2 km synthetic grid, not a real city.** 120 junctions, 436 roads,
every road exactly 180 m and axis-aligned, 1980 m by 1620 m end to end. Several
results depend on that — Manhattan admissibility above all — and would need
rechecking on a real map. The code enforces the precondition rather than assuming
it, so a non-grid map produces a refusal rather than a wrong answer.

**The coverage walking radius was pulled below the planning standard.** 250 m
instead of the usual 400 m, because at 400 m a single route covers the whole map
and the algorithm demonstrates nothing. That is a concession to the map's size,
not a claim about pedestrians.

## About the algorithms

**Algorithm 1 is a heuristic with no approximation bound.** Greedy never
reconsiders an earlier choice, and nothing here says the result is within any
factor of the best possible network. That is deliberate — the alternatives are
outside the course toolkit — but it is a real limitation, not a formality.

**Lines are treated as running both directions at the same cost.** That only holds
because this map has a reverse twin for all 436 roads and no one-way streets. On a
real map the return leg would have to be priced separately.

**The transfer wait is a flat five minutes.** The sandbox publishes no timetable
or headway, so there is nothing to derive a real wait from.

**The first boarding is free.** The initial wait is not modelled, so a journey's
cost is in-vehicle plus transfer time, not door-to-door time.

**Searches run against a frozen snapshot.** They answer "what was best at 18:00",
not "what is best right now".

**A passenger starts at a stop, not an address.** There is no access or egress
leg: the query takes a transit vertex, so walking from an arbitrary point to the
nearest useful stop is not yet modelled.

## About the testing

**The cost model's proof is weaker than planned.** The previous sandbox published
a per-edge weight to check against, giving 436 exact matches. It was removed in
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

**Ten of those live tests have never been run.** The door-to-door journey,
publishing, and failure-case tests in `LiveJourneyTest` were written with no
sandbox available, so they compile and skip. Until somebody runs
`./gradlew liveTest` with the container up, publishing to a real server is
unproven — in particular the test asserting the server rejects a gapped route,
which is the only thing standing behind the claim that carrying the full
junction-by-junction traversal is *necessary* rather than merely tidy.

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

**Integration is done.** A command-line entry point covers all three modes and
routes are published back in both directions; see the "Using it" section of the
[README](../README.md). What has not happened is a run of any of it against a
live simulator.
