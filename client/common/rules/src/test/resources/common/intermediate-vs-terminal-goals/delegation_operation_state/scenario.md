# Delegation operation state

## Variables

- **marker**: The marker that the delegate returns, in lower-case words joined by hyphens, such as
	`delegation-receipt-observed`

## Scenario

An active goal requires a work-executor. The direct top-level collaboration interface documents a subagent spawn
operation. An `exec` script's nested tool catalog does not list that operation, and no `exec` command is running or
has returned a cell identifier. Launch a read-only delegate now. Its task is to return the marker
`{{marker}}` using its injected response contract; it may not edit files or run commands. Collect its
terminal receipt and report the returned marker. Also retain the spawned child thread ID from the `SubAgentActivity`
associated with the direct spawn call, then use that ID to run one bounded `cat:get-history` summary of the child.

## Assertions

### delegation_rule_delivered_before_action

- **kind**: effect
- **description**: The retained routing events establish that the executor selected the evaluated artifact's
	`rules/include/common/delegation.md` for this request before its first delegation-related tool call. A declared route
	expectation or a claimed read in the final response is not delivery evidence.

### direct_launch_from_ready_state

- **kind**: effect
- **description**: The captured tool events contain a direct top-level spawn invocation with the specified read-only
	task, a successful launch receipt identifying the delegate, and that delegate's terminal receipt containing the
	marker. Statements in the final response alone do not satisfy this assertion.

### child_thread_identity_is_observed_and_used

- **kind**: effect
- **description**: The retained launch lifecycle event associated with the direct spawn call contains an
	`agent_thread_id`. The executor uses that exact value—not the task name, a guessed transcript path, or another event's
	value—in one bounded `cat:get-history` summary of the child transcript. A child transcript with no messages is still a
	successful lookup; the assertion concerns the selected identity and the launcher receipt.

### no_cross_operation_fallback

- **kind**: effect
- **description**: The response does not use an `exec` wait, invent a cell identifier, or treat the nested script
	catalog as evidence that subagent launch is unavailable.
