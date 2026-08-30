# Design — Algorithm Module

This module is my (Garv's) part of the Smart Public Bus Route Planning project (CSC210, Group 8). Varun's sandbox simulates a city and its traffic/weather conditions and exposes it over a REST API. This module reads that data and does two things:

1. Decides what bus routes should exist in the city.
2. Answers a passenger's "how do I get from A to B" query.

These are two separate problems that run at different times, so they're two separate algorithms.

## How it talks to the sandbox

The sandbox runs as its own service (C++, SUMO-based) and exposes a REST API — `GET /nodes`, `GET /edges`, `POST /paths/compute`, etc. This module is a separate program that calls that API over HTTP and gets back JSON. There's no shared code or shared memory between the two — just requests and JSON responses.

## Cost model

Every road segment (edge) has a cost, meaning roughly how long it takes to travel:

```
cost(u, v) = (length / free_flow_speed) × traffic_multiplier × weather_multiplier
```

Free-flow speed is how fast you'd go with no traffic. Traffic and weather multipliers slow that down. They're multiplied in, not added, because a jam should slow a road down in proportion to how long it is — a flat penalty would hit a 50m street and a 5km highway the same way, which doesn't make sense.

Cost lives on the edges (roads), not the nodes (intersections) — a traffic jam is a property of the road, not the junction.

The sandbox also computes its own version of this cost as `effective_weight_s`. Instead of just trusting that number, this module recomputes it from the raw inputs (traffic level, flood depth, earthquake damage, any manual overrides) and treats the sandbox's number as a way to check the computation is right, not as an input.

**Not decided yet:** the exact ranges for the traffic and weather multipliers.

## Algorithm 1 — Route Network Builder

Question: given the city's road network, what bus routes should exist and where should they run?

This runs once per generated city, not per passenger request.

Every node has a "demand score" — roughly, how many points of interest (shops, amenities, etc.) are near it, standing in for population since there's no census data available. This is a proxy, not real demand data, and I'm saying that plainly in the report rather than treating it as ground truth.

The approach:

1. Pick the highest-demand node that isn't covered by a route yet.
2. Run Dijkstra's algorithm from that node to find shortest distances to every other node in one pass.
3. Build a route out of that shortest-path structure, extending it to pick up more demand until it hits a length/time budget.
4. Mark everything near the new route as "covered."
5. Repeat until enough demand is covered or the budget runs out.

This is a greedy approach — it makes the locally best choice at each step and never goes back to reconsider. It doesn't guarantee the best possible network overall, and I'm not claiming it does.

**Why greedy instead of actually optimizing the network:** designing a truly optimal bus network is a known hard problem in transportation research (it's NP-hard — meaning there's no known way to solve it exactly except by trying an unreasonable number of combinations as the city grows). The standard ways to attack it — genetic algorithms, simulated annealing, tabu search — aren't part of this course. So the design deliberately stays inside what the course covers (graphs, heaps, Dijkstra, hashing) and is upfront that it's a heuristic, not an optimizer. This is a scoping decision, not something I ran out of time to do properly.

Output of this step: a smaller "transit graph" — stops as nodes, bus routes as edges, plus transfer edges where routes cross.

## Algorithm 2 — Passenger Pathfinder

Question: given the transit graph from Algorithm 1, what's the best way for a specific passenger to get from stop A to stop B?

This runs on every passenger query, so it needs to be fast for a single request rather than computing paths to everywhere at once. That's why it's a different algorithm from Algorithm 1: Dijkstra is better when you need distances to many destinations at once (building the network), A* is better for one specific start and end point (a passenger query).

A* is Dijkstra with a hint: alongside the actual cost so far, it also estimates the remaining cost to the goal and prioritizes nodes that look most promising. The estimate has to never overestimate the real remaining cost, or the search can return a wrong (non-shortest) answer.

The estimate used here:

```
h(n) = smallest_possible_traffic/weather_multiplier × straight_line_distance(n, goal) / fastest_possible_speed
```

This is always less than or equal to the true remaining cost, because it assumes the best-case speed and best-case conditions the rest of the way — so it can't accidentally point the search somewhere wrong.

One rule that matters here: live traffic/weather conditions only affect the actual cost calculation, never the estimate. If they leaked into the estimate, the "never overestimate" guarantee could break and the search could return a worse-than-best path without any error to flag it.

Runs on the transit graph from Algorithm 1, not the raw road graph — a raw road search would give a driving route, not a bus route with transfers.

## Data structures

- **Graph:** adjacency list (each node stores a list of its neighboring edges), not an adjacency matrix. The road network is sparse — each intersection connects to a handful of others, not all of them — so a matrix would waste a lot of memory storing mostly zeros.
- **Node lookup:** hash map from the sandbox's string IDs to plain integer indices, so later array lookups are fast.
- **Priority queue:** a hand-written binary heap rather than Java's built-in `PriorityQueue`. Dijkstra and A* need to lower a node's priority after it's already in the queue (when a shorter path to it is found), and the built-in queue can't do that directly — you'd have to push duplicate entries and skip the stale ones later, which wastes memory. A heap with a real "decrease priority" operation avoids that.

## Language

Written in Java. The reasoning, briefly: the sandbox is a separate service reached over HTTP regardless of what language this module is written in, so there's no integration benefit to matching C++. The graph sizes involved here are small enough that the language's speed difference doesn't matter much compared to the network calls to the sandbox. And while learning the algorithms, a Java bug throws a clear error at the exact line, where a similar C++ bug can silently corrupt memory — worth more than the speed difference at this stage.

Fair concession: C++ would genuinely be faster, and if this needed to run at a much bigger city scale, that would matter. At this project's scale it doesn't.

## Research component

The course asks for a comparison between shortest-path algorithms. This module does that with weighted A*:

```
f(n) = actual_cost_so_far + ε × estimated_remaining_cost
```

At ε = 1 this is plain A*. Raising ε trades away some path quality for speed — fewer nodes get looked at, but the resulting path is guaranteed to be no worse than ε times the true best path (not just "probably okay"). Sweeping ε and plotting nodes-examined against path quality, alongside a direct Dijkstra-vs-A* comparison, is the comparison data for the report.

## Where things stand

Design above is settled enough to build from. Code itself hasn't started — repo currently has just a license, gitignore, and a stub README.

Rough order I'm building in: set up the build tool → basic types matching the sandbox's data → HTTP client to fetch nodes/edges → graph structure → priority queue → Dijkstra → route builder (Algorithm 1) → A* (Algorithm 2) → the ε-sweep experiment.

## Open questions

- Traffic/weather multiplier value ranges — not finalized.
- Build tool: Gradle or Maven — haven't picked yet.
- Whether the city the sandbox generates is fixed real-world map data or randomly generated per run — affects whether the pathfinder's distance estimate should use straight-line or grid distance. Still need to confirm with Varun.
