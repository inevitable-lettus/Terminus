# Booking a ticket

The portal web app is at <http://localhost:5173>. It needs Terminus running (see the
[Quick start](../dstns/quick-start.md)), because the stops and trips it
shows come from Terminus.

## The flow

1. **Search.** On *Where would you like to go?* pick a **From** and a **To** stop and
   press search. Both must be chosen and must differ.
2. **Your journey.** The page lists the bus trips for the journey: the line, the bus,
   the departure time in city (simulated) time and the stops it rides between. Press
   **Choose Seats** on the trip you want.
3. **Select your seat.** The seat map shows each seat as *Available*, *Locked* or
   *Booked*. Click an available seat. It is locked for you for **2 minutes**, with a
   countdown, and the fare is shown.
4. **Confirm booking.** Press **Confirm booking** before the lock runs out. The portal
   books the seat and issues a ticket.
5. **Digital ticket.** The ticket page shows the ticket with a QR code. Enter a
   ticket id on the same page to open any ticket again.

If the lock expires, the page says so and you pick a seat again. The seat map
refreshes every 5 seconds, so a seat another passenger locks shows as *Locked*.

## Booking history

**Booking History** lists a passenger's bookings. There is no login yet: the web app
shows the bookings of the demo passenger `PASSENGER_DEMO_1`.

## What can be booked

- **One bus only.** A journey that needs a change of bus is shown, but the page says
  it needs more than one bus and offers no **Choose Seats** button.
- **Fare.** The portal prices the ride by its distance: under 3 km Rs. 10, from 3 km
  to 8 km Rs. 20, over 8 km Rs. 30. When more than 70% of the seats on the trip are
  taken the fare is multiplied by 1.2. The fare is fixed when you lock the seat, so it
  cannot change while you confirm.
- **Stops without names.** DSTNS names few stops, so most appear as `stop_<n>`.
  That is the map data, not an error.

## The same thing through the API

The web app uses these portal API calls, at `http://localhost:3000`:

| Step | Call |
|---|---|
| List stops | `GET /api/stops` |
| Search | `POST /api/search` with `{"sourceStopId", "destStopId"}` |
| Seat map | `GET /api/trips/:tripId/seats` |
| Lock a seat | `POST /api/trips/:tripId/seats/:seatId/lock` |
| Book | `POST /api/bookings` with `{"tripId", "seatId"}` |
| Ticket | `POST /api/bookings/:id/ticket` |
| Validate a ticket | `GET /api/tickets/:id/validate` |
| Booking history | `GET /api/passengers/:id/bookings` |
| Cancel | `POST /api/bookings/:id/cancel` |

Search asks Terminus for the journey at the simulator's current time. If Terminus is
not running, or the portal has not yet copied its trips, the search fails; see
[Troubleshooting](troubleshooting.md#portal-search-fails).
