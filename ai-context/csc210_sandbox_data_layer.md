---
name: csc210-sandbox-data-layer
description: "Data layer of Varun's sandbox (now DSTNS) — graph model, JSON schemas, REST API, cost model — that the algorithm module consumes. Rewritten 2026-09-06 for the DSTNS rewrite; the old sumo_control contract is dead."
metadata:
  type: project
---

# Sandbox Data Layer — DSTNS

**Pinned (2026-09-08): DSTNS stays the integration target.** Varun has a third,
experimental, unmerged rewrite (`suse-experimental`, "SUSE") — see
[[csc210_varun_sandbox_task]]. Garv decided to ignore it for now; everything below still
applies. Re-open only if Garv says DSTNS is being dropped.

**Rewritten 2026-09-06 after reading `origin/dstns` (commit `257257a`, 2026-09-05) on the
group repo.** The sandbox was **completely rewritten**. Everything in the previous
version of this file described `sumo_control` v1.1 and is now dead: different
namespace, different API paths, different port, different cost model, different
node identifiers, different endpoints. Do not code against the old contract.

## Where the code actually lives — three divergent copies

| Location | Branch | Head | What it is |
|---|---|---|---|
| `CSC210-Monsoon2026/group-08-public-bus-route-planner` | `dstns` | `257257a` (2026-09-05) | **The live one.** Full DSTNS rewrite. |
| same repo | `Sandbox` | `6b05f7e` (2026-08-22) | Old `sumo_control`, no demand work. Dead. |
| `varunkarthic/csc210-sandbox` | `Programs` | `c9bc804` (2026-08-21) | Old `sumo_control` + merged `demand_score` PR #2. Dead. |

The `demand_score` work from issue #1 (PR #2, merged) **did not survive the rewrite** —
`demand_aggregator.{hpp,cpp}` do not exist in DSTNS. Demand is modelled differently now
(see "Demand" below). Any plan keyed to `node.metadata["demand_score"]` is void.

## What DSTNS is

**DSTNS — Deterministic Spatiotemporal Transport Network Simulator**, C++20, by Varun.
Self-described as a scenario compiler + simulation runtime + telemetry engine with an
embedded React 19 / MapLibre GL operator UI. Runs on **port 8090** (was 8080).

Pipeline: a 128-bit seed + `ScenarioConfig` deterministically compiles an OSM XML file
into a canonical road graph (nodes, reverse-twin edge pairs, bus stops, buildings,
signals, weather storms, trips), then a single simulation thread advances an analytical
traffic/weather model over it on a virtual 24-hour clock, publishing state over REST/SSE.

**SUMO is no longer in the request path.** DSTNS *exports* the compiled network to SUMO
plain-XML (`POST /api/v1/export/sumo`, `POST /api/v1/system/sumo-simulate`) for offline
`netconvert`/`sumo` validation, but live edge speeds come from DSTNS's own analytical
model in `SimulationEngine::physics_step`, not from SUMO vehicles. This matters for the
writeup: the old "SUMO reports vehicle speeds → smoothed into traffic_level" mental model
in [[csc210_algorithm_learning_plan]] is **wrong** for DSTNS.

**Current map is not Berlin, and not Ahmedabad — corrected 2026-09-09.** The
"Berlin, 617 nodes/1294 edges" figure below was read from a log file, never
confirmed against a live call, and turned out wrong. A real running container
(branch `dstns`, HEAD `0fb6307` — later than the `257257a` this section was
originally audited against) returns a **Paris-coordinate** OSM extract at
default startup, with no `playback/start` called yet: bounds ~48.87 N, 2.36–2.38 E,
**120 nodes, 436 edges**. Treat node/edge count as provisional, possibly a
function of `playback/start`'s `map`/`max_nodes` params rather than a fixed
constant — re-check at whatever scale Phase 4 onward actually runs at.
Fixtures shipped: `data/fixtures/downtown_osm.xml` (1 MB, the default) and
`data/fixtures/real_network.osm.xml` (8.6 MB). `max_nodes` default 50000.

## Identifiers — this changes Phase 1

