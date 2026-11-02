# Running under the DSTNS launcher

DSTNS starts simulations only from its own launcher (`./launcher` in the DSTNS
checkout). Other programs join a run as **Fabric applications**: the launcher starts
them, waits until each says it is ready, and only then starts the simulation.
`./bus serve --fabric` is Terminus in that role.

## One-time setup

From the DSTNS checkout, add Terminus to the launcher's catalogue
(`config/apps.json`, which stays on your machine):

```sh
./launcher apps add terminus \
    --name "Terminus" \
    --cwd /path/to/Terminus \
    --command "./bus serve --fabric --sandbox {url} --port 8080" \
    --ready fabric --timeout 180 --autostart
./launcher apps doctor terminus
```

`./bus` builds Terminus first if anything changed, so the first start can take a
minute; `--timeout 180` allows for that. `{url}` is filled in by the launcher with the
DSTNS address.

## Every time

```sh
./launcher start --seed 0x2026         # starts Terminus, then the simulation
./launcher apps logs terminus          # what Terminus printed
```

With a simulation already running, `./launcher apps run terminus` starts Terminus
against it.

## What happens

1. Terminus registers as application `terminus` and keeps the token DSTNS gives it in
   `.fabric/terminus.token` (readable only by you, ignored by git). DSTNS refuses to
   register a live id a second time without that token.
2. It tells DSTNS it is `active`. The launcher now starts the run.
3. It waits for the run to start (a `lifecycle` event), because the map does not exist
   before that, then plans the network and serves on `--port` as usual.
4. Every 2 seconds it sends a heartbeat and reads its events. A new world or an
   incident triggers an immediate re-read; road speeds and closures are re-read every
   2 seconds either way, because DSTNS sends no event for a road closed by hand.
5. On exit it unloads, so the next start registers cleanly.

Terminus asks only for capabilities DSTNS grants automatically (drawing in the
observer and reading the simulation). It never changes the simulation: roads are
closed by a person in the DSTNS observer, and Terminus reacts.

## If it will not start

| Message | Fix |
|---|---|
| `DSTNS already has an application 'terminus'` | Another copy is running, or one crashed without unloading. Stop the other copy, or wait about 150 s for DSTNS to unload it |
| `no simulation started within 900 s` | Start a run from the launcher |
