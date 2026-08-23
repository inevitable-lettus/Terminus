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
| Edge cost | **Recomputed from raw fields**, sandbox number is a cross-check | [[csc210_sandbox_data_layer]] |
| Sandbox Dijkstra | **Test oracle only**, never in the submitted pipeline | [[csc210_sandbox_data_layer]] |
| Heap | **Hand-written indexed binary heap** with `decreaseKey` | [[csc210_java_decision]] |
| Route design method | **Greedy demand-covering**, not GA/SA/tabu | [[csc210_route_generation_scope]] |

## Current stage

> **Phase 0, step 0.0 — not started.** No code exists. Nothing installed.
>
> **Blocker:** the only JDK on this machine is **Java 8** (`1.8.0_471`), and it
> is the old x86_64 Applet-plugin JRE. That predates `java.net.http` (Java 11)
> and records (Java 16). JDK 21 must be installed before step 0.1.
>
> _Update this block at the end of every completed step — see
> [[csc210_algorithm_build_protocol]] stage 6._

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
`http://localhost:8080/api/v1`. Methods for `GET /status`, `GET /nodes`,
`GET /edges`. Optional bearer token.
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
Save one real `/nodes` and one real `/edges` response into
`src/test/resources/`. Every later test runs against these, so tests do not
need the sandbox running.
*Concept:* why tests that depend on a live external service are bad tests.
*Proof:* `./gradlew test` passes with the sandbox stopped.

**1.5 — The graph structure. (First real DSA content.)**
Hand-written adjacency list. A `HashMap<String,Integer>` interns the sandbox's
string node ids to dense `int` indices; edges stored in flat arrays indexed by
those integers.
*Concept:* adjacency list vs adjacency matrix, and why a sparse road network
(each junction has ~2–4 neighbours) makes the matrix wasteful — `O(V²)` memory
for `O(E)` real edges. What "interning" means and why dense integer ids make
every later array lookup `O(1)`. Hash table from the required toolkit enters
here, legitimately.
*Proof:* build the graph from fixtures; assert node/edge counts match the API;
assert the sandbox's guaranteed bidirectionality invariant holds — for every
`u→v` there is a `v→u`.

**1.6 — Cost model, recomputed.**
Implement the sandbox's physics in our own code:
```
v_eff = v_limit * max(0.05, (1 - 0.75t²)(1 - 0.85f²)(1 - 0.80d))
w     = L / max(0.1, v_eff) * m
```
`t` = `traffic_level`, `f` = `flood_depth_m / 0.50`, `d` = `seismic_damage`,
`m` = `manual_weight_multiplier`. Weight is infinite when blocked (manual,
node-derived, flood ≥ 0.50 m, or seismic ≥ 0.90).
*Concept:* why the multipliers are multiplicative and not additive — congestion
slows a road in proportion to its length, so a flat additive penalty would
punish a 50 m street and a 5 km highway identically. Why the clamps exist
(never divide by zero, never let a road become infinitely fast). Why costs live
on edges, not nodes.
*Proof — the good one:* for **every** edge in the fixture, assert our computed
weight matches the sandbox's `effective_weight_s` to within floating-point
epsilon. Hundreds of independent checks of the same formula. If one edge
disagrees, we misread the spec.

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

**3.3 — Cross-check against the sandbox oracle.**
`POST /paths/compute` runs the sandbox's own C++ Dijkstra over the same
weights. Run both on the same node pairs, assert equal path **cost**.
*Concept:* why we compare cost and not the node sequence — ties are broken
differently by different implementations, so two equally-optimal paths can
legitimately differ. This distinction is worth understanding; a TA may well ask.
*Proof:* costs match across a sample of random node pairs.

---

## Phase 4 — Algorithm 1: Route Network Builder

Full specification in [[csc210_algorithm_module_writeup]] §3. Unblocked as of
commit `8c50dff` — `node.metadata["demand_score"]` is now populated (issue #1,
closed 2026-08-21).

**4.1 — Read demand.**
Parse `metadata["demand_score"]` — a string-encoded integer count of OSM POIs
(amenity/shop/landuse) within 300 m of the node. Handle nodes without the key.
*Concept:* why demand is a proxy, not ground truth — POI density stands in for
population because no census data exists in the pipeline. Naming that
limitation out loud is a strength in the writeup, not a weakness.
*Proof:* demand distribution printed; highest-demand nodes are plausible.

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
observed combined multiplier. Distance computed in **SUMO metres** (`x`,`y`) —
not lat/lon. Manhattan instead of Euclidean if the layout is grid-like.
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

**6.1 — Determinism harness.**
Manual clock mode: `POST /simulation/pause`, then explicit
`POST /simulation/step` calls instead of realtime. Same root seed + same step
count ⇒ identical edge weights every run.
*Concept:* why a benchmark against a *live* simulation is meaningless — the
weights move under you, so run-to-run differences measure the weather, not the
algorithm.
*Proof:* two runs at the same seed and step count produce byte-identical
weights.

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
**7.2 — Publish routes back** via `POST /paths` so they render in the sandbox.
**7.3 — README** — architecture, how to run, how to reproduce the benchmarks.

---

## Open items carried in

- `trafficMult` / `weatherMult` value ranges: superseded — the sandbox's
  formula in step 1.6 is now the definitive cost model.
- Procedural city generation vs fixed OSM data: still unresolved with Varun.
  Currently real OSM (AU Gate 10 roundabout). Affects only whether the
  heuristic uses Euclidean or Manhattan distance (step 5.2) — not blocking.
- Local commit `8c50dff` may be unpushed to Varun's remote — confirm before
  assuming the team has `demand_score`.

See also [[csc210_algorithm_build_protocol]], [[csc210_java_decision]],
[[csc210_algorithm_module_writeup]], [[csc210_algorithm_architecture]],
[[csc210_sandbox_data_layer]], [[csc210_route_generation_scope]],
[[csc210_algorithm_learning_plan]].
