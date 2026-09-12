# CSC210 Monsoon 2026 — Group 8

## Public Bus Route Planner

Official project repository for **Group 8**, **CSC210 — Intro to DSA, Monsoon 2026, Ahmedabad University**.

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
| Shambhavee | Research and development of bus ticketing and management software |
| Mahima | Research and development of bus ticketing and management software |

Task tracking follows the team's shared Task Sheet + per-task Task Document convention (see `context/`).

### Architecture

The system is split into independent services that talk over HTTP, so each track can be built, tested and graded independently.

```
 ┌──────────────────────────────┐      HTTP / JSON      ┌──────────────────────────────┐
 │  Simulation sandbox (DSTNS)   │ ─────────────────────▶│  Algorithm module             │
 │  C++20, Varun                 │                       │  Java 21, Garv                │
 │                               │  GET  /view/topology  │                               │
 │  Compiles an OpenStreetMap    │  GET  /view/snapshot  │  Own CSR graph, own indexed   │
 │  extract into a canonical     │  GET  /view/edges     │  binary heap, own Dijkstra,   │
 │  road graph, then advances    │  POST /playback/seek  │  own A*.                      │
 │  its own analytical traffic,  │  PUT  /control/edges  │                               │
 │  weather and signal model on  │                       │  Designs a bus route network, │
 │  a virtual 24-hour clock.     │◀───────────────────── │  then answers passenger       │
 │                               │  POST /control/       │  point-to-point queries on it.│
 │  Deterministic: same seed and │        transit/route  │                               │
 │  virtual time ⇒ same state.   │                       │                               │
 └──────────────────────────────┘                       └──────────────────────────────┘
```

The two services are deliberately decoupled. The sandbox owns simulation and environment data; the algorithm module owns the graded DSA work. Neither links against the other — the algorithm module needs only an HTTP client and a JSON parser. A passenger-facing web app (search, tickets, schedules) is planned on top of the algorithm module per the course's suggested stack, and is in its research phase.

### Algorithms

Two independent algorithms, run at different times and frequencies.

| | Algorithm 1: Route Network Builder | Algorithm 2: Passenger Pathfinder |
|---|---|---|
| Question | Which bus routes should exist, and where? | Best way for one passenger to get from A to B? |
| Runs | Once per generated network | On every passenger query |
| Input graph | Raw road network | Transit graph produced by Algorithm 1 |
| Technique | Greedy demand-covering construction, Dijkstra as a subroutine (one-to-many) | A* search (single-source, single-target) |

**Why greedy, not an optimizer.** Designing a mathematically optimal bus network is the Bus Transit Route Network Design Problem (BTRNDP), which is NP-hard and conventionally attacked with genetic algorithms, simulated annealing or tabu search — none of which are in this course's toolkit. The route builder is explicitly a greedy heuristic with no optimality claim, staying inside Graph / Heap / Dijkstra.

**Cost model** — attached to edges, not nodes, and recomputed from the sandbox's raw live speed rather than trusting a precomputed weight:

```
cost_ms(e) = round( length_m(e) / max(0.1, effective_speed_mps(e)) × 1000 )
```

A closed road is excluded from the search entirely rather than given a large weight. Full reasoning in [DESIGN.md](DESIGN.md) and [docs/algorithm.md](docs/algorithm.md).

**Research component:** weighted A* (`f = g + ε·h`) swept from ε = 1.0 upward, plotted against nodes expanded, alongside a direct Dijkstra-vs-A* comparison across network sizes. Not yet built — see [docs/limitations.md](docs/limitations.md).

### Status

| Track | State |
|---|---|
| Explore & plan | Done |
| Simulation sandbox (Varun) | Working. DSTNS serving a 120-node / 436-edge map over HTTP, deterministic seeded playback |
| Algorithm module (Garv) | **Both algorithms built and tested.** 60 tests green. Integration and the research component outstanding |
| Passenger-facing app | Research phase |

Algorithm module detail, with numbers:

- Graph ingestion, cost model, indexed binary heap, Dijkstra, Algorithm 1, transit graph, Algorithm 2 — all built and under test.
- At the 18:00 demand peak the route builder produces **4 routes covering 94.8% of demand**.
- A* agrees with Dijkstra on **all 3660 journey costs** while expanding **15.7% fewer** vertices.
- Outstanding: command-line integration, publishing routes back to the sandbox, and the research deliverable.

### Running it

The algorithm module needs JDK 21. The Gradle wrapper is committed, so Gradle itself does not need installing.

```bash
./gradlew test       # 60 tests against saved sandbox responses; needs nothing running
./gradlew liveTest   # 11 integration tests against a running sandbox
./gradlew build      # compile and test
```

The default suite reads saved sandbox responses from `src/test/resources/fixtures/`, so it passes with the simulator stopped. The live suite talks to a real sandbox on `127.0.0.1:8090` (override with `-Psandbox=http://host:port`) and skips rather than fails when none is reachable. See [docs/testing.md](docs/testing.md).

### Repository structure

```text
.
├── README.md          — this file
├── DESIGN.md          — design of the algorithm module and why it is built this way
├── docs/              — algorithm reference, decision log, build log, testing, plan, limitations
├── context/           — team-facing project context (meeting notes, team decisions)
├── src/main/java/     — the algorithm module
├── src/test/java/     — tests
├── src/test/resources/fixtures/ — saved sandbox responses used by the tests
└── build.gradle       — Java 21 toolchain, Jackson, JUnit 5
```

### Working guidelines

- Keep project work organized and clearly documented.
- Use meaningful commit messages.
- Keep the repository structure clean.
- Document important decisions and changes.
- Do not commit passwords, API keys, tokens or other secrets.
- Follow the submission requirements and instructions provided during the course.

---

**CSC210 — Monsoon 2026**
**Ahmedabad University**
