# The dashboard

The dashboard draws what the two algorithms produce, in the style of the DSTNS
operator interface:

- the bus lines Algorithm 1 built, with buses running round them;
- stops sized by the demand they draw;
- a journey from Algorithm 2 with its rides and walks;
- two replays: the greedy builder choosing a hub and then extensions, and A\*
  against Dijkstra settling stops on the same question.

## Open it

**With the simulator running.** Build it once, then serve:

```bash
cd dashboard && npm install && npm run build   # once
cd .. && ./bus serve --time 18:00:00
```

Open <http://127.0.0.1:8080>. Without `--time`, `./bus serve` reads the run where its
clock is.

Under the DSTNS launcher the same server runs with `--fabric`, on the port in the
catalogue entry (8080 in the [Quick start](../getting-started/quick-start.md)). Serving the built dashboard under the launcher uses the same code path as `./bus serve`; it was not checked live.

**While developing it.** Run the development server against `./bus serve`:

```bash
cd dashboard && npm run dev      # http://localhost:5174
```

**With no simulator.** Save the city and eight worked journeys, then open the
dashboard from them:

```bash
./bus export --bundle --out dashboard/public
```

The dashboard finds the saved data in `dashboard/public/bundle/` and works offline.

## What it reads

The dashboard calls the [local API](../serve-api.md): `/api/world`, `/api/network`,
`/api/journey`, `/api/trace/search` and `/api/trace/build`. It has no data of its
own. If the server is not reachable it falls back to the saved bundle, and says it
is offline: planning a new journey then needs `./bus serve`.

## When it looks wrong

- **"The API is up. The dashboard has not been built"** at 8080. Run
  `npm run build` in `dashboard/`. The JSON API works without it.
- **Old lines after starting a new run.** Reload the page. The server re-plans when
  the run changes; see [Troubleshooting](../troubleshooting.md#the-planned-network-looks-stale).
