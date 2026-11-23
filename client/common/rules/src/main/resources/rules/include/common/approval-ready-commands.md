# Approval-Ready Commands

## Design Goals

- A shell command that may need approval or a sandbox exception reaches the harness with its final literal text, so the
	harness matches it against its allow rules without pausing the agent to ask the user.
- A shell command that an approval reviewer agent judges states every value and path it uses in its own text, so the
	reviewer can tell what the command will do and does not block it or escalate it to the user for that reason.
- A command that needs no approval keeps whatever shell syntax makes the work simplest.

## Guidance

This rule applies when you write a shell command that a harness may approve by matching its text: a command that is
outside the harness's default allowance, one that a sandbox exception or allow rule names, or one that would otherwise
ask the user for permission. It also applies when the harness has an approval reviewer agent judge the command before it
runs, such as Claude Code's auto mode classifier or Codex's automatic approval reviewer. It does not limit a command
that needs no approval.

Shell syntax whose result is known only when the shell runs defeats both kinds of approval. An allow rule's match fails,
so the harness asks the user or keeps the command sandboxed. A reviewer judges the command from its text without running
it, so it cannot tell what a variable, `$(...)`, backticks, `eval`, or a value read at runtime will become, and it
blocks the command or escalates it to the user. Write the command so that its text is final:

- Write every value literally and every path out in full as an absolute path, except a path that an allow rule names in
	relative form, which you write as that rule names it. Do not use a variable, `$(...)`, backticks, `eval`, `~`, or
	another construct whose value the shell computes when it runs, such as input that `xargs` or `read` consumes. This
	holds even when the same command assigns the variable.
- When a command needs data known only at runtime, obtain it in an earlier call and write the resulting value literally
	into the command. When the value cannot be written literally, use a readable file argument if the command and its
	approval rule support one. Otherwise use a tool that binds the runtime value into the final invocation before the
	harness checks that invocation for approval. If neither is available, report the missing transfer capability. Keep
	approval on the exact invocation that would execute.

When an allow rule may approve the command, including in a session that also has a reviewer judge commands that no rule
allows, also keep its text equal to what the rule names:

- Run one command per call. Do not put an assignment, `env NAME=value`, or `cd … &&` in front of it, and do not append
	`;`, `&&`, `||`, a pipe, or a redirection to it.
- Read the exit status from the tool result. Do not append `; echo exit=$?` or similar.
- Create or change a file with a file-writing tool, not a heredoc or a redirection.

```sh
# Rejected: the variable's value is known only at runtime, and the suffix changes the text a rule names
R=/opt/cat/bin/installer; $R --update; echo exit=$?
# Preferred: the text is the command itself, and the exit status comes from the tool result
/opt/cat/bin/installer --update
# Rejected: a reviewer cannot tell which file the substitution names
rm -- "$(cat /work/build/last-output.txt)"
# Preferred: an earlier call read that file, and the command names the path it contained
rm -- /work/build/out/report.json
```
