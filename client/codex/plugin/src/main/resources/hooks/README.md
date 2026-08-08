# Codex Hooks

CAT runs `session-start` for each configured Codex `SessionStart` event.

For this hook, Codex supplies `PLUGIN_ROOT`, the directory of the installed CAT plugin, to locate the shared
`${PLUGIN_ROOT}/hooks/session-start.sh` launcher. The launcher derives CAT's installation directory from its own
location before it runs the native session-start command.

The command hook sets `additionalContextLimit` to `0`, disabling additional-context spilling for this hook. Codex
otherwise applies a default limit. For the meaning and introduction of this Codex hook property, see
[openai/codex#34393](https://github.com/openai/codex/pull/34393).
