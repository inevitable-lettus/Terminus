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

Every answer comes from one immutable planned city. `POST /api/reload` plans a
new one and swaps it in whole, so a request never sees half of each. Responses
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
| `POST /api/reload` `{"time":"HH:MM:SS"}` | re-reads the simulator, at that time if given, and re-plans |

A ride leg's `path` is drawn through the stops the search actually rode through,
along the cheapest road slice for each hop, so its road costs sum to exactly the
leg's `ms`. A test holds that on every ride in a sample of the 2.0 city.

## The ticketing portal's contract (camelCase, frozen by the portal team)

`POST /api/v1/routes/query`

```json
{"sourceStopId": "stop_1645", "destStopId": "stop_201", "departureTime": "2026-09-10T09:00:00Z"}
```

```json
{"data": {"path": ["stop_1645", "stop_201"],
          "segments": [{"fromStopId": "stop_1645", "toStopId": "stop_201", "routeId": "route_2",
                        "busId": null, "cost": 6.97, "distanceMeters": 1120.45}],
          "totalCost": 6.97, "totalDistanceMeters": 1120.45, "estimatedDurationSeconds": 418}}
```

- **Stop ids** are `stop_<junction>`, the junction being the stop's merged
  representative. `GET /api/v1/stops` lists them with position, whether a line
  serves them and which (`route_<n>`), for the portal to map onto its own `stop`
  table. Any junction the simulator flagged as a stop is accepted and resolved.
- **`cost` is in minutes.** The contract names no unit; minutes are what every
  other figure here is printed in.
- **`busId` is `null`.** There is no fleet model to name a bus from.
- **`departureTime` is accepted and ignored.** The network is planned for one
  moment of the simulated day.
- A segment with `routeId` `null` is a walk between two stops to change line.

Errors use the simulator's own envelope, `{"ok": false, "error": {"code", "message"}}`:

| Status | Code | When |
|---|---|---|
| 400 | `BAD_REQUEST` | a malformed id or body |
| 404 | `STOP_NOT_FOUND` | the id names no stop on this map |
| 422 | `NO_ROUTE` | both stops exist, and nothing joins them |
| 409 | `RUN_STATE` | a reload on a server holding a saved bundle |
| 502 | `SIMULATOR_UNAVAILABLE` | a reload could not read the simulator |

## The offline bundle

`./bus export --bundle` writes `out/dashboard/{world,network,build,journeys}.json`,
the same JSON the server sends (a test compares them), plus eight worked
journeys with both searches' traces. Copied into `dashboard/public/bundle/`,
it lets the dashboard run with no simulator and no server.
