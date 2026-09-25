# Plan — finishing the bus route algorithm

> **Update, 2026-09-25.** Verified live against DSTNS 2.0 — see [build-log.md](build-log.md). Next: the Phase 6 research deliverable.
>
> **Update, 2026-09-21 — DSTNS 2.0.** `origin/dstns-v2` retires route publishing
> (Phase B.3), gates run start behind an operator token, and serves a real city
> chosen by seed, which also answers D.1: the larger map is now the normal case,
> not a special one. The module was adapted from that branch's source — see
> [decisions.md](decisions.md#dstns-20) and the first section of
> [limitations.md](limitations.md) — and **has not yet been run against it**.
> Next: run `./bus network` and `./gradlew liveTest` against a live 2.0, recapture
> fixtures ([testing.md](testing.md#recapturing-fixtures-from-dstns-20)), then the
> Phase 6 research deliverable. The text below is the plan as it stood on
> 2026-09-12 and is kept as history.

> **Status, 2026-09-12.** Phases A, B, C and F are done. D.2 is covered by tests.
> D.1 is **blocked**: no endpoint for loading `real_network.osm.xml` has been
> found, and this plan's claim that it is "loadable through the playback API"
> turned out to be an unchecked assumption — establish that first.
>
> Phase B is **verified**: all 21 live tests pass against DSTNS `0fb6307`
> (2026-09-12). The live run found a real bug — `bus_id` is a string, not a
> number — which had also been making the gapped-route test pass for the wrong
> reason.
>
> Built without the propose/approve cycle, by explicit waiver. The code has not
> been read line by line yet.

Written 2026-09-11, from the state after Phase 5. Both algorithms exist and are
tested against saved fixtures. What is missing is everything that turns them into
a system somebody can actually run: a passenger cannot yet ask a question in terms
they would naturally use, nothing has been proven against a live simulator, and
there is no way to run any of it without writing a test.

**The research deliverable is deliberately out of scope here.** The weighted A*
sweep, the benchmark harness, the instrumentation and the plots are being written
by hand separately. Nothing in this plan produces benchmark numbers or performance
claims. Where a step touches something the research component will later need, it
says so and stops there.

Steps are ordered so each one is useful on its own and nothing depends on a step
that comes after it. Sizes are pacing guides, not deadlines.

---

## Where the gaps actually are

Five, found by reading the public surface of what is built rather than by
guessing.

1. **A passenger query cannot be posed in passenger terms.** `AStar.find` takes
   two *transit vertex* ids — internal indices meaning "stop 27 on line R2".
   A real query is "from this junction to that junction", and the walk from a
   starting point to a useful stop, and from the last stop to the destination, is
   not modelled at all. This is the one genuine algorithmic gap left.
2. **The answer is unreadable.** A journey comes back as an array of vertex
   indices. Nothing turns that into "board R2 at stop 27, ride four stops, change
   to R0, ride two more".
3. **The client is read-only.** It can fetch topology, snapshots and edges. It
   cannot control the clock, close a road, or publish a route back.
4. **Nothing has been run against a live simulator end to end.** Every test uses
   saved fixtures, which is right for a test suite and insufficient as proof that
   the module works against the real thing.
5. **There is no entry point.** The only `main` is a smoke test from step 1.2.

---

## Phase A — Passenger queries from anywhere

The algorithmic work. Everything else in this plan is plumbing.

### A.1 — Stop access index

*~40 min. Concept: why a spatial lookup is a separate concern from the graph.*

Given any road junction, find the bus stops a passenger could reasonably walk to,
with the walking time to each. Straight-line distance in projected metres over the
60 stops, filtered by the interchange walking radius.

A linear scan over 60 stops is `O(S)` per query and completely adequate here. Say
so explicitly rather than reaching for a k-d tree or a grid index — those are the
right answer at thousands of stops, and building one for sixty would be a
structure with no defensible reason to exist, exactly like the hash table that was
dropped in step 1.5.

*Proof:* every junction on this map has at least one stop within the radius;
distances match hand-computed values on three junctions; a junction in the middle
of a block finds its surrounding stops and not the far side of the map.

### A.2 — Access and egress legs

*~70 min, the substantial one. Concept: why adding two vertices beats modifying
the search.*

A passenger starting at junction X can board at any stop near X, on any line
serving that stop. That is a *many-source* problem, and there are two ways to
handle it.

The rejected one: seed A*'s open set with several sources at once, each at its own
walk cost. This works, but it changes the search itself, and every property
already proved about A* would need re-proving on the modified version.

The chosen one: add a temporary **source vertex** and **target vertex** to the
transit graph for the duration of one query. The source connects by walk links to
every (stop, line) vertex reachable from X; every (stop, line) vertex near the
destination connects to the target the same way. A* then runs unmodified,
single-source to single-target, exactly as proved. The cost of the walk is a real
edge, so it is paid rather than ignored — the same argument that made transfers
work in step 4.4.

The heuristic needs care: it must stay admissible with the new vertices present.
The target vertex sits at the destination's own coordinates, so `h` measured toward
it is still a straight-line lower bound, and the walk links are slow enough that
the argument in `TransitHeuristic` about walking speed still holds. This must be
asserted, not assumed.

*Proof:* `h ≤ true remaining cost` at every vertex including the two new ones; a
journey between two junctions that are both stops gives the same cost as the
existing stop-to-stop query; a query where walking the whole way is faster than
taking a bus returns the walk; a query to an unreachable destination reports no
journey rather than throwing.

### A.3 — Readable itineraries

*~40 min. Concept: separating the answer from its presentation.*

Turn a vertex path into legs a person can follow: walk to stop, board line, ride
through *n* stops, change, ride, walk to destination. Consecutive ride links on
one line collapse into a single leg. Each leg carries its own duration so the
parts sum to the total.

*Proof:* leg durations sum to the journey's total cost; a journey with no change
produces exactly one ride leg; the leg count matches the transfer count the path
already reports.

---

## Phase B — Live integration

### B.1 — Client write methods

*~45 min. Concept: what changes when a request has a body and side effects.*

Add `POST` and `PUT` to the client: playback start, pause and seek; the edge
control endpoint; and route publishing. Everything so far has been `GET`, which is
safe to retry; these are not, so failures must surface rather than be swallowed.

Two behaviours found while capturing fixtures need encoding rather than
rediscovering: starting a run while one is active returns HTTP 409, and
`play: false` on a seek does **not** pause the clock — it has to be paused
explicitly first or the state drifts away from the time requested.

*Proof:* a pause-then-seek cycle leaves the clock at exactly the requested time;
closing a road and reading it back shows it closed; the 409 surfaces as a clear
error naming the conflict.

### B.2 — Live end-to-end run

*~50 min. Concept: the difference between a unit test and an integration test.*

One path that goes all the way through against a running simulator: set the clock,
fetch topology and snapshot, build the graph and cost model, build the route
network, build the transit graph, answer a query, print it. No fixtures anywhere.

*Proof:* it runs and produces a plausible itinerary. Numbers will not match the
fixture-based tests exactly, because the fixture was captured at one instant and a
live fetch is another — that difference is expected and worth stating, not a bug.

### B.3 — Publish routes back

*~50 min. Concept: designing to someone else's validator.*

`POST /control/transit/route` with `{bus_id, label, nodes[]}`. The server checks
that **every consecutive pair of nodes is joined by a single directed edge** and
does not fill gaps, which is why routes have carried their full junction-by-junction
traversal since step 4.2. This step is where that decision gets paid off or
exposed.

Also needs the return leg: a bus line runs both ways, and the route builder emits
one direction. On this map every road has a reverse twin so the return path is
mechanical, but it has to be built and validated rather than assumed.

*Proof:* all four routes accepted by the server; the routes appear in the
simulator's own view; a deliberately gapped route is rejected, confirming the
validator is real and the full-traversal design was necessary.

---

## Phase C — Command-line entry point

*~45 min. Concept: an interface a person can use without reading the source.*

One `main` with three modes: build and print the network, answer one query between
two junctions, publish the current network to the simulator. Arguments for the
virtual time and the seed, defaulting to the values the design settled on.

*Proof:* each mode runs from a clean checkout with one command, documented in the
README.

---

## Phase D — Behaviour at the edges

Correctness only. No timing, no benchmark numbers — those belong to the research
component.

### D.1 — The larger map

*~40 min. Concept: which results were properties of the algorithm and which were
properties of this particular map.*

The simulator ships `real_network.osm.xml`, a real road topology, loadable through
the playback API. Load it and confirm the module still works: the graph builds,
the reverse-twin invariant holds, Dijkstra and A* agree, routes are produced.

The interesting part is that **the Manhattan heuristic should refuse to run on it**,
because real roads are not axis-aligned. That refusal is the guard from step 5.2
doing its job, and seeing it fire on a real map is stronger evidence than the
skewed three-node test graph. Euclidean should then work unchanged.

If the map does not load, or loads too slowly to be usable, record that and move
on — it is a constraint of the sandbox, not of this module.

*Proof:* graph invariants hold on a non-grid map; Manhattan is refused with a
clear reason; Euclidean produces journeys that still match Dijkstra's costs.

### D.2 — Disruption and failure cases

*~40 min. Concept: what the system does when the answer is "you can't get there".*

Close a road that one of the generated routes runs along, rebuild, and confirm the
network routes around it. Then the cases that must fail gracefully: a destination
no line reaches, a query where origin and destination are the same, a network
where the demand target cannot be met.

*Proof:* a closed road never appears in any route; unreachable queries report no
journey rather than throwing or returning a wrong one; rebuilding after a closure
changes the routes rather than silently keeping the stale ones.

---

## Phase E — Live tests and their documentation

Covered separately and in more detail by [testing.md](testing.md): a test suite
that runs against the live simulator, kept apart from the fixture suite so the
default `./gradlew test` still needs nothing running.

---

## Phase F — Fold it back into the documentation

*~45 min.*

Update [algorithm.md](algorithm.md) with the access and egress model and the
itinerary output, [build-log.md](build-log.md) with an entry per step above,
[decisions.md](decisions.md) with the choices made along the way — the virtual
source and target vertices over a multi-source search above all — and
[limitations.md](limitations.md), striking what is now done and adding whatever
the live runs expose.

---

## Order and rough shape

| Step | What it unlocks | Rough size |
|---|---|---|
| A.1 | Any query that does not start at a stop | 40 min |
| A.2 | Real passenger queries | 70 min |
| A.3 | An answer a person can read | 40 min |
| B.1 | Anything that writes to the simulator | 45 min |
| B.2 | Proof it works outside the test suite | 50 min |
| B.3 | Routes visible in the simulator's UI | 50 min |
| C | Running it without writing code | 45 min |
| D.1 | Knowing which results were map-specific | 40 min |
| D.2 | Sensible behaviour when things break | 40 min |
| F | Documentation matching reality | 45 min |

Phase A is the part with real algorithmic content and the part most worth being
able to explain. If anything gets cut, cut D.1 first and C second — a missing
command-line wrapper is a missing convenience, while a missing access leg means
the passenger pathfinder cannot answer a passenger's actual question.

## What this plan deliberately does not do

- **No benchmark numbers, timing, or the ε-sweep.** That is the research
  component, written separately by hand.
- **No frequency or timetable modelling.** The simulator publishes no headways, so
  a modelled wait would be invented rather than derived. The flat five-minute
  transfer stays, and stays labelled as a limitation.
- **No second demand slice.** Building a network for 08:30 as well as 18:00 and
  comparing them is interesting, but it is a comparison, and comparisons belong to
  the research component.
- **No optimiser.** The greedy heuristic stays greedy, for the reasons in
  [decisions.md](decisions.md).
