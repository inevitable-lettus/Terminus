# Getting started

Terminus is a Java program run through the `./bus` command. Installing it takes a JDK
and one command. **Using** it needs a running sandbox, because every command reads a
road map and live speeds from one.

1. [Requirements](requirements.md): JDK 21, and Node.js if you want the dashboard.
2. [Installation](installation.md): get the code and check it with the offline tests.
3. [Quick start](quick-start.md): the first commands against a sandbox.

The only sandbox supported today is DSTNS. To run the whole system with it, including
the ticketing portal, follow the
[DSTNS quick start](../integrations/dstns/quick-start.md).

!!! tip "Nothing running?"
    `./gradlew test` runs 313 tests against saved sandbox responses and needs no
    sandbox, no network and no simulator.
