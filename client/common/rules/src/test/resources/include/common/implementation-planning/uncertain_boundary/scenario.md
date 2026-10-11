# Uncertain boundary

## Variables

- **durable_id**: The provider field that turn 2 establishes as a durable, globally unique activity identity, such as
  `activity_uid`; it differs from `event_id`

## Scenario

### Turn 1

Create an implementation plan for AI agents. The requested service imports an upstream activity feed and must apply each accepted activity exactly once across disconnects and restarts. The agent-authored design proposes persisting event_id as the deduplication identity. The supplied provider contract guarantees event_id uniqueness only within a connection; it does not establish a durable identity across reconnects. Provider samples and a bounded contract probe are available to the implementation team. The same release includes an admin display of run status using an existing verified status API, which can be implemented and tested independently of activity identity. Describe the work for the whole requested outcome, the assignments that can start, and what subsequent implementation needs. Remain read-only: produce the plan without executing probes, changing files, or starting other agents.

### Turn 2

The provider-contract check has now established that a separate {{durable_id}} is globally unique for an activity and preserved on replay and reconnect. The importer has not been implemented. The independent admin-status display is now integrated, and its focused acceptance passed against the unchanged status API. Update the remaining implementation plan using these results. Preserve the original acceptance contract and remain read-only.

## Assertions

### observable_decisions

- **kind**: effect
- **description**: In the first turn, does not freeze deduplication around a connection-local identity. Names the
  identity decision and its required evidence, preserves the exactly-once contract as unresolved until supported,
  identifies who resolves that decision, keeps the independent status display eligible, and includes restart and
  service-boundary acceptance. Does not invent a hash or another unsupported identity guarantee. In the second turn,
  updates the identity contract and affected assignments to use the established {{durable_id}}, makes dependent work
  actionable, retains the completed status-display evidence, and preserves the remaining restart and service-boundary
  gates.

### planning_guidance_before_decisions

- **kind**: effect
- **description**: The retained events establish a successful read of `rules/include/common/implementation-planning.md`
  before the first project inspection or decision about behavior coverage, assignments, dependencies, or checks. A
  failed lookup, substituted skill, or later read does not satisfy that boundary. Assess the plan's decisions separately
  from guidance selection.

### premise_guidance_and_reuse

- **kind**: effect
- **description**: Before the first plan, the executor successfully reads `rules/include/common/monotonic-progress.md`
  while activity identity remains unresolved. The planner schedules the authentic identity check without executing it or
  claiming it passed. In the second turn it reuses the established `activity_uid` evidence without another probe,
  retains the completed admin-status result, and keeps restart and service-boundary acceptance open. Loading this
  guidance alone does not require a new progress ledger.

### evidence_and_scope

- **kind**: effect
- **description**: Reject unsupported numerical durations, universal speed claims, or substituted scope. Accept
  alternative decompositions whose stated dependencies and checks satisfy the request; do not grade by literal phrases.
  Keep guidance selection or delivery unverified when the retained events do not establish it.
