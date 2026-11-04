# Troubleshooting

Start with what the part itself says. Terminus prints to the terminal it runs in, or
to `logs/apps/terminus.log` in the DSTNS folder when the launcher started it
(`./launcher apps logs terminus`). The portal API prints to its terminal.

## Quick diagnosis

| Symptom | Likely cause | Go to |
|---|---|---|
| `DSTNS already has an application 'terminus'` | A second Terminus, or a crashed one not yet unloaded | [409 APP_ID_CONFLICT](#409-app_id_conflict) |
| Start-up stops at "No data arrived for 200 seconds" | The map download stalled | [The map download stalls](#the-map-download-stalls) |
| `Address already in use` or a service will not start | A port is taken | [Port already in use](#port-already-in-use) |
| Journeys or speeds look old | The planned network or the speeds are stale | [The planned network looks stale](#the-planned-network-looks-stale) |
| `Live update failed, keeping the last speeds` | A read of DSTNS failed | [Refresh timeouts and "closed" mid-read](#refresh-timeouts-and-closed-mid-read) |
| The portal has no stops, or search fails | The portal cannot reach Terminus | [Portal search fails](#portal-search-fails) |
| `No run is going on ...` from `./bus` | Nothing has been started | [./bus cannot find a run](#bus-cannot-find-a-run) |

## 409 APP_ID_CONFLICT

Terminus joins DSTNS as an application with the id `terminus`. DSTNS refuses to
register a live id a second time unless the registrant presents the token it was given
the first time. Terminus keeps that token in `.fabric/terminus.token` in the Terminus
folder (readable only by you, ignored by git).

The error reads:

```text
DSTNS already has an application 'terminus' and .fabric/terminus.token does not hold
its token. Stop the other copy, or wait about 150 s for DSTNS to unload it, then try again.
```

Check, in order:

1. **Another copy is running.** Stop it (`./launcher stop`, or `Ctrl+C` where you
   started it).
2. **A copy crashed without unloading.** Wait about 150 seconds. DSTNS unloads an
   application that stops sending its heartbeat.
3. **The token file is missing or wrong.** That is exactly what the message says.
   Deleting `.fabric/terminus.token` does not clear the conflict; DSTNS keeps
   refusing until it has unloaded the old registration (the 150 seconds above, five
   missed 30-second heartbeats). A crash followed by a restart from the same folder
   keeps the file, reuses the token and does not conflict.
4. **Start fresh.** With the DSTNS server stopped, deleting its sealed application
   store, `logs/fabric-apps.json` and `logs/fabric-apps.json.key` in the DSTNS
   folder, removes every registration. Do this only with the server down, and only
   if nothing else uses the Fabric.

The related launcher message `no simulation started within 900 s` means Terminus
joined but no run began. Start a run from the launcher.

## The map download stalls

Starting DSTNS without `--osm-file` makes it download the seed's city from the
OpenStreetMap Overpass servers. Large cities can stall:

```text
No data arrived for 200 seconds, so the download was stopped.
```

(a run with seed `0x2026` stopped at 30 MiB). Options, easiest first:

1. **Use the bundled district.** It needs no download:
   `./launcher start --seed 0x2026 --osm-file data/fixtures/real_network.osm.xml --apps terminus`
   (a Berlin district of 1,196 junctions, included in the DSTNS repository).
2. **Use a city you downloaded before.** DSTNS documents cached extracts as working
   offline: `./launcher start --osm-file data/maps/<city>_x5000.osm.xml`. This was
   not tried here.
3. **Wait and retry, or change mirror.** Overpass servers rate-limit heavy use. Point
   DSTNS at another with `DSTNS_OVERPASS_ENDPOINTS=https://host/api/interpreter`.
   `DSTNS_MAP_STALL_S` changes the 200-second limit.

`implausibly small map` means the mirror returned an error page, which is the same
rate limiting. The full list is in the
[DSTNS troubleshooting](https://dstns.readthedocs.io/v3/troubleshooting/#map_fetch_failed).

## Port already in use

| Port | Used by | What to do |
|---|---|---|
| 8090 | DSTNS | Start with `DSTNS_API_PORT=8190 ./launcher start ...`, or stop the other program. Another person's simulator on the same machine is a common cause |
| 8080 | Terminus | Change `--port` in the launcher catalogue entry (`./launcher apps set terminus ...`) or run `./bus serve --port 8180`. Also update the portal's `ALGORITHM_SERVICE_URL` to match |
| 5174 | Dashboard dev server | `npm run dev -- --port N` |
| 3000 | Portal API | Free it. The web app calls `http://localhost:3000` and cannot be pointed elsewhere without editing `frontend/src/api.js` |
| 5173 | Portal web app | `npm run dev -- --port N` |
| 5432 | PostgreSQL | Use another port and put it in `DATABASE_URL` |

To see what holds a port: `lsof -i :8080`. `./launcher apps doctor terminus` also
reports what would stop Terminus from starting.

## The planned network looks stale

Terminus plans the bus lines once per run and then keeps two things up to date:

| What | How often |
|---|---|
| Road speeds and closures | Every 2 seconds, from DSTNS |
| The lines, stops and timetable | Only when the simulator's `run_id` changes |

Route and trip ids stay the same for a whole run, and a change in speeds moves
journey times, not the lines. When a new run starts Terminus re-plans by itself and
prints `New simulator run <id>: re-planned the network.` To rebuild the lines in the
middle of a run, or to look at a chosen time, force a re-plan:

```bash
curl -s -X POST http://127.0.0.1:8080/api/reload -d '{}'
```

To also pause DSTNS and jump to a time first, send `{"time":"18:00:00","seek":true}`.
The clock moves only when `seek` is `true`. Under the launcher the seek form may
need the operator token; that was not tested. A server holding a saved bundle (started from
`./bus export --bundle`) answers `409 RUN_STATE`, and one that cannot read DSTNS
answers `502 SIMULATOR_UNAVAILABLE`.

The portal copies stops, routes and trips from Terminus when it sees a new `run_id`,
and every id it stores starts with that run id, so older bookings keep pointing at
their own run's trips.

## Refresh timeouts and "closed" mid-read

Every 2 seconds Terminus re-reads the simulator's speeds. A read can fail now and
then, for example when DSTNS is busy or the connection is closed in the middle of the
answer, or when the request times out. Terminus keeps the last good city and says so
once, then says when it recovers:

```text
Live update failed, keeping the last speeds: <reason>
Live updates are working again.
```

Under the launcher the same applies to the Fabric link, with
`Fabric poll failed, will retry: <reason>` and `Fabric is answering again.`

In the DSTNS observer the Terminus status line shows
**road speeds out of date: cannot re-read DSTNS** while this lasts.

- **One or two occasional messages** are harmless. The next tick recovers and the
  status goes back to normal.
- **Constant failures** mean DSTNS is not answering. Check that it is still running:
  `./launcher apps logs terminus` and `./launcher logs`.
- The portal gives up on a slow Terminus after 1.5 seconds per request and tries
  again on its next 2-second cycle.

## Portal search fails

| What the portal says | Cause | Fix |
|---|---|---|
| `Could not search for routes` (HTTP 500) | The portal could not reach Terminus, or Terminus answered with an error it does not translate | Check Terminus is up (`curl http://127.0.0.1:8080/api/health`) and that `ALGORITHM_SERVICE_URL` has the right port. Check `USE_MOCK_ALGORITHM=false` |
| `Live bus times are not available yet. Please try again in a moment.` (503) | The portal has not read Terminus's `/api/v1/live` yet | Wait a few seconds |
| `This bus is not loaded yet. Please try again in a moment.` (503) | The portal has not copied this run's trips yet | Wait a few seconds |
| `No bus route connects these two stops.` (422) | Terminus found no journey | Pick other stops |
| `One of these stops is not on the bus map.` (404) | The stop id is not in this run | Reload the page so the stop list refreshes |
| `Algorithm returned an invalid route path` (502) | Terminus's answer failed the portal's checks | Report it with the two stops |

The portal prints `Sim sync failed: <reason>` once whenever it cannot copy from
Terminus, and `Copied simulator run <run id> from the algorithm` when it succeeds.

A search with `USE_MOCK_ALGORITHM=true` ignores Terminus entirely and returns a fixed
journey. If every search looks identical, check that setting.

## ./bus cannot find a run

| Message | Fix |
|---|---|
| `No run is going on ...` | Start a run from the launcher |
| `Could not read the simulator` | DSTNS is not running. Start it with `./launcher` |
| `the simulator was still PREPARING after ...` | A city download is slow or blocked; see [The map download stalls](#the-map-download-stalls) |
| `--start needs the simulator's operator token` | Set `DSTNS_OPERATOR_TOKEN` or pass `--token-file <DSTNS>/logs/operator.token`. Nothing was stopped |
| `the simulator refused the operator token` | Wrong token. The previous run was already stopped; start one from the launcher |
| `Warning: the run is RUNNING` | The clock is moving. Pause it in the observer, or pass `--time` |
| `stops ... 0 after merging` or `No bus stops in this world` | That district has no usable stops. Pick another seed |
| A journey says "Walk the whole way" | The two junctions are close. Choose ids from `./bus stops` that are further apart |

## The portal database

| Symptom | Fix |
|---|---|
| `database "csc210_ticketing" does not exist` | `createdb csc210_ticketing`, then load the four SQL files in [Installation](getting-started/installation.md#ticketing-portal) in order |
| `password authentication failed` | The user and password in `DATABASE_URL` do not match your PostgreSQL |
| Relations missing after pulling new portal code | Re-run the SQL files in order. They are written to be re-run |

!!! note "Not verified here"
    The last row assumes the portal's SQL files can be re-run safely. This was not
    tested. Use a fresh database if a re-run fails.
