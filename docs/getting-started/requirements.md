# Requirements

## At a glance

| | DSTNS | Terminus | Portal |
|---|---|---|---|
| Operating system | macOS 13+, Linux; Windows through WSL 2 | Anything that runs a JDK | Anything that runs Node and PostgreSQL |
| Main runtime | C++20 compiler, CMake 3.22+, Rust 1.89+ (for the launcher), Node.js 20+ | JDK 21 | Node.js, PostgreSQL |
| Browser | Current Chrome, Edge, Firefox or Safari, window at least 1024 × 640 | Same, for the dashboard | Same |
| Network | Internet for new cities; none for the bundled district | None, apart from first-time Gradle and npm downloads | None, apart from npm downloads |

## DSTNS

DSTNS is built from source by its own launcher. The prerequisites, platform notes and
hardware sizing are in the DSTNS documentation:

- [DSTNS system requirements](https://dstns.readthedocs.io/v3/getting-started/requirements/)
- [DSTNS installation](https://dstns.readthedocs.io/v3/getting-started/installation/)

In short: a C++20 compiler, CMake 3.22 or newer, SQLite, zlib and libcurl headers,
Node.js 20 or newer, and Rust 1.89 or newer. About 1 GB of memory and 1 GB of disk
is enough for a district of 3,000 junctions.

!!! note "Not verified here"
    While testing the launcher flow the build also asked for `ninja` and `python3`.
    The DSTNS requirements page does not list them. If the build complains, install
    them with your package manager.

## Terminus

| Software | Minimum | Used for |
|---|---|---|
| JDK | 21 | Building and running the algorithm module |
| Node.js | 20 | Building the dashboard (optional) |

Gradle itself does not need installing: the Gradle wrapper is committed, and
`./bus` uses it. The first run downloads Gradle and the Java libraries, so it needs
network access once.

## Ticketing portal

| Software | Used for |
|---|---|
| Node.js and npm | The Express API and the React web app (Vite) |
| PostgreSQL | Passengers, trips, seats, bookings and tickets. The version tested is 16 |

## Ports

| Port | What | Change it with |
|---|---|---|
| 8090 | DSTNS server and observer | `DSTNS_API_PORT=<port>` when starting the launcher |
| 8080 | Terminus local API and dashboard | `./bus serve --port N`, or the `--port` in the launcher catalogue entry |
| 5174 | Dashboard development server (`npm run dev`) | `npm run dev -- --port N` |
| 3000 | Portal API | `PORT` in the portal's `.env` |
| 5173 | Portal web app (Vite) | `npm run dev -- --port N` |
| 5432 | PostgreSQL | The port in `DATABASE_URL` |

The portal web app calls the API at `http://localhost:3000`; this address is written
in `frontend/src/api.js`, so keep the portal API on port 3000.

If one of these ports is taken, see [Troubleshooting](../troubleshooting.md#port-already-in-use).
