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

Result at 18:00: **61 vertices, 560 links over 54 stops**, 7 stops served by more
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
| A* (Manhattan) expansions | 97199 |
| Reduction | **15.7% fewer** |
| Euclidean vs Manhattan, sampled | 11742 vs 11298 expansions |

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

*Honest limitation:* 15.7% is a modest win because the graph is small — 61
vertices, where any search finishes instantly — and the transfer and walk costs
are large flat numbers that geometry cannot predict, so the guess is weak relative
to the true costs. On a larger network the gap would widen.

---

## Where it stands

60 tests pass with the sandbox container stopped. Phases 6 and 7 — the research
deliverable and the integration work — are outstanding. See
[limitations.md](limitations.md) for what is not done and why it matters.
