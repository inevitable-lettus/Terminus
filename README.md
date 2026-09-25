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
| Simulation sandbox (Varun) | Working. DSTNS 2.0 (`dstns-v2`) picks a real city from 181 by seed and downloads its OpenStreetMap extract; runs are started by the operator through the simulator's own CLI. The older `5f75fa5` (Berlin Mitte, 279 junctions, 612 road segments) is what the saved test fixtures were captured from |
| Algorithm module (Garv) | **Both algorithms built, integrated, and tested; verified live against DSTNS 2.0 on 2026-09-25.** 225 offline tests and 20 live tests green. The research component is outstanding |
| Passenger-facing app | Research phase |

Algorithm module detail, with numbers:

- Graph ingestion, cost model, indexed binary heap, Dijkstra, Algorithm 1, transit graph, Algorithm 2 — all built and under test.
- A passenger can ask for a journey between **any two junctions**, not just bus stops: the walk to a stop and the walk from one are priced as real edges, and the answer comes back as readable legs.
- Live on DSTNS 2.0 (Dar es Salaam, 3000 junctions, seed `0x2026`), at the 18:00 demand peak the route builder produces **6 lines covering 91.3% of demand**, every one with a return service. (On the 279-junction Berlin fixture: 1 line, 90.8%. On the synthetic grid served until `5f75fa5`: 4 routes, 94.8%.)
- A* agrees with Dijkstra on **every journey cost** — 6,642 live journeys on 2.0 — while expanding **9.4% fewer** vertices. On the old grid the saving was 15.9%: Manhattan distance was admissible there, and on real roads the heuristic uses the looser Euclidean bound.
- **One-way streets are respected**, from the topology's own `synthetic_reverse` flags (566 fabricated reverses on the 2.0 capture), or `/view/edges` on an older server.
- **Every line comes back.** The return is routed on its own through the outbound stops in reverse — real cities model dual carriageways as two one-way ways — and a line only reaches for stops in its hub's strongly connected component, so it can always return. Stops in one-way pockets at the district's edge are counted and left unserved.
- **Demand from the simulator's trip generators**: every active school, office, shop and so on, credited to its nearest stop within its radius; buildings and then uniform are the fallbacks for older servers.
- Routes are **written to files**, `routes.json` and `routes.geojson`, with each line's return. Publishing them back to the simulator is gone: DSTNS 2.0 retired that endpoint (HTTP 410).
- Outstanding: the weighted-A* research deliverable.

### Running it

The algorithm module needs JDK 21. The Gradle wrapper is committed, so Gradle itself does not need installing.

```bash
./gradlew test       # 241 tests against saved sandbox responses; needs nothing running
./gradlew liveTest   # integration tests against a running sandbox
./gradlew build      # compile and test
```

The default suite reads saved sandbox responses from `src/test/resources/fixtures/`, so it passes with the simulator stopped. The live suite talks to a real sandbox on `127.0.0.1:8090` (override with `-Psandbox=http://host:port`) and skips rather than fails when none is reachable. See [docs/testing.md](docs/testing.md).

### Using it

Start a run in the simulator first — DSTNS 2.0 only lets its operator do that, through its own CLI or UI. Then one command per question:

```bash
./bus network            # the bus lines and how well they cover demand
./bus stops              # the busiest stops, and which lines serve them
./bus journey 5 217      # one passenger journey between two junction ids
./bus export             # write the network to out/ as JSON and GeoJSON
./bus serve              # the local API and the dashboard, on http://127.0.0.1:8080
./bus help               # every option
```

`./bus` builds on first use and after any change, then runs. Each command reads the run that is already going and **leaves its clock alone**, so you can set a scene in the simulator and ask the algorithm about exactly that. Useful options:

| Option | Meaning |
|---|---|
| `--time HH:MM:SS` | pause the run and read it at that virtual time |
| `--minutes N`, `--routes N`, `--walk METRES` | override the budget, which is otherwise derived from the map |
| `--top N` | `stops`: how many to list |
| `--out DIR` | `export`: where to write |
| `--board-wait MIN` | `journey`: minutes to wait for the first bus (0 by default; the simulator publishes no timetable) |
| `--json` | `network`, `stops`, `journey`: print the serve API's JSON instead of text |
| `--bundle` | `export`: also write the dashboard's offline data to `OUT/bundle/` |
| `--port N`, `--host ADDR` | `serve`: where to listen |
| `--start`, `--seed N` | stop the current run and start our own; needs the operator token (`DSTNS_OPERATOR_TOKEN`, or `--token-file PATH`) |
| `--debug` | print the stack trace of an internal error |

Exit statuses: `0` done, `1` internal error, `2` bad usage, `3` simulator unreachable or failing, `4` simulator data unreadable, `5` the run is in a state the command cannot work with.

Every report opens with what it stands on — the run, the map, how many stops after merging duplicates, where the demand came from and the budget in force — because a coverage figure means little without them. It then says *why* the builder stopped (target met, line cap hit, or nothing reachable left) and whether riders can move between the lines. A journey prints as numbered instructions with a running clock:

```text
Journey  junction 5 -> junction 56  at 18:00:00
  from 52.51446, 13.38480   to 52.51712, 13.39258   straight line 605 m

   1  Walk from junction 5 to stop 5                       0.0 min   at   0.0
   2  Ride R0, stop 5 -> stop 214 (2 stops)                0.8 min   at   0.8
   3  Walk from stop 214 to junction 56                    3.0 min   at   3.9

  Total 3.9 min, 0 transfers. Walking straight there would take about 7.2 min.
  The search looked at 8 of 8 transit vertices.
```

That output is from the saved Berlin fixtures; the durations depend on the road speeds at the virtual time asked for. For presenting the simulator and this together, follow [docs/demo.md](docs/demo.md).

### The dashboard

`dashboard/` draws what the two algorithms produce, in the DSTNS operator UI's style: the lines Algorithm 1
built with buses running round them, stops sized by the demand they draw, a journey from Algorithm 2 with its
rides and walks, and two replays — the greedy builder choosing hub after extension, and A* against Dijkstra
settling stops on the same question. It needs Node 20+.

```bash
./bus serve --time 18:00:00                    # with the simulator running
cd dashboard && npm install && npm run build   # once; ./bus serve then serves it at http://127.0.0.1:8080
cd dashboard && npm run dev                    # or develop it at http://localhost:5174 against ./bus serve
```

With no simulator, `./bus export --bundle --out dashboard/public` saves the city and eight worked journeys,
and the dashboard opens offline from them. The API it reads — which also answers the ticketing portal's
`POST /api/v1/routes/query` — is described in [docs/serve-api.md](docs/serve-api.md).

### Repository structure

```text
.
├── README.md          — this file
├── DESIGN.md          — design of the algorithm module and why it is built this way
├── docs/              — algorithm reference, decision log, build log, testing, plan, limitations
├── context/           — team-facing project context (meeting notes, team decisions)
├── dashboard/         — the visual dashboard (Vite, React, Canvas2D)
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
