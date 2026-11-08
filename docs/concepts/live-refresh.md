# Live refresh

`./bus serve` plans once and then keeps part of the plan current.

| What | How often | Why |
|---|---|---|
| Road speeds and closures | Every 2 seconds | A sandbox may change them at any moment and may send no event for a closure made by hand |
| The lines, stops and timetable | When the sandbox's run changes | Planning is the expensive step; route and trip ids stay the same for a whole run |
| Everything, on demand | `POST /api/reload` | To rebuild mid-run, or to look at a chosen time |

Every answer comes from one immutable planned city that is swapped in whole, so a
request never sees half of the old plan and half of the new.

## What a refresh changes

A refresh re-prices the **same lines** with the new speeds. Journey times and arrival
times move; the lines and stops do not. A road that closes later is excluded from journey
searches, but the existing lines cannot be re-routed around it: a bus on a line that uses
it is priced as stuck. Starting a new run re-plans everything.

## When a refresh fails

If re-reading the sandbox fails, Terminus keeps the last good plan, says so once, and
retries on the next tick. The server marks its speeds as stale until a read succeeds
again. Occasional failures are harmless.

```text
Live update failed, keeping the last speeds: <reason>
Live updates are working again.
```

## Time

Terminus does not keep a clock of its own. It reads the sandbox's simulated time with
each refresh. One-shot `./bus` commands read the run at the moment they ask and leave
the clock alone; `--time HH:MM:SS` pauses the run and moves it first.
