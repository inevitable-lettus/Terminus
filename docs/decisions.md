# Decision log — algorithm module

Every judgment call, what was rejected, and the one-line reason. Ordered roughly
by when it was made. Anything overturned later says so, with what overturned it.

## Integration and language

**Java 21, not C++.** The sandbox is reached over HTTP whatever this is written
in, so matching C++ buys no integration benefit. The graphs are small enough that
the language's speed difference is dwarfed by the network calls, and while
learning the algorithms a Java bug throws at the exact line where the equivalent
C++ bug can silently corrupt memory. Honest concession: C++ would be faster and
at a much larger scale that would matter.

**HTTP REST, not a socket or ONNX.** An early plan compiled the Java module into
an ONNX file for the C++ side to consume. That does not work — ONNX is the Open
*Neural Network* Exchange, a serialization format for tensor dataflow graphs, and
A*'s priority-queue-driven irregular traversal is not expressible as a static
tensor graph. A raw socket protocol was the next candidate, but the sandbox
already exposes a documented HTTP API, so building a second wire protocol would
have been duplicate work.

**Gradle, not Maven.** Terser build file, and the committed wrapper means nobody
on the team installs Gradle themselves.

## Data ingestion

**Model the endpoint actually being fetched, not the one with the nicer example.**
The sandbox serializes the same objects two different ways on two endpoints. The
domain types mirror the flat `/view/topology` shape because that is what the
graph is built from; the nested `/view/edges` shape is modelled separately
because it is the only source of demand.

**Tolerate unknown fields.** The contract belongs to DSTNS, so a field added
later should not break this module. This protects against additions and not
against mistakes — see the build log entry where six fields were missing and
nothing errored.

**Fixtures, not live calls, in tests.** A test that needs another service running
fails when that service is down, fails differently on another machine, and cannot
run offline. Five real responses are saved and every test reads those instead.

## Graph representation

**Compressed sparse row, not an adjacency matrix.** A matrix uses space
proportional to junctions squared — 14400 cells — to record 436 roads. Road
networks are sparse, with two to four neighbours per junction.

**Compressed sparse row, not a list of lists.** `List<Integer>` boxes every
number into a heap object. The graph is built once and never modified, so there
is nothing to gain from a structure that supports insertion.

**Counting sort plus prefix sum, not a comparison sort.** Grouping edges by
source junction has a key that is already a dense integer in a known range, so
bucketing is `O(V + E)` and never compares two elements, against `O(E log E)` for
a comparison sort.

**No hash table, for now.** The original plan interned string junction ids into
array indices. The sandbox's ids are already dense integers from zero, so an
array indexed by id is strictly better. A hash table added to satisfy a checklist
would be a structure with no defensible reason to exist. The OpenStreetMap id is
retained on every node so the hash table can be introduced where it genuinely
earns its place, if a map with sparse 64-bit ids ever arrives.

**Reject a non-dense topology at construction.** Better a loud failure than a
silently corrupted graph, especially since nothing upstream had verified the
assumption.

## Cost model

**Recompute cost from raw speed, do not trust a published weight.** The previous
sandbox published its own `effective_weight_s`; the rewrite removed it. Either
way the module computes its own, which is the part being graded.

**Integer milliseconds, not floating-point seconds.** Two equal-cost paths must
compare exactly equal; floating point can differ in the last bit and make a
tie-break arbitrary.

**Exclude closed roads, do not weight them.** A huge weight is still finite and
gets chosen whenever every alternative is worse — a wrong answer, not a slow one.
Verified live that a closed road still reports a healthy 16.67 m/s, so inferring
closure from speed would have routed straight through it.

**Freeze the cost model per snapshot.** Signal phase alone makes weights
oscillate second to second. A search against a moving weight measures the
weather, not the algorithm.

## Priority queue

**Hand-written indexed binary heap, not `java.util.PriorityQueue`.** The library
queue cannot lower the priority of an item already queued; the workaround pushes
a duplicate and skips the stale copy later, growing the queue from one entry per
junction to one per road. A side array recording each element's current slot
makes the lookup constant time.

