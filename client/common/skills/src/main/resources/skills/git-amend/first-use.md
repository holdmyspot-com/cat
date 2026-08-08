# Git Amend

## Design Goals

- Update the requested contents or message of the tip commit.
- Preserve a recoverable, verified repository state.

## Procedure

1. Confirm that the requested commit is `HEAD`. Use interactive rebase for an earlier commit and the history-rewrite
   workflow for a repository-wide correction.
2. Inspect `git status --short`, `git show --stat --oneline HEAD`, the current branch, and remote refs that contain
   `HEAD`. Do not amend a commit created by another author or already published unless the user explicitly authorizes
   rewriting that shared history.
3. Record `HEAD` in a named backup branch. Stage only requested content, then review it with `git diff --cached --check`
   and `git diff --cached`.
4. Use `git commit --amend --no-edit` to retain the message, or provide the approved replacement message. Recheck
   whether the original commit became published before reporting success. Do not force push; explain that a separately
   authorized `--force-with-lease` update is required if the amended commit was shared.

## Verification

Run `git show --stat --oneline HEAD` and `git status --short`. Confirm the new tip has the intended message and staged
content, then delete the backup. Report the new commit ID and remaining changes. If verification fails, retain the
backup and recover with its recorded ref or the reflog.
