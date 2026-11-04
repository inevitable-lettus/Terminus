# Installation

Get and prepare each part once. Choose a folder to hold the three repositories; the
examples use `~/bus-planner`.

```bash
mkdir ~/bus-planner && cd ~/bus-planner
git clone https://github.com/varunkarthic/DSTNS.git
(cd DSTNS && git checkout 4b88994)   # the commit this guide was tested on
git clone https://github.com/inevitable-lettus/Terminus.git
git clone https://github.com/CSC210-Monsoon2026/group-08-public-bus-route-planner.git portal
```

## DSTNS

Nothing to do yet. The launcher (`./launcher`) builds the C++ core and the observer,
and starts the server the first time you start a run, which takes a few minutes.
Install the [prerequisites](requirements.md#dstns) first.

The checkout above pins DSTNS to `4b88994` because that is the commit the launcher
steps in this guide were run against. Newer commits and the `v3` branch were not
tested. For more on building, see the
[DSTNS installation guide](https://dstns.readthedocs.io/v3/getting-started/installation/).

## Terminus

```bash
cd ~/bus-planner/Terminus
./gradlew test       # optional: 241 tests against saved simulator responses; needs nothing running
./bus help           # builds on first use, then prints every command and option
```

`./bus` rebuilds automatically whenever the sources changed, so there is no separate
build step. The dashboard is optional and is built once:

```bash
cd dashboard && npm install && npm run build
```

`./bus serve` then serves it on `http://127.0.0.1:8080`.

## Ticketing portal

### 1. Install the dependencies

```bash
cd ~/bus-planner/portal
npm install
cd frontend && npm install && cd ..
```

### 2. Create the database

Create an empty PostgreSQL database named `csc210_ticketing`, then load the schema in
this order:

```bash
createdb csc210_ticketing
cp .env.example .env
psql "$DATABASE_URL" -f db/schema.sql
psql "$DATABASE_URL" -f db/m3_offline_sync.sql
psql "$DATABASE_URL" -f db/m4_booking_unique.sql
psql "$DATABASE_URL" -f db/m5_sim.sql
npm run seed
```

`$DATABASE_URL` is the value in your `.env`. The example file points at
`postgresql://postgres:postgres@localhost:5432/csc210_ticketing`; change it if your
PostgreSQL user, password or port differ, and export it in your shell before running
`psql` (`export DATABASE_URL=...`).

### 3. Point the portal at Terminus

In `.env`, switch from the built-in mock to the real route planner:

```ini
DATABASE_URL=postgresql://postgres:postgres@localhost:5432/csc210_ticketing
PORT=3000
USE_MOCK_ALGORITHM=false
ALGORITHM_SERVICE_URL=http://127.0.0.1:8080
```

With `USE_MOCK_ALGORITHM=true` (the default in `.env.example`) the portal answers every
search with a fixed fake journey and never talks to Terminus. Use the mock only to
try the portal on its own.

`ALGORITHM_SERVICE_URL` must match the port Terminus serves on.

!!! warning "Do not commit `.env`"
    `.env` holds your database password. Keep it out of version control.
