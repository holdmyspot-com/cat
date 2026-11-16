# Git Amend

## Design Goals

- Update the requested contents or message of the tip commit, and nothing else, and report the new commit and the
	remaining changes.
- Keep the repository recoverable to its pre-amend state until the result is verified, and rewrite a commit that
	another author created or that a remote already holds only with the user's explicit authorization.

## Procedure

1. Confirm that the requested commit is `HEAD`. Use interactive rebase for an earlier commit and the history-rewrite
	workflow for a repository-wide correction.
2. Inspect `git status --short`, `git show --stat --oneline HEAD`, the current branch, and remote refs that contain
	`HEAD`. Do not amend a commit created by another author or already published unless the user explicitly authorizes
	rewriting that shared history.
3. Record `HEAD` in a named backup branch. Stage only requested content, then review it with `git diff --cached --check`
	and `git diff --cached`.
	Compare the complete proposed amended tree and message with the recorded tip: only requested differences are
	authorized. A message-only amendment must keep the original tree. Exclude unrelated pre-existing staging from the
	amendment while preserving that staging and the worktree changes; do not proceed until the complete input meets
	this scope.
4. Use `git commit --amend --no-edit` to retain the message, or provide the approved replacement message. Recheck
	whether the original commit became published before reporting success. Do not force push; explain that a separately
	authorized `--force-with-lease` update is required if the amended commit was shared.

## Verification

Run `git show --stat --oneline HEAD` and `git status --short`. Compare the new tree and message with the backup and
confirm that every difference is authorized and unrelated staging and worktree changes are preserved, then delete the
backup. Report the new commit ID and remaining changes. If verification fails, retain the
backup and recover with its recorded ref or the reflog.
