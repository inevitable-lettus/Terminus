# The conductor page

A conductor works one trip at a time: sees who has a ticket, sells a walk-in ticket,
and marks passengers as boarded and alighted. Open it at
<http://localhost:5173/conductor>. It is not in the navigation bar.

## Choose the trip

The page opens on the demo trip `TRIP_DEMO_1`. Enter another **Trip ID** and reload
the manifest. For a trip taken from a Terminus run the id looks like
`<run id>:route_0-out-0600` (the run id comes first, because the portal prefixes every
id it copies with the run it came from). You can read trip ids from the seat page, or
from `GET /api/v1/trips` on Terminus.

## The passenger manifest

Under *Passenger Manifest* each ticket holder is listed with their seat and a state:
*Not boarded*, *On board* or *Alighted*. The trip header shows the bus and the
departure.

| Button | Does |
|---|---|
| **Board** | Marks the passenger as on board. The page shows the occupancy |
| **Alight** | Marks the passenger as having left the bus |

A passenger who has alighted shows *Completed*.

## Sell a walk-in ticket

Under *Sell a Walk-in Ticket* enter the passenger's name, a seat, a boarding stop and
a destination stop, then submit. The page shows the ticket created and the fare.

!!! note "Not verified here"
    The seat list on this form is built from the demo seat ids `SEAT_DEMO_1` and so
    on, which belong to the seeded demo trip. Whether walk-in sales work on a trip
    copied from a Terminus run was not tested.

## The same thing through the API

| Step | Call |
|---|---|
| Manifest | `GET /api/trips/:tripId/manifest` |
| Walk-in ticket | `POST /api/trips/:tripId/walkin` |
| Board | `POST /api/trips/:tripId/board/:ticketId` |
| Alight | `POST /api/trips/:tripId/alight/:ticketId` |
