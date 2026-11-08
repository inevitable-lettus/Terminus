# Requirements

## Terminus

| Software | Minimum | Used for |
|---|---|---|
| JDK | 21 | Building and running the algorithm module |
| Node.js and npm | Node.js 20 | Building the dashboard (optional) |
| A browser | A current Chrome, Edge, Firefox or Safari | The dashboard (optional) |

Gradle does not need installing: the Gradle wrapper is committed and `./bus` uses it.
The first run downloads Gradle and the Java libraries, so it needs network access once.

`./bus serve` is built on the JDK's own HTTP server, so there is nothing else to install.

## A sandbox

Every command reads a sandbox that is already running. What a sandbox must provide is in
[The sandbox interface](../sandbox/index.md). The only one supported today is DSTNS; its
requirements are in the [DSTNS integration](../integrations/dstns/index.md#requirements).

## Ports

| Port | What | Change it with |
|---|---|---|
| 8080 | The Terminus serve API and dashboard | `./bus serve --port N` |
| 5174 | The dashboard's development server (`npm run dev`) | `npm run dev -- --port N` |

`./bus serve` listens on `127.0.0.1` by default. Pass `--host 0.0.0.0` to listen on every
address, which also lets other machines reach it. The server has no authentication.
Ports used by the integrations are listed on their own pages.
