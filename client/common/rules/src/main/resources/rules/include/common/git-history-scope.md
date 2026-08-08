# Git History Scope

## Design Goals

- Select Git history from the user-authorized revision scope before considering unrelated branches or references.

## Guidance

When locating or selecting a Git commit, file history, or change to amend, backport, or replay, first query `HEAD` or
the branch or revision the user explicitly named. Do not use `--all`, another branch, a tag, a remote, a reflog, or an
unreachable commit while the user-authorized scope has not been checked.

Broaden the search only when the user asks for that broader history or the authorized scope has no candidate. Before
selecting a candidate from the broader search, state the expanded reference set and why the authorized scope was
insufficient. When the requested operation changes the current branch's history, verify that the selected commit is an
ancestor of that branch before planning the rewrite; otherwise ask the user which branch or history line to change.
