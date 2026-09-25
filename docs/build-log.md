# Build log — algorithm module

One entry per step, in the order they were built. Each records what went in, what
proved it, and anything that turned out differently from the plan. The commit
history follows the same sequence, one step per commit.

Dates are 2026.

---

## Phase 0 — Foundations

**0.0 JDK 21** (Homebrew, aarch64 — checked it was not running under Rosetta).

**0.1 Repository** created with a licence, `.gitignore` and a README.

**0.2 Build tooling** (09-04). Picked Gradle over Maven: terser build file, and
committing the wrapper means nobody else installs Gradle. `build.gradle` pins the
Java 21 toolchain and pulls Jackson and JUnit 5. `./gradlew build` green on an
empty project.

**0.3 Hello-world round trip** (09-07). One class, one test, to prove the
source → compile → test loop before anything depended on it. It immediately
earned its keep: Gradle 9.7.1 no longer pulls the JUnit Platform Launcher in
transitively, so `./gradlew test` failed with "Failed to load JUnit Platform"
even though the test was correct. Fixed with an explicit
`testRuntimeOnly 'org.junit.platform:junit-platform-launcher'`.

*Worth saying out loud:* this step proves nothing about the algorithm. That is
the point — finding a toolchain problem with a one-line method beats finding it
while debugging Dijkstra.

---

## Phase 1 — Getting the graph in

**1.1 Domain types** (09-08). `Position`, `Node`, `Edge`, `EdgeConfiguration` as
Java records. `EdgeConfiguration` was split out rather than inlined into `Edge`
because it doubles as the request body for the road-closure endpoint — one type
serving both read and write. `road_class` stayed a plain `String` rather than an
enum, so an unrecognised class from the sandbox does not break deserialization.

**1.1 corrected** (09-09). The first live call against the sandbox — finally
unblocked once Docker was running — showed these types had drifted badly from the
real response, because they were written from documentation and never checked.
`Node` was missing six fields and had `buildingType` where the real key is
`building`. `Edge` carried four fields that do not exist on that endpoint at all.
**Nothing errored.** Tolerating unknown fields meant the wrong data arrived
silently as `null` and zero.

*Lesson, and it repeated later:* tolerating unknown fields protects you from a
field being *added*. It does not protect you from having modelled the wrong thing.

**1.2 HTTP client** (09-09). `SandboxClient` over `java.net.http.HttpClient`, one
reusable instance because a client holds a connection pool. Proved against the
live sandbox: 120 nodes, 436 edges, Paris coordinates — not the city or the
node count our earlier notes had cited, neither of which had ever been checked.

**1.3 JSON wiring** (09-09). `SandboxJson` centralises one `ObjectMapper` policy
— snake_case to camelCase, unknown fields ignored — shared by the client and the
tests, so there is exactly one mapping policy rather than one per call site.

**1.4 Fixtures** (09-09). Real responses saved to `src/test/resources/fixtures/`,
captured live rather than hand-written. Confirmed the suite passes with the
container stopped by actually stopping it, not by reasoning about it.

**1.5 Graph** (09-09). CSR adjacency: one `int[]` of edge ids grouped by source
junction via counting sort, `O(V + E)`, no comparison sort, because `from` is
already a bounded dense integer. Hash table dropped with the reason recorded.
`Graph.of` validates dense 0-indexed ids and throws rather than building a
corrupted graph. Tests assert the reverse-twin invariant across all 436 edges and
that `outgoingEdges` matches the fixture for every junction.

**1.6 Cost model** (09-10).
`cost_ms = round(length_m / max(0.1, effective_speed_mps) × 1000)`, computed once
per snapshot in `O(E)` and then frozen. Needed new types first — `EdgeState`,
`NodeState`, `SnapshotData` — kept separate from the structural `Edge` and `Node`
because those are fixed for the whole run while this changes every tick.

A closed road is *absent*, not expensive: `isTraversable` returns false and asking
for its cost throws.

