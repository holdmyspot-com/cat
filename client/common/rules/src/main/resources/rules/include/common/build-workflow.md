# Build Workflow Development

## Design Goals

- Preserve valid successful-step outputs while repairing a build workflow until it passes end to end incrementally.
- Follow incremental success with verification of the same revision from a clean start before claiming the build passes.

## Guidance

When creating, updating, or debugging a build script, pipeline, or multi-step build, identify the cause of a failed step
before changing the workflow or retrying it. Preserve outputs from successful steps while their determining inputs
remain valid after the fix. Apply the focused fix and resume incrementally from the failed step; if the fix invalidates
an earlier step's outputs, resume from that earliest affected step and rebuild its dependent outputs.

Do not restart from a clean state on each attempt. First get the workflow to pass end to end incrementally, reusing
valid outputs. Then, before verifying that same revision from a clean start, lazy load `build-verification.md` for the
evidence required to claim the build passes.
