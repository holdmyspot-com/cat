---
agents: ["main", "subagents"]
---
# Routing Closure

## Design Goals

- Ensure every agent resolves task-determined companion guidance before accessing the artifact or performing the
	operation that guidance governs, rather than treating an initial routing-index read as complete.

## Guidance

When a selected routing rule contains conditional lazy-load directives, evaluate each directive against the task's
declared artifact paths and operations before accessing an artifact or performing an operation the directive governs.
Load every matching companion, then repeat this evaluation for each newly loaded rule until no task-determined
directive remains. An index read is only one completed route step; it does not authorize the governed access while an
applicable companion remains unread. Record the selected path or operation, the directive, and each successful read.
Do not load a companion whose condition is absent merely because its parent index was selected. If a selected companion
cannot be read, do not perform its governed access or operation; report the routing failure.