Captured a new fixture at 18:00 with the clock paused, because the existing one
was taken with the simulator idle and every road at free-flow speed, which would
have made the cost model look trivially correct.

**Verified live, not only in tests.** `PUT /control/edges/42 {"closed":true}`
returned `{"ok":true}`, and the next snapshot showed `closed: true` on edge 42
**while still reporting `effective_speed_mps: 16.67`**. That is the whole
argument for exclusion over weighting in one line: the sandbox publishes a
perfectly healthy speed for a road nobody may drive on. Edge reverted afterwards.

*Proof:* `effective_speed_mps ≤ free_speed_mps` on all 436 edges; three costs
computed by hand and matched exactly (edge 239 at 0.666 m/s → 270116 ms, edge 110
at 11.11 m/s → 16202 ms, edge 425 at 16.67 m/s → 10798 ms); the speed floor
turning a zero speed into 1800000 ms rather than infinity; closing one edge
repricing nothing else; a mismatched snapshot rejected.

*Honest limitation:* the previous sandbox published its own per-edge weight and
the plan was to match ours against it on all 436. That field is gone, so this
proof is genuinely weaker — an invariant, three hand-checks and a closure test
instead of 436 exact matches.

---

## Phase 2 — The priority queue

**2.1 Indexed binary min-heap** (09-10). Array-backed, over dense integer
elements with `long` priorities, offering `insert`, `extractMin`, `decreaseKey`
and `insertOrDecrease`, all `O(log n)`. Three parallel arrays: `heap` (slot to
element), `position` (element to slot, `-1` when absent) and `priority`.
`position` is the whole point — it makes `decreaseKey` a constant-time lookup
followed by a sift, rather than the `O(n)` scan `java.util.PriorityQueue.remove`
would force.

`decreaseKey` rejects a *raise* rather than accepting it, because a raise needs a
sift in the other direction and quietly sifting the wrong way would corrupt the
heap with no error at all.

*Proof:* 2000 randomised trials pushing identical operation sequences through this
heap and `java.util.PriorityQueue`, asserting identical pop order; a 500-element
drain asserting strictly increasing pops; 500 trials of `decreaseKey` mirrored in
the oracle by remove-and-reinsert; misuse rejected rather than silently corrupting
state.

**The oracle test failed on its first run, and the reason is worth keeping.**
Lowered priorities were allowed to collide with priorities already in the heap, so
"which element is smallest" became ambiguous and the two structures disagreed. Not
a heap bug — a test demanding agreement on tie-breaking, which neither structure
promises. Fixed by keeping every priority in play unique.

---

## Phase 3 — Dijkstra

**3.1 and 3.2** (09-10). `Dijkstra` and `ShortestPathTree`, driven by the 2.1
heap. Distances start at "unreachable", a `settled[]` flag enforces the greedy
invariant, and `insertOrDecrease` keeps exactly one queue entry per junction for
the whole run. Closed roads are skipped in the relaxation loop.
`from(source, target)` stops the moment the target settles, correct for the same
reason the invariant is.

Path reconstruction walks predecessors backward and reverses — two passes, one to
measure and one to fill, since the length is not known until the walk is done.

*Proof:* a five-junction graph computed on paper, including the case where the
direct edge (cost 4) loses to the two-hop route (cost 3); agreement with a
Floyd–Warshall brute force over 200 random graphs, all sources and all targets,
chosen as the oracle precisely because it shares no structure with Dijkstra;
every reconstructed path on the real network re-summed edge by edge against its
reported cost; the whole network reachable from junction 0 in exactly 120
expansions; early stopping giving an identical path for no more work; a closed
middle edge stranding the junction behind it.

**3.3 dropped** (09-10). The plan was to cross-check against the sandbox's own
pathfinder. Re-probed at the current HEAD: `paths/compute`, `routes/compute` and
`view/path` all return 404, so it has no HTTP surface. Dropped rather than left
open — 3.1's brute-force comparison and 5.1's Dijkstra-versus-A* agreement cover
correctness between them. Not porting their C++ implementation to Java as a
"reference", which would be this module's code checking itself.

