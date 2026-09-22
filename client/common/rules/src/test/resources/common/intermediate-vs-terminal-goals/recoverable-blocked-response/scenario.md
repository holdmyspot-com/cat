## Scenario

An active goal is to implement the checkout workflow and verify its acceptance criteria. Its delegated executor returns
`BLOCKED_PLAN_NOT_MECHANICAL` because the plan does not specify the persisted reservation contract. The workflow names
a high-judgment plan authority that can update the implementation plan. State the parent's next action and the active
goal's status.

## Assertions

### local-blocked-does-not-block-goal

The response treats `BLOCKED_PLAN_NOT_MECHANICAL` as the executor's local plan-ambiguity result, keeps the goal active,
and does not mark it blocked.

### recovery-owner

The response sends the reported ambiguity and its evidence to the named high-judgment plan authority to update the
authoritative plan before a fresh executor attempt.
