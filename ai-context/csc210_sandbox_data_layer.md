---
name: csc210-sandbox-data-layer
description: "Data layer of Varun's csc210-sandbox C++ repo — graph model, JSON schemas, HTTP API, persistence files — that the algorithm module will consume"
metadata:
  type: project
---

Full inspection (2026-08-21) of github.com/varunkarthic/csc210-sandbox, C++20, "Deterministic SUMO Control Layer" v1.1. This is the data source the algorithm module (Java or whatever language is chosen — see [[csc210_algorithm_architecture]]) integrates against.

**Access mechanism: HTTP JSON REST API, not a raw socket.** cpp-httplib server, full OpenAPI 3.1 spec at `docs/openapi.yaml`. Runs in Docker (`docker compose up`), port 8080. Bearer-token auth optional. This supersedes the earlier "socket IPC, wire contract TBD" plan — the contract already exists and is documented.

## Graph model (the core data layer)

**Node** (`GET /api/v1/nodes`, `/nodes/{id}`):
```json
{
  "id": "123456",
  "type": "bus_stop",            // road | bus_stop | highway | blocked_road
  "position": {"x": 1432.4, "y": 908.1},   // SUMO network metres, NOT lat/lon
  "nearby": [{"node_id": "...", "edge_id": "..."}],
  "environment": {"flood_depth_m": 0.12, "seismic_damage": 0.03, "accessible": true}
}
```
Node types are a control-layer classification on top of SUMO junctions — `bus_stop` = junction nearest an imported OSM bus stop, `highway` = incident to a motorway/trunk edge. SUMO's internal `:`-prefixed connector junctions are excluded from the API entirely.

**Edge** (`GET /api/v1/edges`, `/edges/{id}`) — splits immutable topology from mutable live conditions:
```json
{
  "id": "88321#0", "from_node": "123456", "to_node": "123457",
  "reverse_edge_id": "-88321#0", "road_class": "highway.primary",
  "length_m": 181.6, "speed_limit_mps": 13.89, "lane_count": 2,
  "lane_ids": [...], "shape": [{"x":.., "y":..}],
  "configuration": {
    "traffic_level": 0.72, "flood_depth_m": 0.18, "seismic_damage": 0.05,
    "manual_weight_multiplier": 1.4, "manually_blocked": false, "node_blocked": false,
    "blocked": false, "base_travel_time_s": 13.07, "effective_speed_mps": 7.91,
    "effective_weight_s": 32.15
  }
}
```
**Every graph edge is guaranteed bidirectional** (structural invariant, enforced at load — service refuses to start otherwise): for every retained `u→v`, a retained `v→u` also exists. Matches [[csc210_algorithm_architecture]]'s undirected-graph assumption.

**Edge weight is precomputed server-side and shipped as a plain number** — the algorithm module does not need to reimplement the physics:
```
v_eff = v_limit * max(0.05, (1-0.75t²)(1-0.85f²)(1-0.80d))
w = L / max(0.1, v_eff) * m
```
`t`=traffic_level, `f`=flood_depth_m/0.50, `d`=seismic_damage, `m`=manual_weight_multiplier. Weight is `null`/infinite when blocked (manual, node-derived, flood ≥0.50m, or seismic ≥0.90). This is a **superset** of the cost model in [[csc210_algorithm_architecture]] — same multiplicative shape, but the sandbox owns and computes it live, already folding in weather+traffic+manual overrides into one `effective_weight_s` per edge.

**PATCH `/edges/{id}`** lets external callers push conditions in: `traffic_level`, `flood_depth_m`, `seismic_damage`, `manual_weight_multiplier`, `blocked`, `persist`. This is the write side — useful if the algorithm module needs to simulate hypothetical closures/conditions rather than just read live state.

## Routing already built into the sandbox

`POST /api/v1/paths/compute` runs the sandbox's **own C++ Dijkstra** (`Pathfinder::shortestPath`, deterministic tie-breaking) over current `effective_weight_s` — not SUMO's `duarouter`.

**Decided (2026-08-21):** the algorithm module will **not** use this built-in Dijkstra for the graded DSA work. It's sandbox-internal plumbing (used for bus rerouting/probe-vehicle display inside the sandbox itself) — the algorithm module pulls raw graph state (`/nodes`, `/edges`) and runs its **own independent** Dijkstra (Algorithm 1, route-network builder) and A* (Algorithm 2, passenger pathfinder) per [[csc210_algorithm_module_writeup]]. `/paths/compute` may still be useful as a cross-check oracle during testing (same weights, so results should match), but is not part of the submitted algorithm implementation.

## Other live data surfaces

