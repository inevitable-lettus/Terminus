# Portal troubleshooting

Problems with the ticketing portal. If Terminus itself is not answering, start with [Troubleshooting](../../troubleshooting.md).

The portal API prints to its terminal. It prints `Sim sync failed: <reason>` once whenever it cannot copy from Terminus, and `Copied simulator run <run id> from the algorithm` when it succeeds.

## Portal search fails

| What the portal says | Cause | Fix |
|---|---|---|
| `Could not search for routes` (HTTP 500) | The portal could not reach Terminus, or Terminus answered with an error it does not translate | Check Terminus is up (`curl http://127.0.0.1:8080/api/health`) and that `ALGORITHM_SERVICE_URL` has the right port. Check `USE_MOCK_ALGORITHM=false` |
| `Live bus times are not available yet. Please try again in a moment.` (503) | The portal has not read Terminus's `/api/v1/live` yet | Wait a few seconds |
| `This bus is not loaded yet. Please try again in a moment.` (503) | The portal has not copied this run's trips yet | Wait a few seconds |
| `No bus route connects these two stops.` (422) | Terminus found no journey | Pick other stops |
| `One of these stops is not on the bus map.` (404) | The stop id is not in this run | Reload the page so the stop list refreshes |
| `Algorithm returned an invalid route path` (502) | Terminus's answer failed the portal's checks | Report it with the two stops |

A search with `USE_MOCK_ALGORITHM=true` ignores Terminus entirely and returns a fixed
journey. If every search looks identical, check that setting.

## The portal database

| Symptom | Fix |
|---|---|
| `database "csc210_ticketing" does not exist` | `createdb csc210_ticketing`, then load the four SQL files in [Installation](index.md#install) in order |
| `password authentication failed` | The user and password in `DATABASE_URL` do not match your PostgreSQL |
| Relations missing after pulling new portal code | Re-run the SQL files in order. They are written to be re-run |

!!! note "Not verified here"
    The last row assumes the portal's SQL files can be re-run safely. This was not
    tested. Use a fresh database if a re-run fails.

## Ports

| Port | What | Fix |
|---|---|---|
| 3000 | Portal API | Free it. The web app calls `http://localhost:3000` and cannot be pointed elsewhere without editing `frontend/src/api.js` |
| 5173 | Portal web app | `npm run dev -- --port N` |
| 5432 | PostgreSQL | Use another port and put it in `DATABASE_URL` |
