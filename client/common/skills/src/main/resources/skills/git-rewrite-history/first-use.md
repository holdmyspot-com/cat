# Git Rewrite History

## Design Goals

- Perform a user-approved repository-history rewrite with `git-filter-repo`.
- Preserve a recoverable backup, the repository's remote configuration, and verify the intended result.

## Procedure

1. Confirm `git filter-repo` is available. Require an explicit rewrite scope and inspect affected refs and paths.
   Resolve
   the approved refs to a concrete, recorded list before rewriting; do not substitute a namespace shorthand, `--all`, or
   another broader selector for that list.
2. Require explicit approval when rewriting shared history. Create a named backup branch and record the pre-rewrite refs
   and tree IDs. Before filtering, create a private temporary backup of every local `remote.*` configuration entry with
   `git config --null --get-regexp '^remote\\.'`; treat its no-match exit status as an empty remote list. This
   NUL-delimited output stores each configuration key, a newline, and its value. Keep the backup until the remote
   configuration is restored and remove it on both success and failure.
3. Run the narrowest `git filter-repo` command that satisfies the approved scope and frozen refs. Do not use
   `git filter-branch`. Use rebase rather than filter-repo when the requested operation is to omit individual commits.
4. After `git filter-repo` returns—whether it succeeds or fails—remove the repository's current `remote.*` entries and
   restore every captured entry with `git config --local --add`. Parse the NUL-delimited backup as key/value pairs so
   repeated values, including multiple fetch or push URLs and fetch refspecs, are retained. Do not replace the entire
   Git configuration file; restore only the remote entries. Capture the filter command's exit status, restore and verify
   the remotes, remove the temporary backup, and only then return the captured status.
5. Verify the restored remote names, fetch URLs, push URLs, and fetch refspecs match the backup, then verify every
   frozen ref, affected path, repository status, and required build or test against the recorded scope. State that every
   collaborator must replace their old history before any authorized remote update. Do not update a remote without
   separate explicit approval; when authorized, use a lease-protected force update.

## Verification

Keep the backup branch until verification succeeds. Report rewritten refs, restored remotes, the verification result,
and that any remote update requires the user's explicit approval.