---

## Phase 4 — Algorithm 1, the route network builder

**4.1 Demand** (09-10). `DemandModel` scores every bus stop as the sum of
`impact × activity` over buildings within their own stated radius, with distance
measured straight-line in projected metres because it stands in for a passenger
*walking* to a stop. Added the nested `/view/edges` types and a paged client
method — written generically even though 436 edges fit one page.

**Two assumptions did not survive contact with the sandbox.**

*First:* the obvious model, reading the per-junction `building_effect` straight
off the snapshot, is useless. All 60 stops report **exactly 0.4**, because that
field is the activity level of the *bus-stop role itself*, not a spatial demand
field. It would have produced 60 identical scores. There is now a test asserting
it is flat, so a change in the sandbox surfaces instead of passing unnoticed.

*Second:* the design was going to target the 08:30 morning peak. Sweeping the
whole virtual day at half-hourly steps says otherwise:

| Virtual time | Active buildings | Total stop demand | Stops with demand |
|---|---|---|---|
| 07:30 | 5 | 3.13 | 31 / 60 |
| 08:30 | 11 (7 school, 4 store) | 66.52 | 57 / 60 |
| 09:00 | 21 | 68.92 | 59 / 60 |
| 14:00 | 17 | 86.42 | 53 / 60 |
| 16:00 | 22 | 91.36 | 57 / 60 |
| **18:00** | **23 (all four types)** | **136.21** | **60 / 60** |
| 20:00 | 17 | 70.23 | 53 / 60 |
| 22:30 | 0 | 0.00 | 0 / 60 |

Every office and every mall is completely shut at 08:30 in this simulation. The
real peak is 18:00, so that is the design slice, at seed `0x2026`.

**4.2 and 4.3 The greedy loop and the budget** (09-10). Take the highest-demand
uncovered stop as a hub, Dijkstra from the current end of the line, extend to
whichever reachable stop maximises *newly covered demand ÷ extra driving time*,
then mark served stops and everything within walking distance as covered. That
ratio is the classic greedy set-cover rule. Ties break on the lower stop id, so
the same snapshot always yields the same network.

Every route carries the **full junction-by-junction traversal**, because the
route-publishing endpoint validates that consecutive junctions share a single
directed edge and does not path-fill. Designed in now rather than retrofitted at
integration time.

**The walking radius had to be argued, not defaulted.** The planning rule of thumb
is 400 m. This map is 1980 × 1620 m with the nearest two stops 255 m apart, so at
400 m every stop covers its diagonal neighbours, one route covers 100% of demand
and the algorithm demonstrates nothing. Default set to 250 m, just under the
nearest-stop spacing. A concession to the map being small, stated in the code.

At the defaults — 8 minutes per route, 250 m walk, 90% target — the 18:00 network
is **4 routes covering 94.8% of demand**: R0 with 17 stops over 7.9 min covering
54.18, R1 with 16 stops over 7.5 min covering 32.10, R2 with 17 stops over 7.4 min
covering 30.37, R3 with 11 stops over 7.1 min covering 12.48.

The budget sweep, which is the evidence that the budget binds:

| Minutes per route | Routes | Coverage |
|---|---|---|
| 25 | 2 | 100.0% |
| 15 | 2 | 90.7% |
| 10 | 3 | 92.1% |
| 8 | 4 | 94.8% |
| 6 | 5 | 91.4% |
| 5 | 6 | 96.9% |
| 4 | 7 | 92.4% |

**One test was wrong and the correction matters.** The requirement "no node
covered twice" was first implemented as "no two routes may share a stop". That is
the opposite of what is wanted — a shared stop is exactly where a passenger
changes buses, and forbidding it would leave the transfer edges in the next step
nowhere to attach. Rewritten to assert what the rule actually protects: no stop's
demand counted twice toward coverage.

