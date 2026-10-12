# Codex Hooks

CAT runs `session-start` for each configured Codex `SessionStart` event.

For this hook, Codex supplies `PLUGIN_ROOT`, the directory of the installed CAT plugin, to locate the shared
`${PLUGIN_ROOT}/hooks/session-start.sh` launcher. The launcher derives CAT's installation directory from its own
location before it runs CAT's `client/bin/session-start` command.

The command hook sets `additionalContextLimit` to `0`, disabling additional-context spilling for this hook. Codex
otherwise applies a default limit. For the meaning and introduction of this Codex hook property, see
[openai/codex#34393](https://github.com/openai/codex/pull/34393).

CAT runs `post-tool-use` after each shell command, through the `${PLUGIN_ROOT}/hooks/post-tool-use.sh` launcher. When
the agent set `max_output_tokens` on a command that runs `${PLUGIN_ROOT}/bin/run-part` or
`${PLUGIN_ROOT}/bin/read-part`, Codex removes lines from the middle of the part, and the part's closing line names the
next part. The hook then returns a blocking decision whose reason is the part with that line replaced by an instruction
to print the same part again without the limit; Codex shows the reason to the model in place of the result. The launcher
passes every other event over without starting Java.
