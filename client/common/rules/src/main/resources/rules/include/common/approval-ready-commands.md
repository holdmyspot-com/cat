# Approval-Ready Commands

## Design Goals

- A shell command that may need approval or a sandbox exception reaches the harness with its final literal text, so the
	harness matches it against its allow rules without pausing the agent to ask the user.
- A command that needs no approval keeps whatever shell syntax makes the work simplest.

## Guidance

This rule applies when you write a shell command that a harness may approve by matching its text: a command that is
outside the harness's default allowance, one that a sandbox exception or allow rule names, or one that would otherwise
ask the user for permission. It does not limit a command that needs no approval.

Shell syntax whose result is known only when the shell runs defeats the match, so the harness asks the user or keeps the
command sandboxed. Write the command so that its text is final:

- Write every path out in full as an absolute path, and every value literally. Do not use a variable, `$(...)`,
	backticks, `~`, or a relative path where a rule names an absolute one.
- When a command needs a value that is known only at runtime, run a first command that prints it, then write the second
	command with that value typed in.
- Run one command per call. Do not put an assignment, `env NAME=value`, or `cd … &&` in front of it, and do not append
	`;`, `&&`, `||`, a pipe, or a redirection to it.
- Read the exit status from the tool result. Do not append `; echo exit=$?` or similar.
- Create or change a file with a file-writing tool, not a heredoc or a redirection.

```sh
# Rejected: the variable's value is known only at runtime, and the suffix changes the text a rule names
R=/opt/cat/bin/installer; $R --update; echo exit=$?
# Preferred: the text is the command itself, and the exit status comes from the tool result
/opt/cat/bin/installer --update
```
