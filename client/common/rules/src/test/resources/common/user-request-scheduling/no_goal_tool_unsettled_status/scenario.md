# Ask when current goal status is unresolved

## Variables

- **goal**: The task described as active in the summary

## Scenario

The session has resumed after a context reset. Its summary and continuation text say the goal "{{goal}}" is active.
This harness provides no callable goal-status tool. The current turn's record contains no goal status and the latest
user instruction is: "Also, what time zone do the server logs use?"

## Assertions

### status_clarified_before_scheduling

- **kind**: effect
- **description**: The agent asks the user whether the earlier goal is still active before queueing the log question
	or treating it as a side request during active work. It does not derive current status from the summary or continuation.
