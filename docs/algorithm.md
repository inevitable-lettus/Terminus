# The algorithm module, explained

> **Map note (2026-09-14).** DSTNS `5f75fa5` replaced the synthetic 12 x 10 grid
> (120 junctions, 436 roads, Paris coordinates) with a real OpenStreetMap extract
> of Berlin Mitte (279 junctions, 612 roads, 8 stops). Figures below have been
> updated to the new map where they describe current behaviour. Where a passage
> records what was learned on the grid, it says so. The build log has the
> [full account](build-log.md#the-map-changed-underneath-the-module-2026-09-14).


Every piece of this module, in plain language first and jargon second, with the
numbers each part actually produces. Technical terms are defined where they first
appear rather than in a glossary at the end.

Companion documents: [DESIGN.md](../DESIGN.md) for the design and its reasoning,
[decisions.md](decisions.md) for every judgment call with its rejected
alternative, [build-log.md](build-log.md) for the step-by-step record,
[limitations.md](limitations.md) for what is weak and what is missing,
[sandbox-api.md](sandbox-api.md) for the contract this module consumes.

---

## 1. Two algorithms, not one

**Algorithm 1, the route network builder.** Given a city and a picture of where
people want to travel, decide *what bus lines should exist* — which stops each
line connects, in what order. A planning question, answered once.

**Algorithm 2, the passenger pathfinder.** Given the lines that now exist, and
one passenger who wants to get from A to B, find *the fastest journey*, including
which buses to take and where to change. A query question, answered thousands of
times.

They are separate because they ask different shapes of question. Algorithm 1
needs travel time from one hub to *everywhere*, so it can compare candidate
stops. Algorithm 2 needs travel time from one place to *one other place*, so it
can aim. That is exactly the difference between Dijkstra's algorithm and A*,
which is why one uses each. Merging them would mean re-planning the entire
network every time somebody asks for directions.

A **graph** here means the mathematical object: points called **nodes** (road
junctions) joined by connections called **edges** (stretches of road). An edge is
**directed** if it works one way only. This road network stores every physical
street as two directed edges pointing opposite ways, which the sandbox calls
**reverse twins**.

---

## 2. Getting the data in

The module simulates nothing itself. It reads a city from the sandbox over HTTP
and turns the responses into typed Java objects. Four stages.

**HTTP.** One reusable client object, because a client holds a pool of open
network connections and discarding that per call is waste.

**Deserialization** — turning received text into objects. The sandbox answers in
**JSON**, a text format for structured data. Two policies are set once, globally,
rather than per field: names convert from the sandbox's `free_speed_mps` style to
Java's `freeSpeedMps`, and **unknown fields are ignored rather than rejected**,
because the contract belongs to the other track and a field added next week
should not break this module.

**Typed records.** `Node`, `Edge`, `Position`, `EdgeState` and the rest are Java
**records** — immutable data holders where the compiler writes the constructor,
accessors, equality and printing. They mirror the sandbox's shape exactly rather
than "improving" it, so a mismatch is visible at this layer instead of being
smeared through the code.

**Fixtures.** A **fixture** is a saved real response, committed to the repository
and used by tests instead of the live service. Tests that depend on a running
external service fail when it is down, fail differently on another machine, and
cannot run offline. All 125 tests pass with the simulator stopped, verified by
stopping it.

**A real bug this caught.** The first domain types were written from the
sandbox's documentation before its container would start. When it finally ran,
`Node` was missing six fields and had one under the wrong name, and `Edge` had
four fields that do not exist on that endpoint. None of it *errored* — tolerating
unknown fields meant wrong data arrived silently as `null` and zero. Tolerance
protects against fields being added. It does not protect against modelling the
wrong thing.

---

## 3. The graph structure

`Graph` holds the road network in **compressed sparse row** (CSR) form. The name
is jargon for a simple idea: instead of giving every junction its own list of
outgoing roads, put *all* the road ids in one flat array, grouped so each
junction's roads sit in a contiguous run, and keep a second array recording where
each run begins.

**Adjacency list versus adjacency matrix.** An **adjacency matrix** is a grid
with a row and column per junction and a mark where a road joins them. It uses
space proportional to junctions squared — 77841 cells here — to record 612 roads.
An **adjacency list** stores only the roads that exist, so it uses space
proportional to the number of roads. Road networks are **sparse**: a junction has
two to four neighbours, never a hundred. The list wins by a factor of over a
hundred on this map.

CSR goes one step further than a list of lists: one array, not 279 small ones.
Java's `List<Integer>` **boxes** every number, wrapping each `int` in a heap
object, and 279 objects holding 612 boxed integers is slower to walk and heavier
than two plain `int[]`. The graph is built once and never modified, so nothing is
lost by using a structure that does not support insertion.

**Counting sort and prefix sum.** Building CSR means grouping edges by their
source junction. A comparison sort would cost `O(E log E)`. But the sort key is a
junction id, and junction ids are already dense integers from 0 to 278 — a small,
known range. That permits **counting sort**: one pass counting how many roads
leave each junction, a **prefix sum** turning counts into starting offsets
(junction 5's run begins where junctions 0 to 4's runs end), and one more pass
dropping each road into its slot. `O(V + E)`, linear, with no comparisons at all.

**Why there is no hash table.** A **hash table** maps arbitrary keys to values in
roughly constant time. The original plan had one interning string junction names
into array indices. The sandbox's junction ids are *already* dense integers from
zero, so an array indexed directly by id is strictly better — faster and simpler.
Rather than include a hash table that does nothing so it can appear on a
checklist, it was dropped with the reason recorded. Each node keeps its original
OpenStreetMap id, so if a future map arrives with sparse 64-bit ids the hash table
goes in there and earns its place.

`Graph.of` refuses to build when ids are not dense and 0-indexed, rather than
silently constructing a corrupted structure; nothing upstream had checked that
assumption. The tests also assert the sandbox's **reverse-twin invariant** — for
every edge `i`, the reverse twin of its reverse twin is `i` — across all 612
edges. That invariant holds even for the 139 fabricated reverses of one-way
streets, which is precisely why it cannot be used to tell them apart; see the
[sandbox contract](sandbox-api.md#one-way-streets-are-invisible-on-viewtopology). An **invariant** is something that must hold at all times; asserting it
means a violation surfaces as a failing test rather than as a wrong route six
steps later.

---

## 4. The cost model

Every edge gets a number: milliseconds to drive it.

```
cost_ms(e) = round( length_m(e) / max(0.1, effective_speed_mps(e)) × 1000 )
```

`effective_speed_mps` is what the simulator says a vehicle actually achieves right
now, after traffic, rain, flooding, signal phase and any operator override. It is
not the speed limit.

**Why cost lives on edges, not nodes.** That is where the physical quantity is. A
road has a length and a speed. A crossroads does not.

**Why the multipliers are multiplicative.** Congestion slows a road in proportion
to its length. A flat additive penalty of thirty seconds would punish a 50 m side
street exactly as hard as a 5 km motorway.

**Why the clamps exist.** The `max(0.1, …)` prevents a division by zero on a
completely blocked road, turning it into a very slow road — 180 m at 0.1 m/s,
about half an hour — rather than infinity. Rounding to integer milliseconds keeps
costs exact, so two equal-cost paths compare exactly equal where floating point
might differ in the last bit.

**Why a closed road is removed rather than made expensive.** A huge weight is
still finite, so whenever every alternative is worse it gets chosen — a wrong
answer, not a slow one. `isTraversable` returns false and asking for the cost
throws. This is not theoretical: closing edge 42 through the control endpoint
produced a snapshot reporting `closed: true` **while still reporting
`effective_speed_mps: 16.67`**. The simulator publishes a perfectly healthy speed
for a road nobody may drive on, so anything that inferred closure from speed would
have routed straight through it.

**Why the model is frozen.** A cost model is built once from one snapshot and
never updated. The simulator re-derives every road's speed each tick, and signal
phase alone makes weights oscillate second to second. A search running against a
moving weight measures the weather, not the algorithm.

**Honest limitation.** The previous sandbox published its own computed weight per
edge and the plan was to check ours against it on every edge. The rewrite removed
that field. What replaced it is weaker: an invariant that must hold (no edge
exceeds its own speed limit, checked on all 612), three costs computed by hand and
matched exactly, the clamp checked, and the closure rule checked.

**Edges that are not roads.** A second kind of exclusion joined closure when the
map became real. DSTNS publishes a reverse direction for every street, one-way or
not, and marks the fabricated ones only on `/view/edges`. Those 139 ids are passed
to `CostModel.of` and excluded exactly as a closed road is. The reason differs —
a closed road exists and may reopen, a fabricated one never existed — but the
instruction to the search is the same.

---

## 5. The heap

Dijkstra repeatedly asks "which unfinished junction is currently cheapest?" A
**priority queue** answers that: a bag you put things into in any order and always
pull the smallest out of.

`IndexedMinHeap` implements one as a **binary heap**. The **heap property** is the
single rule it maintains: every element is smaller than or equal to both of its
children. Note what it does *not* say — nothing about left versus right, nothing
about elements that are not parent and child. That looseness is the point: a
change only ever fixes one path from root to leaf, so inserting and removing both
cost `O(log n)`.

**The array trick.** The tree is *complete* — every level full except possibly the
last, which fills left to right — and a complete binary tree fits in a flat array
with no pointers at all. The element at index `i` has its parent at `(i - 1) / 2`
and children at `2i + 1` and `2i + 2`. No node objects, no `left` and `right`
fields, just arithmetic on indices.

**Why not `java.util.PriorityQueue`.** Dijkstra repeatedly *lowers* the tentative
distance of a junction already sitting in the queue. The library queue cannot
express that: it has no "this item just got cheaper" operation, and removing an
item to re-add it is an `O(n)` linear scan. The standard workaround is **lazy
deletion** — push a second copy at the better priority and ignore the stale one
when it surfaces — which grows the queue from one entry per junction to one per
road, `O(V)` to `O(E)`, and needs an "already finished?" check on every removal.

`decreaseKey` avoids that with a third array: `position[e]` records where element
`e` currently sits. Finding it is one lookup instead of a scan, and only the
sifting remains. Three arrays, all indexed by a dense integer: `heap` (slot to
element), `position` (element to slot), `priority` (element to its priority).

Two deliberate refusals. `decreaseKey` **rejects an attempted raise** rather than
accepting it, because a raise needs sifting the other direction and quietly
sifting the wrong way corrupts the heap with no error. Inserting an element twice
is rejected for the same reason: it would break the position index.

**How it is proved.** 2000 randomised trials push identical operation sequences
through this heap and `java.util.PriorityQueue` and assert identical pop order,
plus 500 more exercising `decreaseKey`, mirrored in the library queue by
remove-and-reinsert.

**The test that was wrong first.** Its first version let lowered priorities
collide with priorities already in the heap. The two structures then disagreed —
not from a bug, but because "which element is smallest" was ambiguous and neither
structure promises a tie-breaking order. Fixed by keeping every priority distinct.
The same point recurs later as "compare costs, not routes".

---

## 6. Dijkstra

`Dijkstra.from(source)` computes the cheapest travel time from one junction to
every junction it can reach.

**Relaxation** is the whole algorithm, repeated. Standing at junction `u`, for
each road `u → v`: is going this way cheaper than the best route to `v` found so
far? If yes, write down the cheaper number and remember the road it came in on.
Everything starts at "unreachable" and only ever gets cheaper.

**The greedy invariant** makes it fast rather than merely correct: *the moment a
junction is removed from the queue, its distance is final and it is never
reconsidered.* The argument is short. The queue always yields the cheapest
unfinished junction. Any other route to it would have to pass through some other
unfinished junction, which already costs at least as much, and then pay for
further roads on top.

**Where non-negative weights are load-bearing.** That last clause — "and then pay
for further roads on top" — only makes a route *worse* if road costs cannot be
negative. With a negative edge, a longer detour could come back cheaper and
undercut a junction already declared final; the invariant collapses and so does
the algorithm. These costs are travel times, non-negative by construction, and
the speed clamp keeps them strictly positive. Bellman–Ford is what handles the
negative case.

**Complexity, derived rather than quoted.** Each junction is inserted at most once
and removed at most once, each `O(log V)`, giving `O(V log V)`. Each road is
examined exactly once, from its source junction, and may trigger one `decreaseKey`
at `O(log V)`, giving `O(E log V)`. Together **`O((V + E) log V)`**. On a road
network `E` is a small multiple of `V` — 612 over 279 here, average out-degree
about 2.2 — so in practice it behaves like `O(V log V)`.

**Path reconstruction.** Storing one predecessor per junction is enough to recover
every route, because a shortest-path tree gives each junction exactly one parent.
If two routes tied, whichever settled first was kept, and both are equally
optimal. The predecessor is stored rather than the path itself because paths share
nearly all of their length; writing each out separately would copy that shared
structure repeatedly, turning an `O(V)` result into `O(V²)`. Rebuilding walks
predecessors backward from the destination and reverses, since the links only
point that way.

**How it is proved, three ways.** A five-junction graph worked out on paper,
including the case where the direct route (cost 4) loses to the two-hop route
(cost 3). Agreement with a **Floyd–Warshall** brute force over 200 random graphs,
every source and every target — chosen as the oracle precisely because it shares
no structure with Dijkstra, so agreement is evidence rather than the same bug
twice. And on the real 279-junction network, every reconstructed route re-summed
road by road against its reported cost.

---

## 7. Algorithm 1 — the route network builder

**The problem.** Choose a set of bus lines over the 8 stops so that as much
travel demand as possible is within reach, without any line being absurdly long.

**Where demand comes from.** Nobody counted passengers. The simulator gives each
building a static strength, a radius over which it is felt, and a live activity
level between 0 and 1 that rises and falls with hardcoded time-of-day windows. A
stop's demand is

```
demand(stop) = Σ over buildings b within radius(b) of  impact(b) × effect(b)
```

with distance measured straight-line in projected metres, because it stands in for
a passenger *walking* to a stop rather than a vehicle driving to one. This is a
**proxy**, and a synthetic one.

**Two assumptions that did not survive contact with the sandbox.**

First, there is a per-junction field called `building_effect` that looks exactly
like the demand signal you want. It is not. All 8 stops report **exactly 0.4** —
as all 60 did on the grid before it —
because it describes the activity of the *bus-stop role itself*, not of nearby
buildings. A test now asserts it is flat, so a change surfaces.

Second, the design was going to target the 08:30 morning peak. Sweeping the whole
simulated day at half-hourly steps says otherwise — every office and mall is
completely shut at 08:30, and the real peak is 18:00 at roughly double the demand
with no stop left at zero. The full table is in the [build log](build-log.md).

**Why greedy, and what that costs.** Choosing routes to cover demand is the **Bus
Transit Route Network Design Problem** (BTRNDP), and it is **NP-hard** — no known
method finds the provably best answer without, in the worst case, effectively
trying all of them, and the number of candidate networks grows far faster than the
map does. The literature attacks it with genetic algorithms, simulated annealing
and tabu search (Fan & Machemehl, 2004). None are in this course's toolkit, and
submitting one would mean submitting a black box that cannot be derived. So this
is a **greedy heuristic**: at every decision it takes whatever looks best right
now and never reconsiders. It claims **no optimality** and carries no bound saying
the result is within any factor of the best possible network.

**The shape underneath is set cover.** **Set cover** is the classic problem of
choosing a few sets whose union covers everything. Its classic greedy rule is to
repeatedly take the set with the best ratio of newly covered weight to cost —
exactly the rule used here to extend a route: *demand newly reached, divided by
extra driving time*.

**The loop.**

1. Take the uncovered stop with the highest demand. That is the hub.
2. Run Dijkstra from the current end of the line. Score every reachable,
   still-uncovered stop by newly covered demand per extra millisecond, and extend
   to the best one. Repeat until the time budget is spent or nothing new would be
   covered.
3. Mark every stop the finished line serves as covered, plus every stop within
   walking distance of one.
4. Stop when the demand target is met, the route ceiling is hit, or no uncovered
   demand is reachable.

Ties break on the lower stop id, so the same snapshot always produces the same
network — asserted by a test.

**Why the budget exists.** Without one the loop degenerates: at every step the
cheapest way to cover one more stop is to extend the line you already have, so it
keeps extending and produces one absurd route visiting the whole city. Sweeping
on the grid the budget moved the route count monotonically, from 2 routes at 25
minutes to 7 at 4 minutes, which was the evidence that it binds. On the current
map it does not bind: every budget from 4 to 25 minutes gives the same 2 routes,
because 8 stops within a kilometre do not need long lines. That is recorded rather
than tuned away — shortening the budget to force more lines was tried, and made
journeys slower. Table in the
[build log](build-log.md).

**The walking radius, argued rather than defaulted.** The planning rule of thumb
is 400 m, about five minutes on foot, and that is what a real city would use. This
map is 901 × 947 m with the nearest two stops 176 m apart. Above that spacing a
stop counts as covered by a bus that never goes there, and one route sweeps the
whole map. The default is 150 m, below the nearest-stop spacing, so a stop counts
as covered only if a bus actually serves it. (On the grid, with stops 255 m apart,
the same argument gave 250 m.) A concession to the map's size,
stated in the code rather than buried: at 400 m the coverage numbers look better
and mean less.

**The result at 18:00** — 8 minutes per route, 150 m walk, 90% target:

| Line | Stops | Driving time | Demand covered |
|---|---|---|---|
| R0 | 6 | 2.7 min | 16.77 |
| R1 | 2 | 0.9 min | 5.64 |

Two routes, **100% of total demand covered** (22.41). On the grid the same
builder produced four routes covering 94.8%.

**A constraint designed in from the start.** Each route carries its *full
junction-by-junction traversal*, not just its stops, because the sandbox's
route-publishing endpoint validates that consecutive junctions are joined by a
single directed edge and does **not** fill in the path between stops. A route
listing only stops is rejected at the first gap.

**A test that was wrong.** "No node covered twice" was first implemented as "no
two routes may share a stop" — the opposite of what is wanted, since a shared stop
is exactly where a passenger changes buses. Rewritten to assert what the rule
protects: no stop's demand counted twice toward coverage.

---

## 8. Algorithm 2 — the passenger pathfinder

**Why it cannot run on the road graph.** A shortest path over roads answers "how
do I drive there". A passenger cannot drive — they can only ride a line that runs
and get off where it stops. More importantly, changing buses costs a wait, and the
road graph has nowhere to put that cost, so the search would "transfer" ten times
for free and report a journey nobody could make.

**The trick that makes transfers cost something.** A vertex in `TransitGraph` is
not a stop, it is a **(stop, line) pair**: "standing at stop 27 on line R2". Riding
one stop further along the same line is one edge. Getting off R2 at stop 27 and
onto R0 at stop 27 is a *different* edge, priced at the wait, and it is the only
way to move between lines. Because the cost sits on a real edge the search cannot
dodge it, and a two-change journey is compared against a slower direct one on the
numbers rather than by a hand-written rule.

| Kind | What it is | What it costs |
|---|---|---|
| Ride | Staying on one bus to the next stop on its line | What driving that stretch actually costs at 18:00 |
| Transfer | Changing line at the same stop | A flat 5-minute wait |
| Walk | Walking to a nearby stop, then waiting there | Distance ÷ 1.4 m/s, plus the wait |

At 18:00 the passenger network is **8 vertices, 39 links over 8 stops**. No stop
is served by more than one line; the two lines connect only through 10 walking
links between nearby stops. (On the grid: 61 vertices, 568 links, 7 shared stops.)

**Two walking radii, and why they are different numbers.** The route builder's
150 m asks "is this stop close enough to count as served?". The transit graph's
400 m asks "will a passenger already out of the house walk to another stop to
change line?". Using one number breaks concretely. On the grid, at 250 m, one line
shared no stop with the others and 16 of 61 vertices became unreachable. On the
current map it is starker: the lines share no stop at all, so walking between
stops is the only way to change line, and the coverage radius would remove it.
A test asserts that some link changes line.

**A\* in one paragraph.** A* is Dijkstra plus one idea. Dijkstra always explores
whatever is cheapest *so far*, spreading outward in every direction including
straight away from the destination. A* orders its queue by

```
f = g + h
```

where `g` is what the journey has genuinely cost so far and `h` is an optimistic
guess of what is left — "money already spent, plus the best case for the rest".
Vertices pointing toward the destination get a small `h` and are examined first.

**How A\* degenerates into Dijkstra.** Set `h = 0` everywhere and the ordering
becomes `f = g`, which *is* Dijkstra — not similar, identical. That is why the two
return identical costs: A* is Dijkstra with a better queue order, and the guess
only changes what is looked at first, never what is optimal. Asserted by a test.

**Why A\* here and Dijkstra there.** A passenger query has one destination, so
aiming pays. Algorithm 1 needs distances from a hub to *everywhere*, so there is
nothing to aim at and the guess would be pure overhead.

**The heuristic.**

```
h(v) = distance(v, goal) / 16.67 m/s
```

Distance in projected metres, never latitude and longitude — degrees are not a
length and dividing them by a speed is meaningless. 16.67 m/s is the fastest
anything moves on this map.

**Admissible** means the guess never overestimates the true remaining cost. That
is what A* needs for its guarantee: a guess that is ever too high could make A*
dismiss the route that was actually best. It holds by construction — the numerator
is the least ground any route could cover, the denominator the fastest anything
travels.

**Consistent** is the stronger, edge-by-edge version: crossing any one edge never
lets the guess drop by more than that edge costs. It follows from the triangle
inequality, and it is what lets A* settle each vertex once and never revisit it,
exactly as Dijkstra does.

**The invariant not to break: traffic and weather live only in `g`, never in
`h`.** Congestion, rain and flooding all make the real remaining journey *longer*.
Folding them into the guess would push it above the true remaining cost somewhere,
admissibility would be gone, and A* could return a path that is simply not the
cheapest. The heuristic is therefore built from geometry alone, which does not
change during a run.

**Manhattan versus Euclidean.** **Manhattan distance** — `|dx| + |dy|`, the
distance you walk on a city grid — is a *tighter* lower bound than the straight
line whenever every edge runs due north-south or east-west, because a route made
only of axis-aligned moves must cover the full `|dx| + |dy|` and cannot cut the
corner. A tighter admissible guess expands fewer vertices. Both are implemented,
and the planner uses Manhattan wherever it is legal.

**The precondition is enforced, not assumed.** Manhattan is admissible *only* when
the edges are axis-aligned. The code walks every edge checking each, and throws if
Manhattan is requested on a map with a diagonal edge. This was written for a map
that turned out to be a perfect lattice, with the note that a real road map would
produce a refusal rather than a wrong answer. Then the sandbox loaded one. None of
its 612 edges is axis-aligned, the planner fell back to Euclidean on its own, and
the only things that broke were tests that had named Manhattan outright — which
now ask the guard instead.

One subtlety: a walk link cuts diagonally between two stops, so Manhattan
overestimates the *distance* there. It still cannot overestimate the *time*, which
is what `h` returns — Manhattan is at most √2 times the straight line, so the guess
is at most `d / 11.8` seconds, while walking that same `d` at 1.4 m/s takes
`d / 1.4` seconds and then adds a wait.

**The results**, over every ordered pair of the 8 vertices at 18:00, Euclidean:

| Measure | Value |
|---|---|
| Journeys compared | 56 |
| Cost disagreements with Dijkstra | **0** |
| Dijkstra vertex expansions | 280 |
| A* (Euclidean) expansions | 261 |
| Reduction | **6.8% fewer** |

On the grid, with Manhattan over 61 vertices: 3660 journeys, 0 disagreements,
115290 against 96956 expansions, **15.9% fewer**. The smaller saving now has two
causes — a looser bound, and a graph so small there is little to prune.

**Worth reporting.** The sandbox's own A* hardcodes 33.33 m/s, roughly twice too
loose. This one at the true 16.67 is strictly tighter and still admissible, so it
expands fewer vertices than theirs would on the same graph.

---

## 9. Door to door — asking the question a passenger would ask

Everything above answers queries between **transit vertices**: internal indices
meaning "stop 27, on line R2". That is the right currency for the search and
useless as an interface. A real query is "from this junction to that junction",
and the walks at either end are part of the journey, not preamble.

### Finding the stops you could walk to

`StopAccessIndex` answers one question: standing at junction X, which served
stops are within walking distance, and how long is each walk? Straight-line
distance in projected metres, divided by 1.4 m/s.

It is a **linear scan** over the roughly sixty served stops — `O(S)` per query.
That is a decision, not an oversight. A k-d tree or a grid index would make this
`O(log S)` or better and is the right answer at thousands of stops; at sixty it
would be a data structure with no defensible reason to exist. If the map grows,
this is the one class that has to change.

Walking distance is straight-line rather than along roads. A passenger walking
200 m does not follow the carriageway, and computing a road path to every
candidate stop would cost far more than the answer is worth at this radius.

### Access and egress, without touching the search

A passenger at a junction can board at any of several nearby stops, on any line
serving them, each at its own walking cost. That is a **many-source** problem,
and there were two ways to handle it.

**Rejected:** seed A*'s open set with all those vertices at once, each
pre-loaded with its walk cost. It works. But it changes the search itself, and
every property already proved about A* — admissibility, settling each vertex
once, the first pop of the goal being final — would have to be re-proved against
the modified version.

**Chosen:** add a temporary **source vertex** and **target vertex** to the
transit graph for the duration of one query. The source connects by walk links
to every (stop, line) vertex reachable from the origin; every vertex near the
destination connects to the target the same way. A* then runs unmodified,
single-source to single-target, exactly as proved. The walk is a real edge, so
it is paid rather than ignored — the same argument that made transfers work.

The graph is **copied**, not mutated, so one query cannot leak into the next.

**Admissibility had to be re-checked, not assumed.** The target sits at the
destination's own coordinates, so `h` measured toward it is still a
straight-line lower bound; and the walk links are slow enough — 1.4 m/s against
the 16.67 m/s the guess divides by — that the estimate stays well under the
truth. This is asserted at every vertex of the extended graph, both new ones
included, against costs Dijkstra computed exactly.

### Choosing the heuristic by asking the map

`JourneyPlanner.of` checks whether every road is axis-aligned and picks
Manhattan if so, Euclidean if not. The grid assumption is therefore never
hardcoded: the same code runs on the sandbox's lattice and on real road geometry
without an edit, and the optimality guarantee survives the move. On a real map
Manhattan refuses itself, which is the step 5.2 guard doing its job.

### The answer, in a form somebody can follow

`Itinerary` collapses the vertex path into legs: walk to the stop, board a line,
ride *n* stops, change, ride, walk to the destination. Consecutive rides on one
line become a single leg, because staying on one bus for six stops is six edges
to the algorithm and **one instruction** to a rider. Each leg carries its own
duration, so the parts sum to the total.

| Leg | Meaning |
|---|---|
| `ACCESS` | Walk from where you are to the first stop |
| `RIDE` | Stay on one line for *n* stops |
| `TRANSFER` | Change line at the same stop, paying the wait |
| `WALK` | Walk to a nearby stop and wait for a line there |
| `EGRESS` | Walk from the last stop to where you are going |
| `WALK_DIRECT` | Walk the whole way, because no bus beats it |

Walking the whole way is compared as a **candidate answer** rather than wired in
as a graph edge: it needs no search, it is one number, and an
origin-to-destination edge would let the search treat it as a transit option. It
is bounded by the same 400 m radius as every other walk, which is also what
keeps "there is no journey" a meaningful answer instead of every query returning
a very long walk.

**A limitation this exposed.** Boarding the first bus is free in this model — no
initial wait — so a one-block ride can beat a one-block walk. What the planner
does guarantee is that an answer is never *worse* than walking when walking is
possible.

---

## 10. Publishing routes back *(retired in DSTNS 2.0 — now export to files)*

> DSTNS 2.0 answers `POST /control/transit/route` with 410. `RoutePublisher` became
> `RouteExporter` (`src/main/java/csc210/algorithm/export/`), which runs the same
> continuity and return-leg checks and writes `routes.json` and `routes.geojson`
> instead. The section below describes the original design.

`POST /control/transit/route` validates that every consecutive pair of nodes is
joined by a single directed edge and does **not** fill gaps. A route listing only
its stops is rejected at the first gap, which is why `BusRoute.nodes()` has
carried the full junction-by-junction traversal since it was first built.

The same check runs locally first, in `RoutePublisher`. Being told which pair of
nodes has no edge between them is more useful than being told "invalid route"
after a round trip.

Each line is published **twice**, outbound and back, at bus ids `2n` and `2n+1`.
The return leg is the outbound path read backwards, which is valid only because
every road on this map has a reverse twin — a property of this map rather than a
law, so it is checked and fails loudly on a map with one-way streets.

---

## 11. Where everything lives

| Concept | Location |
|---|---|
| Domain records | `src/main/java/csc210/algorithm/model/` |
| HTTP client and JSON | `src/main/java/csc210/algorithm/client/` |
| CSR graph, counting sort | `src/main/java/csc210/algorithm/graph/Graph.java` |
| Cost model | `src/main/java/csc210/algorithm/cost/CostModel.java` |
| Indexed binary heap | `src/main/java/csc210/algorithm/ds/IndexedMinHeap.java` |
| Dijkstra, path reconstruction | `src/main/java/csc210/algorithm/search/Dijkstra.java`, `ShortestPathTree.java` |
| A*, heuristic | `src/main/java/csc210/algorithm/search/AStar.java`, `TransitHeuristic.java` |
| Demand model | `src/main/java/csc210/algorithm/demand/DemandModel.java` |
| Algorithm 1 | `src/main/java/csc210/algorithm/routing/` |
| Passenger network | `src/main/java/csc210/algorithm/transit/` |
| Door-to-door queries, itineraries | `src/main/java/csc210/algorithm/journey/` |
| Exporting routes to files | `src/main/java/csc210/algorithm/export/` |
| Command line | `src/main/java/csc210/algorithm/Main.java` |
| Saved sandbox responses | `src/test/resources/fixtures/` |

Run everything with `./gradlew test`. What each suite checks, and why it is
structured that way, is in [testing.md](testing.md). To run the module rather
than its tests, see the "Using it" section of the [README](../README.md).
