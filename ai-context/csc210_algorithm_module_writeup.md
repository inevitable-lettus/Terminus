# Algorithm Module Writeup — Smart Bus Route Planning (CSC210, Group 8)

## 1. Overview

The core routing algorithm module is split into two independent algorithms, addressing two distinct problems that occur at different times and different frequencies:

| | Algorithm 1: Route Network Builder | Algorithm 2: Passenger Pathfinder |
|---|---|---|
| **Question answered** | Which bus routes should exist, and where do they run? | Given an existing route network, what's the best way for a passenger to get from A to B? |
| **When it runs** | Once per generated city (or on route-network regeneration) | On every passenger query |
| **Input graph** | Raw road network (roads + intersections) | Transit graph produced by Algorithm 1 (stops + bus segments + transfers) |
| **Core technique** | Greedy heuristic construction, using Dijkstra as a subroutine | A* search |
| **Why this algorithm** | Needs one-to-many shortest paths (one hub → many demand nodes); Dijkstra is optimal for this | Needs one single-source-single-target query, fast, per user request; A* is optimal for this |

This split is deliberate and directly informs the required shortest-path-algorithm-comparison research component of the course project: both algorithms are exercised on real workloads within the system, not as isolated academic exercises.

## 2. Cost Model (shared by both algorithms)

Costs are attached to **edges**, not nodes — a congested or rain-slicked road is a property of the road segment itself, not of the intersection at either end.

```
cost(u, v) = (length(u,v) / freeFlowSpeed(u,v)) × trafficMult(u,v) × weatherMult(region)
```

- Base term is free-flow travel time (length ÷ speed limit / typical speed).
- Multipliers are **multiplicative, not additive** — congestion slows a road in proportion to how long the road is; a flat additive penalty would unfairly punish short and long edges equally.
- `trafficMult` and `weatherMult` are environment variables supplied by the simulation sandbox (SUMO/TraCI on the C++ side).
- Exact multiplier value ranges are not yet finalized — open item, tracked separately.
- Graph is undirected: all roads are bidirectional.

## 3. Algorithm 1: Route Network Builder

### 3.1 Step 1 — Distance computation via Dijkstra (subroutine)

Standard single-source shortest path:

- **Data structures:** min-priority queue (binary heap) keyed by tentative cost; `dist[]` array initialized to ∞ (0 for source); `prev[]` predecessor array for path reconstruction; visited/settled set.
- **Loop:**
  1. Pop the unvisited node with lowest `dist`.
  2. Mark it settled.
  3. Relax every outgoing edge: if `dist[u] + cost(u,v) < dist[v]`, update `dist[v]`, set `prev[v] = u`, push `v` onto the queue.
- **Complexity:** O((V + E) log V) with a binary heap.
- **Output:** shortest distance and path from the source to *every other node* in the graph, in a single run.

This is run from candidate "hub" nodes to obtain distances to all demand nodes at once — the reason Dijkstra, not A*, is used here: the problem is inherently one-to-many, and one Dijkstra run beats k separate A* runs for that shape of query.

### 3.2 Step 2 — Greedy route construction

- Each node carries a demand score derived from the generated city's population/points-of-interest data.
- **Loop:**
  1. Pick the highest-demand node not yet "covered."
  2. Run Dijkstra from it (Step 3.1) to get distances/paths to all other demand nodes.
  3. Construct a route: a sequence of stops following shortest-path structure, greedily extended to capture as much additional demand as possible within a length/time budget.
  4. Mark every node within walking distance of the new route as "covered."
  5. Repeat until demand coverage crosses a target threshold or the route budget is exhausted.
- This is a greedy, set-cover-flavored construction — no backtracking, no global optimality guarantee, and none is claimed.

### 3.3 Why greedy, not an optimizer

Designing a mathematically optimal bus route network is the Bus Transit Route Network Design Problem (BTRNDP), a known NP-hard combinatorial optimization problem in transportation research, conventionally solved with genetic algorithms, simulated annealing, or tabu search (Fan & Machemehl, 2004). None of those techniques are part of this course's required toolkit (Graph, Heap, Queue, Hash Table, Dijkstra, BFS, A*, Sorting). Implementing a true optimizer here would exceed course scope and invite grading scrutiny over unjustified methodology.

