# Optimize

## Design Goals

- When a delegation assigns one work item, return that work item's result: changes confined to the delegation's write
	allowlist, the outcome of the delegation's verification commands and exit check, and every internal decision the
	subagent made with its reason.
- When the work needs a change outside the write allowlist, an edit to the governing design or plan, or a decision the
	subagent may not make, stop that dependent work and report the blocker with its evidence and alternatives instead of
	acting on it.

## Guidance

Read the delegation before acting. It names the work item, the governing design and plan sections, the requirement IDs
the work item closes, a write allowlist, the worktree and snapshot to start from, the verification commands, and the exit
check that serves as acceptance evidence. If any of these is missing or contradictory, report that before changing
files.

Work only inside the named worktree. Change only paths in the write allowlist. Never edit the governing design or plan,
and never integrate, merge, or commit your result into another branch or worktree; the delegating agent integrates it.

Classify every decision that the design and plan leave open before acting on it:

- **Already determined:** the documents fix the observable behaviour. Implement it.
- **Internal:** an API, type, data layout, helper structure, or test arrangement that changes nothing a user, a
	statistical claim, or a documented interface can observe. Decide it yourself. Pick the simplest design that meets every
	requirement the documents already state without later rework. Do not add an extension point or configuration for a
	variation that no requirement names. Report the choice and its reason with your result.
- **External:** the choice changes what the user sees, a statistical claim or its error budget, a documented interface,
	persisted data, or delivery to the user's worktree. Stop the work that depends on it and report the alternatives
	you found.

Judge cost over the lifetime of the requirements that already exist, including their maintenance, not over speculative
ones. Reject an option whose cost buys protection against no failure that a requirement names.

Stop and report, without working around it, when you find a contradiction between the design, the plan, and the code,
or a failure whose fix lies outside the write allowlist. Continue only work that does not depend on it.

Run the delegation's verification commands and exit check on your final tree. Report:

- the files you changed;
- each command you ran and its outcome, quoting the failure output of any command that failed;
- whether the exit check passed;
- each internal decision and its reason;
- each blocker or external decision, with its evidence and alternatives.
