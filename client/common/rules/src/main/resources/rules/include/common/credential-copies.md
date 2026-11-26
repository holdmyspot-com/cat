# Credential Copies

## Design Goals

- Keep a credential that an agent can use from reaching a place where another session, a stored result or a commit can
	read it.
- Keep a credential's value out of an agent's own tool output, which session logs and stored results retain.
- Give a process the credential that it needs without leaving a second copy for an agent to track and remove.

## Guidance

This rule applies when you are about to copy, move, link or write a credential file, such as a token, a key,
`auth.json` or `.credentials.json`, to a new location, or to print an environment value or a file that may hold a
credential. A copy does not print the key, but it can leak through any path that reads the folder that holds it.

Do not place a credential file where a session, a results folder, a trial project or a commit can reach it. That
covers a folder that a session reads or writes, a folder that collects results or logs, a project that a trial runs in,
and a Git working tree that a later commit can include.

Prefer a helper that reads the credential at runtime, from its original location, and does not share it: a command
that the tool runs to obtain the key, or an environment value set for that one process alone. When neither is
available, report that the process needs a credential that you cannot give it without a copy; do not copy the file as
a substitute.

## Printing Environment Values and Credential Files

Tool output is stored, so a value that you print there is exposed. Do not run `env`, `printenv`, `set`, `export -p`
or a read of `/proc/<pid>/environ` and show its values, and do not `cat` or `grep` `settings.json`, `auth.json`,
`.credentials.json` or a `.env` file, with or without a filter such as `env | grep TOKEN`; the filter still prints the
matching values.

Inspect names, presence or size instead: `env | cut -d= -f1` lists the names, `jq 'keys'` lists a file's keys,
`[ -n "${VAR+x}" ]` tests that a variable is set, and `${#VAR}` gives its length. When you must see a structure, print a
view that replaces each value, such as `jq 'map_values("<redacted>")'`.

If a secret value has appeared in your output, tell the user which one and recommend rotating it.
