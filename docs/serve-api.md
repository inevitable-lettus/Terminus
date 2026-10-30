# `./bus serve` — the local API

`./bus serve` reads the running simulator once, plans the city, and answers over
HTTP from memory: the dashboard's data source, and the ticketing portal's
integration point. It is built on the JDK's own `HttpServer`, so there is nothing
extra to install.

```bash
./bus serve                          # attach to the run, http://127.0.0.1:8080
./bus serve --time 18:00:00          # pause and read it at 18:00 first
./bus serve --port 9000 --host 0.0.0.0
```

Every answer comes from one immutable planned city, swapped in whole, so a request
never sees half of each. Every 2 seconds the server re-reads the simulator's speeds
and re-prices the same lines (route and trip ids do not change); it re-runs
Algorithm 1 only when the simulator's `run_id` changes. `POST /api/reload` forces a
re-plan. Responses
are gzipped when the client accepts it; CORS allows any origin, so the dashboard
and the portal's dev servers can call it directly.

## Dashboard API (snake_case, like `routes.json`)

| Method + path | Answers |
|---|---|
| `GET /api/health` | `{ok, mode, sandbox, run_id, virtual_time}` |
| `GET /api/world` | `provenance` (run, seed, city, demand source, budget, stops in one-way pockets), `bounds` in metres, `junctions[{id, x, y, lat, lon, main}]`, `roads[{id, from, to, road_class, one_way, closed, cost_ms, congestion}]` (fabricated reverses left out), `stops[{node, demand, main, lines}]` |
| `GET /api/network` | `lines[{id, label, outbound{nodes, stops, travel_ms}, back{…} or null, demand, share}]`, `coverage`, `stop_reason`, `interchanges`, `connected` |
| `GET /api/journey?from=&to=&board_wait=` | `legs[{kind, line, from_node, to_node, stops_ridden, ms, path, text}]`, `total_ms`, `transfers`, `walk_ms`, and `a_star_expanded` / `dijkstra_expanded` for the same question |
| `GET /api/trace/search?from=&to=&algo=astar\|dijkstra` | the junctions the search settled, in order, with their costs (capped at 20,000) |
| `GET /api/trace/build` | the greedy builder's decisions: `hub`, `extend` (with the path driven and the demand gained), `accept`, `exclude` |
| `POST /api/reload` `{"time":"HH:MM:SS","seek":true}` | re-reads the simulator and re-plans; moves the shared clock to `time` only when `seek` is `true` |

A ride leg's `path` is drawn through the stops the search actually rode through,
along the cheapest road slice for each hop, so its road costs sum to exactly the
leg's `ms`. A test holds that on every ride in a sample of the 2.0 city.

## The ticketing portal's contract (camelCase, frozen by the portal team)

`POST /api/v1/routes/query`

```json
{"sourceStopId": "stop_1645", "destStopId": "stop_201", "departureTime": "07:07:00"}
```

```json
{"data": {"path": ["stop_1645", "stop_201"],
          "segments": [{"fromStopId": "stop_1645", "toStopId": "stop_201", "routeId": "route_2",
                        "busId": "route_2-out-0715", "tripId": "route_2-out-0715",
                        "cost": 6.97, "distanceMeters": 1120.45}],
          "totalCost": 6.97, "totalDistanceMeters": 1120.45, "estimatedDurationSeconds": 418}}
```

- **Stop ids** are `stop_<junction>`, the junction being the stop's merged
  representative. `GET /api/v1/stops` lists them with position, `name` (from DSTNS
  `/view/stops`, matched through the place's anchor junction; `null` for the many
  stops DSTNS does not name), whether a line serves them and which (`route_<n>`),
  for the portal to map onto its own `stop` table. Any junction the simulator
  flagged as a stop is accepted and resolved.
- **`cost` is in minutes.** The contract names no unit; minutes are what every
  other figure here is printed in.
- **`departureTime`** is simulated time: seconds of the day (`25620`) or
  `HH:MM:SS`. Left out, it is the simulator's current time. A real-world
  date-time (the contract's first form, `2026-09-10T09:00:00Z`) also means "now".
  Anything else is a 400.
- **`tripId` and `busId`** (the same id) name the first timetabled trip on that
  line and direction that reaches the boarding stop at or after the passenger
  does. A later ride starts from when the earlier trip reaches its alighting stop.
  `null` after the last bus (21:45) or on a walk.
- **Unknown fields are ignored**, so the portal can send more than is read.
- A segment with `routeId` `null` is a walk between two stops to change line.

### What the portal copies and polls

| Endpoint | Returns |
|---|---|
| `GET /api/v1/live` | `{data: {runId, simTime, buses: [{tripId, routeId, lat, lng, nextStopId}]}}`; `simTime` in seconds of the day; `buses` are the trips on the road now, placed from the timetable and live speeds |
| `GET /api/v1/routes` | `{data: [{routeId, stops: ["stop_12", ...], returnStops: [...]}]}`; `returnStops` is empty for a line with no return |
| `GET /api/v1/trips` | `{data: [{tripId, routeId, outbound, departSimS, seats, stopTimes: [{stopId, etaSimS}]}]}`; ETAs use the current speeds |

Route and trip ids stay the same for a whole run (the network is planned once per
`run_id`). The timetable: every line, each direction it runs, a bus every 15
simulated minutes from 06:00 to 21:45, 40 seats; trip id = bus id =
`route_<n>-out-HHMM` or `route_<n>-back-HHMM`.

Errors use the simulator's own envelope, `{"ok": false, "error": {"code", "message"}}`:

| Status | Code | When |
|---|---|---|
| 400 | `BAD_REQUEST` | a malformed id or body |
| 404 | `STOP_NOT_FOUND` | the id names no stop on this map |
| 422 | `NO_ROUTE` | both stops exist, and nothing joins them |
| 409 | `RUN_STATE` | a reload on a server holding a saved bundle |
| 502 | `SIMULATOR_UNAVAILABLE` | a reload could not read the simulator |

## The offline bundle

`./bus export --bundle` writes `out/bundle/{world,network,build,journeys}.json`,
the same JSON the server sends (a test compares them), plus eight worked
journeys with both searches' traces. With `--out dashboard/public` it lands in
`dashboard/public/bundle/`, where the dashboard finds it with no simulator and no server.
