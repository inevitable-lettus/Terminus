---
name: csc210-algorithm-learning-plan
description: "Garv's teaching roadmap for building the CSC210 algorithm module — concept sequence (graph/heap/Dijkstra/A*/weighted-A*) and current build stage"
metadata:
  type: project
---

Teaching roadmap given 2026-08-21, before starting algorithm-module implementation. Two parts: the mental model of how [[csc210_sandbox_data_layer]] works, and the concept-by-concept build order for the algorithm module.

**Sandbox mental model:** SUMO = microscopic vehicle simulator (moves cars/buses, has no API). Control layer (Varun's C++) wraps it, keeps a RoadGraph in sync, runs weather sim (DWS), and republishes everything as JSON over REST. Every sim step: SUMO reports vehicle speed/count per edge → smoothed into traffic_level → DWS adds flood/seismic → combined into `effective_weight_s` per edge → algorithm module reads that whenever it computes a route. Full schema in [[csc210_sandbox_data_layer]].

**Concept build order** (each step unlocks the next):
1. Graph ingestion — parse `/nodes` + `/edges` JSON into own adjacency-list graph.
2. Priority queue / min-heap fundamentals (`java.util.PriorityQueue` or equivalent).
3. Plain Dijkstra — single-source-to-all, greedy relaxation, correctness relies on non-negative weights. Test against sandbox's own `/paths/compute` as a cross-check oracle (per [[csc210_sandbox_data_layer]] decision — sandbox's Dijkstra is a test oracle only, never in the submitted pipeline).
4. Greedy demand-covering wrapper around step 3 = Algorithm 1 (route network builder). Blocked on `demand_score` metadata field, tracked in issue https://github.com/varunkarthic/csc210-sandbox/issues/1.
5. Plain A* = Algorithm 2 (passenger pathfinder), runs on the transit graph Algorithm 1 produces, not the raw road graph.
6. Verify `h(n) = m_min × distance(n,goal)/V_MAX` is admissible (never overestimates) and consistent (triangle inequality holds edge-by-edge) — proof already derived in [[csc210_algorithm_architecture]], worth hand-checking on a few real sandbox nodes.
7. Weighted A* (`f = g + ε·h`), sweep ε ≥ 1, plot nodes-expanded vs path-cost — the required algorithm-comparison research deliverable.
8. Determinism harness — use sandbox's manual clock mode (`POST /simulation/step`) instead of realtime, so benchmark runs are reproducible (same seed + same step count ⇒ identical edge weights every run).

**Superseded as the working plan (2026-08-23).** The numbered concept list above is kept as background rationale, but the executable plan — phases, per-step proofs, and the live current-stage tracker — now lives in [[csc210_algorithm_build_order]], and the working method in [[csc210_algorithm_build_protocol]]. Track progress there, not here.

**⚠ The sandbox mental model above is now WRONG (2026-09-06).** It described `sumo_control`, which Varun has replaced with **DSTNS**. In DSTNS, SUMO is *not* in the request path — the sandbox compiles an OSM file into its own canonical graph and advances its **own analytical** traffic/weather model on a virtual 24-hour clock, exporting to SUMO only for offline validation. So "SUMO reports vehicle speed/count per edge → smoothed into traffic_level → ... → `effective_weight_s`" does not describe the running system: there is no `traffic_level` and no `effective_weight_s`. Correct model in [[csc210_sandbox_data_layer]], rewritten for DSTNS.

**Nothing in this file is current.** Kept only as a record of the original teaching sequence.

See also [[csc210_algorithm_module_writeup]], [[csc210_algorithm_architecture]], [[csc210_sandbox_data_layer]], [[csc210_route_generation_scope]].
