# Configuration

Terminus has no configuration file. Everything is a command-line option or an
environment variable, and almost every default is derived from the map.

## The budget

The builder needs three limits. By default each is **derived from the map** and the
report header says so (`(derived)` or `(default)`).

| Option | Meaning | Default |
|---|---|---|
| `--minutes N` | The longest a single line may take, in minutes | 8 minutes |
| `--routes N` | The most lines to build | One per two stops, between 3 and 60 |
| `--walk METRES` | How far a passenger walks to a stop for a stop to count as covered | Half the median stop spacing, but no more than 400 m less the farthest junction's distance to a stop, and clamped to 75 to 400 m |

The coverage target is 100%, so the builder stops when nothing reachable is left, not at
a lower figure or a small cap. The report says why it stopped.

## Covering the whole map

| Option | Meaning | Default |
|---|---|---|
| `--max-gap METRES` | The farthest any junction may be from a stop. Where it is farther, stops are added by farthest-point insertion | 325 |

`--max-gap 0` adds no stops, so only the stops the sandbox flagged are used. Stops are
only added at junctions a line can call at and return from, judged on the road layout so
they do not move when a road closes. The report counts flagged and added stops. See
[Demand and stops](../concepts/demand-and-stops.md).

The **map access** line in the network report is the check: the share of junctions that
have a served stop within the access walk, and the farthest one that does not.

## Journeys

| Option | Meaning | Default |
|---|---|---|
| `--board-wait MIN` | Minutes to wait for the first bus, shown as its own leg | 0 |
| `--time HH:MM:SS` | Pause the run and plan at that time | Wherever the clock is |

Fixed values, not options: a passenger walks at 1.4 m/s, at most 400 m to or from a
stop; a transfer waits a flat five minutes; stops within 60 m merge into one.

## Where it listens and writes

| Option | Meaning | Default |
|---|---|---|
| `--sandbox URL` | The sandbox's address | `http://127.0.0.1:8090` |
| `--host ADDR`, `--port N` | `serve`: where to listen | `127.0.0.1`, `8080` |
| `--dashboard DIR` | `serve`: the built dashboard | `dashboard/dist` |
| `--out DIR` | `export`: where files go | `./out` |

The serve API has no authentication and allows any origin (CORS), so keep it on
`127.0.0.1` unless you mean to expose it.

## Environment variables

| Variable | Used by | Purpose |
|---|---|---|
| `DSTNS_URL` | `serve --fabric` | The DSTNS address, when `--sandbox` is not given. Set by the DSTNS launcher |
| `DSTNS_OPERATOR_TOKEN` | `--start`, `--seed` | DSTNS's operator token, as an alternative to `--token-file` |

Both belong to the [DSTNS integration](../integrations/dstns/index.md).

## Timings that are fixed

| What | Value |
|---|---|
| Re-read of road speeds and closures by `serve` | Every 2 seconds |
| Bus timetable | Every 15 simulated minutes, 06:00 to 21:45, 40 seats |
| Request timeout to the sandbox | 30 seconds (10 seconds to connect) |
