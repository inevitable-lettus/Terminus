# Ticketing portal

The portal is a passenger and conductor web application. It lives in a separate
repository,
[`CSC210-Monsoon2026/group-08-public-bus-route-planner`](https://github.com/CSC210-Monsoon2026/group-08-public-bus-route-planner),
and is one **consumer of the Terminus serve API**. Nothing in Terminus depends on it.

| Part | Technology | Default port |
|---|---|---|
| API | Node.js, Express | 3000 |
| Web app | React, Vite | 5173 |
| Database | PostgreSQL | 5432 |

## How it uses Terminus

```mermaid
flowchart LR
    U["Web app<br/>:5173"] -->|"search, seats, booking"| A["Portal API<br/>:3000"]
    A --> D[("PostgreSQL")]
    A -->|"POST /api/v1/routes/query<br/>on every search"| T["Terminus serve API<br/>:8080"]
    T -->|"GET /api/v1/stops, routes, trips, live<br/>every 2 s"| A
```

- **Every 2 seconds** the portal reads Terminus's `GET /api/v1/live` and, when the run
  changes, copies that run's stops, routes, trips and seats into PostgreSQL. Every id it
  stores starts with the run id, so older bookings keep pointing at their own run's trips.
- **On every search** it asks `POST /api/v1/routes/query` for a journey at the current
  simulated time, and books a seat on the trip Terminus names.
- It gives up on a slow Terminus after 1.5 seconds and tries again on the next cycle.

The contract is described under
[the portal's contract](../../serve-api.md#the-ticketing-portals-contract-camelcase-frozen-by-the-portal-team).
Terminus does not need the portal.

## Requirements

| Software | Used for |
|---|---|
| Node.js and npm | The Express API and the React web app (Vite) |
| PostgreSQL | Passengers, trips, seats, bookings and tickets. The version tested is 16 |

## Install

```bash
git clone https://github.com/CSC210-Monsoon2026/group-08-public-bus-route-planner.git portal
cd portal
npm install
cd frontend && npm install && cd ..
```

Create an empty PostgreSQL database named `csc210_ticketing`, then load the schema in this
order:

```bash
createdb csc210_ticketing
cp .env.example .env
psql "$DATABASE_URL" -f db/schema.sql
psql "$DATABASE_URL" -f db/m3_offline_sync.sql
psql "$DATABASE_URL" -f db/m4_booking_unique.sql
psql "$DATABASE_URL" -f db/m5_sim.sql
npm run seed
```

`$DATABASE_URL` is the value in your `.env`; export it in your shell before running
`psql`. The example points at
`postgresql://postgres:postgres@localhost:5432/csc210_ticketing`.

### Point the portal at Terminus

In `.env`, switch from the built-in mock to the real planner:

```ini
DATABASE_URL=postgresql://postgres:postgres@localhost:5432/csc210_ticketing
PORT=3000
USE_MOCK_ALGORITHM=false
ALGORITHM_SERVICE_URL=http://127.0.0.1:8080
```

With `USE_MOCK_ALGORITHM=true` (the default in `.env.example`) the portal answers every
search with a fixed fake journey and never talks to Terminus. `ALGORITHM_SERVICE_URL` must
match the port Terminus serves on. Keep `.env` out of version control: it holds your
database password.

## Run it

With Terminus running (for example from the
[DSTNS quick start](../dstns/quick-start.md)), start the portal in two terminals:

```bash
# Terminal A: the API on port 3000
cd ~/bus-planner/portal && npm start

# Terminal B: the web app on port 5173
cd ~/bus-planner/portal/frontend && npm run dev
```

Within a few seconds the API copies Terminus's stops, routes, trips and seats into
PostgreSQL. Open **<http://localhost:5173>**. The web app calls the API at
`http://localhost:3000`, an address written in `frontend/src/api.js`, so keep the API on
port 3000.

## Using it

| Page | Covers |
|---|---|
| [Booking a ticket](booking.md) | Search, lock a seat, confirm and open the ticket |
| [The conductor page](conductor.md) | The manifest, walk-in tickets, boarding and alighting |
| [Portal troubleshooting](troubleshooting.md) | Search errors, an empty stop list, database errors |
