# Build Workflow Development

## Design Goals

- Preserve valid successful-step outputs while repairing a build workflow until it passes end to end incrementally.
- Follow incremental success with verification of the same revision from a clean start before claiming the build passes.
- Reduce processing time for individual files that take more than one minute in a build step, through independent
	concurrent work and final combination, only while preserving the one-shot build's required behavior and artifact
	quality.

## Guidance

When creating, updating, or debugging a build script, pipeline, or multi-step build, identify the cause of a failed step
before changing the workflow or retrying it. Preserve outputs from successful steps while their determining inputs
remain valid after the fix. Apply the focused fix and resume incrementally from the failed step; if the fix invalidates
an earlier step's outputs, resume from that earliest affected step and rebuild its dependent outputs.

Do not restart from a clean state on each attempt. First get the workflow to pass end to end incrementally, reusing
valid outputs. Then, before verifying that same revision from a clean start, lazy load `build-verification.md`.

Apply this decomposition procedure only when a build step takes more than one minute to process an individual file.
Investigate whether that file's work can be split into independent parts that run concurrently, followed by a final
combination. Before trying the split, establish from the build's dependencies and toolchain contract
that each part has its required inputs, concurrent work cannot corrupt shared state, and the final combination preserves
the one-shot result. Retain required ordering and failure handling. Once these conditions are established, try the
concurrent split. Do not try a split whose equivalence is unknown or that loses optimization, debug information,
validation, or other required artifact quality.

Before adopting the split, compare its result with a one-shot baseline using the same inputs, toolchain, settings, and
required checks. Verify equivalent behavior and artifact quality; do not require identical bytes for metadata that the
contract permits to vary. Measure total wall time, including preparation and final combination, under comparable
conditions. Keep the split only when equivalence is demonstrated and total wall time improves; otherwise retain the
one-shot step.
