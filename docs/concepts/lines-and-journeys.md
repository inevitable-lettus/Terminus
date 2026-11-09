# Lines and journeys

## Lines

Algorithm 1 builds bus lines by greedy demand-covering construction:

1. Pick the stop with the most uncovered demand as a **hub**.
2. Extend a line from the hub, one stop at a time, to the reachable stop that gains the
   most uncovered demand within the **time budget** for a line.
3. Accept the line, mark the demand it covers, and repeat.

Dijkstra's algorithm, on Terminus's own indexed binary heap, is the subroutine: from
each hub it finds the travel time to every reachable stop in one pass.

- **Every line comes back.** The return is routed on its own through the outbound stops
  in reverse, because real maps model dual carriageways as paired one-way streets. A line
  only reaches for stops in its hub's strongly connected component, so it can always
  return.
- **The budget is derived from the map.** The longest line, the most lines and the
  walking radius that counts a stop as covered come from the map's stop spacing and stop
  count; see [Configuration](../guide/configuration.md). The report header states them.
- **It stops for a reason it states:** the coverage target was met, the line cap was
  hit, or nothing reachable is left.
- **Interchanges.** Stops served by more than one line are listed, and the report says
  whether a rider can reach any served stop from any other.

The result is a heuristic: nothing says it is within any factor of the best possible
network.

## Journeys

Algorithm 2 plans a journey between two **junctions**, not two stops. It runs A\* on a
transit graph made of stops, bus segments, transfers and walks.

- **Access and egress.** The walk to a stop and the walk from one are priced as real
  stretches of road at 1.4 m/s, and a passenger walks at most 400 m to or from a stop.
- **Walking is a floor.** A bus answer never costs more than walking straight there, at
  any distance.
- **Waiting.** A transfer waits a flat five minutes. The wait for the first bus is zero
  by default; `--board-wait MIN` adds one you choose.
- **The heuristic is checked, not assumed.** A\*'s guess must never exceed the true
  remaining cost. The planner uses Manhattan distance only on an axis-aligned map and
  otherwise Euclidean distance with the fastest allowed speed.
- **Readable output.** A journey prints as numbered legs with a running clock, and the
  same plan is available as JSON.

## Timetable

Terminus invents a timetable, because a sandbox publishes none: every line runs a bus
every 15 simulated minutes from 06:00 to 21:45 in each direction it can be driven, with
40 seats each. A trip id is `route_<n>-out-HHMM` or `route_<n>-back-HHMM`. Arrival times
use the current road speeds. The position of every bus on the road is computed from
this timetable and the live speeds.