The chosen approach — greedy demand-covering construction backed by Dijkstra — stays entirely inside the required toolkit, is explainable, and is explicitly documented as a heuristic, not a claim of optimal network design.

### 3.4 Output

A transit graph: stops as nodes, bus segments as edges, each edge tagged with the route(s) that serve it. This becomes the input to Algorithm 2.

## 4. Algorithm 2: Passenger Pathfinder (A*)

Operates on the transit graph produced by Algorithm 1 — not the raw road graph. A raw-road search would produce a *driving* route; passengers need a *bus-taking* route, including transfers. Transfer edges carry their own cost (wait time + walk time between stops).

- **Data structures:** open set (priority queue keyed by `f(n)`), `g[]` (best known cost-so-far to each node), `came_from[]` for path reconstruction, closed set.
- `g(n)`: actual accumulated cost from the start to node `n`.
- `h(n)`: heuristic estimate of remaining cost from `n` to the goal:

```
h(n) = m_min × distance(n, goal) / V_MAX
```

  where `V_MAX` is the highest free-flow speed of any edge in the graph, and `m_min` is the smallest currently-observed combined traffic×weather multiplier. This is a provable lower bound on true remaining cost, which keeps the heuristic admissible and consistent. `distance()` uses straight-line (Euclidean) distance by default, or Manhattan distance if the generated city has a grid-like layout.

- `f(n) = g(n) + h(n)` — the open set always pops the lowest-`f` node next.

**Loop:**
1. Pop the node with lowest `f`.
2. If it is the goal, terminate and reconstruct the path by walking `came_from` backward.
3. Otherwise, for each neighbor: compute `tentative_g = g(current) + cost(current, neighbor)`. If this is better than the neighbor's currently known `g`, update `g[neighbor]`, `came_from[neighbor]`, `f[neighbor]`, and push the neighbor onto the open set.

**Correctness note:** because `h` never overestimates true remaining cost (admissibility), the first time A* pops the goal node off the open set, the reconstructed path is guaranteed optimal.

**Important invariant:** traffic and weather penalties must live only in `g` (actual edge costs), never in `h` (the heuristic estimate). Folding live conditions into the heuristic would break admissibility and could cause A* to return a suboptimal path.

## 5. Research Component: Weighted A*

To satisfy the course's required shortest-path-algorithm-comparison deliverable, the module includes a weighted-A* experiment:

```
f(n) = g(n) + ε · h(n)
```

Sweeping `ε` from 1.0 upward trades off optimality for search speed, with a bounded suboptimality guarantee: the returned path cost is never worse than `ε ×` optimal. Plotting nodes-expanded vs. path cost across a range of `ε` values, alongside a direct Dijkstra vs. A* comparison (query time, nodes expanded, path quality) on the same transit graph, produces the comparison data the course requires.

## 6. System Integration (context, not part of the algorithm module itself)

- Algorithm module language: open (no TA mandate — corrected 2026-08-21). Simulation sandbox is **C++** (SUMO + TraCI), per TA's actual suggestion.
- The two communicate via a **socket**, exchanging the city graph and live conditions in one direction and computed routes in the other. An ONNX-based integration was considered and rejected: ONNX is a tensor-dataflow serialization format for neural networks, not a general-purpose code format, and A*'s priority-queue-driven graph traversal cannot be expressed as a static tensor graph.
- The exact JSON/protobuf wire schema for this socket connection is not yet finalized — the next concrete integration task.

## 7. Open Items

- Exact `trafficMult` / `weatherMult` value ranges: undecided.
- JSON/protobuf wire contract between the Java algorithm module and the C++ sandbox: not yet defined.
- Weather layering on top of SUMO (not natively supported, needs TraCI-driven parameter adjustment): not yet built.
- Whether the generated city is procedurally generated per run or based on fixed real-world OSM data (current sandbox prototype uses real OSM data for the AU Gate 10 roundabout, not procedural generation): unresolved, needs confirmation with the sandbox-track owner before the graph-ingestion pipeline into Algorithm 1 is finalized.
