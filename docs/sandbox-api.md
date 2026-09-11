# The sandbox contract, as this module consumes it

The algorithm module is a client of DSTNS, the simulation sandbox on the other
track. DSTNS owns this contract; this file records only the parts consumed here
and what is assumed about them. Anything checked against a live run rather than
read from documentation is marked as such, because the two have disagreed before.

Base URL: `http://127.0.0.1:8090`. No authentication.

## Endpoints used

| Endpoint | Used for |
|---|---|
| `GET /health` | Is the simulator up, and what lifecycle state is it in |
| `GET /api/v1/view/topology` | The whole road network — every junction and road — in one response |
| `GET /api/v1/view/snapshot` | Live per-road and per-junction state at the current instant of virtual time |
| `GET /api/v1/view/edges?offset&limit` | Richer, paged per-road view; the only source of `demand_vph` |
| `POST /api/v1/playback/start` | Begin a run at a chosen seed and start time |
| `POST /api/v1/playback/pause` | Freeze the clock, so a snapshot is reproducible |
| `POST /api/v1/playback/seek` | Move the clock to a chosen virtual time |
| `PUT /api/v1/control/edges/{id}` | Close a road, or apply a manual speed or capacity override |
| `POST /api/v1/control/transit/route` | Publish a generated bus route back for display (not yet used) |

Every `/view/*` and `/playback/*` response is wrapped in an envelope carrying
`api_version`, `run_id`, `global_seed`, `state_revision`, `config_revision` and a
`clock` block, with the endpoint-specific payload under `data`. `/health` is the
exception and is unwrapped — it predates the run existing.

## The same objects, two different shapes

This caught us once and is worth stating plainly. Roads are serialized two
different ways on two different endpoints, and only one of them carries demand.

- **`/view/topology`** — flat and minimal. Per edge: `id`, `from`, `to`,
  `reverse_twin`, `road_class`, `lanes`, `length_m`, `free_speed_mps`,
  `geometry`. This is what the graph is built from.
- **`/view/edges`** — nested and rich, and paged. Per edge: `source.{osm_way_id,
  synthetic_reverse}`, `road.{class, lanes, length_m, free_speed_mps,
  base_capacity_vph}`, `traffic.{demand_vph, congestion, effective_speed_mps,
  effective_capacity_vph, mean_speed_mps, occupancy, vehicle_count,
  halting_count}`, `weather.{rainfall, flood}`, `control.{closed,
  manual_speed_multiplier, manual_capacity_multiplier}`.
- **`/view/snapshot`** — flat live state. Per edge: `closed`, `congestion`,
  `effective_speed_mps`, `rainfall`, `flood`, `vehicle_count`, `halting_count`.
  Per node: `building_effect`, `rainfall`, `flood`.

At 436 edges a single `limit=1000` request returns everything, but `limit` caps
at 1000 server-side, so the client pages anyway rather than assuming today's map
size.

## What the map actually is

Verified against a live run, not taken from documentation. Earlier notes in this
project cited a different city and roughly five times the size; that figure had
never been checked.

| Property | Value |
|---|---|
| Junctions | 120 |
| Directed road segments | 436 |
| Layout | A 12 × 10 grid, every edge exactly 180 m, all axis-aligned |
| Physical extent | 1980 m × 1620 m |
| Coordinates | Paris latitude/longitude, plus projected metres (`x_m`, `y_m`) |
| Road classes | 146 residential, 114 primary, 102 secondary, 74 tertiary |
| Speed limits | 8.33, 11.11, 13.89, 16.67 m/s |
| Junction degrees | 80 of degree 4, 36 of degree 3, 4 of degree 2 |
| Bus stops | 60 |
| Buildings | 44 — 15 school, 13 store, 9 office, 7 mall |
| Traffic signals | 24 |
| One-way streets | None: all 436 edges have a reverse twin |

Several results in this module depend on the grid. Manhattan distance is
admissible as a heuristic *because* the edges are axis-aligned, and the code
enforces that rather than assuming it.

## Assumptions this module relies on

- **Dense ids.** Junction and edge ids are integers from zero with no gaps. The
  graph builder validates this at construction and refuses a topology that
  violates it rather than building a corrupted structure.
- **Reverse-twin invariant.** For every edge `i`, the reverse twin of its reverse
  twin is `i`. Asserted across all 436 edges in the test suite.
- **Determinism.** The same seed and the same virtual time produce the same edge
  speeds. Fixtures are captured with the clock paused so they are reproducible.
- **Unknown fields are tolerated.** The contract belongs to DSTNS, so a field
  added later must not break deserialization here. This protects against fields
  being *added*; it does not protect against having modelled the wrong thing,
  which is exactly what happened when the domain types were first written from
  documentation alone.

## Behaviour worth knowing

- **A closed road still reports a healthy speed.** Closing edge 42 through the
  control endpoint gives a snapshot with `closed: true` and
  `effective_speed_mps: 16.67` on the same edge. Anything that inferred closure
  from speed would route straight through it. This is why the cost model excludes
  closed edges explicitly.
- **`building_effect` is per role, not spatial.** Every one of the 60 bus stops
  reports exactly 0.4 — the activity level of the bus-stop role itself, not of
  nearby buildings. It cannot be used to rank stops by demand.
- **Seeking backwards resets `state_revision`.** Prefer a fresh start plus a
  forward seek, and compare edge state rather than whole envelopes.
- **`play: false` on a seek does not pause the clock.** Pause explicitly first,
  or the clock keeps running and the state drifts away from the time requested.
- **There is no path-compute endpoint.** DSTNS has its own A* internally
  (`RoutePlanner::route`) but exposes nothing over HTTP — `paths/compute`,
  `routes/compute` and `view/path` all return 404. The planned cross-check
  against it was dropped rather than left open.

## Publishing routes back

`POST /api/v1/control/transit/route` takes `{bus_id, label, nodes[]}` and
validates that **every consecutive pair of nodes is joined by a single directed
edge**. The server does not fill in the path between stops, so a route listing
only its stops is rejected at the first gap. Algorithm 1 therefore emits the full
junction-by-junction traversal, designed in from the start rather than retrofitted.
