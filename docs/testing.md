# How the tests work

Two suites, and they answer different questions.

| Suite | Command | Needs the simulator? | Asks |
|---|---|---|---|
| Default | `./gradlew test` | No | Is the algorithm correct? |
| Live | `./gradlew liveTest` | Yes | Does it actually work against the real thing? |

`./gradlew test` runs 207 tests against saved copies of real sandbox responses
(captured from DSTNS `5f75fa5`) and small hand-built graphs, and passes with no
simulator running. `./gradlew liveTest` runs the integration tests against a
running simulator. The live tests are tagged, and the default
suite excludes that tag, so nobody is ever blocked by a container they do not
have running.

If no simulator is reachable, the live tests **skip** rather than fail. A test
that could not run has not found a defect, and reporting it as one trains people
to ignore red builds.

---

## Why there are two suites at all

A test that depends on another service running is a bad test for everyday use. It
fails when that service is down, it fails differently on somebody else's machine,
and it cannot run on a train. So the day-to-day suite reads **fixtures** — real
responses captured once from the simulator and committed to the repository, in
`src/test/resources/fixtures/`. They were captured live rather than hand-written,
so they are the real shape of the data, not a guess at it.

But fixtures freeze. They keep passing even if the simulator changes underneath,
which is exactly the failure this project has already hit once: the first domain
types were written from documentation, were wrong in ten places, and **nothing
errored**, because the JSON reader is configured to ignore fields it does not
recognise. That tolerance protects against a field being *added*. It does not
protect against having modelled the wrong thing.

The live suite is the answer to that. It is the thing that notices when the world
moves.

---

## The default suite — is the algorithm correct?

207 tests across twenty-two classes.

| Class | Tests | What it establishes |
|---|---|---|
| `DomainTypesTest` | 13 | The data records hold what the sandbox sends |
| `TopologyDataTest` | 3 | A saved response deserializes into those records correctly |
| `SandboxClientTest` | 13 | The HTTP layer parses envelopes and surfaces failures; **[2.0]** the operator token travels on the start and nothing else, a refused start is not reported as an outage, `awaitReady` waits through `PREPARING` and fails on `ERROR`, places page past the server's clamp |
| `GraphTest` | 10 | The graph builds, ids are dense, the reverse-twin invariant holds on all 612 edges; a topology that says which edges are one-way is trusted, one that is silent is not read as "all legal" |
| `CostModelTest` | 6 | Costs are right, the clamp works, closed roads are excluded |
| `IndexedMinHeapTest` | 6 | The heap behaves exactly like a known-good priority queue |
| `DijkstraTest` | 9 | Shortest paths agree with a brute force, and rebuild correctly |
| `DemandModelTest` | 9 | Demand is scored from the right signal at the right time |
| `RouteBudgetTest` | 16 | The stopping conditions reject nonsense and hold their defaults; the derived budget scales with the map, never drops below the merge radius, and survives fewer than two stops |
| `RouteNetworkBuilderTest` | 12 | Routes are continuous, budgeted, and reproducible |
| `TransitGraphTest` | 7 | The passenger network is connected and transfers cost something |
| `AStarTest` | 8 | A* finds the same answers as Dijkstra for less work, and its guess is sound |
| `HeuristicSpeedTest` | 2 | A road faster than the configured maximum shrinks the guess instead of breaking A*'s guarantee |
| `JourneyPlannerTest` | 16 | A query between two junctions is answered, and the spliced endpoints keep the guess admissible |
| `RouteExporterTest` | 13 | Routes are drivable traversals, the return leg is real, both files say what the network says, output is byte-stable, and a broken route writes nothing |
| `OptionsTest` | 12 | Every command and option parses, defaults are attach-and-leave-the-clock-alone, bad values are one-line errors |
| `ReportTest` | 18 | Every report says what it stands on, why the builder stopped, and how a journey unfolds, including the empty and unreachable cases |
| `MainTest` | 15 | The whole flow against a stub that records requests: attach touches nothing, `--time` pauses and seeks only, and a start without a token **stops nothing** |
| `DemandChainTest` | 8 | Places, then buildings, then uniform; merged stops sum their places; NaN and negative figures are discarded |
| `StopSetTest` | 8 | Stops within 60 m merge, the radius is inclusive, chains are not collapsed, the result is deterministic |
| `WorldLoaderTest` | 2 | The whole pipeline over the fixtures: an older server needs `/view/edges` and has no places; a 2.0 server is never asked for edges |
| `HelloTest` | 1 | The toolchain compiles and runs a test at all |