**Reject an attempted priority raise rather than accepting it.** A raise needs
sifting the other direction, and quietly sifting the wrong way corrupts the heap
with no error at all.

## Demand and Algorithm 1

**Consume the sandbox's 60 bus stops rather than placing our own.** This narrows
Algorithm 1 from "choose stops *and* routes" to "choose routes over given stops".
That is a real scope reduction and is stated rather than implied; it also buys
determinism, since the same stops every run means reproducible results. What it
costs: no "our placement versus theirs" comparison.

**Demand from nearby building activity, not from the per-node `building_effect`
field.** The obvious model is dead on arrival — all 60 stops report exactly 0.4,
because that field describes the bus-stop role's own activity rather than nearby
buildings. It would have produced 60 identical scores and a route builder
choosing arbitrarily. A test asserts it is flat, so a change in the sandbox
surfaces rather than passing unnoticed.

**Design for 18:00, not the 08:30 morning peak.** *Overturned by measurement.*
08:30 was chosen on the reasoning that school, office and bus-stop windows
overlap there. Sweeping the whole virtual day half-hourly shows every office and
mall completely shut at 08:30, with the real peak at 18:00 at roughly double the
demand and no stop left at zero. Seed fixed at `0x2026`.

**Greedy set cover, not a genetic algorithm, simulated annealing or tabu search.**
The problem is the NP-hard BTRNDP and the literature does use those methods, but
none are in this course's toolkit, and a black box producing better numbers that
cannot be derived is worth less here than a greedy method that can be defended
line by line. Labelled a heuristic with no optimality claim.

**A route budget, not an unbounded loop.** Without one, extending the existing
line is always the cheapest way to cover one more stop, so the loop produces a
single absurd route covering the whole city.

**Break ties on the lower stop id.** Makes the network reproducible across runs;
asserted by a test rather than assumed.

## Walking radii

**Coverage radius 250 m, not the 400 m planning standard.** The standard is right
for a real city. This map is 1980 × 1620 m with the nearest two stops 255 m
apart, so at 400 m every stop covers its diagonal neighbours, one route covers
100% of demand and the algorithm demonstrates nothing — the whole "city" is a
twenty-minute walk end to end. 250 m sits just below the nearest-stop spacing, so
a stop counts as covered only if a bus actually serves it. A concession forced by
the sandbox's map being small, and stated in the code rather than buried.

**Interchange radius 400 m, kept separate from the coverage radius.** They answer
different questions: "is this stop close enough to count as served?" versus "will
a passenger already out of the house walk to another stop to change line?"
Sharing one number breaks concretely — at 250 m the transit graph splits and one
line becomes a trap its passengers cannot leave.

## Algorithm 2

**A transit vertex is a (stop, line) pair, not a stop.** Otherwise the transfer
wait has no edge to live on and the search transfers for free, returning journeys
nobody could make.

**A\* for passenger queries, Dijkstra for network construction.** A passenger
query has one destination, so aiming pays. The route builder needs distances from
a hub to everywhere, so there is nothing to aim at and the guess is pure overhead.

**Manhattan distance, not Euclidean.** *Overturned by measurement.* Euclidean was
settled on the premise that the map is real road layout. It is a perfect
axis-aligned lattice, which makes Manhattan a tighter lower bound that is still
admissible, so it expands fewer vertices. Enforced rather than assumed: every
edge is checked for axis alignment and Manhattan is refused on a map with a
diagonal road.

**`V_MAX` of 16.67 m/s, the true maximum.** The sandbox's own A* hardcodes
33.33 m/s, roughly twice too loose. The tighter value is still admissible and
expands strictly fewer vertices.

**Traffic and weather stay out of the heuristic.** They only ever make the real
journey longer, so folding them in risks pushing the estimate above the truth and
losing the optimality guarantee.

## Door-to-door queries

