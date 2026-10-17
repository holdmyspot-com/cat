# Codex Hooks

CAT runs `session-start` for each configured Codex `SessionStart` event.

For this hook, Codex sets the environment variable `PLUGIN_ROOT` to the directory of the installed CAT plugin, which
locates the shared `$PLUGIN_ROOT/hooks/session-start.sh` launcher. Codex also writes that directory into a hook's
command in place of `${PLUGIN_ROOT}`, but the shell would then read a `$`, a quote or a backtick in the directory's
name as syntax, so each command reads the environment variable instead. The launcher derives CAT's installation directory from its own
location before it runs CAT's `client/bin/session-start` command.

The hook writes the session's rules to `.cat/sessions/<session_id>/rules.md` in the session's project directory. That
directory is the session's CAT session directory: it also holds `temp/`, the session's `CAT_TEMP`, which the rules
name, and `session.json`, which records the harness, its configuration directory and the directory's creation time. The
hook's additional context holds only the guidance on reading command output whole, from `rules/codex/read-file.md`,
followed by an instruction to read that file whole, as that guidance describes, before anything else. The hook then
removes the CAT session directory of every other session of the project that is more than 24 hours old and whose
transcript Codex or Claude Code no longer keeps.

For a `SubagentStart` event, the hook writes the subagent's rules to the `rules.md` of the CAT session directory of the
event's `agent_id`, the thread ID that Codex gives the subagent's shell commands in `CODEX_THREAD_ID`, so their
`CAT_TEMP` names that directory. Its additional context holds the same guidance on reading command output whole,
followed by an instruction to read the subagent's file whole. A subagent that Codex starts with its parent's
conversation also receives the parent's instruction to read the parent's rules file, the `rules.md` of the event's
`session_id`; Codex places the subagent's context after it. The subagent cannot see whether its conversation was
copied, so the instruction first names the parent's file as belonging to the agent that started the subagent and tells
it not to read that file. Without `agent_id`, the subagent would share its parent's CAT session directory, so the hook
returns every rule as additional context and leaves the parent's rules file unchanged.

The command hook sets `additionalContextLimit` to `0`, disabling additional-context spilling for this hook. Codex
otherwise applies a default limit. For the meaning and introduction of this Codex hook property, see
[openai/codex#34393](https://github.com/openai/codex/pull/34393).

CAT runs `post-tool-use` after each shell command, through the `$PLUGIN_ROOT/hooks/post-tool-use.sh` launcher. When
the agent set `max_output_tokens` on a command that runs `$PLUGIN_ROOT/bin/run-part` or
`$PLUGIN_ROOT/bin/read-part`, Codex removes lines from the middle of the part. The hook then returns a blocking
decision whose reason is a single line: the output was cut off because the agent set `max_output_tokens`, and the
`read-part` command that prints the same part again from the saved output, to run without the limit. Codex shows the
reason to the model in place of the whole result, and the hook deletes nothing. The last part of a saved output ends
with `[End of <path>. The file was deleted]`. When a result that Codex did not cut ends with that line and `<path>` is
an output that `run-part` saved, the hook deletes the `run-part-*` folder that holds it and leaves the result
unchanged. The launcher passes every other event over without starting Java.

## Windows

On Windows, Codex runs each hook's `commandWindows` in PowerShell instead of `command`. It starts the hook's `.cmd`
launcher, such as `$env:PLUGIN_ROOT\hooks\session-start.cmd`, with PowerShell's call operator `&`; a quoted path alone
is a string, which PowerShell prints instead of running. Each `.cmd` launcher runs CAT's `client\bin\<name>.bat`. A
batch file cannot read the event and pass it on unchanged, so the `PostToolUse` command reads the event as UTF-8 in
PowerShell and starts `post-tool-use.cmd`, piping the event to it as UTF-8, only when the event mentions `run-part` or
`read-part`.

The hook's environment has `USERPROFILE` but no `HOME`, so CAT finds the user's home directory through `USERPROFILE`
on Windows.
