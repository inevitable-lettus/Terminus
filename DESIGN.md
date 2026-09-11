# Design — Algorithm Module

This module is my part of the Smart Public Bus Route Planning project (CSC210, Group 8). Varun's sandbox simulates a city with its traffic and weather conditions and exposes it over an HTTP API. This module reads that data and does two things:

1. Decides what bus routes should exist in the city.
2. Answers a passenger's "how do I get from A to B" query.

Those are two separate problems that run at different times, so they are two separate algorithms.

This file is the design and the reasoning behind it. For the step-by-step explanation of every piece, with the numbers it produces, see [docs/algorithm.md](docs/algorithm.md). For the decision log with rejected alternatives, see [docs/decisions.md](docs/decisions.md).

## How it talks to the sandbox

The sandbox (DSTNS, C++) runs as its own service on `http://127.0.0.1:8090` and answers HTTP requests with JSON. This module is a separate program that calls it. There is no shared code and no shared memory — just requests and responses. The endpoints consumed are listed in [docs/sandbox-api.md](docs/sandbox-api.md).

The whole road network arrives in one call to `/view/topology`. Live per-road state — current speed, congestion, whether the road is closed — arrives from `/view/snapshot`, and travel demand from `/view/edges`. The sandbox's clock can be parked at a chosen time of day at a chosen random seed, which is what makes any measurement here reproducible.

## Cost model

Every road segment has a cost: how many milliseconds it takes to drive.

```
cost_ms(e) = round( length_m(e) / max(0.1, effective_speed_mps(e)) × 1000 )
```

`effective_speed_mps` is the speed a vehicle actually achieves right now, after traffic, rain, flooding, signal phase and any operator override. It is not the speed limit.

**Cost lives on edges, not nodes,** because that is where the physical quantity is. A road has a length and a speed; a crossroads does not.

**The multipliers behind that speed are multiplicative, not additive.** Congestion slows a road in proportion to its length, so a flat additive penalty would punish a 50 m side street exactly as hard as a 5 km motorway.

**The clamp** stops a division by zero on a fully blocked road, turning it into a very slow road instead of an infinitely long one. Costs are integer milliseconds so that two equal-cost paths compare exactly equal, where floating point could differ in the last bit.

**A closed road is removed from the search, not made expensive.** A huge weight is still finite, so it gets chosen whenever every alternative happens to be worse — a wrong answer rather than a slow one. This is not hypothetical: closing a road through the sandbox's control endpoint produces a snapshot that reports the road as closed *while still reporting a healthy 16.67 m/s speed for it*. Anything that priced closure from speed alone would route straight through it.

**The model is frozen per snapshot.** The sandbox re-derives every road's speed each tick, and signal phase alone makes weights oscillate second to second. A search run against a moving weight measures the weather, not the algorithm.

## Algorithm 1 — Route Network Builder

Given the road network and a picture of where people want to travel, decide what bus lines should exist. Runs once per network, not per passenger request.

**Where demand comes from.** Nobody counted passengers. The sandbox gives each building a strength, a radius over which it is felt, and a live activity level that rises and falls with time-of-day windows. A stop's demand is the sum of `strength × activity` over every building close enough to walk from. This is a proxy, and a synthetic one — one step further from reality than counting real shops and schools would be, and the report says so rather than dressing it up.

**The loop.**

1. Take the uncovered stop with the highest demand. That is the hub — a line has to start somewhere, and starting where the passengers are needs no further justification.
2. Run Dijkstra from the current end of the line. Score every reachable, still-uncovered stop by *demand newly reached ÷ extra driving time*, and extend to the best one. Repeat until the time budget is spent or nothing new would be covered.
3. Mark every stop the finished line serves as covered, plus every stop within walking distance of one.
4. Stop when the demand target is met, the route ceiling is hit, or no uncovered demand is reachable.

That ratio in step 2 is the standard greedy rule for **set cover**: repeatedly take whatever gives the best newly-covered weight per unit of cost.

**Why greedy and not an optimizer.** Designing a truly optimal bus network is the Bus Transit Route Network Design Problem, which is NP-hard — there is no known way to find the provably best answer without effectively trying all of them, and the number of candidate networks grows far faster than the map does. The research literature attacks it with genetic algorithms, simulated annealing and tabu search (Fan & Machemehl, 2004). None of those are in this course's toolkit, and submitting one would mean submitting a black box I could not derive. So this stays inside Graph, Heap and Dijkstra, is labelled a heuristic, and claims no optimality bound.

**Why there is a budget.** Without one the loop degenerates: at every step the cheapest way to cover one more stop is to extend the line you already have, so it keeps extending and you get one absurd route visiting the whole city. The budget models the fact that a real bus line has a maximum sensible length. Sweeping it moves the route count monotonically, which is the evidence that it binds.

**Output.** A transit graph: stops as nodes, bus segments as edges, plus transfer edges where lines meet. Each route also carries its full junction-by-junction traversal, because the sandbox's route-publishing endpoint validates that consecutive junctions share a single directed edge and does not fill in the path between stops.

## Algorithm 2 — Passenger Pathfinder