### The three kinds of proof used, and why each was chosen

**Hand computation.** Somebody works the answer out on paper and the test asserts
it. Used for the cost model — three edges computed by hand, for example 180 m at
11.11 m/s giving 16202 ms — and for a five-junction graph where the direct route
costs 4 and the two-hop route costs 3, so the shorter-looking path has to lose.
This is the most convincing kind of proof and the least scalable, so it is used
where a handful of cases genuinely pin the behaviour down.

**Oracle comparison.** Run the same thing through a second, independent
implementation and demand they agree. The heap is compared against Java's own
`PriorityQueue` over 2000 randomised sequences of operations. Dijkstra is compared
against a Floyd–Warshall brute force over 200 random graphs, every source and
every destination.

The important part is that the oracle **shares no structure with the code under
test**. Floyd–Warshall works by asking, for every pair of junctions, whether
going via some third junction is cheaper — nothing like Dijkstra's growing
frontier. If the two agree, that means something. Comparing two implementations of
the same idea would just reproduce the same bug twice and feel reassuring.

**Property assertion.** Some things must be true of *every* case, so the test
checks all of them rather than a sample. Every one of the 612 roads reports a
speed no higher than its own limit. Every edge's reverse twin points back at it.
Every reconstructed route, re-added segment by segment, equals the cost the search
reported. And the one that matters most for A*: at every vertex of every one of
the 56 possible journeys, the heuristic's guess is checked against the true
remaining cost that Dijkstra computed exactly, and must never exceed it.

### Two tests that were wrong, and what they taught

Worth keeping, because a test that asserts the wrong thing is more dangerous than
a missing one — it is a green light pointing the wrong way.

**The heap oracle demanded agreement on ties.** Its first version let two elements
end up with the same priority, then required our heap and Java's to pop them in
the same order. They did not, and that was correct behaviour: neither structure
promises anything about how it breaks ties. The test was asserting an accident.
Fixed by keeping every priority distinct. The same idea returns later as "compare
costs, not routes" — two equally good routes can legitimately differ.

**The route builder test forbade shared stops.** The requirement was "no node
covered twice", first implemented as "no two routes may serve the same stop". That
is the opposite of what a bus network needs: a shared stop is exactly where a
passenger changes buses, and forbidding it would have left the transfer edges
nowhere to attach. Rewritten to assert what the rule actually protects — that no
stop's demand is counted twice toward the coverage figure — while shared stops
stay legal.

---

## The live suite — does it work against the real thing?

The integration tests, in three classes. Run them with the simulator up and a run
going:

```bash
./gradlew liveTest
```

