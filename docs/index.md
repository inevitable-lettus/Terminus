# Terminus

Terminus designs a bus network on a road map and plans passenger journeys on it. It
is built to take its road map, its live road speeds and its demand from a **sandbox**:
any simulation or data source that can describe a city's roads and how fast traffic
moves on them. The [DSTNS](integrations/dstns/index.md) traffic simulator is the sandbox
supported today, and the ticketing portal is one program that consumes Terminus's
output.

## What Terminus does

| Part | What it does | Run it with |
|---|---|---|
| **Route builder** (Algorithm 1) | Chooses where bus lines should run so that every reachable junction is near a served stop. A greedy, demand-covering heuristic that uses Dijkstra's algorithm as a subroutine | `./bus network`, `./bus export` |
| **Journey planner** (Algorithm 2) | Finds the best way for one passenger to get from one junction to another, using A\* search on the transit graph the builder produced. Walks, rides and transfers are priced as real time | `./bus journey FROM TO` |
| **Serve API** | Plans once, then answers over HTTP from memory: the network, journeys, search traces, a timetable and the positions of buses. Keeps road speeds and closures current while it runs | `./bus serve` |
| **Dashboard** | A browser view of the lines, buses, journeys and how the algorithms worked | `./bus serve`, then open it |

Every command reads a sandbox that is already running and leaves its clock alone. The
reports state what they stand on: the run, the map, the stops, where the demand came
from, and the budget in force. A coverage figure means little without them.

## What it needs from a sandbox

A directed road graph with positions, each road's length and free speed, the current
speed and closed flag of every road, and a run identity. Demand points and named stops
are optional. The full list is in [The sandbox interface](sandbox/index.md).

!!! warning "Today the code talks to DSTNS directly"
    Terminus does not yet have a sandbox-neutral input layer. Its core still uses
    DSTNS's data types, so a different sandbox needs an adapter that does not exist
    yet. The decoupling is planned work: epic
    [#17](https://github.com/inevitable-lettus/Terminus/issues/17) and issues
    [#28](https://github.com/inevitable-lettus/Terminus/issues/28) to
    [#31](https://github.com/inevitable-lettus/Terminus/issues/31). This site describes
    the interface as it is. See [Connect your own sandbox](sandbox/connect-your-own.md).

## Where to start

<div class="grid cards" markdown>

-   **Understand it**

    ---

    The road network, demand, stops, lines, journeys and live refresh.

    [Concepts](concepts/index.md)

-   **What a sandbox provides**

    ---

    Exactly what Terminus reads, and what is required or optional.

    [The sandbox interface](sandbox/index.md)

-   **Install and try it**

    ---

    JDK 21 and `./bus`; tests need no sandbox.

    [Getting started](getting-started/index.md)

-   **Use it with DSTNS**

    ---

    Run the simulator, Terminus and the portal together.

    [DSTNS integration](integrations/dstns/index.md)

</div>

## Integrations

| Integration | What it is |
|---|---|
| [DSTNS](integrations/dstns/index.md) | The sandbox supported today: a deterministic, seeded traffic simulator with a browser observer. Covers the launcher, the Fabric link, the observer drawing, road closures and the offline fixture |
| [Ticketing portal](integrations/portal/index.md) | One consumer of the serve API: passenger search, seat booking, tickets and a conductor page |

## Status

The route builder, the journey planner and the serve API are built and tested against
DSTNS. The research deliverable (a weighted A\* sweep across network sizes) is not
built. See [Known limitations](known-limitations.md).
