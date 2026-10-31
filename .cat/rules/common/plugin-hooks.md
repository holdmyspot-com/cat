---
paths:
  - "client/*/plugin/src/main/resources/hooks/**"
---
# Plugin Hooks

## Design Goals

- Ship each CAT lifecycle hook that has one execution contract across harnesses once, with every harness-specific
	value supplied at its manifest boundary.
- Start every hook from its harness's manifest on every platform, whatever characters the plugin's directory holds.

## Guidance

When CAT lifecycle hooks have one execution contract across harnesses, ship the hook once in the common plugin resource
layer. Each harness manifest must invoke that artifact-relative hook through its native plugin-root placeholder. The
hook derives CAT's installation directory from its own location, not a harness-specific environment variable. Keep
harness-specific commands, configuration, paths, environment variables, and argument adaptation at the manifest or
entry boundary; pass those values to the shared hook rather than making it name a harness or its layout.

Codex runs a hook's `commandWindows` in PowerShell, not `cmd.exe`, so the command starts a `.cmd` launcher with the
call operator, `& "$env:PLUGIN_ROOT\hooks\<name>.cmd"`. A quoted path without `&` is only a string, which PowerShell
prints. Codex also writes the plugin's directory into the command in place of `${PLUGIN_ROOT}`, where a `$` or a quote
in the directory's name would be read as syntax, so a Codex hook's command names the directory through the environment
variable `PLUGIN_ROOT` instead, on every platform. `CodexPluginHooksTest` runs each hook's command as Codex does on the
platform that runs the test.

Claude Code runs a hook's `command` in a POSIX shell, Git Bash on Windows, and the shell expands
`${CLAUDE_PLUGIN_ROOT}`, so the command double-quotes it, as in `"${CLAUDE_PLUGIN_ROOT}/hooks/<name>.sh"`; a space in
the plugin's path, such as one in a user's name, otherwise splits the command. `ClaudePluginHooksTest` runs each hook's
command from a plugin path that holds a space.