**4.4 The transit graph** (09-10). A vertex is a **(stop, line) pair**, not a
stop. Riding one stop further on the same line is one edge; changing lines at the
same stop is a *different* edge priced at the wait, so the transfer cost sits on a
real edge the search cannot dodge. Three link kinds: ride, priced at what driving
that stretch actually costs; transfer, a flat five-minute wait; walk, distance at
1.4 m/s plus the wait. Stored CSR-style, the same layout as the road graph.

**The interchange walking radius had to be split from the coverage one.** Built
with the route builder's 250 m the transit graph is **disconnected**: line R1
shares no stop with R0, R2 or R3, so 16 of 61 vertices are unreachable and a
passenger boarding R1 could never leave it. At 300 m and above it is connected.
The two radii answer different questions, so they are two numbers, and both the
split and the fix are asserted in tests so the reason stays visible.

Result at 18:00: **61 vertices, 568 links over 54 stops**, 7 stops served by more
than one line, fully connected.

---

## Phase 5 — Algorithm 2, the passenger pathfinder

**5.1, 5.2 and 5.3** (09-10). `AStar`, `TransitHeuristic` and `TransitPath`.
Ordered by `f = g + h` on the same heap; the guess steers the queue and never
enters the reported cost, which stays `g`. A zero heuristic makes it *exactly*
Dijkstra, asserted rather than claimed. `h(v) = distance(v, goal) / 16.67 m/s` in
projected metres, Manhattan by default with Euclidean available.

Results at 18:00, over every ordered pair of the 61 vertices:

| Measure | Value |
|---|---|
| Journeys compared | 3660 |
| Cost disagreements with Dijkstra | **0** |
| Dijkstra vertex expansions | 115290 |
| A* (Manhattan) expansions | 96956 |
| Reduction | **15.9% fewer** |
| Euclidean vs Manhattan, sampled | 11752 vs 11334 expansions |

**The distance form was resolved by measurement, not argument.** Euclidean had
been settled on the premise that the map is real road layout. It is an
axis-aligned lattice, so both were implemented and Manhattan wins as the tighter
admissible bound. The precondition is enforced, not assumed: every one of the 436
edges is checked for axis alignment, and asking for Manhattan on a map with a
diagonal edge throws. Tested against a deliberately skewed graph.

One subtlety worth having ready: a walk link cuts diagonally between two stops, so
Manhattan overestimates the *distance* there. It still cannot overestimate the
*time*, which is what the heuristic returns — Manhattan is at most √2 times the
straight line, so the guess is at most `d / 11.8` seconds while walking that same
`d` at 1.4 m/s takes `d / 1.4` seconds and then adds a wait.

Hand checks on real vertices:

| Journey | dx, dy | Manhattan h | Euclidean h | True cost |
|---|---|---|---|---|
| v0 (stop 77, R0) → v30 (stop 36, R1) | 900, 540 m | 86382 ms | 62961 ms | 673767 ms |
| v5 (stop 60, R0) → v44 (stop 70, R2) | 1800, 0 m | 107978 ms | 107978 ms | 615763 ms |
| v12 (stop 53, R0) → v58 (stop 116, R3) | 540, 900 m | 86382 ms | 62961 ms | 725909 ms |

Both forms sit far below the truth, and Manhattan is the larger whenever the
journey is not purely axis-parallel — "tighter, still admissible" in numbers. The
middle row is the sanity check: with `dy = 0` the two must coincide, and they do.

*Proof:* identical costs to Dijkstra on all 3660 pairs with strictly fewer
expansions; Manhattan never expanding more than Euclidean; `h ≤ true remaining
cost` at every vertex of every pair for both forms, with the closest approach
1 ms; consistency `h(u) ≤ cost(u,v) + h(v)` across every link toward every goal;
journeys re-summed link by link; the zero heuristic proven identical to Dijkstra;
Manhattan refused on a skewed map.