`NodeId` and `EdgeId` are **0-indexed contiguous `uint32`**, assigned after topological
sort, and stated as a system invariant (`context/06_INVARIANTS.md` #3). They are already
dense integers in the JSON.

**Resolved 2026-09-09 (was: decide before writing 1.5).** Went with option (a):
index arrays directly by id, drop the hash table here. Full reasoning and the
built `Graph` (CSR flat-array adjacency) are in
[[csc210_algorithm_build_order]]'s step 1.5 entry — `osmNodeId` stays on `Node`
as a sparse key for whenever a genuine sparse→dense lookup is needed later
(option (b) revisited then, not decorated in now).

## Graph model

**Superseded by live verification, 2026-09-09 — read this part first.** Everything
in this section was written from a static read of `257257a`'s source. Built and
ran `0fb6307` (later HEAD of the same `dstns` branch) and the *actual*
`/view/topology` response matches neither example below — no `roles` wrapper, no
nested `source`/`road`/`traffic`/`weather`/`control` objects on edges. Whether the
branch changed shape between those two commits, or the static read never matched
the built binary even at `257257a`, is unresolved — but it's the second time
"verified in code" turned out to mean "verified in one version of the code."
**Practical rule going forward: trust nothing here over a fresh live call.**

Real `/view/topology` node (captured live, `0fb6307`):

```json
{
  "id": 4, "osm_node_id": 9000000004,
  "position": {"lat": 48.8666, "lon": 2.3668, "x_m": 720.0, "y_m": 0.0},
  "degree": 3,
  "bus_stop": false, "bus_stop_radius_m": 0.0,
  "signal": false, "signal_cycle_s": 0, "signal_green_s": 0, "signal_offset_s": 0,
  "building": "store", "building_impact": 0.76, "building_radius_m": 234.8
}
```

Real `/view/topology` edge (captured live, `0fb6307`):

```json
{
  "id": 0, "from": 1, "to": 0, "reverse_twin": 1,
  "road_class": "primary", "lanes": 2, "length_m": 180.0, "free_speed_mps": 16.67,
  "geometry": [{"lat": 48.8666, "lon": 2.3594, "x_m": 180.0, "y_m": 0.0},
               {"lat": 48.8666, "lon": 2.3569, "x_m": 0.0, "y_m": 0.0}]
}
```

Everything **flat**, no nesting. `osm_way_id`, `synthetic_reverse`,
`base_capacity_vph`, and any `control`/`traffic`/`weather` block **do not exist on
this endpoint at all** — confirmed by hitting it live, not inferred. `geometry`
(the road's polyline) is real and wasn't in the old example. The domain types in
`Node.java`/`Edge.java` were fixed to match this on 2026-09-09 — see
[[csc210_algorithm_build_order]] step 1.1's correction note.

Real `/view/snapshot` node/edge (live, `0fb6307`) — this is where the
`traffic`/`weather`-shaped live state actually lives, flat here too:

```json
{"id": 0, "building_effect": 0.0, "flood": 0.0, "rainfall": 0.0}
{"id": 0, "closed": false, "congestion": 0.0, "effective_speed_mps": 16.67,
 "flood": 0.0, "halting_count": 0, "rainfall": 0.0, "vehicle_count": 0}
```

`building_effect` and `effective_speed_mps`/`closed` are exactly what Phase 4's
demand read and step 1.6's cost model need — those two names held up. Nothing in
either snapshot shape carries `manual_speed_multiplier`/`manual_capacity_multiplier`
— `EdgeConfiguration` (the `PUT /control/edges/{id}` body shape) remains an
educated guess from the old doc, **unverified against any live response**, since
nothing has written to that endpoint yet.

<details>
<summary>Original examples from the <code>257257a</code> static code read (kept for
history — do not code against these)</summary>

Static node (`GET /api/v1/view/nodes`, paged; also claimed for `/view/topology`):

```json
{
  "id": 0,
  "osm_node_id": 9000000000,
  "position": {"lat": 23.0185, "lon": 72.5682, "x_m": 0.0, "y_m": 0.0},
  "degree": 3,
  "roles": {"bus_stop": false, "signal": true, "building": true},
  "building": {"type": "school", "impact": 0.50, "radius_m": 250.0, "current_effect": 0.31},
  "weather": {"rainfall": 0.0, "flood": 0.0, "flood_susceptibility": 0.4, "drainage": 0.6}
}
```

Static edge (`GET /api/v1/view/edges`, paged):

```json
{
  "id": 0, "from": 0, "to": 1, "reverse_twin": 1,
  "source": {"osm_way_id": 8000000000, "synthetic_reverse": false},
  "road": {"class": "primary", "lanes": 2, "length_m": 142.8,
           "free_speed_mps": 16.67, "base_capacity_vph": 3200},
  "traffic": {"demand_vph": 980.0, "effective_capacity_vph": 3040.0,
              "effective_speed_mps": 9.8, "vehicle_count": 8, "halting_count": 2,
              "mean_speed_mps": 8.1, "occupancy": 0.28, "congestion": 0.28},
  "weather": {"rainfall": 0.0, "flood": 0.0},
  "control": {"closed": false, "manual_speed_multiplier": 1.0,
              "manual_capacity_multiplier": 1.0}
}
```

</details>

**Reverse-twin invariant** (`context/06_INVARIANTS.md` #4):
`edges[edges[i].reverse_twin].reverse_twin == i`. Every physical segment is a pair of
opposed directed edges. This is the DSTNS spelling of the old bidirectionality
guarantee, and it is a better test assertion than the old one because it is exact.

`Point` carries **both** `lat`/`lon` **and** projected `x_m`/`y_m`. Projection is
equirectangular about the map origin (`src/osm.cpp:335`):
`y_m = (lat - lat0) * 111320`, `x_m = (lon - lon0) * 111320 * cos(lat0)`.
So the A* heuristic can still work in metres exactly as planned — **use `x_m`/`y_m`,
not lat/lon**, and Euclidean distance is the right form (real OSM road layout, not a grid).

Road-class speed table (`config/road_types.json`):

| class | `free_speed_mps` | `capacity_vph` |
|---|---|---|
| primary | 16.67 | 3200 |
| secondary | 13.89 | 2400 |
| tertiary | 11.11 | 1800 |
| residential | 8.33 | 1100 |
| service | 5.56 | 600 |

**`V_MAX` = 16.67 m/s** for heuristic purposes. Note DSTNS's *own* A* hardcodes
`33.33` m/s — admissible but ~2× loose. Garv using the true 16.67 gives a strictly
tighter (still admissible) heuristic and therefore fewer expansions. **That is a real,
defensible result for the research deliverable**, and a good answer to "what did you do
that the sandbox didn't."

## Cost model — replaced

The old `v_eff = v_limit · max(0.05, (1-0.75t²)(1-0.85f²)(1-0.80d))`, `w = L/v_eff · m`
formula is **gone**, and so is the precomputed `effective_weight_s` field. DSTNS
publishes `effective_speed_mps` per edge and the traversal weight is derived:

```
cost_ms(e) = round( length_m(e) / max(0.1, effective_speed_mps(e)) * 1000 )
```

integer milliseconds — this is exactly what DSTNS's own `RoutePlanner` uses
(`src/graph.cpp`, `RoutePlanner::route`). `closed == true` means skip the edge entirely
(infinite cost), not a large finite number.

`effective_speed_mps` is itself produced by `physics_step` (`src/engine.cpp:802`), and
notably it is **rate-limited, not instantaneous** — it eases toward a target at
+2.4 / −3.2 m/s per tick:

```
target = closed ? 0 : free_speed_mps · signal_mult · rain_speed_mult · flood_speed_mult · manual_speed_mult
signal_mult       ∈ {1.0 green, 0.40 yellow, 0.08 red}   (Webster-style NS/EW phases)
rain_speed_mult   = 1 - 0.18 · rainfall
flood_speed_mult  = max(0.35, 1 - 0.55 · flood)
closed            = manual_closed || flood >= 0.98
```

**Two consequences worth understanding before Phase 1.6:**

1. There is no longer a server-side weight to cross-check our arithmetic against
   edge-by-edge. The old step 1.6 proof ("assert our weight matches `effective_weight_s`
   for every edge") **cannot run as written**. Replacement proof: assert
   `cost_ms == round(length_m / max(0.1, effective_speed_mps) * 1000)` — which is
   trivially our own formula — plus assert the *published* `effective_speed_mps` never
   exceeds `free_speed_mps`, and that `closed` edges are excluded. Weaker than the old
   check; say so honestly rather than pretending it is equivalent.
2. Because signal phase multiplies speed by 0.08 on red, edge weights **oscillate with
   the signal cycle**. A route computed at one instant is not stable a few seconds later.
   This is a genuinely interesting property to discuss in the writeup, and it makes
   determinism control (below) essential for benchmarking.

## Demand — replaced, and now time-varying

No `demand_score`. Demand is generated from synthetic buildings and time-of-day:

- Node carries `building` ∈ {School, Office, Mall, Store} with `building_impact` (0.50)
  and `building_radius_m` (250 m), plus `tmax` activity windows.
- `physics_step` computes per-node `building_effect` from a smooth beta curve over those
  windows, times a weekend factor (`day` 0 = weekday, 1 = weekend), plus hardcoded
  peak-hour rushes (school 07:45–09:15 / 14:30–16:00, office 08:15–10:00 / 17:00–19:30,
  mall 12:00–14:00 / 18:00–21:30, store 11:00–20:00, bus stops 07:30–09:30 / 16:30–19:00).
- Edge demand: `demand_vph = base_capacity_vph · (0.18 + 0.68·day_profile
  + 0.60·attraction + 0.35·hotspot) · surge_mult`, where `attraction` is the mean
  `building_effect` of the edge's two endpoints.

**Consequence for Algorithm 1** ([[csc210_algorithm_build_order]] Phase 4): the demand
proxy is now `building_effect` / `demand_vph` read from the API, and it is a **function
of virtual time**. The greedy route builder must either pick a representative time slice
(e.g. morning peak) and justify that choice, or aggregate across the day. Picking one
slice and naming it is fine and simpler; the honest limitation to state is that a route
network optimised for the 08:30 peak is not optimal at 14:00. See
[[csc210_route_generation_scope]].

**Bus stops are now placed by the sandbox, not by us.** DSTNS runs its own deterministic
placement (`min_spacing_m` 300, `target_spacing_m` 500, `max_coverage_m` 800) and marks
`roles.bus_stop`. 22 of them in the current run. This *narrows* Algorithm 1's scope from
"choose stops and routes" to "choose routes over given stops" — which is a defensible
scope reduction, but it must be a **deliberate, stated** decision, not a silent one.
Alternative: ignore the sandbox's stops and select our own from the road graph, using
theirs only as a comparison baseline. **Open decision, flag to Garv before Phase 4.**

## API surface (port 8090, all under `/api/v1` unless noted)

Verified against registered handlers in `src/api.cpp:179–461`, not just the OpenAPI file.

**System / health**
- `GET /health`, `GET /api/v1/system/health` — liveness, `{"ok":true,"product":"DSTNS",...}`
- `GET /api/v1/system/status`, `GET /api/v1/system/info`
- `POST /api/v1/system/terminate`
- `POST /api/v1/export/sumo`, `POST /api/v1/system/sumo-simulate`

**Playback lifecycle** (replaces the old `/simulation/*`)
- `POST /playback/prepare` — compile scenario at a seed without starting the clock
- `POST /playback/start` — body: `seed`, `playback_duration_seconds` (60–3600),
  `start_virtual_time`, `tick_rate`, `day` (0/1/`auto`), `modules`, `dws`, `map`
- `POST /playback/pause` · `play` · `stop` · `reset`
- `POST /playback/seek` — body `{"target_time": "14:30:00", "play": false}`
- `GET /playback/status`

**View (read side — this is what the algorithm module consumes)**
- `GET /view/topology` — full immutable graph in one call (nodes, edges, geometry,
  bounds, `graph_hash`, `root_node`). **This is the Phase 1 ingestion endpoint.**
- `GET /view/snapshot` — dynamic state: per-edge `congestion`, `effective_speed_mps`,
  `vehicle_count`, `closed`, `flood`; per-node `rainfall`/`flood`/`building_effect`;
  active weather, `active_incidents`, `active_transit_buses`, recent news
- `GET /view/nodes?offset&limit`, `GET /view/edges?offset&limit` — paged, richer
  per-entity detail (default limit 250, max 1000)
- `GET /view/global` — topology + snapshot + metrics combined; also written to
  `logs/global_view.json` every second (**useful as an offline fixture**)
- `GET /view/bus-stops`, `/buildings`, `/signals`, `/weather`, `/traffic`, `/events`,
  `/metrics`, `/manifest`, `/run`
- `GET /view/stream` — 1 Hz SSE snapshot feed
- Aliases for topology: `/topology`, `/view/network`, `/view/map/full`

**Control (write side)**
- `POST /control/transit/route` — **publish a bus route.** Body `{bus_id, label, nodes[]}`.
- `POST /control/events/weather` — `{epicenter_node, intensity, radius_m,
  duration_virtual_minutes, flood_gain}`
- `POST /control/events/traffic`, `POST /control/events/surge`
- `PUT /control/edges/{id}` **and** `POST /control/edges/{id}/override` — both registered
  to the same handler; body `{speed_multiplier, capacity_multiplier, closed}`
- `POST /control/signals/{id}` and `POST /control/signals/{id}/toggle` — force a signal
  phase. Registered but **absent from `api/openapi.yaml`**; only discoverable in `src/api.cpp`.
- `PUT /control/tick-rate`, `POST /control/day`, `PUT /control/modules/{module}`
  (modules: `traffic`, `signals`, `buildings`, `dws`, `flooding`, `news`)
- `POST /control/undo`, `POST /control/redo`, `GET /control/history`

**News** — `GET /news?since_news_id&limit`, `GET /news/stream` (SSE). This is the DSTNS
replacement for the old ROE hash-chained ledger.

Every read returns a **revisioned envelope**: `api_version`, `run_id`, `global_seed`,
`state_revision`, `config_revision`, `clock`, `data`. Snapshot arrays are indexed
positionally to match topology order — fetch topology once, stream snapshots after.

## Publishing routes back — `POST /control/transit/route`

```json
{"bus_id": "BUS-METRO-42", "label": "Downtown Express", "nodes": [213, 6, 14]}
```

**Validation is strict adjacency, and this constrains Algorithm 1's output format**
(`SimulationEngine::validate_transit_route`, `src/engine.cpp:233`): every consecutive
pair `(nodes[i], nodes[i+1])` must be a single directed edge in the graph. It is **not**
a stop-to-stop list the server path-fills — the server does a linear scan over all edges
for each pair and rejects the whole request on the first gap:

```json
{"ok": false, "valid": false, "error_step": 0, "from_node": 140, "to_node": 226,
 "message": "Discontinuous route: Node #140 and Node #226 are not directly adjacent..."}
```

So a published route must be the **full node-by-node traversal**, every intermediate
junction included. Success returns `route_edges[]` and `total_distance_m`, registers the
bus in `active_transit_buses`, emits a news event, and the UI animates it along the path.
Re-posting the same `bus_id` replaces that bus.

## The oracle is gone — this breaks build-order step 3.3

DSTNS has its own router, `RoutePlanner::route(NodeId, NodeId)` in `src/graph.cpp`:
A* over `cost_ms`, `std::priority_queue` with lazy deletion, ties broken by lowest
`NodeId` then lowest `EdgeId`, heuristic `distance_m / 33.33 * 1000`, closed edges
skipped. Benchmarked by Varun at ~5.7 µs/query.

**It is not exposed over HTTP.** There is no `/paths/compute` and no path endpoint of any
kind in `src/api.cpp`. So [[csc210_algorithm_build_order]] step 3.3 ("cross-check our
Dijkstra against the sandbox oracle") **has no oracle to call**.

Three ways out, in order of preference:

1. **Ask Varun to expose it** — a `POST /api/v1/paths/compute` wrapper over the existing
   `RoutePlanner` is a few lines, and the class already exists. Cheapest fix, restores
   the strongest test we had. **Do this first; it is the single highest-value ask.**
2. **Self-verify without an oracle** — brute-force all-pairs on a small random graph,
   plus Dijkstra-vs-our-own-A* agreement on cost (already planned as step 5.1), plus
   hand-computed paths on a 5-node graph. This is what step 3.1 already specifies, so
   3.3 becomes redundant rather than impossible.
3. **Port their A* to Java as a reference implementation** — defeats the purpose; it
   would be our code checking our code. Not recommended.

## Determinism — the benchmark harness changes

Old plan: `POST /simulation/pause` then explicit `POST /simulation/step` calls. **There is
no `/step` endpoint in DSTNS.** The clock is driven by a background loop at `tick_rate`.

What DSTNS gives instead, and it is arguably better:

- `POST /playback/prepare` compiles the scenario at a seed **without advancing the clock**
  — a fully deterministic standby state.
- `POST /playback/seek {"target_time": "08:30:00", "play": false}` jumps to an exact
  virtual time and stays paused. `restore_to`/`step_to` replay deterministically from
  checkpoints internally.
- Invariant #1: same `(Seed128, ScenarioConfig, ControlJournal)` ⇒ identical
  `scenario_hash`, `graph_hash`, and state snapshots. `dstns_replay_verify` asserts this.
- Every envelope carries `global_seed` and `state_revision`, so a benchmark run can
  record exactly which state it measured.

**Revised recipe for step 6.1:** `prepare` at a fixed seed → `seek` to a fixed
`target_time` with `play: false` → `GET /view/snapshot` → assert `state_revision` and
`graph_hash` match across runs → run the ε-sweep against that frozen snapshot. Verify
empirically that two prepare+seek cycles at the same seed and time produce byte-identical
edge speeds before trusting any benchmark numbers.

## Running it

```bash
python3 launcher.py            # interactive menu: start / logs / config / tests / reset
# or
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release && cmake --build build -j4
./build/dstns_server --host 127.0.0.1 --port 8090 --logs logs
# or
docker compose up --build -d
```

UI at `http://127.0.0.1:8090/`. Health at `/health`. Docker stack adds an nginx/Caddy TLS
gateway on 8443 with a self-signed cert (browser warning is expected).

Varun's own status (`context/01_CURRENT_STATE.md`, 2026-09-04) claims all 12 build phases
complete and all test suites passing: 4 C++ CTest targets, a 15-assertion Python API
smoke test, a SUMO `netconvert` integration test, and a Vitest UI suite. **Partially
independently verified 2026-09-09** — built and ran the container locally
(`docker compose up -d --build`, HEAD `0fb6307`), confirmed `/health`,
`/view/topology`, `/view/snapshot` all serve real, consistent data end to end
(120 nodes, 436 edges, reverse-twin invariant holds for all of them). Have **not**
verified the CTest/pytest/Vitest suites themselves, nor the other 11 claimed build
phases beyond what the API surface above actually exercises.

## Known bugs in DSTNS (found 2026-09-06, filed on the group repo)

Read pass over `src/{engine,graph,osm,scenario,api}.cpp`. Six issues filed, all
authored as Garv, no AI attribution (see [[csc210_algorithm_build_protocol]]).

| # | Severity | Bug | Bites us how |
|---|---|---|---|
| 1 | High | **One-way streets not enforced.** `source_oneway`/`synthetic_reverse` are recorded, serialised and unit-tested for *retention*, but no traversal code checks them. | Our Dijkstra/A* will drive the wrong way down one-ways too, unless we filter `synthetic_reverse` ourselves. **Do that in step 1.5 regardless of whether Varun fixes it.** |
| 2 | High | **Checkpointing silently stops** after the first non-60-aligned step (`virtual_s_ % 900 == 0` with `dt` capped at 60, boundary stepped over). | Backward `seek` replays from t=0. Makes the step 6.1 benchmark harness slow, and the more seeks, the worse. |
| 3 | Medium | **`seek` breaks news-id reproducibility** — `restore_to` truncates `news_` but never rewinds `next_news_id_`. | Contradicts DSTNS invariant #1. Snapshot comparisons across a seek show spurious diffs. Compare edge state only, not whole envelopes. |
| 4 | Medium | **`map_hash` hashes only the first 10 KB** of the OSM XML, and `scenario_hash` derives from it. | Don't cite `map_hash`/`scenario_hash` as proof two runs used the same map. **`graph_hash` is sound** (hashes real node/edge data) — use that one. |
| 5 | Low | Missing/wrong-typed JSON fields return **500**, not 400 (nlohmann exceptions don't derive from `std::out_of_range`). | Client dev annoyance; don't treat 500 as "server broken" while building `SandboxClient`. |
| 6 | Low | Unchecked iterator deref in bus-stop coverage repair; signal phases can sum past the cycle length at `cycle == 30`. | Marginal. |

**Two of these change what we build:** filter `synthetic_reverse` edges ourselves
(#1), and use `graph_hash` rather than `scenario_hash` for benchmark provenance (#4).

## How much to trust DSTNS (audit 2026-09-06)

Audited the documented claims against the actual implementation before building on
them. Verdict: **the engine is real, the documentation is inflated, the tests are
thin.** Details below so a future session doesn't re-derive them.

**Genuinely implemented, verified by reading:** Philox4x32-10 RNG (correct multipliers
and Weyl constants), SHA-256 (passes the standard `"abc"` vector in their own test),
SQLite WAL persistence, the SUMO `netconvert` bridge, A* routing, the whole
physics/weather/signal model, the REST layer. This is not a shell.

**Inflated:** `docs/components/` describes ~20 subsystems in namespaces
(`dstns::graph`, `dstns::spatial`, `dstns::replay`, …). **Exactly one namespace
exists — `dstns`** — containing nine classes. `dstns::spatial` and `dstns::replay`
have no code at all; the "spatial index" is two free functions and every radius query
is a linear scan. `drncp.md` claims nodes are sorted by `(y,x)` with OSM-id
tie-break and edges sorted by `(from,to,segment)`; the code plain-sorts OSM ids and
never sorts edges. Grand acronyms (CRFG, DRNCP) name ~30-line and ~2-line code paths.
Filed as issue #8.

**Thin tests** (issue #7): `dstns_performance_smoke` has **no assertions at all** — it
prints timings and always exits 0. `dstns_replay_reproducibility` compiles the same
scenario twice *in one process* and compares hashes; it never builds an engine, never
advances the clock, never compares a state snapshot — so the "identical state
snapshots" half of their invariant #1 is untested. Property tests are compile-time
structural only, never touching `physics_step` output. The unit test
(`tests/unit/test_main.cpp`) is the one genuinely good suite — real SHA vector, RNG
domain isolation, reverse-twin invariant, OSM seed determinism, routing around a
closure, engine lifecycle, undo/redo.

**Why our bugs #2 and #3 survived:** the unit test only ever seeks to 3600 and 7200 —
both multiples of 60 *and* exactly on checkpoint boundaries. That keeps the clock
60-aligned (so checkpointing works) and makes the post-restore replay a no-op (so no
fresh news ids are minted). Every failure mode lives outside the tested path.

**Practical stance:** trust the data the API returns, verify anything the docs assert.
When doc and code disagree, code wins — that happened three times in one afternoon
(`/view/topology` node shape, the edge-override route, DRNCP ordering).

## Practical implications for the algorithm module

1. **The Phase 1 ingestion target is `GET /api/v1/view/topology`**, one call, port 8090,
   not `/nodes` + `/edges` on 8080. Fixtures for step 1.4 can come from a saved
   `logs/global_view.json` or a captured topology response.
2. **Node ids are already dense integers** — step 1.5's interning story resolved,
   hash table dropped (above), `Graph` built and tested 2026-09-09.
3. **Recompute weight as `length_m / effective_speed_mps`**; there is no server-side
   weight to compare against, so step 1.6's strong per-edge proof is weakened (above).
   Confirmed live: `/view/snapshot` really does carry `effective_speed_mps` and
   `closed` per edge, flat, exactly as this formula needs.
4. **Heuristic still works in `x_m`/`y_m` metres**, Euclidean, with `V_MAX = 16.67`.
   Our heuristic will be tighter than the sandbox's own — a publishable comparison.
5. **Algorithm 1's output must be an edge-adjacent node sequence** to be publishable via
   `/control/transit/route`, and it can be rendered live in the UI, which is a strong
   demo for the presentation.
6. **Bus stops already exist**; decide explicitly whether to consume or ignore them.
7. **Demand is time-varying**; pick and justify a time slice.
8. **Ask Varun for a path-compute endpoint** to restore the test oracle.

See also [[csc210_varun_sandbox_task]], [[csc210_algorithm_architecture]],
[[csc210_algorithm_module_writeup]], [[csc210_algorithm_build_order]],
[[csc210_route_generation_scope]], [[csc210_algorithm_learning_plan]].
