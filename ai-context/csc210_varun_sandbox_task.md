---
name: csc210-varun-sandbox-task
description: "Teammate Varun's sandbox track for CSC210 — settled on SUMO (traffic simulator) rather than a from-scratch C++ engine, still mid-build"
metadata: 
  node_type: memory
  type: project
  originSessionId: 37e7b776-051c-4fdb-9fad-4bf836029a67
  modified: 2026-08-11T10:59:52.765Z
---

Varun (teammate, task prefix V-, see [[csc210_group8_workspace]]) owns the simulation-sandbox track of [[csc210_dsa_project]]. His Task Sheet (V-01 through V-17, deadlines 7–30 Aug 2026) originally scoped this as evaluating SUMO vs Gazebo vs a fully custom C++ engine (V-02/V-03), then designing/building whichever was chosen from scratch.

**Decision made (as of 2026-08-09, via chat, not yet reflected in the Task Sheet checkboxes):** Varun picked **SUMO (Simulation of Urban MObility)**, not a custom-built engine. Status update from him:
- Already has a working prototype using real OSM data for the roundabout near Gate 10 (AU campus).
- Traffic demand currently uses SUMO's built-in `RandomTraffic` generator; custom demand/rules are configurable later.
- SUMO has a built-in traffic light handler — not something the team needs to build.
- Weather effects are NOT built into SUMO natively — still an open TODO for the team (would need to be layered on via runtime parameter changes, e.g. adjusting vehicle speed factors/lane friction through TraCI, since SUMO has no native weather model).
- Plan: SUMO + SUMO-GUI handle the actual simulation and visualization; the team's API is a thin layer on top of SUMO (almost certainly meaning TraCI, SUMO's built-in Python/C++ control interface, rather than a hand-rolled protocol).

**Why this matters:** this resolves the earlier scope-creep flag — SUMO is a mature, purpose-built traffic simulator, so most of the heavy lifting in V-05/V-08/V-10/V-11/V-16/V-17 (custom architecture, OSM ingestion, sandbox core, visual engine) becomes configuration/integration work around SUMO + TraCI rather than building a simulation engine from zero. That's the right call over hand-rolling this. But the Task Sheet still lists these as full from-scratch build tasks and every checkbox is still FALSE — worth getting Varun to update the sheet/task docs to reflect the real (much smaller) remaining scope, per the team's own documentation norms in [[csc210_group8_workspace]].

**Open technical risk to watch:** SUMO's own router (`duarouter`) already implements shortest-path routing internally. The DSA course grade depends on the *team's own* Dijkstra/BFS/A*/Heap implementations ([[csc210_dsa_project]]), so the architecture needs a clear line: SUMO/TraCI supplies ground-truth simulated traffic/positions/congestion data; Garv's/the team's own backend does its own graph extraction from OSM and its own routing/scheduling algorithms on top — it should not just proxy SUMO's built-in routing, or the DSA content of the project gets hollowed out.

**How to apply:** treat the sandbox as settled infra (SUMO + TraCI + SUMO-GUI), not an open research question.

**Major update (2026-08-21):** sandbox repo inspected directly — github.com/varunkarthic/csc210-sandbox. It is far more complete than prior status implied. See [[csc210_sandbox_data_layer]] for full data-layer report. Headline changes to prior open items:

