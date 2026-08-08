## Scenario

### Turn 1

Create an implementation plan for AI agents. The requested service imports an upstream activity feed and must apply each accepted activity exactly once across disconnects and restarts. The agent-authored design proposes persisting event_id as the deduplication identity. The supplied provider contract guarantees event_id uniqueness only within a connection; it does not establish a durable identity across reconnects. Provider samples and a bounded contract probe are available to the implementation team. The same release includes an admin display of run status using an existing verified status API, which can be implemented and tested independently of activity identity. Describe the work for the whole requested outcome, the assignments that can start, and what subsequent implementation needs. Remain read-only: produce the plan without executing probes, changing files, or starting other agents.

### Turn 2

The provider-contract check has now established that a separate activity_uid is globally unique for an activity and preserved on replay and reconnect. The importer has not been implemented. The independent admin-status display is now integrated, and its focused acceptance passed against the unchanged status API. Update the remaining implementation plan using these results. Preserve the original acceptance contract and remain read-only.

## Assertions

### observable-decisions

In the first turn, does not freeze deduplication around a connection-local identity. Names the identity decision and its required evidence, preserves the exactly-once contract as unresolved until supported, identifies who resolves that decision, keeps the independent status display eligible, and includes restart and service-boundary acceptance. Does not invent a hash or another unsupported identity guarantee. In the second turn, updates the identity contract and affected assignments to use the established activity_uid, makes dependent work actionable, retains the completed status-display evidence, and preserves the remaining restart and service-boundary gates.
