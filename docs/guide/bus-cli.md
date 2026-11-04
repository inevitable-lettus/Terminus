# The ./bus commands

`./bus` is the command-line front end to the algorithm module. Run it from the
Terminus folder.

```bash
./bus network            # the bus lines and how well they cover demand
./bus stops              # the busiest stops, and which lines serve them
./bus journey 5 217      # one passenger journey between two junction ids
./bus export             # write the network to out/ as JSON and GeoJSON
./bus serve              # the local API and the dashboard, on http://127.0.0.1:8080
./bus help               # every option
```

`./bus` builds on first use and after any change to the sources, then runs.

Each command **reads the run that is already going and leaves its clock alone**. Set
a scene in the simulator first, then ask the algorithm about exactly that. DSTNS
only lets its operator start a run, so start one through the
[launcher](../getting-started/quick-start.md) before using these commands.

## Options

| Option | Meaning |
|---|---|
| `--time HH:MM:SS` | Pause the run and read it at that virtual time. Without it the run is read wherever the clock is |
| `--sandbox URL` | Where DSTNS is. Default `http://127.0.0.1:8090` |
| `--minutes N`, `--routes N`, `--walk METRES` | Override the budget: longest line in minutes, most lines, how far a passenger walks to a stop. By default these are derived from the map |
| `--top N` | `stops`: how many to list. Default 15 |
| `--board-wait MIN` | `journey`: minutes to wait for the first bus. Default 0 |
| `--json` | `network`, `stops`, `journey`: print the local API's JSON instead of text |
| `--out DIR` | `export`: where to write. Default `./out` |
| `--bundle` | `export`: also write the dashboard's offline data to `OUT/bundle/` |
| `--port N`, `--host ADDR` | `serve`: where to listen. Default `127.0.0.1:8080` |
| `--dashboard DIR` | `serve`: the built dashboard. Default `dashboard/dist` |
| `--fabric` | `serve`: join DSTNS as a Fabric application. See [Running under the DSTNS launcher](../fabric.md) |
| `--fabric-id ID`, `--fabric-token-file PATH` | `serve --fabric`: the application id (default `terminus`) and where its token is kept (default `.fabric/ID.token`) |
| `--start`, `--seed N`, `--token-file PATH` | Stop the current run and start one of your own. Needs the operator token: set `DSTNS_OPERATOR_TOKEN`, or pass `--token-file` |
| `--debug` | Print the stack trace of an internal error |

!!! warning "`--start` stops the current run"
    DSTNS answers a second start with a conflict, so `--start` stops the run that is
    going first. A token that is present but wrong is refused after the stop. Reading
    an existing run, the default, has no such risk.

## What the reports say

Every report opens with what it stands on: the run and seed, the map, how many stops
remain after merging duplicates, where the demand came from, and the budget in
force. A coverage figure means little without them. It then says why the builder
stopped (target met, line cap hit, or nothing reachable left) and whether riders can
move between the lines.

A journey prints as numbered instructions with a running clock:

```text
Journey  junction 5 -> junction 56  at 18:00:00
  from 52.51446, 13.38480   to 52.51712, 13.39258   straight line 605 m

   1  Walk from junction 5 to stop 5                       0.0 min   at   0.0
   2  Ride R0, stop 5 -> stop 214 (2 stops)                0.8 min   at   0.8
   3  Walk from stop 214 to junction 56                    3.0 min   at   3.9

  Total 3.9 min, 0 transfers. Walking straight there would take about 7.2 min.
  The search looked at 8 of 8 transit vertices.
```

That output is from saved Berlin fixtures. The durations depend on the road speeds at
the virtual time you ask for.

A journey is between two **junctions**, not two stops: the walk at each end is priced
as a real stretch of road. Use `./bus stops` to find junction ids that are far enough
apart. If the two ids are close the answer is "Walk the whole way". A bus answer
never costs more than walking straight there.

## Exporting

`./bus export` writes `routes.json` and `routes.geojson` to `out/`, with each line's
return service. Drag `routes.geojson` onto any GeoJSON viewer such as geojson.io. A
line that cannot be driven back along its own path, because of one-way streets, is
written outbound only, and the command says so.

With `--bundle` (for example `./bus export --bundle --out dashboard/public`) it also
saves the city and eight worked journeys so the [dashboard](dashboard.md) opens with
no simulator.

## Exit statuses

| Status | Meaning |
|---|---|
| 0 | Done |
| 1 | Internal error (`--debug` prints the trace) |
| 2 | Bad usage |
| 3 | The simulator is unreachable or failing |
| 4 | The simulator's data is unreadable |
| 5 | The run is in a state the command cannot work with |
