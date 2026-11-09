# Quick start with DSTNS

Run the DSTNS simulator and Terminus together under the DSTNS launcher. This uses the
recorded district that ships with DSTNS, so it works offline and does not wait on a map
download. To add the ticketing portal on top, continue with the
[portal page](../portal/index.md#run-it).

Install DSTNS and Terminus first: see [DSTNS](index.md#install) and
[Installation](../../getting-started/installation.md). The examples assume both are
cloned side by side in `~/bus-planner`.

## 1. Add Terminus to the DSTNS launcher (once)

The DSTNS launcher starts programs alongside a run. Register Terminus with it. Run this
from the DSTNS folder and replace the `--cwd` path with where you cloned Terminus:

```bash
cd ~/bus-planner/DSTNS
./launcher apps add terminus \
    --name "Terminus" \
    --cwd ~/bus-planner/Terminus \
    --command "./bus serve --fabric --sandbox {url} --port 8080" \
    --ready fabric --timeout 180
./launcher apps doctor terminus
```

`apps doctor` should report that nothing is in the way. `{url}` is filled in by the
launcher. The entry is stored in `config/apps.json` on your machine only. The options
and what happens at start-up are in
[Running under the DSTNS launcher](../../fabric.md).

## 2. Start the simulation and Terminus

```bash
cd ~/bus-planner/DSTNS
./launcher start --seed 0x2026 \
    --osm-file data/fixtures/real_network.osm.xml \
    --apps terminus
```

The launcher builds DSTNS the first time (a few minutes), starts Terminus, waits until it
reports itself ready, then starts the simulation. You should see, in order:

1. `Starting applications Terminus`
2. `Terminus ready`
3. `Simulation running`
4. `Observer ready http://127.0.0.1:8090/`

Open **<http://127.0.0.1:8090>**. The observer shows the district, and Terminus draws
the bus lines, stops and buses on top of it. See [Drawing in the observer](observer.md).

!!! tip "Another port"
    If 8090 is taken, start with `DSTNS_API_PORT=8190 ./launcher start ...`. Terminus
    finds the simulator through the address the launcher passes to it.

Terminus's own output is in `logs/apps/terminus.log` in the DSTNS folder, and
`./launcher apps logs terminus` prints it.

## 3. Check Terminus

```bash
curl -s http://127.0.0.1:8080/api/health
cd ~/bus-planner/Terminus
./bus network        # the bus lines and how well they cover demand
./bus stops          # the busiest stops
```

The health answer names the run (`run_id`) and the simulated time. The dashboard, if you
built it, is at <http://127.0.0.1:8080>. See [The dashboard](../../guide/dashboard.md).

## 4. Close a road and watch it

In DSTNS close a road that a bus line uses. Within about 2 seconds Terminus re-prices the
lines, and journey times through that road rise. See [Road closures](road-closures.md).

## Stopping

`./launcher stop` from the DSTNS folder stops the simulation and Terminus. Terminus
unloads itself on exit, so the next start registers cleanly.
