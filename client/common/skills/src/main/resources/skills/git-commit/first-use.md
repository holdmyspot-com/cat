# Git Commit

## Design Goals

- Create one verified commit containing only the intended changes, with the message that CAT's commit-message
	guidance requires.

## Procedure

1. Confirm that each requested path is inside the repository and inspect the current branch, staged, and unstaged
	changes with `git status --short`, `git diff --stat`, and `git diff --cached --stat`. Stop if the branch or requested
	scope is ambiguous.
2. Stage only the requested files or hunks. Do not use `git add -A` or `git add .` unless the user requests all changes.
3. Review with `git diff --cached --check` and `git diff --cached`. Write the message as CAT's commit-message guidance
	requires, in the commit-message convention that the target repository provides, if any.
4. Create the commit using a literal-safe message mechanism; do not let shell expansion alter the approved message.

## Verification

Run `git show --stat --oneline HEAD` and `git status --short`. Confirm the new commit contains only the reviewed staged
change. Report the new commit and unrelated remaining changes.
