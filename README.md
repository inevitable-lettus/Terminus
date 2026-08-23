# CSC210 Monsoon 2026 — Group 8

## Public Bus Route Planner

This repository is the official project repository for **Group 8** of **CSC210 — Intro to DSA, Monsoon 2026, Ahmedabad University**.

### Project

**Smart Public Bus Route Planning and Passenger Information System** (Project #3 from the course project list).

**Motivation:** students use GSRTC, AMTS, BRTS and city buses regularly, but route planning and passenger information around them is fragmented. The project designs a bus route network for a simulated city and gives passengers a way to query the best way to get from A to B on it.

**Users:** Passengers, Conductors, Administrators.

**Required DSA focus:** Graph, Heap, Queue, Hash Table, Dijkstra, BFS, A*, Sorting — plus a required research component comparing shortest-path algorithms.

### Team

| Member | Track |
|---|---|
| Varun | Simulation sandbox (traffic/city simulation, environment data) |
| Garv | Algorithm module (route network design, pathfinding) |
| Shambhavee | Research and Development of Bus Ticketing and Management Software  |
| Mahima | Research and Development of Bus Ticketing and Management Software |

Task tracking follows the team's shared Task Sheet + per-task Task Document convention (see `context/`).

### Architecture

The system is split into independent services that talk over HTTP, so each track can be built, tested, and graded independently:

```
 ┌─────────────────────────┐        REST / JSON        ┌──────────────────────────┐
 │   Simulation Sandbox     │ ─────────────────────────▶│   Algorithm Module        │
 │   (C++20, Varun)         │   GET  /nodes /edges       │   (Java 21, Garv)         │
 │                          │   POST /paths/compute      │                          │
 │  SUMO + TraCI simulates   │   POST /simulation/step    │  Own Dijkstra, own A*,    │
 │  real city traffic; a     │   GET  /status /roe        │  own binary heap, own     │
 │  control layer keeps a    │◀───────────────────────── │  adjacency-list graph.    │
 │  RoadGraph in sync and    │   POST /paths (publish)    │                          │
 │  runs a weather/hazard    │                            │  Builds a bus route        │
 │  simulation (DWS) on top. │                            │  network, then answers    │
 │                          │                            │  passenger point-to-point  │
 │  Exposes the graph +      │                            │  queries on it.            │
 │  live conditions as a     │                            │                          │
 │  documented OpenAPI 3.1   │                            │                          │
 │  REST API.                │                            │                          │
 └─────────────────────────┘                            └──────────────────────────┘
```

The two services are deliberately decoupled: the sandbox owns simulation and ground-truth environment data, the algorithm module owns the graded DSA work, and neither needs to link against the other — they only need an HTTP client and a JSON parser. A passenger-facing web app (search, tickets, schedules) is planned on top of the algorithm module per the course's suggested stack (React / Node+Express / Postgres or Mongo), not yet started.

### Algorithms

Two independent algorithms, run at different times and frequencies:

| | Algorithm 1: Route Network Builder | Algorithm 2: Passenger Pathfinder |
|---|---|---|
| Question | Which bus routes should exist, and where? | Best way for one passenger to get from A to B? |
| Runs | Once per generated city / route-network regeneration | On every passenger query |
| Input graph | Raw road network | Transit graph produced by Algorithm 1 (stops + bus segments + transfers) |
| Technique | Greedy demand-covering construction, using Dijkstra as a subroutine (one-to-many) | A* search (single-source-single-target) |

**Why greedy, not an optimizer:** designing a mathematically optimal bus network is the Bus Transit Route Network Design Problem (BTRNDP), NP-hard, conventionally solved with genetic algorithms / simulated annealing / tabu search — none of which are in this course's toolkit. The route builder is explicitly a greedy heuristic with no optimality claim, staying inside Graph / Heap / Dijkstra.

**Cost model** (shared by both algorithms, lives on edges, not nodes):

```
cost(u, v) = (length(u,v) / freeFlowSpeed(u,v)) × trafficMult(u,v) × weatherMult(region)
```

Multiplicative, because congestion slows a road in proportion to its length rather than by a flat amount.

**Research component:** weighted A* (`f = g + ε·h`), sweeping ε from 1.0 upward and plotting nodes-expanded vs. path cost, alongside a direct Dijkstra-vs-A* comparison — satisfies the course's required algorithm-comparison deliverable.

### Progress

- **Explore & Plan:** done. Project chosen, DSA scope confirmed, architecture and cost model settled.
- **Simulation sandbox (Varun):** working prototype. SUMO + TraCI simulation over real OSM data (AU Gate 10 roundabout), full REST API (nodes/edges/paths/simulation control), weather/hazard layer (DWS) implemented, deterministic seeded replay.
- **Algorithm module (Garv):** design finalized (both algorithms, cost model, heuristic, language choice: Java 21). Implementation not yet started — no code written yet.
- **Passenger-facing app:** Research phase.

### Repository Structure

```text
.
├── README.md
├── CLAUDE.md         — where to look before working on this repo
├── context/          — human-readable project context (team-facing)
├── ai-context/        — Claude's working memory for this project
├── docs/
├── src/
└── ...
```

### Working Guidelines

- Keep project work organized and clearly documented.
- Use meaningful commit messages.
- Keep the repository structure clean.
- Document important decisions and changes.
- Do not commit passwords, API keys, tokens, or other secrets.
- Follow the submission requirements and instructions provided during the course.

---

**CSC210 — Monsoon 2026**
**Ahmedabad University**
