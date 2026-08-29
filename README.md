# csc210-algorithm

Algorithm module for CSC210 Project #3 (Smart Bus Route Planning). Builds a
route network from demand data (Algorithm 1: greedy demand-covering) and
answers passenger shortest-path queries over it (Algorithm 2: A*).

Talks to the `csc210-sandbox` simulation over its REST API — reads node/edge
graph data, recomputes edge costs from raw fields, and can publish generated
routes back for visualization.

See `ai-context/` in the sibling `group-08-public-bus-route-planner` repo for
the full build order, architecture decisions, and algorithm writeup.
