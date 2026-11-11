# Build Workflow Development

## Design Goals

- Get fast feedback when introducing or repairing an expensive many-unit workflow by validating representative small
	scopes before progressively reaching the complete scope.
- Preserve valid successful-step outputs while repairing a build workflow until it passes end to end incrementally.
- Follow incremental success with verification of the same revision from a clean start before claiming the build passes.

## Guidance

When introducing or repairing an expensive build or workflow that processes many units and supports valid subsets,
begin with a small representative subset through the same production execution path, including its final assembly and
checks. Choose units the workflow actually supports, such as packages, datasets, or jobs; account for required
dependencies separately from the selected unit count. In the main progression, double the selected unit count after
each successful stage, capped at the full scope. Fix and retry a failing stage before increasing scope, reusing valid
earlier outputs. Select work that exercises relevant failure and lifecycle paths, rather than only the easiest units.
Add small targeted diagnostic stages when needed, including a deliberately short workload to reach terminal handling.
Do not invent a partition for an indivisible operation.

When creating, updating, or debugging a build script, pipeline, or multi-step build, identify the cause of a failed step
before changing the workflow or retrying it. Preserve outputs from successful steps while their determining inputs
remain valid after the fix. Apply the focused fix and resume incrementally from the failed step; if the fix invalidates
an earlier step's outputs, resume from that earliest affected step and rebuild its dependent outputs.

Record each diagnostic stage's actual scope and result; neither a subset pass nor a cached full pass replaces required
clean full acceptance. A small stage may prove workflow behavior without producing artifacts reusable under the full
run's configuration. Do not restart from a clean state on each attempt. First get the workflow to pass end to end
incrementally, reusing valid outputs. Then, before verifying that same revision from a clean start, lazy load
`build-verification.md`.
