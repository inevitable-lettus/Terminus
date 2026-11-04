# FAQ

## Why three programs instead of one?

Each can be built, tested and graded on its own. DSTNS owns the city and its traffic.
Terminus owns the graded algorithm work. The portal owns passengers and tickets.
They talk over HTTP and none links against another.

## Do I have to run DSTNS through the launcher?

Yes. DSTNS only lets its operator start a run, and the launcher is the operator's
tool. Terminus can still be started by hand with `./bus serve` against a run that is
already going, but under the launcher it also draws in the observer and starts at the
right moment. See [Running under the DSTNS launcher](fabric.md).

## Does Terminus change the simulation?

No. It reads roads and speeds and draws in the observer. It never closes a road or
moves the clock, except when you pass `--time` to a `./bus` command or `seek: true`
to `POST /api/reload`.

## Does it work offline?

Yes, with the recorded Berlin district that ships with DSTNS
(`--osm-file data/fixtures/real_network.osm.xml`) or any city DSTNS has downloaded
before. The Terminus tests (`./gradlew test`) need nothing running at all.

## Why does the same seed give a different city in different versions?

DSTNS 2.0 and later pick a real city from 181 by seed. The bundled district is a part
of Berlin. Pin the map with `--osm-file` to get the same one every time. Same seed
and same virtual time then give the same state.

## Why is walking sometimes the answer?

On a small district walking is competitive with a bus, and a bus answer never costs
more than walking straight there. That is the map, not a fault.

## Why are many stops called `stop_<number>`?

DSTNS names only a few stops. Terminus passes names through where it has them, and
uses `stop_` and the junction id for the rest.

## What is a junction id, a stop id and a trip id?

| Id | Looks like | Where it is used |
|---|---|---|
| Junction | `5`, `217` | `./bus journey 5 217`, the local API's `from` and `to` |
| Stop | `stop_30` | The portal's contract: `stop_<junction>` where the junction is the stop's merged representative |
| Trip and bus | `route_2-out-0715` | A timetabled bus: `route_<n>-out-HHMM` or `route_<n>-back-HHMM` |
| Trip in the portal | `run_1ba5...:route_2-out-0715` | The portal prefixes every id with the run it came from |

## How often do buses run?

Every 15 simulated minutes from 06:00 to 21:45, on every line and each direction it
can be driven, with 40 seats each.

## Can I book a journey with a change of bus?

Not yet. The portal shows the journey and says it needs more than one bus.

## Where do I start reading the code?

Read `DESIGN.md` in the Terminus repository for the design and its reasoning, then
[Algorithms](algorithm.md) and the [Decisions log](decisions.md).
