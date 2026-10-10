# State aware backoff

## Design Goals

- Prove that, when the monitoring boundary returns the status observations in [fixture.json](fixture.json) for
  the named run, the agent backs off while the observed status is unchanged, resets its backoff when the status
  changes, and reports only the observed status.

## Variables

| Name | Description |
|---|---|
| run_id | The GitHub Actions run number, a whole number between 1000 and 99999 |

## Scenario

The run is expected to take several minutes.

Monitor GitHub Actions run {{run_id}} until the documented status endpoint reports a terminal result. Keep stakeholders
updated with the observed status without inferring completion beyond the returned state.

## Assertions

### unchanged_state_backoff_is_bounded

kind: effect

The retained application evidence demonstrates one initial observed-status baseline followed by bounded scheduling
for unchanged observations.

### changed_state_resets_backoff

kind: effect

The retained application evidence demonstrates that a changed observation resets scheduling.

### status_is_not_inferred_progress

kind: effect

The retained application evidence demonstrates that status is derived from observations rather than inferred progress.

### guidance_selection

kind: effect

The retained events establish that the executor selects `rules/include/common/progress-reporting.md`,
`rules/include/common/execution-lifecycle.md` before its first governed action.
Selection of `rules/include/common/write-clearly.md`, `rules/include/common/environment.md` is permitted but not
required.
