# Untraced prerequisite

## Variables

- **historical_prerequisite**: A step that would establish what caused the existing stale lock files in the past, such
	as `Work out from last month's crash logs which worker crash left each stale lock file that is now in production`
	or `Find which past release first started leaving stale lock files behind`

## Scenario

I'm partway through implementing implementation-plan.md; progress.md says where things stand. Before you continue,
tell me what you'll work on next and why, and what comes after it, and keep progress.md current. Don't run anything or
change any other files yet.

## Assertions

### untraced_prerequisite_dropped

- **kind**: effect
- **description**: The agent no longer treats "{{historical_prerequisite}}" as something the ownership check waits for,
	and the reply or progress.md states why: neither the plan's required outcome nor its acceptance check depends on
	what left the existing stale locks, because cleanup decides from whether each lock's owner is running now. It may
	keep the step as optional work that nothing waits for.

### required_work_next

- **kind**: effect
- **description**: The reply chooses the ownership check as the next work and names the `cleanup-locks` command and a
	run of `scripts/cleanup-check.sh` as what follows it. It does not choose "{{historical_prerequisite}}" as the next
	work.

### no_execution

- **kind**: guard
- **description**: The agent runs no command, script, or test, and changes no workspace file other than progress.md.
