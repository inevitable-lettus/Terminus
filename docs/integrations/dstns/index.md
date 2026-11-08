# DSTNS

DSTNS is the Deterministic Spatiotemporal Transport Network Simulator: a seeded,
real-geography traffic simulator with a C++20 core, an operator command line (the
launcher) and a browser observer. It is the sandbox Terminus is built and tested against.
The DSTNS project documents itself at
[dstns.readthedocs.io/v3](https://dstns.readthedocs.io/v3/).

!!! info "DSTNS is one sandbox, not a requirement of the algorithms"
    Terminus's algorithms need only a road graph and live speeds
    ([the sandbox interface](../../sandbox/index.md)). Today the code reads DSTNS's types
    directly, so DSTNS is the only supported source until the work in
    [#17](https://github.com/inevitable-lettus/Terminus/issues/17) lands.

## What the integration covers

| Page | Covers |
|---|---|
| [Quick start](quick-start.md) | Starting the simulator and Terminus together, with the offline fixture map |
| [Running under the DSTNS launcher](../../fabric.md) | Registering Terminus as a launcher application, and what happens at start-up |
| [Drawing in the observer](observer.md) | The bus lines, buses and arrivals panel Terminus draws in DSTNS |
| [Road closures](road-closures.md) | Closing a road in DSTNS and seeing journeys change |
| [DSTNS troubleshooting](troubleshooting.md) | The 409 conflict, the stalled map download, DSTNS ports |

## How the two connect

Terminus reads DSTNS over HTTP, and, under the launcher, also joins it as a **Fabric
application**: a program that DSTNS's launcher starts, waits for, and lets draw in the
observer. Terminus never changes the simulation. It asks only for permissions DSTNS
grants automatically (drawing in the observer and reading the simulation). Roads are
closed by a person, and Terminus reacts.

## Requirements

This was tested against DSTNS commit `4b88994` on its `main` branch. Its README lists:

| Software | Minimum |
|---|---|
| C++ compiler | C++20 |
| CMake | 3.22 |
| SQLite and zlib | Development headers |
| Node.js | 20 |
| Python | 3.10. The launcher is a Python program (`./launcher` runs `python3 -m dstns_launcher`) |

About 1 GB of memory and 1 GB of disk is enough for a district of 3,000 junctions. The
default DSTNS port is 8090. Platform notes and sizing are in the
[DSTNS system requirements](https://dstns.readthedocs.io/v3/getting-started/requirements/).

!!! warning "The published DSTNS docs describe a different launcher"
    The DSTNS documentation on Read the Docs (branch `v3`) lists Rust 1.89 for the
    launcher. The tested commit's launcher is Python and its README does not list Rust.
    Whether `v3` runs the launcher steps in these pages was not checked.

!!! note "Not verified"
    `ninja` is not required by the README. Whether the build needs it was not checked.

## Install

```bash
git clone https://github.com/varunkarthic/DSTNS.git
cd DSTNS && git checkout 4b88994    # the commit these pages were tested on
```

Nothing else to do yet. The launcher builds the C++ core and the observer, and starts the
server, the first time you start a run, which takes a few minutes. For more on building,
see the [DSTNS installation guide](https://dstns.readthedocs.io/v3/getting-started/installation/).

## Offline map

DSTNS ships a recorded real district (a part of Berlin, 1,196 junctions) at
`data/fixtures/real_network.osm.xml`. Start with `--osm-file` and no map is downloaded:

```bash
./launcher start --seed 0x2026 --osm-file data/fixtures/real_network.osm.xml
```

Without `--osm-file`, DSTNS picks a real city from the seed and downloads its
OpenStreetMap extract on the first run. DSTNS documents cities it has downloaded before
as working offline; that was not tried here.

## Ports

| Port | What | Change it with |
|---|---|---|
| 8090 | DSTNS server and observer | `DSTNS_API_PORT=<port>` when starting the launcher |

Terminus's own ports are in [Requirements](../../getting-started/requirements.md#ports).
Other DSTNS ports were not checked.
