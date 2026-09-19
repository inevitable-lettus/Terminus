# Limitations, and what is not done

Written before anyone asks. Knowing where a result is weak is part of the result.

## About the data

**Demand is synthetic.** The sandbox generates it from building types and
hardcoded time-of-day windows; nobody counted passengers. That is one step further
from reality than a count of real shops and schools would be.

**The network is designed for one time slice.** 18:00, at seed `0x2026`. A network
optimal at 18:00 is not optimal at 08:30, and the demand table in the
[build log](build-log.md) shows how far the picture moves across a day.

**The map is real, but tiny.** Since DSTNS `5f75fa5` it is an OpenStreetMap
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

**The coverage walking radius was pulled below the planning standard.** 150 m
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

**All 21 live tests now pass** against DSTNS at `origin/dstns` (`0fb6307`),
run 2026-09-12. That includes publishing every line in both directions and the
test asserting the server rejects a gapped route — so the claim that carrying
the full junction-by-junction traversal is *necessary* rather than merely tidy
is now evidence rather than assertion.

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

**Integration is done and verified.** A command-line entry point covers all
three modes, and routes are published back in both directions and accepted by
the simulator; see the "Using it" section of the [README](../README.md).