- `GET /healthz` (not under `/api/v1`) — liveness check, returns `{"ok":true,"status":"running"}`. Confirmed working 2026-08-23 (see [[csc210_varun_sandbox_task]]); terminal also logs `SUMO control API listening on 0.0.0.0:8080` once ready.
- `GET /api/v1/status` — clock, seed, SUMO version, graph summary.
- `POST /api/v1/dws/events`, `GET /api/v1/dws/events` — schedule/list rain or earthquake hazard events (epicenter, radius, intensity, duration, falloff exponent).
- `GET /api/v1/roe?...` — **Record of Events**: hash-chained, append-only JSONL log (`record-of-events.jsonl`) of every semantic transition (lifecycle/mutation/hazard/path/movement categories), each record with simulation timestamp, monotonic sequence, and hash-link to the prior record. `/roe/verify` checks chain integrity. Good candidate for feeding analytics/history features on the passenger-info side.
- `POST /simulation/pause|resume|step` — manual clock control for deterministic experiments (needed for reproducible algorithm benchmarking, e.g. the weighted-A* ε-sweep in [[csc210_algorithm_module_writeup]]).

## Persistence files (on-disk scenario bundle, `data/scenarios/<scenario_id>/`)

| File | Meaning |
|---|---|
| `manifest.json` | identity, seeds, map hashes, config (replay contract) |
| `snapshot.osm.xml` | frozen OSM input |
| `network.net.xml` | SUMO-compiled network |
| `overrides.json` | persistent node/edge mutations |
| `events.json` | persistent DWS (weather) event definitions |
| `paths.json` | persisted routes + draw styles |
| `record-of-events.jsonl` | ordered semantic activity log (see ROE above) |
| `runtime-state.json` / `checkpoint.state.xml.gz` | control-side + SUMO microscopic checkpoint |

## Determinism (relevant for reproducible benchmarking)

One root seed (decimal string, avoids JS `2^53` rounding) derives independent sub-seeds (SplitMix64) for OSM region choice, SUMO, DWS, bus demand, background traffic. Same seed + same OSM snapshot + same config ⇒ identical replay. Manual clock mode + logged `/simulation/step` calls is the documented way to run controlled, reproducible algorithm experiments against this data — directly usable for the course's required algorithm-comparison research component.

## Practical implications for the algorithm module

1. No need to design a wire format — REST/JSON contract already exists, documented in `docs/openapi.yaml`. Next real integration task is just: write an HTTP client in whatever language the algo module ends up in, and map `nodes`/`edges` JSON onto the module's own graph/`Node`/`Edge` types.
2. `effective_weight_s` already matches the multiplicative cost-model shape agreed in [[csc210_algorithm_architecture]] — algorithm module can either trust this precomputed weight directly, or recompute from raw `configuration` fields if the team wants the DSA-code-ownership story to include the cost-model math itself (worth a team decision, ties into the Dijkstra-ownership question above).
3. Coordinates are SUMO-metre `x,y`, not lat/lon — any heuristic distance function (`h(n)` in A*) must operate in this coordinate space, not geographic degrees.
4. Bus-stop/route-network design (Algorithm 1 in [[csc210_algorithm_module_writeup]]) can source demand/candidate-hub nodes from `type: bus_stop` nodes directly, since the sandbox already tags them from real OSM public-transport data.

## Known gap: no demand/POI data (issue filed 2026-08-21)

Algorithm 1 needs a per-node demand score (population/POI density) per [[csc210_algorithm_module_writeup]]. Confirmed by reading `src/osm_pipeline.cpp`: the Overpass query only fetches `highway` ways + `bus_stop`/`platform` nodes — no population/amenity/landuse data anywhere in the pipeline. `Node.metadata` (`model.hpp`) is a generic string map, already serialized, only ever populated with `bus_stop_id` (`network_parser.cpp:138`) — a ready slot, just unfilled.

**Filed as issue:** https://github.com/varunkarthic/csc210-sandbox/issues/1 (2026-08-21) — requests extending the Overpass query with `amenity`/`shop`/`landuse`, aggregating a per-node demand score into `node.metadata["demand_score"]`, and documenting the key in `docs/openapi.yaml`.

**How to apply:** algorithm module's Algorithm 1 (route network builder) should wait on `metadata.demand_score` being populated before wiring up real demand-based hub selection — check the issue status before assuming this field exists. Everything else needed for the algorithm module (topology, cost model, bus_stop typing, REST API) is already sufficient; this was the one real sandbox gap found.

## Known issue: OSM map fails to load (open, 2026-08-23)

Per Varun (2026-08-23), the sandbox's OSM map load is currently broken, leaving the GUI blank. The API is reported to still work despite this, but whether `/nodes`/`/edges` return real data or come back empty has **not been independently verified yet** — confirm before relying on it for Phase 1 graph ingestion in [[csc210_algorithm_build_order]]. See [[csc210_varun_sandbox_task]] for full startup/verification notes (Docker build time, `/healthz`, benign TraCI startup error, macOS GUI setup via XQuartz).

See also [[csc210_varun_sandbox_task]], [[csc210_algorithm_architecture]], [[csc210_algorithm_module_writeup]].
