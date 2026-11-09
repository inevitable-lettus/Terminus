# Quick start

The first commands, against a sandbox that is already running. Start one first: with
DSTNS, see the [DSTNS quick start](../integrations/dstns/quick-start.md).

The examples assume the sandbox is at `http://127.0.0.1:8090`, which is the default. Add
`--sandbox URL` to any command if it is somewhere else.

## Look at the network

```bash
./bus network        # the bus lines and how well they cover demand
./bus stops          # the busiest stops, and which lines serve them
```

The first lines of every report say what it stands on: the run and seed, the map, the
stops, where demand came from, and the budget. Read them before the numbers.

## Plan a journey

```bash
./bus journey 5 217      # between two junction ids
```

Choose junction ids from `./bus stops` that are far enough apart, or the answer is
"Walk the whole way". Add `--time 18:00:00` to read the run at a chosen time. That
pauses the sandbox's clock.

## Export and serve

```bash
./bus export                    # out/routes.json and out/routes.geojson
./bus serve                     # the API and the dashboard on http://127.0.0.1:8080
curl -s http://127.0.0.1:8080/api/health
```

The health answer names the run and the sandbox's simulated time. Open
<http://127.0.0.1:8080> for the dashboard if you built it.

## Next

- [The ./bus commands](../guide/bus-cli.md): every command and option.
- [Configuration](../guide/configuration.md): the budget, the stop gap and the ports.
- [The local API](../serve-api.md): every endpoint.
- [Concepts](../concepts/index.md): what the reports mean.
