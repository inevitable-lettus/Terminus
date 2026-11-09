# Troubleshooting

Start with what Terminus itself says. It prints to the terminal it runs in. Problems specific to one program are on that program's pages: [DSTNS](integrations/dstns/troubleshooting.md) and the [ticketing portal](integrations/portal/troubleshooting.md).

## Quick diagnosis

| Symptom | Likely cause | Go to |
|---|---|---|
| `Address already in use` | A port is taken | [Port already in use](#port-already-in-use) |
| Journeys or speeds look old | The planned network or the speeds are stale | [The planned network looks stale](#the-planned-network-looks-stale) |
| `Live update failed, keeping the last speeds` | A read of the sandbox failed | [Refresh failures](#refresh-failures) |
| `No run is going on ...` or `Could not read the simulator` | Nothing is running, or the address is wrong | [./bus cannot find a run](#bus-cannot-find-a-run) |
| The dashboard says it has not been built | `npm run build` was not run | [The dashboard](guide/dashboard.md#when-it-looks-wrong) |
| `DSTNS already has an application`, a stalled map download | DSTNS integration | [DSTNS troubleshooting](integrations/dstns/troubleshooting.md) |
| The portal has no stops, or search fails | The portal cannot reach Terminus | [Portal troubleshooting](integrations/portal/troubleshooting.md) |

## Port already in use

Terminus listens on 8080 by default.

- Run `./bus serve --port 8180`, or stop the other program. To see what holds a port:
  `lsof -i :8080`.
- Anything that reads Terminus by address, such as the portal's `ALGORITHM_SERVICE_URL`,
  must then use the new port.
- The dashboard's development server uses 5174: `npm run dev -- --port N`.
- Ports used by the integrations are on their own pages: [DSTNS](integrations/dstns/troubleshooting.md#dstns-port-in-use) and the [portal](integrations/portal/troubleshooting.md#ports).

## The planned network looks stale

Terminus plans the bus lines once per run and then keeps two things up to date:

| What | How often |
|---|---|
| Road speeds and closures | Every 2 seconds, from DSTNS |
| The lines, stops and timetable | Only when the sandbox's `run_id` changes |

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

Programs that copy from Terminus, such as the portal, do so when they see a new
`run_id`.


## Refresh failures

Every 2 seconds Terminus re-reads the sandbox's speeds. A read can fail now and
then, for example when DSTNS is busy or the connection is closed in the middle of the
answer, or when the request times out. Terminus keeps the last good city and says so
once, then says when it recovers:

```text
Live update failed, keeping the last speeds: <reason>
Live updates are working again.
```


- **One or two occasional messages** are harmless. The next tick recovers and the
  status goes back to normal.
- **Constant failures** mean the sandbox is not answering. Check that it is still running
  and that its address is the one you gave with `--sandbox`. Requests time out after 30
  seconds. For DSTNS see [Speeds out of date in the observer](integrations/dstns/troubleshooting.md#speeds-out-of-date-in-the-observer).

## ./bus cannot find a run

| Message | Fix |
|---|---|
| `No run is going on ...` | Start a run in the sandbox |
| `Could not read the simulator` | The sandbox is not running, or `--sandbox` has the wrong address |
| `the simulator was still PREPARING after ...` | The sandbox is slow to build its world. For a DSTNS city download see [The map download stalls](integrations/dstns/troubleshooting.md#the-map-download-stalls) |
| `Warning: the run is RUNNING` | The clock is moving. Pause it in the sandbox, or pass `--time` |
| `stops ... 0 after merging` or `No bus stops in this world` | That map has no usable stops. Pick another seed or map |
| A journey says "Walk the whole way" | The two junctions are close. Choose ids from `./bus stops` that are further apart |

For `--start` and operator-token messages see
[Starting a run from ./bus](integrations/dstns/troubleshooting.md#starting-a-run-from-bus).
