# Reference

Engineering documents kept from the project, grouped by what they answer.

| Group | Pages | Answers |
|---|---|---|
| Design | [Algorithms](../algorithm.md), [Decisions log](../decisions.md), [Build log](../build-log.md), [Plan](../plan.md) | How the two algorithms work and why they are built this way |
| Integration | [DSTNS API notes](../sandbox-api.md), [Integration changes](../integration-changes.md), [Read-back integration](../readback-integration.md) | What Terminus reads from DSTNS and what changed between versions |
| Quality | [Testing](../testing.md), [Engineering limitations](../limitations.md) | How correctness is shown, and where the work is weak |
| Presenting | [Demo runbook](../demo.md) | A path for showing the sandbox, then the algorithm |
| API | [The local API](../serve-api.md) | Every endpoint of `./bus serve`, including the portal's contract |

These pages were written while the code was being built against DSTNS, so they name
DSTNS where it was the only source. For what a sandbox must provide see
[The sandbox interface](../sandbox/index.md).
