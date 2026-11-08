# Concepts

The ideas behind Terminus, in the order a question travels through it. None of them
depends on a particular sandbox.

| Page | Answers |
|---|---|
| [The road network](road-network.md) | What a map is to Terminus, how a road's cost is computed, and how closed and one-way roads are treated |
| [Demand and stops](demand-and-stops.md) | Where stops come from, how gaps are filled, and where demand comes from |
| [Lines and the builder](lines-and-journeys.md#lines) | How Algorithm 1 chooses lines and when it stops |
| [Journeys](lines-and-journeys.md#journeys) | How Algorithm 2 prices walks, rides and transfers |
| [Live refresh](live-refresh.md) | What is planned once and what stays current |

## Two algorithms, run at different times

| | Algorithm 1: route builder | Algorithm 2: journey planner |
|---|---|---|
| Question | Which bus lines should exist, and where? | What is the best way for one passenger to get from A to B? |
| Runs | Once per run (or per forced re-plan) | On every query |
| Input graph | The raw road network | The transit graph built from Algorithm 1's lines |
| Technique | Greedy demand-covering construction, with Dijkstra as a one-to-many subroutine | A\* search, single source to single target |

**Why greedy.** Designing an optimal bus network is the bus transit route network
design problem, which is NP-hard and usually attacked with genetic algorithms,
simulated annealing or tabu search. The builder is a greedy heuristic with no
optimality claim. The reports say which limit ended the search.

The full reasoning is in [Algorithms](../algorithm.md) and the
[Decisions log](../decisions.md).
