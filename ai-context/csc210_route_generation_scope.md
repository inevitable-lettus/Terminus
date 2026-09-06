---
name: csc210-route-generation-scope
description: "Team's stated core-algorithm goal for CSC210 bus project — design bus routes from scratch for a randomly generated city; must stay heuristic, not full network-design optimization"
metadata: 
  node_type: memory
  type: project
  originSessionId: 2e664cb5-dba5-4d68-b302-6f772abc113b
  modified: 2026-08-11T10:59:47.256Z
---

Team's stated goal (2026-08-11): a sandbox randomly generates a city (a graph of nodes/roads) on each run with simulated conditions; the core algorithm should design an efficient bus route network for that generated city (which routes exist, where they run), then a passenger-facing platform (search, tickets) sits on top.

**Why this needs guardrails:** structurally this is the Bus Transit Route Network Design Problem (BTRNDP) — an NP-hard combinatorial optimization problem in transportation research, normally solved with genetic algorithms, simulated annealing, tabu search, or local search (see Fan & Machemehl, "Optimal Transit Route Network Design Problem," SWUTC/04/167244-1, 2004). None of those methods are in [[csc210_dsa_project]]'s required DSA focus list (Graph, Heap, Queue, Hash Table, Dijkstra, BFS, A*, Sorting). If the team implements a true optimizer as "the algorithm," it's effectively a grad-level OR project wearing a DSA-course costume — real grading risk since it strays from the required toolkit.

**Recommended scoping (given to Garv 2026-08-11):** frame route generation as a greedy/heuristic pass, not an optimizer. Build the graph from the generated city, identify high-demand nodes, greedily construct routes that cover demand while using Dijkstra for stop-to-stop distances/times. Never claim optimality — describe it explicitly as a heuristic in docs/writeup, since a grader who recognizes this is NP-hard will ask what guarantees optimality.

**That paper's actual useful part:** Chapter 5 (Dijkstra + Yen's K-shortest-path, used there for candidate-route generation) is a fair citation. The rest — the GA/SA/tabu-search network-design methodology — is the thing to avoid replicating, not a template to follow.

**How to apply:** when helping design or review the route-generation algorithm, push back on any approach that reaches for GA/SA/tabu search/exhaustive combinatorial search as the primary method. Steer toward greedy heuristics + Dijkstra/BFS/A* that stay inside the course's required toolkit.

**Scope narrowed by the sandbox rewrite (2026-09-06).** DSTNS now places bus stops itself — deterministic placement at 300 m minimum / 500 m target spacing with 800 m coverage, 22 stops in the current 617-node run, flagged as `roles.bus_stop`. If we consume them, Algorithm 1 drops from "choose stops **and** routes" to "choose routes over given stops," which is a materially smaller problem — still BTRNDP-shaped, still NP-hard, but with the stop-location half already fixed. That is a legitimate scope reduction and it makes the greedy framing even easier to defend. It must be **stated explicitly** in the writeup, not left implicit, or it reads as if we solved the harder problem. The alternative — ignore DSTNS's stops, select our own, and use theirs as a comparison baseline — keeps the full problem and gives a built-in benchmark. Open decision, flagged in [[csc210_algorithm_build_order]] Phase 4.

Also note demand is now **time-varying** in DSTNS (synthetic buildings with rush-hour windows), so "identify high-demand nodes" is now "identify high-demand nodes *at a chosen virtual time*." Pick one, justify it, and name the limitation. See [[csc210_sandbox_data_layer]].

See also [[csc210_dsa_project]], [[csc210_varun_sandbox_task]], [[csc210_sandbox_data_layer]].
