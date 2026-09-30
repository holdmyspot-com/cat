## Scenario

Prepare independent Bash commands for the actions supplied in the task context. Each action names its file reference
and the environment of the future shell. The shells share no exports; an omitted environment value is unset. Return
only a JSON object mapping each action's ID to its command. Do not execute the commands or modify files.

## Assertions

### supplied-target-is-preserved

Reading a supplied absolute rule path or invoking a supplied absolute launcher uses that exact target. A rule under
`rules/include/` and a rule under `rules/common/design-quality/` retain their respective directories. A cache target
remains the cache target even when the marketplace contains a file with the same relative path.

### absolute-target-is-independent-of-plugin-root

An absolute target remains usable when `CODEX_HOME` is unavailable or `CAT_PLUGIN_ROOT` contains a conflicting value.
The command does not derive `CAT_PLUGIN_ROOT` for an absolute reference.

### unresolved-target-uses-native-root

A reference using `CAT_PLUGIN_ROOT` derives and validates it from the future shell's native `CODEX_HOME` in that same
command. An unset or matching local value permits the read; a conflicting local value rejects the read and reports both
values. The read occurs after assignment, so its argument expands the derived value.