Given the transit graph, find the fastest journey for one passenger. Runs on every query, so it needs to be fast for a single request rather than computing paths to everywhere at once.

**Why it cannot run on the road graph.** A shortest path over roads is a *driving* route. A passenger cannot drive — they can only ride a line that runs and get off where it stops. More importantly, changing buses costs a wait, and the road graph has nowhere to put that cost, so a road search would transfer ten times for free and report a journey nobody could make.

**The structure that fixes it.** A vertex in the transit graph is not a stop, it is a **(stop, line) pair** — "standing at stop 27 on line R2". Riding one stop further on the same line is one edge. Getting off at stop 27 and onto another line there is a *different* edge, priced at the wait. Because the wait sits on a real edge, the search has to pay it, and a two-change journey is weighed against a slower direct one on the numbers instead of by a hand-written rule.

**A\* itself.** Dijkstra always explores whatever is cheapest so far, which spreads outward in every direction including away from the destination. A* orders its queue by

```
f = g + h
```

where `g` is what the journey has genuinely cost so far and `h` is an optimistic guess of what is left. Set `h = 0` and the ordering becomes `f = g`, which *is* Dijkstra — not similar to it, identical. That is why the two return the same costs: the guess changes what gets looked at first, never what is optimal.

**The heuristic.**

```
h(v) = distance(v, goal) / 16.67 m/s
```

Distance in projected metres, never latitude and longitude — degrees are not a length and dividing them by a speed is meaningless. 16.67 m/s is the fastest anything moves on this map.

`h` must be **admissible**, meaning it never overestimates the true remaining cost. If it were ever too high, A* could dismiss the route that was actually best. Here it holds by construction: the numerator is the least ground any route could cover and the denominator is the fastest anything travels.

**The invariant that must not break: traffic and weather live only in `g`, never in `h`.** Congestion, rain and flooding only ever make the real remaining journey longer. Folding them into the guess would push it above the truth somewhere and the optimality guarantee would be gone, with no error to flag it. The heuristic is therefore built from geometry alone.

**Manhattan rather than Euclidean, with a guard.** The map turned out to be a perfect axis-aligned grid. On such a graph Manhattan distance (`|dx| + |dy|`) is a *tighter* lower bound than the straight line, because a route made only of axis-aligned moves cannot cut the corner. Tighter and still admissible means fewer vertices examined. It is admissible *only* because the edges are axis-aligned, so the code checks every edge and refuses Manhattan on a map with a diagonal road rather than silently returning wrong answers.

## Data structures

- **Graph:** compressed sparse row — one flat array of edge ids grouped by source junction, plus a row-offset array. Not an adjacency matrix, which would use space proportional to junctions squared (14400 cells) to record 436 roads; road networks are sparse, with two to four neighbours per junction. Not a list of lists either, because Java boxes every integer in a `List<Integer>` and the graph is built once and never modified.
- **Grouping edges by source:** counting sort plus a prefix sum, `O(V + E)`. The sort key is a junction id, already a dense integer in a known range, so bucketing beats the `O(E log E)` comparison-sort floor and never compares two elements at all.
- **Priority queue:** a hand-written indexed binary heap rather than `java.util.PriorityQueue`. Dijkstra repeatedly lowers the tentative distance of a junction already in the queue, and the library queue has no operation for that — removing an item to re-add it is an `O(n)` scan. The usual workaround pushes a duplicate and skips the stale copy later, which grows the queue from one entry per junction to one per road. A side array recording where each element currently sits makes that lookup constant time instead.
- **Hash table:** deliberately absent, for now. The sandbox hands out junction ids that are already dense integers from zero, so an array indexed by id is strictly better — faster and simpler. Adding a hash table so that one appears on a checklist would be a structure I could not justify. The original OpenStreetMap id is kept on every node, so if a map with sparse 64-bit ids arrives, the hash table goes in there and earns its place.

## Language

Java 21. The sandbox is reached over HTTP whatever language this is written in, so there is no integration benefit to matching C++. The graphs here are small enough that the language's speed difference is dwarfed by the network calls. And while learning the algorithms, a Java bug throws at the exact line where the equivalent C++ bug can silently corrupt memory.

Fair concession: C++ would genuinely be faster, and at a much larger city scale that would matter. At this project's scale it does not.

## Research component

The course asks for a comparison between shortest-path algorithms across network sizes. The plan is weighted A*:

```
f = g + ε · h
```

At ε = 1 this is plain A*. Raising ε trades path quality for speed, with a **bounded suboptimality guarantee** — the returned path is never worse than ε times the true best, which makes it an honest trade rather than a hack. Sweeping ε and plotting nodes expanded against path cost, alongside a direct Dijkstra-versus-A* comparison, is the comparison data.

This part is **not built yet**, and the current 120-node grid is one small uniform case that cannot demonstrate the across-sizes requirement on its own. See [docs/limitations.md](docs/limitations.md).

## Where things stand

Both algorithms are built and tested; 60 tests pass with the sandbox stopped. Outstanding: a command-line entry point, publishing routes back to the sandbox so they render in its UI, live end-to-end tests, and the research deliverable. Progress and evidence per step are in [docs/build-log.md](docs/build-log.md).
