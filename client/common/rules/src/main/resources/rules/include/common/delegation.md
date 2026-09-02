# Safe Work Delegation

## Design Goals

- Allocate workflow work to the least costly authority that can verify or decide it: deterministic work to authoritative
  automation, bounded review to independently checkable delegation, and high-judgment decisions to the authority able to
  evaluate them.
- Isolate delegated writes and integrate them through one parent-owned boundary so concurrent verification cannot grant
  one delegate authority over another delegate's files.

## Guidance

When designing, updating, or executing a prompt-driven workflow, first classify every operation as deterministic,
bounded-review, or high-judgment work.

Move deterministic operations into authoritative automation, normally a CLI. The automation owns authoritative-state
capture, derived paths and arguments, validation, generated artifacts, recovery evidence, execution, and
machine-readable diagnostics. Do not leave an agent or human to reproduce those operations.

Delegate bounded-review work to a cheaper model only when its acceptance check is materially cheaper, narrower, or more
reliable than independently performing the delegated work. Give it explicit inputs, a bounded deliverable, and
acceptance evidence. Treat its result as a candidate until the specified deterministic check or higher-authority review
accepts it.

When a delegated response must use a specific form, state that form in the task sent to the recipient. Name the
recipient's injected response contract and required form when one exists; otherwise specify every required response
component. Do not redefine that contract's statuses, keys, or terminal meanings in the task. Do not rely on a
parent-only requirement or generic handoff language.

Delegate one verification-closed behavior cluster at a time: one coherent observable outcome whose production changes
and focused tests end in an exact gate. Paths that overlap do not make independently testable lifecycle, schema, or
quality changes one cluster. Sequence those clusters through one exclusive writer or retain them at the higher
authority. Do not delegate work when accepting it would require the parent to rederive its decisions or review work as
broadly as implementing it directly.

For every write-capable delegation, supply a task contract with an exact write allowlist, verification commands,
workspace mode, and acceptance evidence. Treat the write allowlist as authority and the verification commands only as
observation: compiling or testing a wider dependency closure does not authorize the delegate to repair it. A diagnostic
whose owning path is outside the allowlist is an out-of-scope dependency to report, not work to absorb.

Run concurrent write-capable delegates only in separate isolated worktrees created from an immutable shared snapshot.
Include each worktree root and snapshot identity in its task contract. Build a scheduling conflict graph from both write
sets and verification dependencies; file-disjoint tasks are not independent when either task's verification consumes
the other's changing output. If an authoritative workspace owner cannot create and later integrate isolated results,
use one exclusive writer in the shared worktree instead of improvising temporary indexes, worktrees, commits, or merge
commands in prompt prose.

A delegate never integrates its own result. The parent or an authoritative integration operation first rejects changed
paths outside the allowlist, then integrates accepted results one at a time and runs the combined affected checks. A
clean integration worktree may use the repository's approved merge operation. A dirty parent worktree must not receive
a direct merge or cherry-pick; use an authoritative validated patch replay that preserves its existing changes, or keep
the result isolated until a clean integration boundary exists. Start a dependent delegation only from the resulting
integrated snapshot.

Keep high-judgment work with the higher-level model: semantic design, intent, topic boundaries, earliest complete
placement, conflict resolution, security-sensitive decisions, and any result whose verification would require redoing
the same analysis. Do not delegate merely to create an appearance of independent review.

When a cheaper agent is available and delegation satisfies these conditions, use it; otherwise continue directly.
