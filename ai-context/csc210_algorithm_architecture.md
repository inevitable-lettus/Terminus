---
name: csc210-algorithm-architecture
description: "CSC210 bus project — settled Java/C++ integration decision (socket IPC, ONNX rejected), cost model, and A*/Dijkstra role split"
type: project
---

**Language note (corrected 2026-08-21):** no TA mandate on algorithm language. TA's actual suggestion was **C++ for the SUMO sandbox** side. Algorithm module language choice open — earlier claim of "Java, TA insists" was wrong.

**ONNX plan rejected (2026-08-14).** The team originally planned to compile the Java algorithm into an ONNX file for the C++ sandbox to consume. This does not work: ONNX is the Open *Neural Network* Exchange, a serialization format for tensor dataflow graphs, not a general-purpose code format. No Java→ONNX compiler exists for arbitrary imperative code, and A* (priority-queue-driven irregular graph traversal) is not expressible as a static tensor graph.

**Why:** confusing "portable model file" with "portable program." Alternatives given were socket/stdin IPC, JNI, and GraalVM native-image.

**Decision (2026-08-14):** Garv chose **separate algorithm process communicating with the C++ sandbox over a socket**, serializing the city graph + conditions in and routes out.

**Superseded (corrected 2026-08-21):** Varun's actual sandbox (github.com/varunkarthic/csc210-sandbox) does not use a raw socket. It exposes a full **HTTP JSON REST API** (cpp-httplib, OpenAPI 3.1 spec at `docs/openapi.yaml`), already implemented and documented. This IS the wire contract — it's done, not an open item. See [[csc210_sandbox_data_layer]] for the full schema.

**How to apply:** algorithm module should consume the sandbox's REST API directly (poll/query nodes+edges, POST path computes, GET ROE stream) instead of building a new socket protocol. Do not revisit ONNX or raw sockets.

**Environment/cost model:** two environment variables — traffic congestion and weather conditions — inflate edge cost.
- Costs belong on **edges**, not nodes (a congested road is a property of the road, not the intersection). Garv's team initially planned to store cost in the node object; flagged as wrong.
- Recommended form (multiplicative, time-based): `cost(u,v) = (length / freeFlowSpeed) × trafficMult(edge) × weatherMult(region)`. Multiplicative because congestion slows traversal proportionally to segment length; additive penalties would punish short and long edges equally.
- Exact multiplier values still undecided by the team as of 2026-08-14.

**Graph properties (confirmed 2026-08-14):** all roads are **bidirectional** (undirected graph).

**A* vs Dijkstra role split (advice given):** A* only outperforms Dijkstra for single-source-**single**-target queries. The greedy route-network builder in [[csc210_route_generation_scope]] needs one-to-many distances, where a single Dijkstra run beats k separate A* runs. Recommended: implement both, use Dijkstra for bulk route construction, use A* for passenger-facing point-to-point queries, and benchmark both for the writeup.

**Heuristic guidance given:** `h(n) = m_min × distance(n, goal) / V_MAX`, where V_MAX is the highest free-flow speed on any edge and m_min the smallest current combined traffic×weather multiplier — provably admissible and consistent, and tighter than naive Euclidean/V_MAX. Use Manhattan distance instead of Euclidean if the generated city is grid-like. Traffic/weather penalties must live in `g` (edge costs) only, never in `h`, or admissibility breaks.

**Research angle suggested:** weighted A* (`f = g + ε·h`), sweeping ε from 1.0 upward, plotting nodes-expanded vs. path cost. Bounded suboptimality guarantee (result ≤ ε × optimal) makes it an honest, gradeable experiment satisfying the course's algorithm-comparison requirement.

See also [[csc210_dsa_project]], [[csc210_route_generation_scope]], [[csc210_varun_sandbox_task]].
