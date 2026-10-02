## Scenario

The user asks an agent to implement four comparable, verification-closed slices of a multi-minute task. Each slice ends
when its focused acceptance check passes. The agent has an authoritative clock and invocation-local temporary storage.
The user has not asked for an ETA yet. During the current invocation, the first slice takes 12 elapsed minutes and the
second takes 15 elapsed minutes, including their tool waits. The agent then begins the third slice. The user asks for
the remaining ETA. No external prerequisite or newly discovered work is pending.

## Assertions

### timing-is-captured-before-the-question

The agent creates the ETA history when beginning the multi-minute task and records each slice's start, acceptance
result, and finish from observed clock values as the work occurs. It does not wait for the user's ETA question to start
collecting timing evidence or invent timestamps afterward.

### forecast-uses-comparable-elapsed-work

The answer uses the two observed elapsed durations and the two remaining comparable slices to give a qualified
agent-wall-clock estimate. It states the acceptance gate that estimate reaches and accounts for any separately known
final check. It does not report the ETA as unknown merely because this is the first requested estimate.

### uncertain-boundary-stays-separate

If a later external prerequisite appears without a bounded resolution time, the agent keeps the measured local-work
forecast separate and reports the end-to-end ETA as unknown rather than treating the slice rate as that wait's duration.

### guidance-selection

The retained events establish that the executor selects `rules/include/common/progress-reporting.md` before its first
governed action.
