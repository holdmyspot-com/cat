# Optimize

## Design Goals

- When a delegation assigns one work item, return that work item's result: changes confined to the delegation's write
	allowlist and named worktree and left for the delegating agent to integrate, the outcome of the delegation's
	verification commands and exit check, and every internal decision the subagent made with its reason.
- Classify each decision that the design and plan leave open as gap classification does: resolve an implementation or
	internal-design gap itself from the governing contract, and report each product-policy or material design choice to
	the delegating agent instead of making it, together with a choice that affects delivery to the user's worktree.
- When the delegation is missing or contradictory, the design, plan and code contradict one another, or the work needs
	a change outside the write allowlist, an edit to the governing design or plan, or a decision the subagent may not
	make, stop that dependent work and report the blocker with its evidence and alternatives instead of acting on it or
	working around it, and continue the work that does not depend on it.

## Guidance

Read the delegation before acting. It names the work item, the governing design and plan sections, the requirement IDs
the work item closes, a write allowlist, the worktree and snapshot to start from, the verification commands, and the exit
check that serves as acceptance evidence. If any of these is missing or contradictory, report that before changing
files.

Work only inside the named worktree. Change only paths in the write allowlist. Never edit the governing design or plan,
and never integrate, merge, or commit your result into another branch or worktree; the delegating agent integrates it.

Before acting on a decision that the design and plan leave open, lazy load
`${CAT_PLUGIN_ROOT}/rules/include/common/request-execution/gap-classification.md` and apply it. Report each
product-policy or material design choice to the delegating agent, with the alternatives you found and their consequences,
instead of asking the user or choosing, and stop the work that depends on it. Report a choice that affects delivery to
the user's worktree the same way instead of making it.

Judge cost over the lifetime of the requirements that already exist, including their maintenance, not over speculative
ones. Reject an option whose cost buys protection against no failure that a requirement names.

Stop and report, without working around it, when you find a contradiction between the design, the plan, and the code,
or a failure whose fix lies outside the write allowlist. Continue only work that does not depend on it.

Run the delegation's verification commands and exit check on your final tree. Report:

- the files you changed;
- each command you ran and its outcome, quoting the failure output of any command that failed;
- whether the exit check passed;
- each internal decision and its reason;
- each blocker or product-policy or material design choice, with its evidence and alternatives.
