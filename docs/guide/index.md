# User guide

How to use each part once the system is running. If it is not running yet, start with
the [Quick start](../getting-started/quick-start.md).

## Terminus

| Page | Use it to |
|---|---|
| [The ./bus commands](bus-cli.md) | Print the bus network, the busiest stops and a passenger journey; export the network; start the server |
| [The local API](../serve-api.md) | Read the planned city, journeys and traces as JSON; the contract the portal uses |
| [The dashboard](dashboard.md) | See the lines, buses and journeys in a browser, and replay how they were built |
| [Running under the DSTNS launcher](../fabric.md) | Start Terminus together with a simulation |
| [Drawing in the DSTNS observer](observer.md) | Read the bus lines, buses and arrivals panel that Terminus draws inside DSTNS |
| [Road closures](road-closures.md) | Close a road and see journeys change |

## Ticketing portal

| Page | Use it to |
|---|---|
| [Booking a ticket](portal-booking.md) | Search, lock a seat, confirm and open the ticket |
| [The conductor page](portal-conductor.md) | See a trip's passengers, sell a walk-in ticket, board and alight passengers |

## Roles

| Role | Does | Uses |
|---|---|---|
| Operator | Starts and stops runs, sets the clock, closes roads | DSTNS launcher and observer |
| Passenger | Searches journeys, books seats, shows a ticket | Portal web app |
| Conductor | Boards and alights passengers, sells walk-in tickets | Portal conductor page |
| Presenter or reviewer | Reads the network and shows how it was built | `./bus` commands and the dashboard |
