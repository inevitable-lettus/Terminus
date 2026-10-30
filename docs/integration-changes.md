# Suggested changes for integration with the ticketing portal

*Drafted 2026-10-22 · status: items 1 and 2 built (2026-10-30, build protocol waived; read-back in [`readback-integration.md`](readback-integration.md)); 3–5 to follow.*

The group's integration plan (`group-08-public-bus-route-planner/context/integration_plan.md`) has real users booking seats on buses that run inside the DSTNS city. DSTNS v2 has no buses (`POST /api/v1/control/transit/route` returns `410 TRANSIT_API_RETIRED`) and will not be changed, so this module becomes the owner of buses, timetable and live bus positions. Everything below is kept deliberately simple so it is easy to explain.

## Where we are

- `./bus serve` plans the network once at startup and freezes it (`serve/PlannedCity`, swapped whole on `POST /api/reload`).
- Costs come from one snapshot. There is no refresh loop.
- No fleet, trips, timetable or capacity. `PortalContract.Segment.busId` is always `null`; `departureTime` is ignored.
- Route ids (`route_<n>`) are reassigned on every re-plan, so the portal can't store them.
- `POST /api/reload` with `time` seeks the shared DSTNS clock, which would move time for everyone.

## 1. Plan once per run, then only update speeds

**What.** Keep the route network fixed for a DSTNS run. Every 2 seconds, read `/api/v1/view/snapshot`, rebuild `CostModel` and the ride costs in `TransitGraph`, and swap in a new `PlannedCity` that reuses the same `RouteNetwork`. Re-run Algorithm 1 only when `run_id` (from `/api/v1/playback/status`) changes.

**Why.** Route ids stay the same for the whole run, so the portal can store trips and bookings against them. Journeys still use live traffic.

**Where.** `Main.startServing` (start a `ScheduledExecutorService` with one task), `serve/PlannedCity` (a method that keeps the network and re-costs), `client/SandboxClient` (already has `snapshot()` and `playbackStatus()`).

**Also.** `POST /api/reload` should stop seeking by default. Only the operator moves the clock.

## 2. A simple timetable

**What.** For every route, in both directions, a bus departs every 15 simulated minutes from 06:00 to 22:00. Each bus has 40 seats.

- Trip id: `route_2-out-0615` (route, direction, departure as HHMM).
- Bus id: the same as the trip id. One trip = one bus run, which keeps it simple.
- New record: `Trip(String id, int routeId, boolean outbound, int departSimS, int seats)`.

**Why.** The portal needs real trips with departure times to sell seats on. Fixed headway is easy to explain. Headway from demand (`docs/ideas.md` Tier 2) can come later.

**Where.** A new `schedule/Timetable` class that builds the list from `RouteNetwork`.

## 3. Simple bus position and ETA

**What.** For a trip that has departed and not finished:

```
travelSoFar = simNow - departSimS
progress    = travelSoFar / liveRouteTravelTime
```

Walk the route's stops, adding up the live travel time between them, until the total passes `travelSoFar`. The bus is between those two stops; place it proportionally between their lat/lng.

ETA at a stop = `departSimS` + live travel time from the first stop to that stop.

**Why.** It uses the live speeds the module already reads, so a closed or slow road makes buses late with no extra modelling. No vehicle simulation is needed.

**Where.** `schedule/Timetable` (or a small `schedule/BusPositions`). Travel times between stops already exist as the ride-link costs in `TransitGraph`.

## 4. New endpoints (camelCase, like the portal contract)

| Endpoint | Returns |
|---|---|
| `GET /api/v1/live` | `{data: {runId, simTime, buses: [{tripId, routeId, lat, lng, nextStopId}]}}` |
| `GET /api/v1/routes` | `{data: [{routeId, stops: ["stop_12", "stop_47", ...], returnStops: [...]}]}` |
| `GET /api/v1/trips` | `{data: [{tripId, routeId, outbound, departSimS, seats, stopTimes: [{stopId, etaSimS}]}]}` |

All read from the current `PlannedCity` and timetable. No new state on the server beyond what items 1–3 add.

**Where.** `serve/ApiServer` (register the routes), `serve/PortalContract` (build the replies).

## 5. Small additions to the journey contract

`POST /api/v1/routes/query` stays backward compatible:

- **Use `departureTime`.** Read it as simulation time (seconds of day, or `HH:MM:SS`). Default to the current simulation time.
- **Fill `busId` and add `tripId`** on each ride segment: the first trip on that route that reaches the boarding stop at or after the passenger gets there.
- **Add `name` to `GET /api/v1/stops`.** Take it from DSTNS `/api/v1/view/stops`, matched through the stop's anchor node.
- **Ignore unknown fields.** The portal mapper is a plain `new ObjectMapper()`, so any extra field gives a 400. Turn off `FAIL_ON_UNKNOWN_PROPERTIES`.

## 6. Tests

Offline (fixtures, no simulator):
- The timetable has the expected number of trips: (22:00 − 06:00) / 15 min × 2 directions per route.
- With a fixed speed, a bus is halfway along at half its travel time.
- Route ids are the same before and after a speed update.
- A query with `departureTime` picks the next trip after that time.

Live (needs DSTNS):
- A bus moves forward when the clock advances.
- Closing a road on a route makes the ETA at later stops go up.

## Order

Items 1, 2, 4 and 5 unblock the portal's search, sync and fare work. Item 3 is needed only for the live bus view and can come last.

## Not suggested

- No vehicle simulation, no demand-based headways, no optimisation of the timetable.
- No changes to DSTNS. Buses read traffic; they do not add to it.