*Honest limitation:* 15.9% is a modest win because the graph is small — 61
vertices, where any search finishes instantly — and the transfer and walk costs
are large flat numbers that geometry cannot predict, so the guess is weak relative
to the true costs. On a larger network the gap would widen.

---

## Phase A — Passenger queries from anywhere

**A.1** (09-12). `StopAccessIndex` and `StopAccess`. Given any junction, the
stops a passenger could walk to and how long each walk takes, by linear scan
over the served stops in projected metres. `O(S)` per query, sixty stops, so
sixty distance computations. A k-d tree is the right answer at thousands of
stops and a structure with no defensible reason to exist at sixty — the same
call made against a hash table in step 1.5, and the reason is written into the
class so nobody re-opens it.

*Proof:* every one of the 120 junctions has a served stop within 400 m; a stop
sitting on the junction comes back as a zero-length walk rather than being
skipped; results come back nearest-first; 180 m — this map's grid pitch —
converts to 128571 ms at 1.4 m/s, computed by hand and asserted.

**A.2** (09-12). `TransitGraph.withEndpoints`. A source and a target vertex
spliced into the graph for the duration of one query, joined by real walk edges
to every (stop, line) vertex in reach.

The alternative was seeding A*'s open set with many sources at once. It works,
but it changes the search, and every property already proved — admissibility,
settling each vertex once, the first pop of the goal being final — would have
needed re-proving against the modified version. Adding two vertices reduces the
same problem to the plain single-source case A* already handles, and the walk
gets *paid* on a real edge rather than assumed away, which is the same argument
that made transfers work in step 4.4.

The heuristic needed checking, not assuming: the target sits at the
destination's own coordinates, so a straight line to it is still a lower bound,
and the egress walk at 1.4 m/s is far slower than the 16.67 m/s the guess
divides by.

*Proof:* `h ≤ true remaining cost` at every vertex of the extended graph,
including both new ones, against costs Dijkstra computed exactly; the original
graph is unchanged after a query, so one query cannot leak into the next; the
target has no outgoing links; a journey between two junctions that are both
stops costs exactly what the old stop-to-stop query costs.

**A.3** (09-12). `Itinerary`. The vertex path collapsed into instructions —
walk, ride *n* stops, change, ride, walk — with consecutive rides on one line
merged into a single leg. Presentation, deliberately kept out of the search.

This also corrected `TransitPath.transfers()`, which counted every non-ride
move. Walking from your front door to the first stop is not a change of bus.

*Proof:* leg durations sum to the journey's total; a journey with no change
produces exactly one ride leg; the number of change-of-line legs equals the
reported transfer count.

**A test that was wrong, and what it taught.** The first version asserted that
walking 180 m beats the bus. It does not: boarding the first bus is free in this
model, so a one-block ride wins. That is a stated simplification of the transit
graph surfacing in a new place, not a bug, and the test was rewritten to assert
what is actually guaranteed — that an answer is never *worse* than walking when
walking is possible.

---

## Phase B — Live integration

**B.1 and B.3** (09-12). `SandboxClient.publishRoute` and `RoutePublisher`.
`POST /control/transit/route` validates that every consecutive pair of nodes is
joined by a single directed edge and does not fill gaps, which is why routes
have carried their full junction-by-junction traversal since step 4.2. The same
check runs locally first: being told which pair of nodes has no edge between
them beats being told "invalid route" after a round trip.

The return leg is the outbound path read backwards, valid only because every
road here has a reverse twin — a property of this map, so it is verified rather
than assumed. Each line is published twice, at bus ids `2n` and `2n+1`, so the
two directions do not overwrite each other.

*Proof, offline:* all four generated routes are continuous, and so are their
return legs; removing a single intermediate junction from a real route makes it
fail, so the check is not vacuous; all 436 edges have a reverse twin.

*Proved live* (09-12): all four lines accepted in both directions, and a
stops-only route rejected with "not directly adjacent".

