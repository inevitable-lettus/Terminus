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
