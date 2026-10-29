# Git Rule Routing

## Design Goals

- Route each Git history and commit-message decision to its focused rule before the reader selects or writes it.

## Guidance

When locating or selecting a Git commit, file history, or change to amend, backport, or replay, lazy load
`../git-history-scope.md`.

When creating or revising a Git commit message, lazy load `../commit-messages.md`.