**The live run found a real bug immediately.** `bus_id` is a **string** on this
endpoint, not a number; sending an integer is answered
`400 INVALID_FIELD_TYPE`. The field *names* had been recorded when the API was
surveyed, but not their *types*, and no fixture can catch that — a saved
response carries no record of what was sent.

Worse, it made the gapped-route test **green for the wrong reason**: the server
rejected the request over field types before it ever looked at the nodes, so the
assertion that a gap is rejected was passing without testing a gap. The test now
also asserts the complaint mentions adjacency, not field types.

---

## Phase C — Command line

**C** (09-12). `Main`. Three modes — `network`, `journey FROM TO`, `publish` —
with `--time`, `--seed` and `--sandbox`, defaulting to the values every figure
in these docs was measured at. Argument parsing and rendering are kept apart
from the fetching so they stay testable with the simulator stopped.

*Proof:* defaults and overrides parse; six malformed command lines are rejected
with a usage message; a junction id off the map is refused before any search
runs; the network and a journey render against fixtures. Verified by running the
installed binary: a refused connection prints "Could not reach the sandbox:
ConnectException" and exits 1, and an unknown mode prints usage and exits 2.

---

## The map changed underneath the module (2026-09-14)

**What happened.** DSTNS moved from `0fb6307` to `5f75fa5`, which switched its
default map from the synthetic grid to a real OpenStreetMap extract of Berlin
Mitte. The offline suite still passed 124/124 — it was testing saved responses
from a map that no longer existed. The live suite dropped to 16/21.

`5f75fa5` did not build as committed: `ui-engine/tests/mapFocus.test.tsx` passes
`exact: true` to `getByRole`, which `tsc -b` rejects with `TS2769`. The image used
here was built with that option removed locally; the fix belongs upstream.

**One real defect.** Publishing failed with "Node #32 and Node #242 are not
directly adjacent", though `/view/topology` lists that edge. A sweep of all 612
edges as two-node routes found exactly 139 refused, and they are exactly the 139
flagged `source.synthetic_reverse` on `/view/edges` — the fabricated reverse of
every one-way street. They are also the only 139 edges with zero demand. The
module had parsed that flag and ignored it.

*Fix:* `EdgeDetail.syntheticReverseIds`, a three-argument `CostModel.of` that
excludes those ids, used by `Main`, the live suite and every fixture-based test.
`RoutePublisher` checks the reverse is traversable and publishes one-way lines
outbound only; its local continuity check ignores excluded edges so it agrees
with the server.

*Proof:* `publish` is accepted; the live publish test asserts the exact count,
two per line minus the one-way lines.

**Also fixed.** The CLI died with `409 LIFECYCLE_CONFLICT` on every run after the
first, because it started playback without stopping the previous run. It now
stops first. `--attach` reads the running simulation without touching its clock.

*Proof:* two consecutive `network` runs both succeed; `run_id` from
`/api/v1/playback/status` is identical before and after a `--attach` journey.

