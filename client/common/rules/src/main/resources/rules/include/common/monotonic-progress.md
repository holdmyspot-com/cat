---
depends-on:
  - ../../common/intermediate-vs-terminal-goals.md
---
# Monotonic Progress

## Design Goals

- For every workflow that records progress for later use across retries, resumptions, or actors, converge on its
  terminal acceptance state through one authoritative, evidence-backed transition record. Each accepted transition
  preserves or advances verified state, or explicitly invalidates only the affected state with its reason and
  replacement path; stale, duplicate, or conflicting work cannot replace verified progress.
- Complete independent work units with the highest verified bounded concurrency that preserves their isolated state,
  evidence, and failure visibility rather than serializing them without a dependency.
- Limit avoidable invalidation of completed work by settling unverified premises before dependent work expands, reusing
  valid evidence, and allowing work that does not depend on those premises to advance.

## Guidance

Before fixing a workflow's work units, order, or dependent interfaces, identify each unverified premise whose different
result could invalidate work across a later boundary. For each premise, name a different outcome permitted by the known
facts, the dependent decisions and work it would change, the evidence that would settle it, and the earliest authentic
check available. Trace a shared premise through its dependent branches so that its check precedes expanding those
branches, rather than leaving each branch to discover the same uncertainty after implementation.

Reuse authoritative evidence that still establishes the premise under the current determining inputs and contract.
Do not add another premise probe or defer otherwise-ready work when that evidence already settles the decision. This
does not remove acceptance checks for newly implemented behavior.

When execution is in scope, run an available authorized check before starting work that assumes its answer. A
planning-only actor assigns the check and its dependent readiness condition to an authorized executor; the planned
check leaves the premise unresolved. If the check needs unfinished implementation, identify the bounded work needed
to make that boundary runnable and put the check there. Advance that enabling work and independent work while keeping
other dependent decisions conditional until the evidence settles them. This limits avoidable rework; new evidence
that changes the contract still requires the affected-state transitions below.

Before a workflow records progress for later use, define one authoritative durable transition record, its responsible
actor, storage location, consumers, terminal acceptance state, independently nameable work units, and permitted
transitions. An actor must claim a work unit through that record or receive an equivalent serialized assignment before
advancing it. Define the evidence and transition that invalidates or reassigns a claim when its responsible actor cannot
complete it.
At minimum, model each unit as unclaimed, claimed, verified, invalidated, or reassigned; the workflow is terminal only
when its acceptance record verifies every required unit.

Record each proposed transition with its current predecessor state or version, responsible actor, work unit, evidence,
and resulting state. Accept it only when the predecessor remains current and the evidence verifies its result. An
accepted transition must preserve verified work and advance the remaining path to the terminal acceptance state, or
explicitly invalidate only the affected state with its reason and replacement path. Reject or explicitly reconcile
stale, duplicate, or conflicting results without overwriting unaffected verified state.

Use a queue, single orchestrator, lease, append-only ledger, compare-and-set record, or another mechanism that provides
these observable guarantees; do not require a particular mechanism when the workflow does not need it. Before an actor
treats work as assigned or complete, it must retrieve the durable record and verify the stated evidence. On retry or
resumption, reuse every verified completed unit and continue only with the next unverified, unclaimed, or explicitly
invalidated or reassigned unit.

When a workflow evaluates, compares, certifies, or otherwise reuses a result, record the complete evaluation identity
before work that can change its inputs begins. The identity includes every candidate, delivered guidance bundle, input
population, reserved input, runtime artifact, execution plan, and policy whose change would make a later result answer
a different question. A retry must reuse those exact recorded inputs and completed units. Reject an attempted retry that
silently changes one; create a distinct identity only through a terminal, explicit superseding transition that retains
the earlier record and states why its result cannot be compared with the replacement.

When independently executable units can run concurrently, choose the highest bounded concurrency supported by the
available capacity and the workflow's isolation guarantees. A persistent parent must own and reap every child, record
each terminal result, and return failure when any required child fails; a parent that exits while children run cannot
establish their result. Give concurrent units separate mutable resources and durable evidence. Do not treat partial
output, a started process, or a child that has not reached a terminal recorded state as successful work.
