# Known limitations

What does not work yet, and where the results are weak. These are the limitations a
user will meet. The engineering view, with the history of each, is in
[Engineering limitations](limitations.md).

## Ticketing portal

| Gap | What you see |
|---|---|
| **No live arrival countdown.** The portal's live view is not built | A page does not count a bus down. `GET /api/v1/live` on Terminus does list buses, and the DSTNS observer shows them, but the portal does not draw them yet |
| **One bus per booking.** Journeys with a change of bus are not bookable | The journey is shown with a message that it needs more than one bus |
| **No login.** Booking history uses a fixed demo passenger | The history page shows `PASSENGER_DEMO_1` |
| **A line that visits a stop twice** | A seat lock can be refused with "drop-off before boarding" |
| **A walk-only journey can be offered as bookable** from 06:00 onwards | The search lists a bus that you would not take because walking is as good |
| **About 23% of rides in one sweep got no trip id** on a reverse-direction match | Such a ride cannot be booked |
| **Stop names.** Only a few stops have names | Most show `stop_<n>` |
| **Conductor page walk-in form** uses demo seat ids | Not verified on trips copied from a Terminus run |

## Terminus and the simulator

| Gap | What it means |
|---|---|
| **Lines are planned once per run** | A road that closes later is priced as gridlock for the bus. It cannot re-route. Start a new run to re-plan |
| **Closures of the opposite direction of a street** | Only the directed road a bus rides counts |
| **Heuristic network** | Algorithm 1 is greedy and makes no claim to be optimal. The problem is NP-hard and the usual methods are outside the course toolkit |
| **Demand is synthetic and relative** | DSTNS generates it from building types and time windows. "Coverage" is a share of generator pull, not of passengers |
| **One time slice** | The network is designed for 18:00 at seed `0x2026`. It is not optimal at other times of day |
| **Some stops are left unserved** | Stops in one-way pockets at the edge of the district cannot be served both ways. The report counts them |
| **A passenger starts at a junction**, not an address, and walks at most 400 m to a stop | A destination with no stop in reach is reported unreachable |
| **Walking distances are straight lines**, not road distances | A stop across a barrier can look reachable |
| **The transfer wait is a flat five minutes** and the first boarding wait is zero by default | `--board-wait` adds a wait of your choice. The simulator publishes no timetable of its own |
| **Lines cannot be sent back to the simulator** | DSTNS retired that endpoint, so output reaches a map only through the DSTNS observer drawing, the dashboard or `routes.geojson` |
| **Re-reads sometimes fail** | A read of DSTNS can time out or be closed mid-read; Terminus keeps the last speeds and recovers on the next tick |

## Research deliverable

The course requires comparing shortest-path algorithms across network sizes, with a
weighted A\* sweep (`f = g + ε·h`). That deliverable is not built. A\* and Dijkstra
agree on every journey cost in testing, and A\* expands fewer vertices (7.9% fewer
over 5,700 live journeys on one map), but the sweep across network sizes is
outstanding.

## About the documentation

- The printed outputs in this guide are from saved Berlin fixtures and from live
  runs on one map. Your numbers will differ.
- The run steps were tested on a Mac. Linux was not tested.
