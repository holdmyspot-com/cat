# Git Linear Merge

## Design Goals

- Integrate a branch through a verified linear history without creating a merge commit or losing intended changes.

## Procedure

1. Name the source and target branches explicitly. Inspect any repository merge policy; if it requires a merge commit or
   squash instead, obtain an explicit override before using this linear workflow. Check out the target branch in its
   intended integration worktree; resolve source-branch conflicts and run its tests before entering that worktree.
2. Require a clean worktree; do not discard, stash, or commit unrelated changes to make it clean. Inspect both branches
   and verify that the target is an ancestor of the source with `git merge-base --is-ancestor <target> <source>`.
3. If it fails, the source must first be reconciled with the target. Do not silently rebase, merge, or squash it; obtain
   the user's direction or follow an already selected reconciliation workflow.
4. Immediately before merging, re-resolve the target ref and repeat the ancestry check. Then run
   `git merge --ff-only <source>` from the target branch.

## Verification

Run `git status --short`, `git log --graph --oneline --decorate -n 15`, and `git rev-parse <target> <source>`. Confirm
the target now equals the source and no merge commit was created.
