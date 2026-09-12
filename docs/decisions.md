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

**Continuity is checked locally before anything is sent.** The server rejects a
route whose consecutive nodes are not joined by a single directed edge, but it
does not say which pair. Checking first costs no round trip and names the gap.

**The return leg is verified, not assumed.** It is the outbound path read
backwards, which is only valid because every road on this map has a reverse
twin. That is a property of this map rather than a law, so it fails loudly on a
map with one-way streets instead of publishing a route no bus could drive.

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
