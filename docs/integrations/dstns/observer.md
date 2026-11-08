# Drawing in the DSTNS observer

When Terminus runs [under the DSTNS launcher](../../fabric.md) it draws on the DSTNS
observer, the browser map at <http://127.0.0.1:8090>. Nothing needs switching on:
once Terminus reports itself ready, its elements appear.

## What you see

| Element | Where | Meaning |
|---|---|---|
| **Bus lines** | On the map | Each line is drawn along its roads. The outbound direction is a thick solid line and the return direction a thinner dashed line. Each line has its own colour, taken from the observer's numbered colour series 0 to 7, so line 8 reuses line 0's colour |
| **Stops** | On the map | A stop marker at each stop of every line, labelled with the stop's name, or `Stop <junction id>` when DSTNS has no name for it |
| **Buses** | On the map | One marker per bus on the road, labelled `R` and its line, gliding along its road between reports. Buses run every 15 simulated minutes from 06:00 to 21:45 |
| **Terminus button** | Map controls | A pin button labelled *Terminus* that opens the arrivals panel |
| **Next arrivals** | Panel | Counts of lines, buses on the road and the simulated time, then a table of the next buses to reach a stop: line, trip, next stop and minutes until arrival (`due` when under a minute) |
| **Status** | Status line | `Terminus: <n> lines, <m> buses`, with a warning tone when speeds could not be re-read |

The arrivals table lists at most 12 buses, soonest first. Outside service hours, or
when the clock is before 06:00, the panel says no bus is on the road and when
buses run.

The names of the layers and toggles that DSTNS lists these under were not checked.

## How it stays current

- Lines and stops are drawn once per run and again only when the network changes,
  which means a new run.
- Buses, the arrivals panel and the status line are redrawn every 2 seconds in one
  update, so the observer never shows half of one.
- Speeds and closures are re-read every 2 seconds, so the arrival times follow the
  traffic.

## The warning status

If Terminus cannot re-read DSTNS (for example because the simulator is busy), the
status line changes to **road speeds out of date: cannot re-read DSTNS**. The
drawing carries on with the last speeds it had and recovers by itself on the next
successful read. See [Troubleshooting](troubleshooting.md#speeds-out-of-date-in-the-observer).

## What Terminus is allowed to do

Terminus asks only for the permissions DSTNS grants automatically: to draw in the
observer and to read the simulation. It never changes the simulation. Roads are
closed by a person, and Terminus reacts. See [Road closures](road-closures.md).
