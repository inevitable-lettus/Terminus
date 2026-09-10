---
name: csc210-algorithm-build-order
description: "The step-by-step build plan for the CSC210 algorithm module — 7 phases from JDK install to the weighted-A* research deliverable, with a live current-stage tracker"
metadata:
  type: project
---

# Build Order — CSC210 Algorithm Module

Written 2026-08-23. Supersedes the concept list in
[[csc210_algorithm_learning_plan]] (that file stays as the mental model of how
the sandbox works; this file is the executable plan).

**Every step runs through the six-stage cycle in
[[csc210_algorithm_build_protocol]]** — propose in chat, Garv reads line by
line, explicit approval, then write, verify, record. No exceptions.

## Settled decisions this plan assumes

| Decision | Choice | Where argued |
|---|---|---|
| Language | **Java** (JDK 21) | [[csc210_java_decision]] |
| Repo | **New sibling repo** `CSC210/csc210-algorithm/` | this file, Phase 0 |
| Edge cost | **Recomputed from raw fields** — no server-side weight to cross-check any more (2026-09-06) | [[csc210_sandbox_data_layer]] |
| Sandbox Dijkstra | **Test oracle only** — but it is no longer exposed over HTTP (2026-09-06) | [[csc210_sandbox_data_layer]] |
| Heap | **Hand-written indexed binary heap** with `decreaseKey` | [[csc210_java_decision]] |
| Route design method | **Greedy demand-covering**, not GA/SA/tabu | [[csc210_route_generation_scope]] |

## Current stage