**[2.0]** Only the operator can start a run, so `LiveSandbox` has two modes. With
`DSTNS_OPERATOR_TOKEN` set it starts a run of its own at seed `0x2026`, as it
always did. Without it, it **adopts the run already going** — pausing and seeking
it, which are not gated — and skips if there is none. It never stops a run it
could not restart. In adopt mode the map is whatever the operator chose, so
figures that were pinned to the Berlin fixtures (for example "coverage above
80%") may not hold on another city, and a failure there is a finding about the
assumption, not necessarily a bug.

Against a simulator somewhere else, pass its address:

```bash
./gradlew liveTest -Psandbox=http://192.168.1.20:8090
```

### Pinning the simulator to a known state

Every live test starts from the same place, or the numbers would move under it.
Three things are pinned, in `LiveSandbox`:

1. **The seed** (`0x2026`), which fixes the random draws the simulator makes.
2. **The virtual time** (18:00, the demand peak the network is designed for).
3. **A pause before the seek.**

The third one is not obvious and was found the hard way. Asking the simulator to
seek with `play: false` does **not** stop a clock that is already running — the
simulation carries on from wherever it was put, and by the time the state is read
it is several minutes past the time requested. The clock has to be paused
explicitly first. The test `the clock lands exactly where it is told to, once
paused` exists to keep that honest: it asserts the clock reports exactly 64800
seconds, and that two consecutive reads return identical road speeds, which they
only do if the clock is genuinely frozen.

### `LiveContractTest` — is the world still what we assumed?

Six tests, none of which touch the algorithm. They check the ground it stands on.

- **The sandbox answers** and identifies itself.
- **The live map still matches the fixtures** — same node count, same edge count,
  and the same `graph_hash`. If this fails, every fixture-based test in the
  project is measuring a map that no longer exists and the fixtures need
  recapturing. This is the single most valuable test in the suite.
- **Live data satisfies what the graph assumes** — ids dense and unique, every
  road with a positive length and speed, every junction with real coordinates
  (the A* heuristic is meaningless without them), and the reverse-twin invariant
  holding across all 612 edges on live data rather than on a saved copy.
- **The snapshot lines up with the topology** — one state per road, one per
  junction, nothing listed twice.
- **No road exceeds its own speed limit**, checked live.
- **The clock lands where it is told**, as above.

### `LivePipelineTest` — does the whole thing run?

Five tests that go the entire way through with no fixtures anywhere: fetch the
map, build the graph, price it, design the route network, build the passenger
network, answer journeys.

- **End to end.** Routes are produced; coverage is high; every route stays inside
  its time budget; every route is a genuinely continuous traversal, where each
  consecutive pair of junctions really is joined by the road claimed. That last
  check matters because publishing a route back to the simulator is validated the
  same way, and a route with a gap is rejected at the gap.
- **A\* agrees with Dijkstra** on every journey, while expanding fewer vertices.
- **The heuristic stays admissible** against live costs. Admissibility is a
  property of the map's geometry and the speed ceiling, not of the fixture, so it
  has to survive contact with real numbers unchanged.
- **A live road closure is honoured.** This one is the most interesting. It picks
  a road that the shortest path from junction 0 to junction 60 *actually uses* —
  closing a road nothing routes through would pass without proving anything —
  closes it through the control endpoint, re-reads the live state, and asserts
  the destination is still reachable, the new route avoids the closed road, and
  the journey got no cheaper. Then it reopens the road in a `finally` block, so
  the simulator is left as it was found even if the test fails.
- **The same seed and time twice give the same network,** road for road. Without
  this, no measurement taken from the system means anything.

### `LiveJourneyTest` — can a passenger actually use it?

Ten tests covering the door-to-door layer, publishing, and the ways a query is
allowed to fail.

- **A journey between two junctions is answered on live data**, and its legs are
  printed, so a live run leaves evidence a person can read rather than only a
  green tick.
- **Every junction on the live map can reach a stop on foot.** If this fails, some
  part of the map has quietly become unservable.
- **Walking is a floor.** Whenever the destination is within walking distance, the
  answer never costs more than simply walking there. It is often *less*, because
  the wait for the first bus is not modelled — a stated simplification, and this
  is where it shows.
- **The server really does reject a route with a gap.** The route's stops are
  published without the junctions between them, and an error is demanded. This is
  the test that makes carrying the full traversal *necessary* rather than merely
  tidy: if it ever starts passing, `BusRoute` could be simplified.
- **Every line is accepted, in whichever directions it can be driven.** A line
  that uses a one-way street is published outbound only; the test asserts the
  exact count — two per line, minus the one-way lines. At 18:00 on the current
  map both lines use one, so two directed routes are published.
- **A closed road stays out of journeys planned after it closes**, and the road is
  reopened in a `finally` block either way.
- **A query with no answer says so.** A passenger who will not walk, starting at a
  junction with no stop on it, is told there is no journey rather than being given
  a wrong one or an exception.

### What these tests deliberately do not assert

**Exact numbers.** Live results are not required to match the fixture results.
On the current map, at 18:00, they happen to agree on every headline figure:

| | Fixture at 18:00 | Live at 18:00 |
|---|---|---|
| Total demand | 22.41 | 22.41 |
| Routes | 2 | 2 |
| Coverage | 100% | 100% |
| A* saving over Dijkstra | 6.8% | 6.8% |

That agreement should not be relied on. On the synthetic grid the sandbox served
before, they differed — 4 routes at 94.8% coverage from the fixture against 96.9%
live, and a 15.9% A* saving against 16.9% — because the simulator's road speeds
are **rate-limited**: they ease toward their target rather than jumping to it, so
the state at 18:00 depends slightly on the path the clock took to get there.
Pinning the seed and the time makes a run reproducible against itself, which the
reproducibility test confirms, but it does not make a live run identical to a
capture made on a different day through a different sequence of seeks. The
current map is small and mostly uncongested at 18:00, which is likely why the
effect does not show here.

So the live tests assert **properties**, not values: routes are continuous,
budgets hold, coverage is above a floor, A* and Dijkstra agree, the heuristic
never overestimates. Demanding 94.8% to the decimal would produce a test that goes
red for no reason, and a test that cries wolf gets ignored.

---

## A bug the live tests found immediately

The first run failed eight of eleven tests with the same error:

```
POST /api/v1/playback/start returned HTTP 202
```

The client treated anything other than HTTP 200 as a failure. But starting a run
answers **202 Accepted** — the simulator acknowledges the request and prepares the
scenario rather than finishing the work inside the response — and 202 is a success
code. The request had worked every time; the client was throwing the result away.

This is precisely the class of defect fixture tests cannot find, because a saved
response has no status code attached to it. Fixed by accepting any 2xx, with a
comment saying why these are not all 200.

---

## Running everything

```bash
./gradlew test                  # 207 tests, no simulator needed
# start a run in the simulator's own CLI, then:
./gradlew liveTest              # against the live simulator
```

The live suite changes simulator state: it moves the clock, and it closes a road
and reopens it in a `finally` block so that one is restored pass or fail. It no
longer publishes routes — DSTNS 2.0 retired that — so nothing is left behind on
the map.

---

## Recapturing fixtures from DSTNS 2.0

The saved fixtures are from `5f75fa5`. Nothing offline has seen a 2.0 response
yet, and that is the largest gap in this module's evidence. With a run going and
paused at 18:00, from the repository root:

```bash
B=http://127.0.0.1:8090/api/v1
curl -s $B/view/topology            > src/test/resources/fixtures/v2/topology.json
curl -s $B/view/snapshot            > src/test/resources/fixtures/v2/snapshot_1800.json
curl -s "$B/view/places?kind=bus_stop&limit=2000" > src/test/resources/fixtures/v2/places_bus_stop.json
curl -s $B/playback/status          > src/test/resources/fixtures/v2/status.json
```

Note the seed and the city (`/system/map-status`) in the commit message. Then
check, in this order, before writing any new test against them:

1. Does every edge in `topology.json` carry `synthetic_reverse`?
2. Does each stop place's `anchor_node` fall on a node whose `bus_stop` is true?
   If not, the module falls back to 1.0 per stop for those — worth knowing.
3. How many nodes are flagged `bus_stop`, and how many survive merging?
4. Is `data.items[].position` in the shape `{lat, lon, x_m, y_m}`?

Each of these is an assumption the 2.0 code makes from reading the source.
