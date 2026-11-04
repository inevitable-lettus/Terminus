# Quick start

Run the simulator, Terminus and the portal together, then book a ticket. This uses
the recorded district that ships with DSTNS, so it works offline and does not wait
on a map download.

Finish [Installation](installation.md) first.

## 1. Add Terminus to the DSTNS launcher (once)

The DSTNS launcher starts programs alongside a run. Register Terminus with it. Run
this from the DSTNS folder and replace the `--cwd` path with where you cloned
Terminus:

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
launcher. The entry is stored in `config/apps.json` on your machine only. Details
and the options are in [Running under the DSTNS launcher](../fabric.md).

## 2. Start the simulation and Terminus

```bash
cd ~/bus-planner/DSTNS
./launcher start --seed 0x2026 \
    --osm-file data/fixtures/real_network.osm.xml \
    --apps terminus
```

The launcher builds DSTNS the first time (a few minutes), starts Terminus, waits
until it reports itself ready, then starts the simulation. You should see, in order:

1. `Starting applications Terminus`
2. `Terminus ready`
3. `Simulation running`
4. `Observer ready http://127.0.0.1:8090/`

Open **<http://127.0.0.1:8090>**. The observer shows the district, and Terminus draws
the bus lines, stops and buses on top of it. See
[Drawing in the DSTNS observer](../guide/observer.md).

!!! tip "Another port"
    If 8090 is taken, start with `DSTNS_API_PORT=8190 ./launcher start ...`. Terminus
    finds the simulator through the address the launcher passes to it.

Terminus's own output is in `logs/apps/terminus.log` in the DSTNS folder, and
`./launcher apps logs terminus` prints it.

## 3. Check Terminus

```bash
curl -s http://127.0.0.1:8080/api/health
```

The answer names the run (`run_id`) and the simulator's virtual time. Then:

```bash
cd ~/bus-planner/Terminus
./bus network        # the bus lines and how well they cover demand
./bus stops          # the busiest stops
```

The dashboard, if you built it, is at <http://127.0.0.1:8080>. See
[The dashboard](../guide/dashboard.md).

## 4. Start the portal

In two more terminals:

```bash
# Terminal A: the API on port 3000
cd ~/bus-planner/portal
npm start

# Terminal B: the web app on port 5173
cd ~/bus-planner/portal/frontend
npm run dev
```

Within a few seconds the portal API copies Terminus's stops, routes, trips and seats
into PostgreSQL. Open **<http://localhost:5173>**.

## 5. Book a ticket

1. On the home page pick a **From** and a **To** stop and search.
2. On **Your journey**, choose **Choose Seats** on a bus.
3. Pick a seat, then **Confirm booking**. You get a digital ticket with a QR code.

The full walk-through, including the conductor page, is in
[Booking a ticket](../guide/portal-booking.md).

## 6. Close a road and watch it

In the DSTNS observer close a road that a bus line uses. Within about 2 seconds
Terminus re-prices the lines, and journey times through that road rise. See
[Road closures](../guide/road-closures.md).

## Stopping everything

- Stop the portal with `Ctrl+C` in each terminal.
- Stop DSTNS and Terminus with `./launcher stop` from the DSTNS folder. Terminus
  unloads itself on exit, so the next start registers cleanly.
- Stop PostgreSQL the way you started it.
