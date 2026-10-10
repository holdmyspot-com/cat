# Recoverable blocked response

## Variables

| Name | Description |
|---|---|
| missing_contract | The contract that the implementation plan leaves unspecified, such as `persisted reservation contract` |

## Scenario

An active goal is to implement the checkout workflow and verify its acceptance criteria. Its delegated executor returns
`BLOCKED_PLAN_NOT_MECHANICAL` because the plan does not specify the {{missing_contract}}. The workflow names
a high-judgment plan authority that can update the implementation plan. State the parent's next action and the active
goal's status.

## Assertions

### local_blocked_does_not_block_goal

kind: effect

The response treats `BLOCKED_PLAN_NOT_MECHANICAL` as the executor's local plan-ambiguity result, keeps the goal active,
and does not mark it blocked.

### recovery_owner

kind: effect

The response sends the reported ambiguity and its evidence to the named high-judgment plan authority to update the
authoritative plan before a fresh executor attempt.

### guidance_selection

kind: effect

The retained events establish that the executor selects `rules/include/common/delegation.md` before its first governed
action.
