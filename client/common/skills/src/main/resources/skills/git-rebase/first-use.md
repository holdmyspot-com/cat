# Git Rebase

## Design Goals

- Replay the current branch onto an explicit base while preserving its intended commits.
- Preserve a backup, explicit conflict recovery, and verified resulting history.
- Preserve applicable behavior introduced on the new base when a replayed branch renamed, replaced, or otherwise changed
  the files that should carry that behavior.

## Procedure

1. Require a clean worktree. Inspect any repository merge policy, resolve `<base>` to an immutable commit, inspect
   `git log --oneline <base>..HEAD`, and confirm that rewriting is intended. Obtain explicit approval if the commits may
   be shared or pushed.
2. Before creating a backup, compare renamed or removed base paths with the replayed paths and their literal path
   references. Move or update the replayed branch to the known replacement when that mapping is unambiguous; otherwise
   stop for direction. Do not begin a rebase merely to discover a predictable repository-wide path mismatch as
   conflicts.
3. Record the original merge base, the resolved new-base commit, and the union of files touched by the commits to
   replay.
   Inspect the base delta from the original merge base through the resolved new base. For each behaviorally meaningful
   base change that intersects a replayed file or its replacement, classify the needed result as `PORT`, `KEEP`, or
   `UNCERTAIN`, with evidence. Do not use only the final net diff: a replayed commit may have touched a file whose
   final diff is empty. When an independent reviewer is available, have it review the intent map and candidate
   classifications;
   retain its evidence, but keep history mutations with the primary actor. If review output is incomplete or invalid,
   retry once with the missing evidence named; then record the limitation and perform the same review directly rather
   than accepting an unsupported classification.
4. Create a named backup branch at `HEAD`, then run `git rebase <base>`.
5. On conflict, inspect `git status`, resolve only the reported files, stage them, and run `git rebase --continue`. Use
   `git rebase --abort` when the intended resolution is unclear. Do not use `--skip` without explicit approval. For a
   rename/delete conflict, compare the base's changes to the deleted path with its replacement before accepting the
   deletion; port any still-applicable behavior to the replacement in the same resolution. During a rebase, use `HEAD`
   for the base version and `REBASE_HEAD` for the replayed commit rather than ambiguous `--ours` or `--theirs` names.
6. Apply every high-confidence `PORT` after the replay, then verify the resulting behavior. Investigate every
   high-confidence `UNCERTAIN`; a completed Git replay alone is not completion of this workflow. Obtain approval before
   a port that materially changes behavior beyond the requested rebase unless the repository policy explicitly delegates
   that authority. Keep each port as an identifiable post-replay edit so it can be reversed independently if requested,
   then rerun the semantic-port verification.

## Verification

Run `git status --short`, `git merge-base --is-ancestor <base> HEAD`, and `git log --oneline <base>..HEAD`. Remove the
backup only after those checks and the semantic-port review pass. Record the base changes inspected, each
`PORT`/`KEEP`/`UNCERTAIN` decision, applied ports (or why none applied), and behavior-level verification. Retain the
backup when any decision or verification remains unresolved.
