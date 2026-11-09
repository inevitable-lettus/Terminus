# Integrations

Terminus works with other programs in two directions.

| Direction | Program | Page group |
|---|---|---|
| Terminus **reads** a sandbox | DSTNS, the traffic simulator supported today | [DSTNS](dstns/index.md) |
| Another program **reads** Terminus | The ticketing portal, through the serve API | [Ticketing portal](portal/index.md) |

Everything on this page group is specific to one program. Generic material is in the
[Concepts](../concepts/index.md), the [sandbox interface](../sandbox/index.md) and the
[User guide](../guide/index.md).

## DSTNS

A deterministic, seeded traffic simulator with a browser observer. It is the only
sandbox Terminus reads today, because Terminus's core still uses DSTNS's data types (see
[The sandbox interface](../sandbox/index.md)). This group covers installing it, starting
Terminus under its launcher, the drawing Terminus adds to its observer, closing roads,
the offline map and DSTNS-specific troubleshooting.

## Ticketing portal

A passenger and conductor web application with a PostgreSQL database. It is one consumer
of the serve API: it copies stops, routes and trips from Terminus and asks it for a
journey on every search. Another program could do the same with the
[same API](../serve-api.md).
