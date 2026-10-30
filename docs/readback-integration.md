# Read-back: integration changes (2026-10-30, build protocol waived)

The build protocol was **waived** for this work, as on 2026-09-25. This file is
the read-back it owes: every change, why, what was rejected, and the questions a
TA is likely to ask. Read it with the PR diff open.

Plan: [`integration-changes.md`](integration-changes.md). Built in three PRs, in
plan order: **1 + 2** (this part), then 4 + 5, then 3.

## Part 1: plan once per run, refresh speeds, a timetable (items 1 and 2)

### What changed

| Change | Where | Defend it as |
|---|---|---|
| Speeds re-read every 2 s | `serve/LiveRefresher` (new), started in `Main.startServing` | "One background task. Each tick asks DSTNS for `run_id`; same run → re-read the snapshot and re-price; new run → plan from scratch." |
| Re-price without re-planning | `PlannedCity.withLiveCosts` | "Keeps the `RouteNetwork` and the timetable; rebuilds only `CostModel`, `TransitGraph` and the planner. Algorithm 1 does not run, so route ids stay fixed." |
| A closed road on a line | `CostModel.busMillis` | "A bus cannot re-route off its line, so a closed road is priced as gridlock (0.1 m/s), not refused. The bus is late, not gone." |
| Reload no longer moves the clock | `ApiServer` `/api/reload` | "The DSTNS clock is shared. It moves only if the caller sends `seek: true`; the dashboard (the operator's tool) does." |
| Swapping the city | `ApiServer.city()` / `replace()` | "One `volatile` field. A request reads it once and finishes on that city." |
| Timetable | `schedule/Timetable`, `schedule/Trip` (new) | "Every line, each direction it runs, a bus every 15 sim-minutes from 06:00; last bus 21:45; 40 seats. Trip id `route_2-out-0615`; the bus id is the trip id." |

### Why

- **Fixed ids.** The portal stores trips and bookings against route ids. Re-planning
  every tick renumbers lines (`route_<n>` is build order), so bookings would point
  at the wrong bus. One plan per run keeps them fixed.
- **Live journeys anyway.** Ride costs follow the latest snapshot, so a jam or a
  closure changes journey times within 2 seconds.

### Rejected alternatives

