# The sandbox contract, as this module consumes it

The algorithm module is a client of DSTNS, the simulation sandbox on the other
track. DSTNS owns this contract; this file records only the parts consumed here
and what is assumed about them. Anything checked against a live run rather than
read from documentation is marked as such, because the two have disagreed before.

Base URL: `http://127.0.0.1:8090`. Reads need no authentication; starting a run
needs the operator token on DSTNS 2.0 (below).

> **Two versions are described here.** Sections marked **[2.0]** come from reading
> the source of `origin/dstns-v2` (`bc6e517`, 2026-09-20) and **have not been
> checked against a live run**. Everything else was checked live against DSTNS
> `5f75fa5`. The client is written to work with both, and to say so where the two
> disagree.

## DSTNS 2.0 — what changed for this module **[2.0]**

| Change | How the module handles it |
|---|---|
| `POST /control/transit/route` returns **410** `TRANSIT_API_RETIRED` ("not part of the supported API") | Publishing is removed. Lines are written to `routes.json` and `routes.geojson` instead (see [decisions.md](decisions.md#dstns-20)). |
| `POST /playback/start` and `/prepare` return **403** `CLI_START_REQUIRED` unless header `X-DSTNS-Operator` matches the server's token (`DSTNS_OPERATOR_TOKEN`, or the file `logs/operator.token`) | The client sends the header on those two requests only. Without a token, `./bus` reads the run already going and refuses `--start` **before** stopping anything. |
| `start` is asynchronous, and the lifecycle passes through `PREPARING` while a city is downloaded (`BOOTING, IDLE, PREPARING, READY, RUNNING, PAUSED, SEEKING, STOPPING, STOPPED, COMPLETED, ERROR, TERMINATING`) | `awaitReady` polls `/health` (case-insensitively) until `READY`, `RUNNING` or `PAUSED`, fails on `ERROR`, and rides out a busy server. *Verified live: about 11 s in `PREPARING` for a cached city.* |
| `seek` and `stop` are synchronous; `stop` is idempotent; a seek on a `STOPPED` run brings it back to `PAUSED` | Nothing waits after a seek. Attach reads a `STOPPED` run with a note and refuses `ERROR`. The server stalls for about 1.5 s during a seek, and a poll can time out then. *Verified live.* |
| The world is a real city chosen by seed (`urban-crfg-v3`: 181 cities, then a district), so the map differs per seed | Nothing about the map is assumed: stop merging, the route budget and the heuristic speed are all derived from it. |
| `/view/topology` edges carry `synthetic_reverse` (and `name`, `tags`) | Read into `Edge.syntheticReverse`, a nullable `Boolean`: `null` means the server did not say (5f75fa5), and then `/view/edges` is asked instead. |
| New `GET /view/places` and `GET /view/stops` (= `places?kind=bus_stop`) | **Not read.** Live, `bus_stop` places are 11 stations against 81 flagged stops — not a demand field over the stops. Demand comes from the snapshot's `demand` list (every generator's live `multiplier`, `radius_m`, `active`) joined to the topology's `features` for position. *Verified live 2026-09-25.* |
| `GET /view/topology` node `bus_stop` is the raw OSM-tagged nodes plus the simulator's own placed stops; **only the places are thinned** | Flagged stops within 60 m are merged into one before anything is designed. |
| `PUT /control/edges/{id}` kept; `POST /control/edges/{id}/override` added | No change needed. |
| Playback `seek`, `pause`, `stop` are not gated | Used as before. |

**Places and stop demand.** A place is `{id, name, kind, position, anchor_node,
demand:{multiplier, baseline, factors, radius_m}}`. For a `bus_stop` the
multiplier is 1.0 when unmodulated and rises with the generators around it (the
server couples stops to nearby schools, offices and shops, and caps every place
at 3.0). It is a relative intensity, not a passenger count. Unverified: whether
the `anchor_node` of a stop place is a junction the topology also flags as a stop.
The module tolerates either — a stop with no place attached counts as 1.0, and if
no stop has one the demand falls back to buildings, then to uniform.

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
| `GET /health` | Lifecycle, polled while a run prepares or stops |
| `GET /api/v1/playback/status` | The run's id, seed and clock, for the header of every report |
| `POST /api/v1/control/transit/route` | Was: publish a bus route back for display. **Retired in 2.0 (410)** and no longer called |

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

At 612 edges a single `limit=1000` request returns everything, but `limit` caps
at 1000 server-side, so the client pages anyway rather than assuming today's map
size.

## What the map actually is

Verified against a live run of DSTNS `5f75fa5`, 2026-09-14. This replaced a
synthetic grid (120 junctions, 436 segments, every edge 180 m and axis-aligned,
no one-way streets) when the sandbox's default `map.source` changed from
`offline-road-fixture` to `osm-xml`.

| Property | Value |
|---|---|
| Source | OpenStreetMap extract, Berlin Mitte (`data/fixtures/downtown_osm.xml`) |
| Junctions | 279 |
| Directed road segments | 612 |
| Of which fabricated one-way reverses | 139 (`source.synthetic_reverse`) |
| Physical extent | 901 m x 947 m; farthest junction pair 951 m |
| Axis-aligned edges | 0 |
| Road classes | residential, secondary, primary, service, tertiary |
| Speed limits | 5.56, 8.33, 11.11, 13.89, 16.67 m/s |
| Edge length | 0.6 m to 193.1 m, median 15.5 m |
| Bus stops | 8; closest pair 176 m apart |
| Buildings | 7 — 3 office, 2 store, 1 mall, 1 school |

Manhattan distance is not admissible on this map, and the planner uses Euclidean.

## One-way streets are invisible on `/view/topology`

The most important thing on this page. `/view/topology` gives **every** edge a
`reverse_twin`, including the reverse direction of a one-way street, which does
not exist. Nothing on that endpoint distinguishes the fabricated reverse from a
real road. Only `/view/edges` marks it, with `source.synthetic_reverse: true`.

Three independent signals agree on exactly the same 139 edges:

- `source.synthetic_reverse` is `true`;
- `traffic.demand_vph` is `0` — they are the only edges with no demand;
- `POST /control/transit/route` rejects them as "not directly adjacent in the
  road network". Swept across all 612 edges as two-node routes: exactly 139
  refused, the same set.

A graph built from `/view/topology` alone drives the wrong way down all of them.
This module excludes them from the cost model (`EdgeDetail.syntheticReverseIds`).

## Assumptions this module relies on

- **Dense ids.** Junction and edge ids are integers from zero with no gaps. The
  graph builder validates this at construction and refuses a topology that
  violates it rather than building a corrupted structure.
- **Reverse-twin invariant.** For every edge `i`, the reverse twin of its reverse
  twin is `i`. Asserted across every edge of both fixture maps in the test suite.
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
- **Starting a run does not remove published bus routes.** `start` clears
  weather, surges, signal overrides and news, but not the list of buses sent to
  `POST /control/transit/route`, so they carry over into the next run and stay on
  the map. `POST /api/v1/playback/reset` is what clears them. Read from
  `src/engine.cpp` at `5f75fa5` and confirmed live: after a stop and start, four
  previously published routes were still listed; after a reset, none.
- **There is no path-compute endpoint.** DSTNS has its own A* internally
  (`RoutePlanner::route`) but exposes nothing over HTTP — `paths/compute`,
  `routes/compute` and `view/path` all return 404. The planned cross-check
  against it was dropped rather than left open.

## Publishing routes back — retired in DSTNS 2.0

Historical: this module used to publish routes with the endpoint below, and it
still works on DSTNS `5f75fa5`. On 2.0 it answers 410, and the module no longer
calls it. The continuity rule it enforced is still checked locally before any
file is written, because it is simply a true statement about what a drivable
route is.

`POST /api/v1/control/transit/route` takes `{bus_id, label, nodes[]}` and
validates that **every consecutive pair of nodes is joined by a single directed
edge**. The server does not fill in the path between stops, so a route listing
only its stops is rejected at the first gap. Algorithm 1 therefore emits the full
junction-by-junction traversal, designed in from the start rather than retrofitted.

**`bus_id` and `label` are strings; `nodes` is an array of node ids.** Sending
`bus_id` as a number is answered with `400 INVALID_FIELD_TYPE`. The field
*names* were recorded when this API was first surveyed but their *types* were
not, and the mistake survived until the first live publish — a fixture cannot
catch it, because a saved response carries no record of what was sent.

The server keys buses by `bus_id`, so the two directions of one line need
distinct ids or the second overwrites the first. This module publishes `R0`
outbound and `R0-R` back.

A discontinuous route is answered `400` with `ok: false, valid: false` and a
message naming the two nodes that are not adjacent. The endpoint's contract
also allows `valid: false` alongside a `200`, so both are treated as rejection.
