# User guide

How to use Terminus. Each page works with any sandbox that Terminus can read; where a
step belongs to one sandbox it is in an [integration](../integrations/index.md).

| Page | Use it to |
|---|---|
| [The ./bus commands](bus-cli.md) | Print the bus network, the busiest stops and a journey; export the network; start the server |
| [Configuration](configuration.md) | Change the budget, the stop gap, the boarding wait, the ports and the output |
| [The local API](../serve-api.md) | Read the planned city, journeys and traces as JSON |
| [The dashboard](dashboard.md) | See the lines, buses and journeys in a browser, and replay how they were built |

## Roles

| Role | Does | Uses |
|---|---|---|
| Operator | Starts and stops runs, sets the clock, closes roads | The sandbox's own tools |
| Analyst or presenter | Reads the network, plans journeys, shows how it was built | `./bus` and the dashboard |
| Integrator | Builds another program on the planner's output | The local API |
