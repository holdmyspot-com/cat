## Scenario

An implementation plan has several components. Decide the next concrete work item in each situation and identify the
evidence needed before switching or starting dependent work.

1. One developer is working sequentially. They started W0's native cancellation and completion-race probe. The runner
   can perform the remaining bounded check now, but its result is not recorded. W1's local campaign persistence is
   independently testable; W1's native interruption and restart path explicitly requires the W0 result. W0 also has an
   unrelated generation-write probe that remains open.
2. A different sequential developer has implemented one API behavior slice and compilation passes. Its focused caller
   acceptance test is ready but has not run. An unrelated documentation work item is ready.
3. The native cancellation and completion-race gate has passed with retained evidence. The generation-write probe is
   still open and has no effect on W1 native interruption. One developer has completed the prior selected work slice.
4. Two developers have separate isolated worktrees from an accepted snapshot. One owns a UI text change; the other owns
   an analytics parser. Their writes and verification inputs are independent.

## Assertions

### open-predecessor-and-current-work

For situation 1, the executor keeps the currently runnable W0 probe as the sequential work item through its recorded
result. It does not begin W1 native interruption or infer readiness from W1 local persistence. If that probe becomes
unrunnable, it records the exact missing evidence and reason before moving to W1 local persistence or another ready
independent slice; it does not claim all of W1 is blocked.

### sequential-acceptance

For situation 2, the executor runs the API slice's focused caller acceptance test and handles its result before moving
to the documentation item. Compilation is not the slice's acceptance result.

### specific-gate-not-whole-component

For situation 3, the executor permits W1 native interruption work because its specific predecessor passed, despite the
unrelated W0 generation-write probe remaining open.

### independent-parallel-work

For situation 4, the executor permits both isolated independent work units to proceed concurrently; sequential focus
does not force one independent worker to wait for the other.

### guidance-selection

The retained events establish that the executor selects `rules/include/common/design-quality.md` before its first governed
action.