**Fixtures recaptured** at seed `0x2026`, clock paused, 08:30 and 18:00.
Hardcoded Paris figures re-derived; the coverage radius retuned to 150 m; tests
that asserted the grid rewritten to assert the heuristic guard. See the
[decision log](decisions.md#the-map-change-2026-09-14).

---

## Where it stands

125 tests pass with the sandbox container stopped, and all 21 live tests pass
against DSTNS `5f75fa5` (with the `mapFocus.test.tsx` build fix applied locally),
verified 2026-09-14.

At 18:00 the route builder produces 2 lines covering 100% of demand on the
Berlin extract. On live data A* agrees with Dijkstra on every journey cost while
expanding 6.8% fewer vertices — a smaller saving than on the grid, because
Euclidean is a looser bound than Manhattan and the transit graph is tiny.

The research deliverable — the weighted-A* sweep across network sizes — is
outstanding, and this map is too small to answer it. See
[limitations.md](limitations.md).

## 2026-09-21 — catching up to DSTNS 2.0

**What happened.** The sandbox track published `origin/dstns-v2` (`bc6e517`,
18 commits past `dstns`). Docker was down and nothing was run; the whole adaptation
is from reading its source and this module's own code end to end first.

**What the reading found, and what each became.**

| Found | Consequence if left | Now |
|---|---|---|
| Route publishing returns 410 | `publish` fails | export to `routes.json` / `routes.geojson` |
| Start needs an operator token; `stop` is not gated | `Main` stopped the run, then was refused — the user's run destroyed | token checked before any stop; attach is the default |
| Start is async, a city may be downloading | seek races the preparation | `awaitReady` |
| Building demand needs tagged *junctions* | probably all-zero demand, so an empty network | places, then buildings, then uniform; source printed |
| Flagged stops are raw OSM, unthinned | duplicate stops inflate demand and lines | merge within 60 m |
| Budget and heuristic constants tuned to Berlin | unreachable coverage target; a possible inadmissible heuristic | derived from the map |
| Topology now carries `synthetic_reverse` | a needless paged request | trusted when present, `/view/edges` when not |
| `Main` and the live setup duplicated the loading pipeline | the next fix reaches one of them | one `WorldLoader` |

**Also changed.** The command line became `./bus` with `network`, `stops`,
`journey` and `export`, and every report opens with the run, map, stop count,
demand source and budget, then says why the builder stopped and whether the
lines connect.

**Numbers.** 207 offline tests, up from 125, all green. The existing Berlin
fixtures are untouched and still pass; every new behaviour is proved on small
hand-built data.

**Not done, and not knowable from here.** Nothing has run against a live 2.0. The
places payload, the stop anchors and the lifecycle strings are readings of source.
[limitations.md](limitations.md) lists each and what happens if it is wrong.


## 2026-09-25 — the first live run against DSTNS 2.0

**What happened.** DSTNS 2.0 (`origin/dstns-v2` `bc6e517`) was built from source
in a scratch worktree and run with an operator token. `./bus network --seed
0x2026` worked end to end on the first try — Dar es Salaam, 3000 junctions, 6330
roads, 72 stops after merging — and the live suite passed 16 of 20. The four
failures were tests pinned to the Berlin map. The run itself exposed two real
defects that no offline test could have: every stop on one demand figure, and no
line with a return.

**What it found, and what each became.**

| Found live | Now |
|---|---|
| 11 `bus_stop` places, 1 on a flagged stop: 71 of 72 stops at baseline | `CATCHMENT` demand from 3,246 active generators; 2,940 within reach of a stop |
| All 6 lines one-way: they follow dual carriageways | the return routed on its own through the outbound stops in reverse |
| 13 of 81 stops in one-way pockets at the district's edge | `StrongComponents`; a line reaches only for stops it can come back from |
| Reverse rides offered on one-way lines | return rides only from a real return service |
| Seek is synchronous; stop is idempotent; a seek revives a STOPPED run | attach waits out STOPPING, notes STOPPED, refuses ERROR |
| The suite restarted a real city per test: 7 min 53 s | one run per suite: 1 min 9 s |

The review of the same code also fixed:
- **Exit statuses** that called bad simulator data "bad usage".
- **Unchecked reverse twins.** A missing `reverse_twin` silently meant edge 0.
- **Stops beside the hub** that could never be driven to.
- **The heuristic divisor**, which did not cover a sped-up road.

**Numbers, live at 18:00.** 6 lines, every one both ways, 91.3% of demand
(stopped at the 90% target); every one of the 3000 junctions within 400 m of a
served stop. A\* agrees with Dijkstra on all 6,642 journeys tested and expands
9.4% fewer vertices. Before the fixes the same map gave 6 lines, all one-way,
67.3% of a demand figure that ranked nothing.

**Numbers, offline.** 225 tests, up from 207, all green; 20 live tests, all green
against 2.0. The 2.0 captures are committed as fixtures, gzipped.
