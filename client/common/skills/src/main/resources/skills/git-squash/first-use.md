# Git Squash

## Design Goals

- Rewrite an authorized commit range into exactly the user-approved commit groups.
- Preserve a recoverable backup through verification, then remove temporary recovery state after a successful rewrite
  while retaining the intended final tree, Git identity, and quality gates.
- Leave ownership and placement unchanged unless the user explicitly includes a change in a selected group.

## Procedure

1. Use this skill when the user identifies commits or groups to combine. If the grouping is unclear, ask for it. Do not
   infer topic organization; use Commit Git History One Topic at a Time only when the user explicitly requests
   responsibility-first history.
2. Record the subject branch, authorized range, selected source commits and destination groups, identity policy, and
   quality gates. Capture the branch tip, pre-rewrite references, and tree ID, then create a recoverable backup. The
   authorized range is a scope contract: use the exact range the user named. For the full branch, use its root and
   captured tip; do not substitute `main` or another branch unless the user explicitly asks to squash against, onto, or
   into it. When the user names a target but not a first source commit, derive the range from that target's merge base
   through the captured tip and show it for confirmation.
3. Confirm each selected source is assigned once and unselected commits remain separate. Use one planned rewrite from
   the earliest selected commit, preserving original authors unless the approved identity policy requires normalization.
4. Combine only the selected commits in their requested groups. Preserve group order and unrelated commit order. Resolve
   conflicts only to preserve the recorded final tree and requested groups.
5. Do not move, split, fold, or absorb a correction, test, style change, refactor, documentation, configuration, plan,
   or performance change merely because of its path, commit type, or relationship to an earlier feature. If the
   requested grouping cannot yield a buildable or meaningful intermediate commit, explain the conflict and request a
   revised grouping.
6. Treat a temporary aggregation commit, rewrite plan, staging branch, or conflict-resolution state as an intermediate
   artifact, never as completion. Do not report completion or return control until the requested groups exist in the
   rewritten history, every intermediate artifact is no longer reachable from the subject branch, and all verification
   evidence below succeeds.
7. When selected commits are not at the branch tip, use an interactive rewrite that changes only the recorded commits.
   If the request combines dropping commits with squashing others, perform and verify the drop and combine phases
   separately. Do not use `git reset --soft` or synthesize a replacement commit from the whole working tree after a
   safety block or conflict.
8. After any conflict or automatic reconciliation, inspect comments and documentation in the changed files against the
   resulting behavior. Amend a necessary correction into the affected destination group; do not leave it as a separate
   follow-up commit that violates the approved grouping.
9. For non-adjacent, reordered, or otherwise complex groups, obtain an independent grouping and conflict-review plan
   when a reviewer is available. Validate every proposed commit and path against the recorded scope; the primary actor
   remains responsible for the history rewrite and its verification.

## Verification

1. Verify each selected source appears once in its requested destination group.
2. Verify no unselected semantic change was absorbed, split, moved, or dropped.
3. Verify the rewritten tip tree matches the recorded tree unless an explicitly approved difference is recorded. When
   comparing tree objects after a rewrite, use `git diff --exit-code "${CAPTURED_TIP}^{tree}" HEAD^{tree}`; do not use a
   three-dot range, whose merge base may change during the rewrite.
4. Verify that no temporary aggregation commit, staging branch, or other intermediate rewrite artifact remains reachable
   from the subject branch.
5. Verify changed comments and documentation still describe the final behavior, including every automatically reconciled
   file.
6. Run the applicable shared quality and identity checks. After every verification check succeeds, delete the temporary
   backup created for this rewrite and verify that its ref no longer exists. Keep that backup only while verification is
   failed or incomplete, or when the user explicitly asks to retain it. Report the rewritten groups and terminal
   verification result.