- **Weather layer: BUILT, not open.** A full `DynamicWeatherSimulation` (DWS) module exists — rain (epicentre/radius/intensity/falloff/runoff/drainage/accumulation) and earthquake (spatial falloff, per-edge vulnerability, cumulative damage) both implemented in C++, applied to edge state every simulation step. Earlier "weather layer still open" status is stale.
- **Procedural-vs-OSM conflict: resolved.** Sandbox uses real OSM data exclusively (`map.mode: osm_auto`, Overpass download + `netconvert`), deterministically selected from a curated catalog of inland regions via seed, not per-run procedural generation. "Procedurally generates a city" was Garv's miscommunication — actual system is seed-deterministic OSM selection + reproducible pipeline, not procedural graph synthesis.
- **DSA-content risk: partially resolved, still worth watching.** The sandbox has its *own* C++ `Pathfinder::shortestPath` (Dijkstra, own implementation, not proxying SUMO's `duarouter`) — so the separation-of-concerns goal was honored. Open question: does the team's required Java/algorithm-module Dijkstra/A*/BTRNDP work duplicate this, or does the algo module treat the sandbox's own Dijkstra as sufficient and build only the route-network/A* layers on top? Needs explicit team decision — grading is on the team's own DSA implementation, and "the sandbox already has Dijkstra" is not the same as "we implemented Dijkstra."

Task Sheet checkbox staleness (all FALSE as of 2026-08-09) may now be significantly wrong given actual repo maturity — worth Varun updating the sheet to match.

**Sandbox confirmed live (2026-08-23), via Varun in team chat:**

- **Docker build:** first `docker compose up` takes ~1 hour (image build); after that, seconds to start. The SUMO/TraCI engine itself then takes 2–3 minutes to come up on macOS.
- **Health check:** `GET http://localhost:8080/healthz` → `{"ok":true,"status":"running"}` confirms the service is up. Terminal also logs `SUMO control API listening on 0.0.0.0:8080` once ready.
- **Benign startup error, ignore it:** `Could not connect to TraCI server at localhost:40759 ... Connection refused` appears during startup — expected, self-resolves, no effect on operation. Do not treat as a bug.
- **GUI (macOS only):** needs XQuartz 11, with "Allow connections from network clients" enabled in XQuartz's Security settings. Launch in a separate terminal:
  ```
  xhost +localhost
  DISPLAY=host.docker.internal:0 docker compose --profile gui up gui
  ```
- **Open bug (Varun's side, 2026-08-23):** OSM map fails to load, so the GUI renders blank. The API itself is reported working despite this. **Not yet independently verified** whether `/nodes` and `/edges` return real data or come back empty — check this before building Phase 1 graph ingestion on top of it, since a blank map could mean an empty graph rather than just a rendering issue.
- **Unblocks Garv:** per Varun, the API layer is usable now — Phase 0 (JDK 21 install, repo scaffold, [[csc210_algorithm_build_order]]) can start regardless of the map/GUI bug.

See also [[csc210_sandbox_data_layer]] for the `/healthz` endpoint noted alongside the rest of the API surface.

---

## ⚠ Sandbox rewritten from scratch — DSTNS (discovered 2026-09-06)

Everything above about `sumo_control` describes a codebase Varun has **replaced**. New system: **DSTNS — Deterministic Spatiotemporal Transport Network Simulator**, C++20, on the group repo's **`dstns`** branch (`257257a`, 2026-09-05; initial commit 2026-09-04). Full data-layer report rewritten in [[csc210_sandbox_data_layer]].

**Three divergent copies now exist** — `dstns` (live), `Sandbox` (old `sumo_control`, 2026-08-22), and `varunkarthic/csc210-sandbox` `Programs` (old `sumo_control` + the merged `demand_score` PR). Confirm with Varun that `dstns` is canonical and the others are dead.

What changed that affects Garv:

- **Port 8080 → 8090.** Entire API surface renamed: `/api/v1/view/*` (read), `/api/v1/playback/*` (lifecycle), `/api/v1/control/*` (write), `/api/v1/news` (replaces the ROE ledger).
- **`demand_score` is gone.** The work from issue #1 / PR #2 did not survive the rewrite — `demand_aggregator.{hpp,cpp}` do not exist in DSTNS. Demand is now synthetic buildings (School/Office/Mall/Store) with time-of-day activity windows, so it is **time-varying**.
- **The Dijkstra oracle is gone from the API.** DSTNS has its own A* (`RoutePlanner`), benchmarked at ~5.7 µs/query, but no path endpoint is registered. **Ask Varun to expose it** — highest-value request we have.
- **`/simulation/step` is gone.** Determinism now comes from `playback/prepare` + `playback/seek` at a fixed seed.
- **Node/edge ids are dense integers**, not strings. **Cost model replaced** — no precomputed `effective_weight_s`; derive `length_m / effective_speed_mps`.
- **Bus stops are now placed by the sandbox** (22 in the current run), which narrows Algorithm 1's scope unless we deliberately ignore them.
- **Map is not the AU Gate 10 roundabout.** "Berlin, 617/1294" was never checked
  live and turned out wrong — confirmed 2026-09-09 against a real running
  container: Paris-coordinate OSM extract, 120 nodes, 436 edges. Node/edge count
  may depend on scenario/seed params passed to `playback/start`; treat any figure
  as provisional until re-checked at whatever scale later phases actually run at.
- **SUMO is out of the request path** — DSTNS exports to SUMO for offline validation, but live speeds come from its own analytical model. The "SUMO reports speeds → traffic_level" mental model in [[csc210_algorithm_learning_plan]] no longer describes the system.
- New scope Varun added: React 19 + MapLibre GL operator UI, SSE streaming, Docker/TLS gateway, SQLite WAL logging, four CLI tools, five test suites, ~40 docs files.

**Old OSM-map-load bug: moot.** It was a `sumo_control` bug; that code is gone. DSTNS loads a real graph fine — confirmed independently, see below.

**Independently verified 2026-09-09.** Built and ran DSTNS locally (`docker compose up -d --build` on the group repo's `dstns` branch, HEAD `0fb6307` — note: *later* than the `257257a` this file and [[csc210_sandbox_data_layer]] were originally audited against). `/health`, `/view/topology`, `/view/snapshot` all return real data. But the actual JSON shapes returned differ from what the `257257a` code-read documented — see [[csc210_sandbox_data_layer]]'s "Live-verified schema, 2026-09-09" section. Either the branch moved between audits, or the original static code-read didn't match the built binary; either way, "code wins" now means *this* commit's live response, not last week's source read.

**How to apply:** treat DSTNS as the integration target, `sumo_control` as dead. Re-read [[csc210_sandbox_data_layer]] before any Phase 1 work.

---

## ⚠ Third rewrite in progress — SUSE, unmerged branch (found 2026-09-08)

Varun pushed `suse-experimental` to the group repo (2 commits: `9bc1b46` Initial Commit,
`177a703` Bug Fixes, both 2026-09-08). **Not merged, not `dstns`, not confirmed
canonical.** Third full engine rewrite in ~4 weeks: `sumo_control` → `DSTNS` → **SUSE
(Stochastic Urban Simulation Engine)**.

What it is: C++20 core (`suse_core`) with real `libsumo` link, Node.js gateway
(`api/server.mjs`) as a stateless REST/SSE proxy over Unix-socket JSON-RPC, port 8080.
Claims 38 "modules" — weather, Joyner-Boore seismic PGA + structural fragility, flood
hydrology, power grid with cascading blackouts, VIP motorcades, civic-event crowd
curves, emergency dispatch, transit, land-use, mode choice, etc. Old React/MapLibre UI
(`ui-engine/`) deleted; replaced with a plain `web/` (vanilla JS + Leaflet).

**Assessment (spot-checked source, not just docs):** not vaporware — real formulas exist
in `src/models/models.cpp` (e.g. `power_substation_overload_risk`) backing the test names
listed in `context/test-results.md`. But total C++ is only ~5.9K lines across src+include
for 38 claimed modules, and the self-reported `context/handoff.md` /
`context/known-bugs.md` / `context/test-results.md` claim "38 modules fully implemented,
mathematically verified" and "59/59 100% PASS" — **same inflation pattern as DSTNS**
(see the "How much to trust DSTNS" section above: engine real, docs inflated, tests
thin). Treat these claims as unverified until independently built and run.

**Concrete problems found:**
1. **103MB of generated SUMO/OSM XML committed directly to git** under `worlds/cache/`
   (one file, `network.net.xml`, is 817K lines) — not covered by `.gitignore`. This will
   bloat the repo badly on every clone/fetch going forward. Flag to Varun to purge and
   gitignore.
2. `SUMO_HOME` defaults hardcoded to `/Users/varun/sumo` in the README/build scripts —
   not portable to any other machine or CI without an explicit env override.
3. Scope keeps expanding beyond the course's bus-route-planner target (power grid,
   seismic fragility, VIP motorcades are well outside a DSA route-planning project) —
   worth a direct conversation with Varun about scope control given three rewrites
   already.

**Decision (Garv, 2026-09-08): staying on DSTNS.** SUSE is experimental, unmerged, and
not the integration target for now. Do not build Phase 1 ingestion against
`suse-experimental`. Treat `dstns` (`257257a`) as canonical until/unless Garv says
otherwise. Ignore SUSE's port-8080 API, `worlds/cache` bloat, and 38-module claims for
algorithm-module purposes — [[csc210_sandbox_data_layer]]'s DSTNS port-8090 contract
remains the one to build against.
