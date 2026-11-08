# Known limitations

What does not work yet, and where the results are weak. The engineering view, with the
history of each, is in [Engineering limitations](limitations.md).

## Sandbox independence

| Gap | What it means |
|---|---|
| **The code reads DSTNS directly** | Terminus's core uses DSTNS's data types and the command line carries DSTNS-only options. Another sandbox needs an adapter that does not exist. Tracked in epic [#17](https://github.com/inevitable-lettus/Terminus/issues/17) |
| **No neutral model or input interfaces** | [#28](https://github.com/inevitable-lettus/Terminus/issues/28), [#29](https://github.com/inevitable-lettus/Terminus/issues/29) |
| **No generic demand points or candidate stops** | [#30](https://github.com/inevitable-lettus/Terminus/issues/30) |
| **DSTNS is not yet an optional module** | [#31](https://github.com/inevitable-lettus/Terminus/issues/31), [#32](https://github.com/inevitable-lettus/Terminus/issues/32) |
| **No neutral world file, OpenStreetMap loader or GTFS import** | [#33](https://github.com/inevitable-lettus/Terminus/issues/33), [#34](https://github.com/inevitable-lettus/Terminus/issues/34), [#35](https://github.com/inevitable-lettus/Terminus/issues/35) |
| **Bus-specific names and constants in the core** | [#36](https://github.com/inevitable-lettus/Terminus/issues/36) |
| **Not published as a library; the dashboard and tests assume DSTNS** | [#37](https://github.com/inevitable-lettus/Terminus/issues/37), [#40](https://github.com/inevitable-lettus/Terminus/issues/40), [#41](https://github.com/inevitable-lettus/Terminus/issues/41) |

## Planning

| Gap | What it means |
|---|---|
| **Lines are planned once per run** | A road that closes later is excluded from journey searches, but a bus line cannot re-route around it. Start a new run to re-plan |
| **Heuristic network** | Algorithm 1 is greedy and makes no claim to be optimal. The problem is NP-hard and the usual methods are outside the course toolkit |
| **Demand is synthetic and relative** | A sandbox generates it from building types and time windows. "Coverage" is a share of demand pull, not of passengers |
| **One time slice** | The network is designed for the time the run is read at (18:00 in the reference runs). It is not optimal at other times of day ([#27](https://github.com/inevitable-lettus/Terminus/issues/27)) |
| **Some stops are left unserved** | Stops in one-way pockets at the edge of a district cannot be served both ways. The report counts them ([#20](https://github.com/inevitable-lettus/Terminus/issues/20)) |
| **A passenger starts at a junction**, not an address, and walks at most 400 m to a stop | A destination with no stop in reach is reported unreachable |
| **Walking distances are straight lines**, not road distances | A stop across a barrier can look reachable |
| **The transfer wait is a flat five minutes** and the first boarding wait is zero by default | `--board-wait` adds a wait of your choice. The timetable is invented: a sandbox publishes none |
| **Re-reads sometimes fail** | A read of the sandbox can time out or be closed mid-read. Terminus keeps the last speeds and recovers on the next tick |

## Research deliverable

The course requires comparing shortest-path algorithms across network sizes, with a
weighted A\* sweep (`f = g + ε·h`). That deliverable is not built
([#21](https://github.com/inevitable-lettus/Terminus/issues/21),
[#22](https://github.com/inevitable-lettus/Terminus/issues/22),
[#23](https://github.com/inevitable-lettus/Terminus/issues/23)). A\* and Dijkstra agree on
every journey cost in testing, and A\* expands fewer vertices (7.9% fewer over 5,700 live
journeys on one map).

## DSTNS integration

| Gap | What it means |
|---|---|
| **Lines cannot be sent back to DSTNS** | DSTNS retired that endpoint. Output reaches a map through the Fabric drawing in the observer, the dashboard or `routes.geojson` |
| **Tested against one DSTNS commit** | `4b88994` on `main`. The `v3` branch was not tested |
| **No click in the observer closes a road** | Closing a road uses DSTNS's control API |

## Ticketing portal

| Gap | What you see |
|---|---|
| **No live arrival countdown.** The portal's live view is not built | A page does not count a bus down. `GET /api/v1/live` on Terminus lists buses and the DSTNS observer shows them, but the portal does not draw them yet |
| **One bus per booking** | A journey with a change of bus is shown with a message that it needs more than one bus |
| **No login.** Booking history uses a fixed demo passenger | The history page shows `PASSENGER_DEMO_1` |
| **A line that visits a stop twice** | A seat lock can be refused with "drop-off before boarding" |
| **A walk-only journey can be offered as bookable** from 06:00 onwards | The search lists a bus you would not take because walking is as good |
| **About 23% of rides in one sweep got no trip id** on a reverse-direction match | Such a ride cannot be booked |
| **Stop names.** Only a few stops have names | Most show `stop_<n>` |
| **Conductor page walk-in form** uses demo seat ids | Not verified on trips copied from a Terminus run |

## About this documentation

- The printed outputs are from saved Berlin fixtures and from live runs on one map. Your
  numbers will differ.
- The run steps were tested on a Mac. Linux was not tested.
