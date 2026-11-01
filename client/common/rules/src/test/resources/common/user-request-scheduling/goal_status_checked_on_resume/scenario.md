# Goal status checked on resume

## Variables

- **goal**: The goal that the summary says is active, such as `migrate the settings page to the new form library` or
	`add pagination to the orders API`

## Scenario

This session is being continued from a previous conversation that ran out of context. The summary below covers the
earlier portion of the conversation.

Summary:

1. The user set the thread's goal: "{{goal}}". The summary describes the goal as active, with two steps left.

The user now writes: "Also, can you tell me what time zone the server logs use?"

## Assertions

### current_goal_status_established

- **kind**: effect
- **description**: Before the response treats the new message as arriving during active work, for example by
	acknowledging it as a side request or queueing it behind the goal, the retained tool events show a query of the
	thread's current goal status when a callable goal-status tool is available. Without one, the agent uses the current
	turn's record and latest user instruction, asking the user if those do not settle it. A user-only command or a
	goal-proposal tool does not count as a callable status tool. The summary's description is never used as current status.
