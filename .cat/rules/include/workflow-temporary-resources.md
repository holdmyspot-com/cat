# CAT Workflow Temporary Resources

## Design Goals

- Keep every workflow-created artifact that is not intended for commit beneath the CAT temporary root resolved once by
  the owning pouch scope, whether its consumer is the current invocation or a later workflow.
- Preserve required uncommitted evidence until its last recorded consumer finishes, then remove it through an explicit
  cleanup owner rather than treating either invocation completion or an ignored directory as its lifecycle boundary.

## Guidance

For workflows, the owning pouch scope resolves the CAT temporary root once. Its built-in value is the gitignored
`.cat/work/temp` directory in the current project; future configuration may override that value at the scope boundary.
Create a unique child directory per invocation and pass it to every cooperating command. Do not read an environment
variable to rediscover this value.

Before creating a workflow artifact, classify its commit intent and last required consumer. Put an artifact intended for
commit at its version-controlled repository path. Put every other artifact beneath the resolved CAT temporary root:
scratch files, manifests, review packets, downloads, extractions, temporary Git indexes and worktrees, and evidence that
a later review, comparison, retry, recovery, or trend report needs. Do not move uncommitted evidence into a repository
directory merely because it must outlive its creating invocation.

Record the cleanup owner and the last consumer before retaining an uncommitted artifact. Delete it when that consumer
has recorded a terminal result and no retained artifact still refers to it; its creating invocation may finish earlier.
A live owner or a recorded recovery workflow may retain it only until its own terminal cleanup. Do not leave
uncommitted evidence merely because the workflow finished, and do not delete it merely because one invocation finished.

Create an invocation directory with `mktemp -d -p ${CAT_TEMP} <workflow-name>-XXXXXX`. It prints the new directory's
absolute path on one line; use that output as the directory. At the terminal boundary, remove work with no remaining
consumer with `rm -rf -- <directory>`. To retain uncommitted evidence, move the directory with
`mv -- <directory> <retained-directory-under-cat-temp>`, where the retained path does not exist yet, and record that
path and the cleanup owner. Remove or move only a directory below CAT_TEMP that the workflow created, and not one that a
live process still owns.
