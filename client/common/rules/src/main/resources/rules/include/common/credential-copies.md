# Credential Copies

## Design Goals

- Keep a credential that an agent can use from reaching a place where another session, a stored result or a commit can
	read it.
- Give a process the credential that it needs without leaving a second copy for an agent to track and remove.

## Guidance

This rule applies when you are about to copy, move, link or write a credential file, such as a token, a key,
`auth.json` or `.credentials.json`, to a new location. A copy does not print the key, but it can leak through any path
that reads the folder that holds it.

Do not place a credential file where a session, a results folder, a trial project or a commit can reach it. That
covers a folder that a session reads or writes, a folder that collects results or logs, a project that a trial runs in,
and a Git working tree that a later commit can include.

Prefer a helper that reads the credential at runtime, from its original location, and does not share it: a command
that the tool runs to obtain the key, or an environment value set for that one process alone. When neither is
available, report that the process needs a credential that you cannot give it without a copy; do not copy the file as
a substitute.
