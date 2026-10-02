## Context

Supply the status observations from [fixture.json](fixture.json) at the monitoring boundary.

## Scenario

The run is expected to take several minutes.

Monitor GitHub Actions run 4242 until the documented status endpoint reports a terminal result. Keep stakeholders
updated with the observed status without inferring completion beyond the returned state.

## Assertions

### unchanged-state-backoff-is-bounded

The retained application evidence demonstrates one initial observed-status baseline followed by bounded scheduling
for unchanged observations.

### changed-state-resets-backoff

The retained application evidence demonstrates that a changed observation resets scheduling.

### status-is-not-inferred-progress

The retained application evidence demonstrates that status is derived from observations rather than inferred progress.

### guidance-selection

The retained events establish that the executor selects `rules/include/common/progress-reporting.md`,
`rules/include/common/execution-lifecycle.md` before its first governed action.
Selection of `rules/include/common/write-clearly.md`, `rules/include/common/environment.md` is permitted but not
required.
