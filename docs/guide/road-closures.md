# Road closures

Terminus does not close roads. A person closes a road in DSTNS, and Terminus reads
the change and re-prices its lines and journeys.

## Close a road

DSTNS takes a road closure through its control API. Use the id of a directed road
(an edge). Roads are listed in Terminus's `GET /api/world` as `roads[{id, from, to,
closed, ...}]`, and each bus line's roads are drawn in the observer.

```bash
# Close one directed road
curl -s -X PUT http://127.0.0.1:8090/api/v1/control/edges/412 -d '{"closed":true}'

# Undo the last change
curl -s -X POST http://127.0.0.1:8090/api/v1/control/undo -d '{"count":1}'
```

Replace `412` with a road a bus line actually uses; closing a road no line uses
changes nothing. Roads can also close on their own: DSTNS incidents and flooding close
roads during a run.

The control API is described in the
[DSTNS control API](https://dstns.readthedocs.io/v3/api/control-api/).

## What changes

Terminus re-reads road speeds and closures every 2 seconds, because DSTNS sends no
event for a road closed by hand. Within about two seconds:

- **Journeys through the closed road get slower** or take another way. In a test on
  a real run, one journey went from 48 s with the road open to 148 s closed.
- **The portal's search follows.** The portal calls Terminus on every search, so
  `estimatedDurationSeconds` rises too.
- **The observer's arrival times lengthen.**
- **A closed road is excluded from the search.** A journey never routes through it.

The direction matters. A road and its opposite direction are two separate edges.
Closing the one a bus rides changes the journey. Closing only the other direction
of a two-way street changed nothing in testing.

!!! warning "Bus lines are not re-planned"
    The lines are planned once per run. A road that closes later is priced as
    gridlock (0.1 m/s) for the bus that uses it, and the bus cannot go another
    way. A new run re-plans the lines. See
    [Known limitations](../known-limitations.md).

## Check it from the command line

```bash
cd ~/bus-planner/Terminus
./bus journey 78 510     # before closing
# close the road, wait a few seconds
./bus journey 78 510     # after
```

Reopen the road when you are done, with `undo` as above, so the next demonstration
starts from a normal city.

!!! note "Not verified here"
    The junction ids above are the ones used in testing on one run. Junction ids and
    road ids depend on the map and seed; pick your own from `./bus stops` and
    `GET /api/world`.
