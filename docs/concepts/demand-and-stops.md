# Demand and stops

## Stops

A stop is a junction where a bus calls. Stops come from two places.

1. **Flagged by the sandbox.** The sandbox marks some junctions as bus stops. Stops
   within 60 m of each other are merged into one, with the merged stop standing for
   all of them.
2. **Added by Terminus to cover the map.** Where a junction is farther than the
   **maximum gap** (325 m by default) from every stop, Terminus makes a junction a stop.
   Each pick is the worst gap left, so the stops added are the fewest this rule gives,
   and ties go to the lower junction id, so the same map is always filled the same way.
   Only junctions a line can call at and return from are considered, and the choice is
   made on the road layout alone so the stops do not move when a road closes.

Set the gap with `--max-gap METRES`; `0` adds no stops. Added stops get at least the
smallest flagged demand so that the builder drives to them. The report counts flagged
and added stops separately.

## Demand

Demand says how much pull each stop has. It is relative, not a head count: a stop's
figure is the sum of the pull of the places nearest to it. Coverage is therefore a share
of that pull within reach, not of passengers.

Terminus takes demand from the best source available, and the report header names it:

| Source | Used when | What it is |
|---|---|---|
| `CATCHMENT` | The sandbox publishes demand points and their active intensities | Each active point (a school, an office, a shop) is credited to its nearest stop within its radius |
| `BUILDINGS` | No usable demand points, but junctions carry building data | Building type, impact and radius, with the sandbox's current building effect |
| `UNIFORM` | Neither | Every stop counts the same, so the network is designed purely for reach |

The note under the header says why a better source was not used.

!!! note "Demand is synthetic"
    A sandbox generates demand from building types and time-of-day windows. Nobody
    counted passengers.
