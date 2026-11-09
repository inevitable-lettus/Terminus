# Connect your own sandbox

!!! warning "No adapter layer exists"
    The sections below describe what is possible today, which means working with the
    Terminus code. A supported way to plug in another sandbox is planned, not built:
    [#29](https://github.com/inevitable-lettus/Terminus/issues/29) (input interfaces),
    [#31](https://github.com/inevitable-lettus/Terminus/issues/31) (DSTNS as an optional
    adapter), [#33](https://github.com/inevitable-lettus/Terminus/issues/33) (a neutral
    world file) and [#38](https://github.com/inevitable-lettus/Terminus/issues/38) (a CLI
    that works against any source), under epic
    [#17](https://github.com/inevitable-lettus/Terminus/issues/17).

Read [The sandbox interface](index.md) first. It lists what any sandbox must supply.

## Option 1: serve DSTNS's API shapes

`./bus` reads a server over HTTP at the address you give with `--sandbox URL`. A program
that answers the endpoints in [What Terminus reads today](index.md#what-terminus-reads-today)
with the same JSON shapes works with `./bus` unchanged:

```bash
./bus network --sandbox http://127.0.0.1:9000
```

You need `GET /health`, `GET /api/v1/view/topology`, `GET /api/v1/view/snapshot` and
`GET /api/v1/playback/status`. `view/edges` is needed only if your topology does not
carry `synthetic_reverse`, and `view/stops` is optional. Pause and seek are called only
when you pass `--time`, so a sandbox with no clock control can leave them out and avoid
that flag.

This is an emulation of DSTNS's API, not a neutral interface. The shapes and names are
DSTNS's. It has not been tried against any server other than DSTNS.

## Option 2: build the records and call the loader

The planner builds a `World` from a topology and a snapshot that are already in hand,
and the tests use exactly this entry point with saved responses:

```java
World world = WorldLoader.assemble(topology, snapshot, edgeDetails, maxGapM);
```

`topology` is a `TopologyData`, `snapshot` a `SnapshotData`, and `edgeDetails` supplies
the one-way flags if the topology does not. Producing those records from your own data
is the adapter. This needs the Terminus source: the project is not published as a
library yet ([#37](https://github.com/inevitable-lettus/Terminus/issues/37)), and these
types are DSTNS's.

## Not available yet

| Missing | Tracked in |
|---|---|
| A neutral model and input interfaces in the core | #28, #29 |
| Generic demand points and candidate stops | #30 |
| DSTNS as an optional adapter module | #31 |
| A neutral world file format and a JSON/CSV loader | #33 |
| An OpenStreetMap road-extract loader | #34 |
| GTFS import for real stops and timetables | #35 |
| A stable library API | #37 |
| A CLI and dashboard that work against any source | #38, #40 |
| Tests on non-DSTNS networks | #41 |

## A checklist for a sandbox author

- Number junctions and roads densely from 0, and list each once in the snapshot.
- Give every junction `x_m`, `y_m`, `lat` and `lon`, consistent with each other.
- Give every road `length_m` and `free_speed_mps`, finite and not negative.
- State each road's reverse twin, and mark fabricated reverses of one-way streets.
- Report each road's `closed` flag and `effective_speed_mps` on every snapshot.
- Publish a run id that changes when the world changes, and the simulated time.
- Flag bus stops if you know them. Otherwise Terminus adds them.
- If you publish demand, give each point an id, a position, a radius and an intensity.