> **Phase 1, step 1.1 done — next up 1.2 (HTTP client).**
>
> 0.0 done: JDK 21 (Homebrew, aarch64) installed.
> 0.1 done: repo, `.gitignore`, `README.md`, `DESIGN.md` all present.
> 0.2 done (2026-09-04): Gradle chosen over Maven — terser committed
> `build.gradle`, wrapper means the team never installs Gradle themselves.
> Wrapper bootstrapped via a one-time `brew install gradle`; `build.gradle`
> (Java 21 toolchain, Jackson, JUnit 5) and `settings.gradle` written;
> `./gradlew build` green on the empty project (`BUILD SUCCESSFUL`, no
> sources yet). `.gitignore` extended for `.gradle/`, `build/`, IDE files.
> 0.3 done (2026-09-07): `csc210.algorithm.Hello` + `HelloTest`, one
> assertion, proves the full source→compile→test loop. Hit a real build-tool
> bug along the way: Gradle 9.7.1 no longer pulls the JUnit Platform Launcher
> in transitively from `junit-jupiter`, so `./gradlew test` failed with
> "Failed to load JUnit Platform" even though the test code was correct. Fix:
> added `testRuntimeOnly 'org.junit.platform:junit-platform-launcher'` to
> `build.gradle`. `./gradlew test` now green —
> `csc210.algorithm.HelloTest > greetReturnsExpectedMessage() PASSED`,
> `BUILD SUCCESSFUL`.
>
> **How to explain this step to the prof:** "Before writing any graph code I
> proved the toolchain end to end with a throwaway class and one test — if
> something's wrong with compilation or test wiring, I want to find that with
> a one-line method, not while debugging Dijkstra. It actually caught a real
> issue: Gradle 9 stopped auto-including the JUnit test launcher, so the
> build failed for a tooling reason, not a code reason, and I had to add one
> explicit runtime dependency to fix it." Honest limitation: this step proves
> nothing about the algorithm — it's pure infrastructure, and it's meant to.
>
> 1.1 done (2026-09-08): `Position`, `Node`, `Edge`, `EdgeConfiguration` as
> Java records in `csc210.algorithm.model`. `Node` mirrors the *flat*
> `/view/topology` node shape (not the nested `/view/nodes` shape — a real
> schema drift in DSTNS), so it carries `busStop`/`signal` as bare booleans
> and `buildingType` as a nullable String, no building-impact detail (that's
> dynamic, belongs to Phase 4's demand read from `/view/snapshot`). `Edge` is
> deliberately structural only — no `traffic`/`weather` fields — since those
> are live per-tick state Phase 1.6 will source separately, not something to
> freeze into a one-time topology fetch. `EdgeConfiguration` was split out as
> its own record (not inlined into `Edge`) because it doubles as the request
> body shape for `PUT /control/edges/{id}` — one type serving both read and
> write. `roadClass` stayed a plain `String`, not an enum, to stay resilient
> if DSTNS adds a road class we haven't enumerated — consistent with the
> "tolerate unknown fields" stance planned for step 1.3. `DomainTypesTest`
> constructs one of each and asserts every field; `./gradlew test` green,
> 5/5 tests (`DomainTypesTest` ×4 + `HelloTest` ×1), 0 failures.
>
> **How to explain this step to the prof:** "These are pure data records
> mirroring exactly what DSTNS's `/view/topology` endpoint returns — no
> logic, nothing invented. The one interesting call is that DSTNS actually
> serialises the same node information two different ways on two different
> endpoints, and I modelled against the one I'm actually going to fetch from,
> not the one that happened to be documented with a nicer example. I also
> kept `EdgeConfiguration` separate because it's literally reusable as the
> request body when I later *write* an edge override, not just when I read
> one." Honest limitation: none of this has been checked against a real
> topology response yet — that happens at step 1.4 (fixtures) — so there's a
> real chance the flat node shape needs a field added once I see it live.
>
> **1.1 corrected (2026-09-09):** first live hit against the real sandbox
> (finally unblocked — Docker Desktop wasn't running before) showed `Node`
> and `Edge` had drifted from the real `/view/topology` shape, since 1.1 was
> written from the doc/OpenAPI spec, never checked live. Fixed: `Node` was
> missing `busStopRadiusM`, `signalCycleS`, `signalGreenS`, `signalOffsetS`,
> `buildingImpact`, `buildingRadiusM`, and had `buildingType` where the real
> key is `building` (silently deserialized to `null` before the fix — no
> error, just wrong data, exactly what "tolerate unknown fields" doesn't
> protect against). `Edge` had four fields that don't exist on this endpoint
> at all — `osmWayId`, `syntheticReverse`, `baseCapacityVph`, `control` —
> all silently defaulting instead of erroring. Both fixed against the real
> payload, `EdgeConfiguration` flagged as unverified (write-only shape, no
> live GET checked it). `DomainTypesTest` updated to match, still 4/4 green.
>
> 1.2 done (2026-09-09): `SandboxClient` (`client/` package) over
> `java.net.http.HttpClient`. `health()` hits root `/health` (unwrapped);
> `topology()` hits `/api/v1/view/topology`, parses the `Envelope` and
> returns typed `TopologyData`; `snapshot()` returns the raw `Envelope` —
> left untyped since nothing needs its shape until step 1.6. Proven against
> the live sandbox (`docker compose up -d --build` in the group repo's
> `dstns` branch, worktree at `/tmp/dstns-worktree`): 120 nodes, 436 edges,
> Paris coordinates (not the Berlin figure this file used to cite — stale,
> now corrected below), `graph_hash` present.
>
> 1.3 done (2026-09-09): `SandboxJson` centralizes the snake_case +
> tolerate-unknown-fields `ObjectMapper` config, shared by `SandboxClient`
> and tests so there is exactly one mapping policy. `TopologyData` and
> `Bounds` added as the typed shape of `/view/topology`'s `data` payload.
>
> 1.4 done (2026-09-09): real `topology.json` / `snapshot.json` responses
> saved to `src/test/resources/fixtures/` (captured live, not hand-written).
> `TopologyDataTest` deserializes the fixture with no sandbox running,
> asserts exact counts (120/436) and known node/edge field values.
> `./gradlew test` green — 8/8 — with the container stopped, confirmed by
> actually stopping it and rerunning, not just by reasoning about it.
>
> **Open item resolved:** "nothing has been built or run locally" is no
> longer true as of this step — and it caught a real bug in already-approved
> 1.1 work, which is the whole reason that check existed.
>
> 1.5 done (2026-09-09): `Graph` (new `graph/` package) — CSR-style
> (compressed sparse row) flat-array adjacency, proposed and approved before
> writing per Garv's split (scaffolding moves fast, algorithm logic goes
> through full propose→review→approve→write). One `int[]` of edge ids
> grouped by source node via counting sort — O(V+E), no comparison sort,
> since `from` is already bounded `[0, V)`. Hash table dropped for now
> (decision from the 1.5 doc): node ids are already dense, nothing sparse
> to map yet; `osmNodeId` stays on `Node` for whenever that changes.
> `Graph.of` validates dense 0-indexed ids at construction and throws
> rather than silently building a corrupted graph — a real defensive check,
> not decorative, since nothing upstream had verified that assumption
> before this step. `GraphTest` builds from the 1.4 fixture: counts match
> (120/436), every node/edge sits at its own array index, reverse-twin
> invariant holds for all 436 edges, `outgoingEdges` matches the fixture
> exactly for every node, and a non-dense input is rejected. 14/14 tests
> green project-wide.
>
> 1.6 done (2026-09-10): `CostModel` (new `cost/` package) —
> `cost_ms(e) = round(length_m / max(0.1, effective_speed_mps) * 1000)`,
> integer milliseconds, computed once per snapshot in O(E) and frozen.
> Needed three new types first: `EdgeState` / `NodeState` (the live per-tick
> shape from `/view/snapshot`, deliberately separate from the structural
> `Edge`/`Node`, which are fixed for the whole run) and `SnapshotData`;
> `SandboxClient.snapshot()` went from returning a raw `Envelope` to the
> typed `SnapshotData`. A closed edge is *absent*, not expensive —
> `isTraversable(id)` returns false and `costMillis(id)` throws, so a closed
> road can never silently contribute a number to a path cost.
>
> New fixture `snapshot_0830.json` captured live at seed `0x2026`, virtual
> time 08:30:00, clock PAUSED — the old `snapshot.json` was captured IDLE
> with every edge at free-flow, which would have made the cost model look
> trivially correct. `edges_0830.json` (the nested `/view/edges` shape, with
> `demand_vph`) captured at the same instant for Phase 4. A `Fixtures` test
> helper now loads all of them in one place.
>
> Verified live, not just in tests: `PUT /control/edges/42 {"closed":true}`
> returned `{"ok":true,"state_revision":1}` and the next snapshot showed
> `"closed": true` on edge 42 — **while still reporting
> `effective_speed_mps: 16.67`**. That is the whole argument for exclusion
> over weighting in one line: DSTNS keeps publishing a perfectly healthy
> speed for a road nobody may drive on, so anything that priced closure from
> speed alone would route straight through it. Edge reverted afterward.
>
> `CostModelTest`: `effective_speed_mps <= free_speed_mps` on all 436 edges,
> three hand-computed costs matched exactly (edge 239 at 0.666 m/s →
> 270116 ms, edge 110 at 11.11 m/s → 16202 ms, edge 425 at 16.67 m/s →
> 10798 ms), the 0.1 m/s floor turns a zero speed into 1800000 ms rather
> than infinity, closing one edge reprices nothing else, and a snapshot that
> does not match the topology is rejected. 20/20 tests green project-wide.
>
> **How to explain this step to the prof:** "The cost of an edge is just
> distance over speed, in milliseconds, using the live speed the simulator
> reports rather than the speed limit — so traffic, rain and signal phase all
> show up in the number. Two deliberate choices. First, cost lives on edges,
> not junctions, because that's where the physical quantity is: a road has a
> length and a speed, a crossroads doesn't. Second, a closed road is removed
> from the search rather than given a huge weight — I checked live that the
> sandbox still reports a normal 16.67 m/s speed on an edge it has marked
> closed, so if I'd priced closure by speed I'd have routed traffic straight
> through a shut road. And the model is a snapshot, frozen at one instant,
> because the weights genuinely oscillate second by second as signals cycle —
> searching against a moving weight measures the weather, not the algorithm."
> Honest limitation: DSTNS used to publish its own computed edge weight and
> we could check ours against it edge for edge. It doesn't any more, so this
> proof is weaker — an invariant, three hand-checks and a closure test rather
> than 436 exact matches.
>
> 2.1 done (2026-09-10): `IndexedMinHeap` (new `ds/` package) — array-backed
> binary min-heap over dense int elements with `long` priorities, and
> `insert` / `extractMin` / `decreaseKey` / `insertOrDecrease`, all O(log n).
> Three parallel arrays: `heap[]` (position → element), `position[]`
> (element → position, `-1` when absent) and `priority[]` (element →
> priority). `position[]` is the whole point — it is what makes
> `decreaseKey` an O(1) lookup followed by a sift, instead of the O(n) scan
> `java.util.PriorityQueue.remove(Object)` would force.
>
> Priorities are `long` milliseconds, not doubles, so two equal-cost paths
> really do compare equal rather than differing in the last bit.
> `decreaseKey` rejects a *raise* rather than accepting it, because a raise
> needs a sift-down and quietly sifting the wrong way would corrupt the heap
> without any error.
>
> `IndexedMinHeapTest`: 2000 randomised trials pushing identical operation
> sequences through our heap and `java.util.PriorityQueue`, asserting
> identical pop order; a 500-element drain asserting strictly increasing
> pops; 500 trials of `decreaseKey` mirrored in the oracle by
> remove-and-reinsert; a direct root-reaching `decreaseKey`; and misuse
> (double insert, raise, out-of-range element, pop from empty) rejected
> rather than silently corrupting state. 26/26 green project-wide.
>
> **The oracle test failed on its first run, and the reason is worth
> keeping.** Lowered priorities were allowed to collide with priorities
> already in the heap, so "which element is the minimum" became ambiguous
> and the two structures disagreed — not a heap bug, a test that demanded
> agreement on tie-breaking, which neither structure promises. Fixed by
> keeping every priority in play unique. This is exactly the point step 3.3
> makes about comparing costs rather than node sequences, arrived at the
> hard way one phase early.
>
> **How to explain this step to the prof:** "A priority queue is a bag you
> can throw things into in any order and always pull the smallest out of. I
> wrote my own as a binary heap in a flat array — the tree is implicit, the
> children of slot i are at 2i+1 and 2i+2, so there are no pointers and no
> objects per node. The reason I didn't just use Java's built-in one is
> `decreaseKey`. Dijkstra keeps finding better routes to a node that's
> already sitting in the queue, and the standard library has no way to say
> 'that thing in there just got cheaper'. The usual workaround is to push a
> duplicate and ignore the stale copy later, which grows the queue from one
> entry per node to one per edge. I keep an extra array saying where each
> node currently sits, so I can find it instantly and just bubble it up." 
> Honest limitation: the heap is fixed-capacity and its elements have to be
> dense integers from 0 upward. That's fine because DSTNS hands out node ids
> exactly that way, but it is not a general-purpose priority queue and I
> wouldn't hand it to someone else as one.
>
> **Next up: 3.1 — Dijkstra.**
>
> _Update this block at the end of every completed step — see
> [[csc210_algorithm_build_protocol]] stage 6._

> **⚠ SANDBOX REWRITTEN — 2026-09-06.** Varun replaced `sumo_control` with **DSTNS**
> (group repo, branch `dstns`, commit `257257a`). New port, new API paths, new cost
> model, integer node ids, no path-compute oracle, no `/simulation/step`, no
> `demand_score`, bus stops now placed by the sandbox. [[csc210_sandbox_data_layer]] is
> rewritten for it. **Phase 0 is unaffected — 0.3 proceeds as planned.** Phases 1, 3, 4,
> 5 and 6 have revisions marked inline below; read them before proposing those steps.

---

## Phase 0 — Foundations

Nothing algorithmic yet. Get a toolchain and an empty project that builds.

**0.0 — Install JDK 21.**
Homebrew: `brew install openjdk@21`, then symlink it where macOS's
`java_home` will find it. Verify `java -version` reports 21 and the
architecture is `aarch64`, not `x86_64` under Rosetta.
*Concept:* what a JDK actually is versus a JRE, and why the version gates
which language features exist.
*Proof:* `java -version` prints 21.

**0.1 — Create the repo.**
`CSC210/csc210-algorithm/`, `git init`, `.gitignore`, `LICENSE`, a `README.md`
stating what the module is and what it talks to.
*Proof:* `git log` shows one initial commit.

**0.2 — Build tooling.**
Gradle wrapper, one readable `build.gradle` (~15 lines): Java 21 toolchain,
Jackson for JSON, JUnit 5 for tests. Nothing else.
*Concept:* what a build tool does that `javac` does not — dependency
resolution, reproducible builds, test running. Why a wrapper is committed.
*Decision to flag at proposal time:* Gradle vs Maven — Maven is already
installed on the machine and is more common in university courses; Gradle is
terser. Garv picks.
*Proof:* `./gradlew build` succeeds on an empty project.

**0.3 — Hello-world round trip.**
One class, one JUnit test, both passing. Confirms the whole loop works before
any real code depends on it.
*Proof:* `./gradlew test` — one test, green.

---

## Phase 1 — Get the graph in

This is the "graph ingestion" step of [[csc210_algorithm_learning_plan]], split
into pieces small enough to read in one sitting.

**1.1 — Domain types.**
`Position`, `Node`, `Edge`, `EdgeConfiguration` as Java **records**.
*Concept:* records — what they generate for you (constructor, accessors,
`equals`, `hashCode`, `toString`) and why immutable value types are the right
shape for data fetched from an API. Also: why these mirror the sandbox's JSON
schema exactly rather than being "improved" at this layer.
*Proof:* a test constructs one of each and asserts field values.

**1.2 — HTTP client.**
`SandboxClient` over `java.net.http.HttpClient`, base URL
**`http://127.0.0.1:8090/api/v1`** (revised 2026-09-06). Methods for
`GET /health`, **`GET /view/topology`** (the whole graph in one call) and
`GET /view/snapshot` (live edge state). No bearer token in DSTNS.
*Concept:* what an HTTP GET is on the wire, status codes, why the client is
one reusable object rather than one per call.
*Proof:* run against the live sandbox (`docker compose up` in
`csc210-sandbox/`), print the node count from `/status`.

**1.3 — JSON to objects.**
Jackson `ObjectMapper` wiring, mapping the API's `snake_case` onto Java's
`camelCase`, tolerating unknown fields so a sandbox update cannot break us.
*Concept:* deserialisation; why tolerating unknown fields is the right default
for a contract someone else owns.
*Proof:* deserialise a saved JSON fixture, assert a known node's `id` and
`position`.

**1.4 — Fixtures.**
Save one real `/view/topology` and one real `/view/snapshot` response into
`src/test/resources/` (or a copy of DSTNS's own `logs/global_view.json`, which
already contains both). Every later test runs against these, so tests do not
need the sandbox running.
*Concept:* why tests that depend on a live external service are bad tests.
*Proof:* `./gradlew test` passes with the sandbox stopped.

**1.5 — The graph structure. (First real DSA content.)**
Hand-written adjacency list, edges stored in flat arrays indexed by node id.

*Revised 2026-09-06:* DSTNS node ids are **already dense 0-indexed integers**
(system invariant), so there are no string ids left to intern. Decide at
proposal time which way to take the hash table so it stays honest rather than
decorative: (a) drop it here and introduce it where it genuinely earns its
place later, or (b) keep a `HashMap<Long,Integer>` from `osm_node_id` (a sparse
`int64`) to our dense index, which is a real sparse-to-dense mapping. Whichever
is chosen, the *reason* must survive a TA asking "why is this here?"
*Concept:* adjacency list vs adjacency matrix, and why a sparse road network
(each junction has ~2–4 neighbours) makes the matrix wasteful — `O(V²)` memory
for `O(E)` real edges. What "interning" means and why dense integer ids make
every later array lookup `O(1)`. Hash table from the required toolkit enters
here, legitimately.
*Proof:* build the graph from fixtures; assert node/edge counts match the API;
assert DSTNS's **reverse-twin invariant** holds exactly — for every edge `i`,
`edges[edges[i].reverse_twin].reverse_twin == i`. (Revised 2026-09-06: this is
the DSTNS spelling of the old bidirectionality guarantee, and it is a sharper
assertion than the old one.)

**1.6 — Cost model, recomputed.** *(Rewritten 2026-09-06 — the old formula is dead.)*
```
cost_ms(e) = round( length_m(e) / max(0.1, effective_speed_mps(e)) * 1000 )
```
integer milliseconds, matching DSTNS's own `RoutePlanner`. An edge with
`closed == true` is skipped entirely — infinite cost, not a large finite one.
`effective_speed_mps` is read live from `/view/snapshot`; DSTNS derives it from
free-flow speed times signal, rain, flood and manual multipliers, rate-limited
so it eases toward its target rather than jumping.
*Concept:* why the multipliers are multiplicative and not additive — congestion
slows a road in proportion to its length, so a flat additive penalty would
punish a 50 m street and a 5 km highway identically. Why the clamps exist
(never divide by zero, never let a road become infinitely fast). Why costs live
on edges, not nodes. New: why *skipping* a closed edge is not the same as giving
it a huge weight, and why signal phase makes weights oscillate second to second.
*Proof — weaker than it used to be, say so out loud:* DSTNS no longer publishes
a precomputed `effective_weight_s`, so the old "match the server's number on
every edge" check is impossible. Replacement: over every edge in the fixture
assert `effective_speed_mps <= free_speed_mps`, assert closed edges are excluded
from the adjacency used for search, and assert our arithmetic against a
hand-computed value on a handful of edges. Honestly a regression in test
strength — worth one sentence in the writeup.

---

## Phase 2 — The priority queue

**2.1 — Indexed binary min-heap.**
Array-backed binary heap with `insert`, `extractMin`, `decreaseKey`, and a
position index so `decreaseKey` can find an element in `O(1)` before sifting.
*Concept:* the heap property; why a complete binary tree fits in a flat array
with `parent = (i-1)/2` and `children = 2i+1, 2i+2` — no pointers needed;
sift-up vs sift-down; why both `insert` and `extractMin` are `O(log n)`. Then
the key one: **why `decreaseKey` exists at all** — Dijkstra lowers a node's
tentative distance repeatedly, and `java.util.PriorityQueue` has no way to
express that, forcing the "lazy deletion" workaround that grows the queue to
`O(E)` entries instead of `O(V)`.
*Proof:* randomised oracle test — push identical random operation sequences
through our heap and `java.util.PriorityQueue`, assert identical pop order over
thousands of trials. Plus a direct `decreaseKey` test the library queue cannot
express.

---

## Phase 3 — Dijkstra

**3.1 — Single-source shortest path.**
`dist[]` initialised to infinity, `prev[]` for reconstruction, settled set, the
relaxation loop, driven by our own heap.
*Concept:* the greedy invariant — once a node is popped, its distance is final,
and *why that requires non-negative weights* (a negative edge could otherwise
improve an already-settled node). Relaxation as a single idea. Complexity
`O((V + E) log V)`, derived rather than quoted.
*Proof:* hand-computed shortest paths on a tiny 5-node graph; plus a
brute-force all-paths comparison on a small random graph.

**3.2 — Path reconstruction.**
Walk `prev[]` backward from target to source, reverse.
*Concept:* why storing one predecessor per node is enough to recover the whole
path, and why we store the predecessor rather than the path itself.
*Proof:* reconstructed path costs equal the reported `dist[]` values.

**3.3 — Cross-check against the sandbox oracle. ⚠ BLOCKED, 2026-09-06.**
DSTNS has its own A* (`RoutePlanner::route`, `src/graph.cpp`) but **does not
expose it over HTTP** — there is no `/paths/compute` and no path endpoint at all.
*Action:* ask Varun to add a thin `POST /api/v1/paths/compute` wrapper over the
existing `RoutePlanner`. The class already exists; this is a few lines and
restores the strongest test in the plan. Highest-value ask we have of him.
*If he declines or it slips:* drop this step. Step 3.1's brute-force all-pairs
comparison on a small random graph plus step 5.1's Dijkstra-vs-A* cost agreement
already cover correctness; 3.3 becomes redundant rather than missing. Do **not**
port their A* to Java as a "reference" — that is our code checking our code.
*Concept (keep it either way):* why we compare cost and not the node sequence —
ties are broken differently by different implementations, so two equally-optimal
paths can legitimately differ. A TA may well ask.
*Proof (if unblocked):* costs match across a sample of random node pairs.

---

## Phase 4 — Algorithm 1: Route Network Builder

Full specification in [[csc210_algorithm_module_writeup]] §3.

**⚠ Rebased 2026-09-06.** `demand_score` **does not exist in DSTNS** — the PR that
added it was merged into the old `sumo_control` repo and did not survive the
rewrite. Two decisions must be settled with Garv before proposing 4.1:

- **Demand source.** Now `building_effect` per node (School/Office/Mall/Store with
  time-of-day activity windows) and `demand_vph` per edge, both read from the API.
  Both are **functions of virtual time** — the same node is high-demand at 08:30
  and low at 14:00.
- **Bus stops.** DSTNS now places its own stops (22 in the current run, 300 m min
  spacing / 800 m max coverage) and flags them `roles.bus_stop`. Either consume
  them — which narrows Algorithm 1 from "choose stops and routes" to "choose
  routes over given stops" — or ignore them and select our own, using theirs as a
  comparison baseline. Both are defensible; the choice must be **stated**, not silent.

**4.1 — Read demand.**
Parse per-node `building_effect` / per-edge `demand_vph` at a chosen virtual time.
Handle nodes with no building.
*Concept:* why demand is a proxy, not ground truth — here it is a *synthetic*
proxy, generated by the sandbox from building types and hardcoded rush-hour
windows, so it is one step further from reality than the old POI-density proxy
was. Naming that limitation out loud is a strength in the writeup, not a weakness.
Also: why a time-varying demand field forces us to pick and justify a time slice,
and what that costs — a network optimised for the 08:30 peak is not optimal at 14:00.
*Proof:* demand distribution printed at a fixed seed and virtual time; the
highest-demand nodes coincide with schools/offices/malls at plausible hours.

**4.2 — Hub selection and the greedy cover loop.**
Pick the highest-demand uncovered node, Dijkstra from it, build a route along
shortest-path structure, mark everything within walking distance as covered,
repeat.
*Concept:* set cover, and why greedy is chosen — this is the BTRNDP, NP-hard,
conventionally attacked with genetic algorithms, simulated annealing, or tabu
search (Fan & Machemehl, 2004), **none of which are in the course toolkit**.
Greedy stays inside Graph/Heap/Dijkstra and is honestly labelled a heuristic
with no optimality claim. See [[csc210_route_generation_scope]].
*Proof:* routes generated, coverage percentage reported, no node covered twice.

**4.3 — Budget and stopping conditions.**
Route length/time budget, target coverage threshold.
*Concept:* why an unbounded greedy loop produces one absurd route covering
everything, and what the budget is really modelling — a real bus line has a
maximum sensible length.
*Proof:* sweeping the budget changes route count monotonically.

**4.4 — Emit the transit graph.**
Stops as nodes, bus segments as edges tagged with serving route(s), plus
**transfer edges** carrying wait + walk cost.
*Concept:* why Algorithm 2 must run on this graph and not the road graph — a
road-graph search returns a *driving* route; passengers need a *bus-taking*
route, and the cost of changing buses has to be a real edge or the search will
happily transfer ten times for free.
*Proof:* transit graph is connected; transfer edges present at shared stops.

---

## Phase 5 — Algorithm 2: A*

Full specification in [[csc210_algorithm_module_writeup]] §4.

**5.1 — A* over the transit graph.**
Open set keyed by `f`, `g[]`, `cameFrom[]`, closed set, on our own heap.
*Concept:* `f = g + h` as "cost already paid plus optimistic guess of what's
left"; how A* degenerates into exactly Dijkstra when `h = 0`; why A* is right
for single-source-**single**-target and Dijkstra is right for the one-to-many
queries in Phase 4.
*Proof:* on the same pairs, A* returns the identical **cost** as Dijkstra —
same optimum, fewer nodes expanded.

**5.2 — The heuristic.**
```
h(n) = m_min × distance(n, goal) / V_MAX
```
`V_MAX` = highest free-flow speed on any edge; `m_min` = smallest currently
observed combined multiplier. Distance computed in **projected metres**
(`position.x_m`, `position.y_m`) — not lat/lon.

*Revised 2026-09-06:* DSTNS ships both lat/lon and equirectangular `x_m`/`y_m`,
so the metre plan is intact. `V_MAX = 16.67 m/s` (primary roads, per
`config/road_types.json`). Use **Euclidean** — the map is real OSM road layout
(Paris, confirmed live 2026-09-09, not Berlin as earlier notes said), not a
grid, so Manhattan is not the right form here.
**Worth knowing:** DSTNS's own A* hardcodes `33.33 m/s`, roughly 2× loose. Ours
at the true 16.67 is strictly tighter and still admissible, so it expands fewer
nodes than theirs on the same graph — a real, defensible result to report in
Phase 6 and a clean answer to "what did you do that the sandbox didn't?"
*Concept:* admissible ("never overestimates") and consistent ("triangle
inequality holds edge by edge"), both in plain language before any notation.
The critical invariant: **traffic and weather live only in `g`, never in `h`** —
folding live conditions into the heuristic breaks admissibility and A* can then
return a suboptimal path.
*Proof:* assert `h(n) ≤ trueRemainingCost(n)` for every node on paths already
solved exactly by Dijkstra in Phase 3.

**5.3 — Hand-check the proofs.**
Verify admissibility and consistency by hand on a handful of real sandbox
nodes. The proof is already derived in [[csc210_algorithm_architecture]] —
this is confirming it against real numbers.
*Concept:* the difference between a proof and a passing test, and why the
writeup wants both.
*Proof:* written into the module writeup.

---

## Phase 6 — Research deliverable

The required algorithm-comparison component ([[csc210_algorithm_module_writeup]] §5).

**6.1 — Determinism harness.** *(Rewritten 2026-09-06 — `/simulation/step` no longer exists.)*
DSTNS drives its clock from a background loop, but gives a cleaner equivalent:
`POST /playback/prepare` at a fixed seed (compiles the scenario without starting
the clock) → `POST /playback/seek {"target_time":"08:30:00","play":false}` →
`GET /view/snapshot`. Same seed + same target time ⇒ identical edge speeds.
Each envelope carries `global_seed`, `graph_hash` and `state_revision`, so a run
can record exactly which state it measured.
*Concept:* why a benchmark against a *live* simulation is meaningless — the
weights move under you, so run-to-run differences measure the weather, not the
algorithm.
*Proof:* two prepare+seek cycles at the same seed and target time produce
byte-identical edge speeds and equal `graph_hash`. Verify this empirically before
trusting any benchmark number — Varun's `dstns_replay_verify` asserts it on his
side, but we have not run it.

**6.2 — Instrumentation.**
Nodes-expanded counter, wall-clock timing with JIT warmup runs discarded, peak
open-set size.
*Concept:* why nodes-expanded is the honest primary metric — it is
machine-independent and language-independent, so it measures the *algorithm*.
Why raw JVM wall-clock without warmup is misleading. This ties directly back to
[[csc210_java_decision]] §2.
*Proof:* counters are stable across repeated identical runs.

**6.3 — Weighted A* ε-sweep.**
`f = g + ε·h`, sweep `ε` from 1.0 upward, CSV output.
*Concept:* the bounded-suboptimality guarantee — the returned path is never
worse than `ε ×` optimal — and why that makes weighted A* an *honest*
speed/quality trade rather than a hack. At `ε = 1` it is exactly A*.
*Proof:* every returned cost satisfies `cost ≤ ε × optimalCost`; nodes expanded
falls monotonically as `ε` rises.

**6.4 — Dijkstra vs A* comparison.**
Same transit graph, same query set: query time, nodes expanded, path quality.
*Proof:* identical costs, A* strictly fewer expansions.

**6.5 — Plots and writeup.**
Nodes-expanded vs path-cost across `ε`; Dijkstra vs A* table. Fold results into
[[csc210_algorithm_module_writeup]].

---

## Phase 7 — Integration and handoff

**7.1 — Command-line entry point.** Build the network, answer a query, print
the route.
**7.2 — Publish routes back** via `POST /api/v1/control/transit/route` so they
render and animate in the DSTNS UI. *Revised 2026-09-06:* body is
`{bus_id, label, nodes[]}`, and the server validates that **every consecutive
pair of nodes is a single directed edge** — it does not path-fill between stops.
So Algorithm 1 must emit the full node-by-node traversal, every intermediate
junction included, or the request is rejected at the first gap. Worth designing
Phase 4.4's output format around this from the start rather than retrofitting.
**7.3 — README** — architecture, how to run, how to reproduce the benchmarks.

---

## Open items carried in

*(Refreshed 2026-09-06 for the DSTNS rewrite.)*

- **Ask Varun to expose `RoutePlanner` over HTTP** as `POST /api/v1/paths/compute`.
  Unblocks step 3.3; cheapest high-value ask we have. **Do this first.**
- **Which sandbox copy is canonical?** Three divergent branches exist (group repo
  `dstns` and `Sandbox`, plus `varunkarthic/csc210-sandbox` `Programs`). Confirm
  with Varun that `dstns` is the one to build against and that the others are dead,
  and ideally get it merged to the group repo's `main`.
- **Bus stops:** consume DSTNS's 22 pre-placed stops, or select our own? Decide
  before Phase 4.2 (see the Phase 4 preamble).
- **Demand time slice:** which virtual time does Algorithm 1 optimise for, and
  what is the stated justification? Decide before Phase 4.1.
- **Nothing has been built or run locally.** Varun's docs claim all phases complete
  and all suites green; we have not independently verified the API returns real
  data. Do that at step 1.2 before trusting anything downstream.
- Map is **not** Berlin — corrected 2026-09-09, confirmed live: the current
  run is Paris-coordinate OSM data, ~120 nodes / 436 edges (small dev-sized
  extract, not the AU Gate 10 roundabout). The "Berlin 617/1294" figure in
  earlier notes was never checked against a live run; treat any node/edge
  count as provisional until re-confirmed at whatever scale Phase 4 onward
  actually runs at. Euclidean distance is still settled for step 5.2 either
  way — real OSM layout, not a grid.
- `trafficMult` / `weatherMult` value ranges: superseded twice over — step 1.6 as
  rewritten is now the definitive cost model.

See also [[csc210_algorithm_build_protocol]], [[csc210_java_decision]],
[[csc210_algorithm_module_writeup]], [[csc210_algorithm_architecture]],
[[csc210_sandbox_data_layer]], [[csc210_route_generation_scope]],
[[csc210_algorithm_learning_plan]].
