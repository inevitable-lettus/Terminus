# FAQ

## What is Terminus, in one line?

A route builder and journey planner that takes a road map and live road speeds from a
sandbox, designs a bus network on it, and answers passenger questions over a local API.

## Does it only work with DSTNS?

Today, yes in practice. The algorithms need only a road graph and live speeds
([the sandbox interface](sandbox/index.md)), but the code reads DSTNS's types directly, so
another sandbox needs an adapter that does not exist yet. The work is tracked in epic
[#17](https://github.com/inevitable-lettus/Terminus/issues/17). See
[Connect your own sandbox](sandbox/connect-your-own.md).

## Does Terminus change the sandbox?

No. It reads roads and speeds. The only calls that change the sandbox are made when you
ask: `--time` pauses and seeks the clock, `POST /api/reload` with `seek: true` does the
same, and `--start` or `--seed` starts a run on DSTNS.

## Do I need the ticketing portal?

No. The portal is one consumer of the serve API. Nothing in Terminus depends on it.

## Does it work offline?

The Terminus tests (`./gradlew test`) need nothing running at all. A sandbox is needed
for every command, and DSTNS can run a recorded district with no network. See
[DSTNS](integrations/dstns/index.md#offline-map).

## Why does the same seed give a different city in different versions?

That is DSTNS behaviour: newer versions pick a real city from 181 by seed. Pin the map to
get the same one every time (`--osm-file` on DSTNS). Same seed and same simulated time
then give the same state.

## Why is walking sometimes the answer?

On a small district walking is competitive with a bus, and a bus answer never costs more
than walking straight there. That is the map, not a fault.

## Why are many stops called `stop_<number>`?

A sandbox may name few stops. Terminus passes names through where it has them and uses
`stop_` and the junction id for the rest.

## What is a junction id, a stop id and a trip id?

| Id | Looks like | Where it is used |
|---|---|---|
| Junction | `5`, `217` | `./bus journey 5 217`, the local API's `from` and `to` |
| Stop | `stop_30` | The portal contract: `stop_<junction>` where the junction is the stop's merged representative |
| Trip and bus | `route_2-out-0715` | A timetabled bus: `route_<n>-out-HHMM` or `route_<n>-back-HHMM` |
| Trip in the portal | `run_1ba5...:route_2-out-0715` | The portal prefixes every id with the run it came from |

## How often do buses run?

Every 15 simulated minutes from 06:00 to 21:45, on every line and each direction it can
be driven, with 40 seats each. The timetable is Terminus's own: a sandbox publishes none.

## Can a passenger book a journey with a change of bus?

Not yet. The portal shows the journey and says it needs more than one bus.

## Where do I start reading the code?

Read `DESIGN.md` in the Terminus repository for the design and its reasoning, then
[Algorithms](algorithm.md) and the [Decisions log](decisions.md).