| Rejected | Why not |
|---|---|
| Re-run Algorithm 1 every tick | Route ids change; bookings break. Also wasted work: ~15 ms each time for an answer the portal cannot use. |
| Stable ids by matching new lines to old ones | Matching two networks is its own problem (which line is "the same"?). Fixing the network per run is simpler and exact. |
| Refuse a journey over a closed road on a line | The bus still exists and still runs its line; refusing would hide it. Gridlock pricing shows it as very late. |
| Re-read the whole topology each tick | The map does not change within a run; only speeds do. Reusing the topology saves the biggest read. |
| `Thread` + `sleep` loop | A `ScheduledExecutorService` with one task is the standard tool, and `scheduleWithFixedDelay` never runs two ticks at once. |
| Return direction named `ret` | The portal (PR #43) already uses `back` (`route_1-back-0615`), so we match it. |

### Details worth knowing

- **A failing tick** (simulator down) keeps the last good city and prints one line,
  not one every 2 seconds. The `try/catch` is required: an exception escaping a
  scheduled task silently cancels every later run.
- **The thread is a daemon**, so it never keeps the program alive after the server
  stops.
- **A line with no return** gets outbound trips only. In the captured city all 6
  lines run both ways, so 6 × 2 × 64 = 768 trips.
- **64 departures per direction:** (22:00 − 06:00) / 15 min = 64, i.e. 06:00 … 21:45.
- **A forced reload with `seek`** re-plans on the same run, so ids can change; only
  the operator does this, and the portal re-syncs on a new run anyway.

### Tests (offline, fixtures)

- `TimetableTest`: 64 per direction, 768 for the capture, first and last ids, ids unique.
- `LiveCostsTest`: route and trip ids are the same object after a speed update,
  costs do change (18:00 vs 08:30); a closed road on a line adds exactly the
  gridlock time, no exception.
- `LiveRefresherTest`: same run keeps the network; new run re-plans; a failed tick
  keeps the city and prints once, then recovers.
- `ApiServerTest`: reload passes the time on only with `seek: true`.

### Likely TA questions

- **Why every 2 seconds?** The portal polls `/api/v1/live` every 2 s; DSTNS runs
  about 144× real time, so 2 s real is about 5 simulated minutes. Faster buys
  nothing the portal can show.
- **What if a request arrives during a swap?** It read the old city at its start
  and finishes on it. The field is `volatile`, so the next request sees the new one.
- **Is this thread-safe?** Only the refresher (and a reload) writes the field;
  `PlannedCity` is immutable, so readers never see a half-built city.
- **Why price a closed road at 0.1 m/s?** It is the same floor the cost model already
  uses for a jammed road, and DSTNS uses in its own planner. Same rule, one place.
- **Why not demand-based headways?** Fixed headway is easy to explain and check.
  Demand-based is future work (`ideas.md` Tier 2).

### New concepts (to add to `csc210_algorithm_concepts_used.md`)

- **Periodic background task** — `ScheduledExecutorService.scheduleWithFixedDelay`;
  fixed *delay* (not rate) so ticks never overlap.
- **Immutable snapshot + atomic reference swap** — build a whole new object, then
  replace one `volatile` reference; readers need no locks.
- **Separating structure from weights** — the network (structure) is fixed per run;
  the costs (weights) are refreshed. Same graph, new edge weights.
- **Error containment in a loop** — catch at the tick boundary, report on change of
  state, not on every failure.
- **Fixed-headway timetable** — departures at a constant interval; count =
  service span / headway.

## Part 2: the portal's endpoints and the journey contract (items 4 and 5)

### What changed

| Change | Where | Defend it as |
|---|---|---|
| `GET /api/v1/live` | `PortalContract.live` | "The run id and the simulated clock in seconds; the portal polls it every 2 s and re-copies when `runId` changes. `buses` is filled in part 3." |
| `GET /api/v1/routes` | `PortalContract.routes` | "Each line's stops in calling order, outbound and return." |
| `GET /api/v1/trips` | `PortalContract.trips` | "The timetable, with an ETA at every stop: departure + live travel time from the first stop." |
| ETA arithmetic | `schedule/LiveTimes` (new) | "Time to junction *k* of a line = sum of the first *k* road costs at the current speeds." |
| `departureTime` read | `schedule/SimTime` (new), `PortalContract.answer` | "Seconds of the day or HH:MM:SS; missing means the simulator's clock." |
| `tripId` / `busId` on rides | `Timetable.firstReaching`, `PortalContract.answer` | "Walk the journey's legs with a clock. At a ride, take the first trip whose ETA at the boarding stop is at or after the clock; the clock jumps to that trip's ETA at the alighting stop." |
| Stop `name` | `SandboxClient.stopNames`, `PlannedCity.withStopNames`, `PortalContract.stops` | "DSTNS names some stops as places; a place's `anchor_node` is a junction, mapped to our merged stop." |
| Unknown fields ignored | `ApiServer` portal mapper | "`FAIL_ON_UNKNOWN_PROPERTIES` off, so an extra field from the portal is not a 400." |

### Rejected alternatives

| Rejected | Why not |
|---|---|
| `tripId` from the planner's own times only (departure + leg ms) | Ignores that a bus leaves only every 15 minutes. Walking the legs with a clock makes the second ride follow the first bus's real arrival. |
| Work out the trip with a formula (`ceil((t - offset - 06:00) / 15 min)`) | Correct but harder to read aloud. A loop over the line's trips in departure order says the same thing plainly, and 64 trips per direction is nothing. |
| Find the ride's direction from its stop list | A line can call at the same stop more than once. Matching the drawn junction path against each direction's junction list is exact. |
| Refuse the old ISO `departureTime` (`2026-09-10T09:00:00Z`) | The contract must stay backward compatible; a real-world date says nothing about the simulated day, so it means "now", as before. Other garbage is a 400. |
| Read stop names every 2 s | Names do not change within a run; read once per plan. A failed read is a note, and the portal falls back to the id. |

### Details worth knowing

- `estimatedDurationSeconds` and segment `cost` are still the planner's times
  (no waiting for the scheduled bus). `tripId` adds which bus, not a new total.
- Only some DSTNS stops are named places (11 of 81 in the captured city), so most
  `name`s are `null`. The `/view/stops` reply shape was not captured live; the
  parser takes either a list or `{items: [...]}`.
- ETAs and `stopTimes` follow the current speeds, so they move during a run; ids
  do not.

### Tests (offline)

- `PortalScheduleTest`: live (run id, 64800 s), routes, trips (768, ETA at the first
  stop = departure, ETAs never go down); 07:07 → the 07:15 bus, same with
  `25620`, exactly 07:15 → 07:15, after 21:45 → `null`; no `departureTime` → 18:00
  bus; an unknown field is fine; a bad time is a 400; names through the anchor
  junction; the names parser takes both shapes.
- `SimTimeTest`: both forms, and refusals.
- `ApiServerTest`: rides carry `tripId` = `busId`; walks carry none.

### Likely TA questions

- **How do you pick the bus?** Loop over that line direction's trips in departure
  order; the first whose arrival at the boarding stop (departure + live travel time
  to it) is at or after the passenger. Linear scan, at most 64 trips.
- **Why is the bus id the same as the trip id?** One trip is one bus run. A real
  fleet would reuse buses across trips (vehicle scheduling, a different problem).
  Not needed for selling seats.
- **What if the passenger misses the last bus?** `tripId` is `null`; the journey is
  still returned so the portal can say so.
- **Why ignore unknown fields?** Postel's rule: be strict in what you send, liberal
  in what you accept. The portal's fields can grow without breaking us.

### New concepts

- **Prefix sums along a path** — ETA at stop *k* = departure + sum of the first *k*
  edge costs.
- **Simulation of a passenger's clock** — step through legs, advancing a clock,
  snapping to the next scheduled departure at each boarding.
- **Linear search for the first match** in a list sorted by time.
- **Backward-compatible API evolution** — add optional fields, ignore unknown ones,
  keep accepting the old input form.
- **Tolerant parsing** — accept either reply shape; treat a missing optional
  source as "no data", not an error.