**Two temporary vertices, not a many-source search.** A passenger at a junction
can board at several nearby stops, each at its own walking cost — a many-source
problem. Seeding A*'s queue with all of them at once works, but it modifies the
search, and every property already proved about A* would have to be re-proved
against the modified version. Splicing in a source vertex joined to each
candidate by a real walk edge reduces the same problem to the plain
single-source case, so the search runs exactly as proved and the walk is paid
rather than assumed away. The target vertex sits at the destination's own
coordinates, which is what keeps the heuristic admissible.

**The spliced graph is a copy, not a mutation.** One query cannot leak into the
next, and the shared transit graph stays immutable and safe to reuse.

**A linear scan for stop lookup, not a k-d tree.** Sixty stops means sixty
distance computations per query. A spatial index is the right answer at
thousands of stops and a structure with no defensible reason to exist at sixty
— the same judgement that dropped the hash table in step 1.5.

**Walking the whole way is a candidate answer, not a graph edge.** It needs no
search — it is one number — and adding an origin-to-destination edge would let
the search "discover" it as though it were a transit option. It is bounded by
the same 400 m radius as every other walk, which is also what keeps
"unreachable" a meaningful answer instead of every query returning a very long
walk.

**The heuristic is chosen by asking the map.** `JourneyPlanner.of` checks
axis-alignment and picks Manhattan or Euclidean accordingly, rather than
hardcoding the grid assumption. The same code then runs on a real road network
without an edit, and the optimality guarantee survives the move.

**Access and egress walks are not transfers.** `TransitPath.transfers()`
previously counted every non-ride move, which would have reported walking from
your front door to the first stop as a change of bus.

## Publishing

