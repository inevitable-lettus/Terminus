# DSTNS troubleshooting

Problems that belong to the DSTNS integration. For anything else start with [Troubleshooting](../../troubleshooting.md).

Terminus prints to the terminal it runs in, or to `logs/apps/terminus.log` in the DSTNS folder when the launcher started it (`./launcher apps logs terminus`).

## Quick diagnosis

| Symptom | Likely cause | Go to |
|---|---|---|
| `DSTNS already has an application 'terminus'` | A second Terminus, or a crashed one not yet unloaded | [409 APP_ID_CONFLICT](#409-app_id_conflict) |
| Start-up stops at "No data arrived for 200 seconds" | The map download stalled | [The map download stalls](#the-map-download-stalls) |
| Port 8090 is taken | Another DSTNS or program | [DSTNS port in use](#dstns-port-in-use) |
| The observer shows "road speeds out of date" | Terminus cannot re-read DSTNS | [Speeds out of date](#speeds-out-of-date-in-the-observer) |
| `--start needs the simulator's operator token` | `--start` or `--seed` without a token | [Starting a run from ./bus](#starting-a-run-from-bus) |

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

## DSTNS port in use

DSTNS listens on 8090. Start it on another port with
`DSTNS_API_PORT=8190 ./launcher start ...`, or stop the other program. Another person's
simulator on the same machine is a common cause. To see what holds a port:
`lsof -i :8090`. `./launcher apps doctor terminus` also reports what would stop Terminus
from starting. If Terminus's own port is taken, see
[Troubleshooting](../../troubleshooting.md#port-already-in-use).

## Speeds out of date in the observer

In the DSTNS observer the Terminus status line shows
**road speeds out of date: cannot re-read DSTNS** while Terminus cannot read DSTNS. The
drawing carries on with the last speeds it had and recovers by itself on the next
successful read. See [Refresh failures](../../troubleshooting.md#refresh-failures).

Under the launcher the Fabric link logs its own messages as well:

```text
Fabric poll failed, will retry: <reason>
Fabric is answering again.
```

If it stays failing, check that DSTNS is still running with `./launcher apps logs terminus`
and `./launcher logs`.

## Starting a run from ./bus

| Message | Fix |
|---|---|
| `--start needs the simulator's operator token` | Set `DSTNS_OPERATOR_TOKEN` or pass `--token-file <DSTNS>/logs/operator.token`. Nothing was stopped |
| `the simulator refused the operator token` | Wrong token. The previous run was already stopped; start one from the launcher |
