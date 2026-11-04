# Getting started

Running the whole system means starting three programs. The order matters, because
each one reads from the one before it.

1. **DSTNS** starts first, through its launcher. The launcher also starts Terminus
   and waits until it is ready, then starts the simulation.
2. **Terminus** runs as a Fabric application under that launcher. You do not start
   it by hand in this setup.
3. **The portal** (database, API and web app) starts last and talks to Terminus.

Then:

1. [Requirements](requirements.md): what to install first.
2. [Installation](installation.md): get and build each part once.
3. [Quick start](quick-start.md): the commands to run all three together and
   what you should see.

!!! tip "No internet?"
    DSTNS ships a recorded real district (a part of Berlin, about 1,200 junctions).
    The quick start uses it, so nothing is downloaded and the first start does not
    depend on the map servers.