> **Superseded by [DSTNS 2.0](#dstns-20).** The endpoint these decisions were
> about is retired, and the lines are written to files. The continuity and
> return-leg decisions survive, applied to the export.

**Continuity is checked locally before anything is sent.** The server rejects a
route whose consecutive nodes are not joined by a single directed edge, but it
does not say which pair. Checking first costs no round trip and names the gap.

**The return leg is verified, not assumed.** It is the outbound path read
backwards, which is only valid when every road on it can be driven the other
way. Checking that the reverse twin *exists* turned out not to be enough: DSTNS
gives one-way streets a reverse twin id that is not a road. The check now asks
the cost model whether the twin is traversable.

**A line that cannot be driven back is published one way, not refused.**
Throwing would lose the outbound line too, which is less true than publishing
what can be driven and saying which lines are one-directional.

## Testing

**Oracles that share no structure with the code under test.**
`java.util.PriorityQueue` for the heap, Floyd–Warshall for shortest paths.
Agreement then means something; two implementations of the same idea would just
reproduce the same bug twice.

**Compare costs, not node sequences.** Two equally optimal paths can legitimately
differ by tie-breaking, and neither structure promises a tie-breaking order.

**Drop the sandbox cross-check rather than port their A\* to Java.** There is no
HTTP endpoint for the sandbox's pathfinder, re-checked at the current HEAD.
Porting their C++ implementation as a "reference" would be this module's own code
checking itself.

## The map change (2026-09-14)

DSTNS `5f75fa5` replaced the synthetic grid with a real OpenStreetMap extract.
These are the calls that change forced.

**Exclude one-way reverses through the cost model, not a second mechanism.**
`CostModel.isTraversable` already kept closed roads out of every search. A
fabricated reverse is a different fact — a closed road exists and may reopen, a
fabricated one never existed — but "no search may use this edge" is the same
instruction, so it reuses the same exclusion. The two-argument `CostModel.of`
still works and excludes nothing, so no caller that predates this changed.

**Recapture the fixtures rather than keep testing the old map.** Keeping them
would have left the offline suite green while describing a map that no longer
exists. Recapturing meant re-deriving every hardcoded figure, and it is why
several tests were rewritten rather than renumbered.

**Tests assert the guard, not the grid.** Tests that named Manhattan outright were
correct only while the map was a lattice. They now ask
`isManhattanAdmissibleOn` which measure applies, so they hold on either kind of
map. Where a test's premise was the grid itself, it was split into the claims
that survive — Manhattan is the tighter bound; it is only valid when axis-aligned.

**Coverage radius 150 m; route length left at 8 minutes.** The closest pair of
stops is 176 m apart, so the old 250 m radius counted stops as covered by buses
that never reached them. Shorter route budgets were tried and rejected: 3, 4 and
8 minutes all give the same two lines covering all demand, and 2 minutes lost
coverage and made journeys slower. The shorter budget bought transfers at the
passenger's expense, which is the wrong trade.

**Assert that changing line is possible, not that a stop is shared.** On this
map the two lines share no stop, so the only interchange is walking between
nearby stops. The old assertion demanded a shared stop; the new one demands that
some link changes line.

**`--attach` reads the running simulation rather than starting one.** The
default resets the simulator, which is right for reproducibility and wrong when
someone is driving the UI. Attaching takes the run's own time and seed, so
`--time` and `--seed` are refused alongside it rather than silently ignored.

**`--attach` reads the running simulation rather than starting one.** *(Now the
default, and no longer needs the flag — see below.)*

## DSTNS 2.0

Decided 2026-09-21 from a reading of `origin/dstns-v2`'s source, **before any live
run against it**. Each of these is a bet placed on the source; the first live run
is what confirms or overturns it. Where a bet is unverifiable it is written to
degrade rather than fail.

**Export to files, not to the simulator.** `POST /control/transit/route` is
retired (410, "not part of the supported API"). Rejected: asking the sandbox track
to restore it — the docs say it is gone on purpose. The output is `routes.json`
(everything the algorithm produced, plus the run it came from) and
`routes.geojson` (drawable anywhere). No timestamps, so the same input gives the
same bytes and a difference means the result changed. The continuity check stays
because it was never really the server's rule; it is what a drivable route is.

**Attach by default; starting a run is explicit and checks the token first.** 2.0
only lets its operator start a run, so the ordinary case is that one is already
going. Rejected: keeping "stop, start, seek" as the default. It cannot work
without the token, and worse, `stop` is not gated while `start` is, so a refused
start had already killed the run somebody was watching. The token is now checked
before anything is stopped, and the one case that cannot be — a wrong token — is
named in the message. `--attach` is still accepted and does nothing.

**Wait for `READY` after a start.** A start is acknowledged before the world
exists, and on 2.0 the world may be a city being downloaded. `IDLE` and `STOPPED`
are waited through rather than accepted, because straight after a start they can
be the state from before it took effect; the timeout is what keeps that from
being an endless wait.

**One world loader for the command line and the live suite.** They each carried a
copy of topology → snapshot → graph → one-way exclusion → demand, which is exactly
how a fix reached one and not the other. Rejected: fixing both copies. A third
change would have missed one again.

**One-way streets: trust the topology when it says, ask `/view/edges` when it
doesn't.** `Edge.syntheticReverse` is a nullable `Boolean` because *absent* and
*false* are different answers: an older server that says nothing must not read as
"every road is legal". Rejected: defaulting to `false`, which would silently
route the wrong way down 139 roads on the older server.

**Merge stops within 60 m.** The topology's `bus_stop` flag is raw OSM plus the
simulator's placed stops, and only the *places* are thinned, so one real stop can
arrive as several flagged junctions. 60 m is the server's own rule for stops on
parallel streets. Rejected: using `/view/stops` as the stop set — it is the
thinned *places*, whose anchors need not be junctions we can route between, and
it does not exist on the older server. The merge is greedy in id order and
deliberately **non-transitive**, so a chain of stops 50 m apart is not collapsed
into one stop hundreds of metres long.

**Demand chain: places, then buildings, then uniform.** Real-city extracts tag
few buildings on road junctions, so the building-based demand can score every stop
zero, and a builder handed zeros skips every hub and returns nothing. Rejected:
buildings only (the probable failure above), and places only (the older server
has none). The chosen source is printed in every report header, because "coverage
94%" means something different on each. The places figure is a relative intensity
(1.0 unmodulated, capped at 3.0), not a passenger count, and every stop starts
from 1.0 — so coverage on `PLACES` is closer to "share of stops, weighted a
little", and that is worth saying if asked.

**Derive the budget from the stops.** The earlier 150 m walk and 8-line cap were
an argument about one 8-stop map. Now the radius is half the median
nearest-neighbour spacing, clamped to 75–400 m, and the line cap is one per twelve
stops, clamped to 3–20. The 75 m floor is not arbitrary: it must exceed the 60 m
merge radius, or a merged-away stop is outside its own representative's reach and
is never counted covered. `RouteBudget.defaults()` stays, because tests pin
numbers to it. Every figure can be overridden and the header says which were.

**Heuristic speed follows the map.** `h` divides by the fastest speed anything
moves at. The constant 16.67 m/s was correct for the road classes DSTNS
configured; a real extract can carry a class configured faster, and dividing by
too small a speed overestimates, which silently costs A* its optimality. It is now
the larger of that constant and the fastest edge present.

**Keep every existing fixture, and prove new behaviour on small hand-built data.**
No fixture was recaptured, because there is no live 2.0 to capture from yet.
Rejected: hand-writing a "2.0 fixture" — it would encode what I *expect* the
server to send, which is the assumption under test.

## The first live 2.0 run (2026-09-25)

Decided after running against a live DSTNS 2.0 (`bc6e517`, built from source,
seed `0x2026`, Dar es Salaam). Each overturns or sharpens a 2026-09-21 bet.

**Demand from the trip generators, each credited to its nearest stop.** The
`bus_stop` places turned out to be 11 stations against 81 flagged stops, so 71
of 72 stops scored the same baseline. The snapshot's `demand` list carries every
generator's live multiplier and radius; the topology's `features` say where each
is. A generator goes to its nearest stop within its radius. Rejected: crediting
every stop in range — a generator 300 m from three stops is one generator, and
counting it three times makes clustered stops look busier than they are.
Rejected: fetching `/view/places` for positions — 17,867 places over nine pages,
when the topology already carries them.

**The return is routed on its own, terminal to terminal.** With the return
modelled as the outbound path read backwards, every line on a real city came out
one-way, because lines follow dual carriageways. Rejected: allowing one-way lines
and leaving it there — a bus network nobody can ride home on is not a network.
Tried and rejected: routing the return back *through the outbound stops in
reverse*. On a real city it loops — the outbound stops sit on one carriageway,
and visiting them backwards means circling the block for each one; R2's return
went round the same block four times, 608 junctions against 254 outbound. The
return now takes the shortest path from the last stop to the first and calls at
the stops on its own side, as a real return does; a rider going back between two
outbound stops walks across to the opposite stop, which the passenger network
already prices.

**A line only reaches for stops it can come back from.** Strongly connected
components of the drivable road graph (Kosaraju, iterative) mark which junctions
can be driven between both ways; 13 of 81 stops sit in one-way pockets at the
district's edge. Rejected: serving them with a one-way line — it would bring back
exactly the lines with no return this change removes. They stay uncovered, and the
header counts them.

**Return rides only where a return service exists.** A reverse ride used to be
offered on any segment whose own roads could be driven back, even on a line with
no return. Rejected: keeping per-segment reversibility as a separate idea from the
line's return — the only reverse bus is the one that runs.

**The A\* divisor covers the fastest link, not just the fastest limit.** 2.0 lets
an operator push a road past its limit, and costs follow the live speed. Taking
the maximum of straight-line distance over link time across the transit graph
makes the guess consistent link by link, by the triangle inequality, rounding
included. Rejected: rounding every edge cost up to absorb the rounding error — it
fixes the milliseconds and not the override.

**Five exit statuses.** Usage 2, simulator unreachable or failing 3, simulator
data unreadable 4, run state 5, internal 1 (`--debug` for the trace). Rejected:
keeping one status per exception family — `IllegalArgumentException` meant both
"you typed it wrong" and "the simulator sent inconsistent data".

**Attach reads a STOPPED run, with a note.** Live, a stopped run keeps its world,
and a seek brings it back to PAUSED. Rejected: refusing it — the state is
readable and sometimes exactly what the user left there on purpose. `ERROR` and
`TERMINATING` are refused; `STOPPING` is waited out.
