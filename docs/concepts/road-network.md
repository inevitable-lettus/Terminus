# The road network

Terminus sees a city as a directed graph.

| Term | Meaning |
|---|---|
| **Junction** | A node of the graph, with a position. Junction ids are dense: `0` to `count - 1` |
| **Road** (edge) | A directed link from one junction to another, with a length in metres and a free-flow speed |
| **Reverse twin** | The edge that runs the opposite way along the same street, or none (`-1`) |
| **One-way street** | A street that has no real reverse. A sandbox that publishes a reverse edge for every street must say which reverses are fabricated |
| **Closed road** | A road that, right now, cannot be driven |

Positions come in two forms, both needed. Metres on a flat plane (`x_m`, `y_m`) drive
every computed distance: the search heuristic, walking and interchange distances, stop
merging and demand radii. Latitude and longitude are only used for output: GeoJSON,
stop positions given to other programs, bus positions and text reports.

## What a road costs

The cost of driving a road is its travel time, computed from the sandbox's raw live
speed rather than from any precomputed weight:

```text
cost_ms(e) = round( length_m(e) / max(0.1, effective_speed_mps(e)) × 1000 )
```

The speed is clamped to at least 0.1 m/s so that a jammed road is very slow, not
infinite, and so that costs stay positive.

- **Closed roads are excluded** from the search, not given a large weight.
- **One-way streets are respected.** A fabricated reverse edge is excluded, so no
  search drives the wrong way down a one-way street.
- **Costs live on edges**, not junctions.

## Why one-way matters

Many sandboxes publish the road network with a reverse direction for every street,
including one-way ones. If nothing says which reverses are fake, a search drives the
wrong way down them. Terminus reads the flag per edge when the topology carries it,
and otherwise asks a separate endpoint that lists it. A sandbox that publishes only
real reverses needs no flag.

## Which part of the map a bus can serve

Real maps model dual carriageways as two one-way streets, and a district is cut out of
a larger city, so one-way streets run off its edge. A bus can only serve a stop it can
reach and leave again. Terminus finds the strongly connected component of the drivable
roads (the largest set of junctions that can all reach each other) and plans lines only
inside it. Stops in one-way pockets outside it are counted in the report and left
unserved.
