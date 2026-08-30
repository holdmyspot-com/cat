# CAT Workflow Temporary Resources

## Design Goals

- Keep every workflow-created artifact that is not intended for commit beneath the CAT work root resolved once by the
  owning pouch scope, whether its consumer is the current invocation or a later workflow.
- Preserve required uncommitted evidence until its last recorded consumer finishes, then remove it through an explicit
  cleanup owner rather than treating either invocation completion or an ignored directory as its lifecycle boundary.

## Guidance

For workflows, the owning pouch scope resolves the CAT work root once. Its built-in value is the gitignored `.cat/work`
directory in the current project. Do not read an environment variable to rediscover or override it. Create a unique
child directory below `${CAT_WORK}/temp` for each invocation and pass it to every cooperating command.

Before creating a workflow artifact, classify its commit intent and last required consumer. Put an artifact intended for
commit at its version-controlled repository path. Put every other artifact beneath the resolved CAT work root:
scratch files, manifests, review packets, downloads, extractions, temporary Git indexes and worktrees, and evidence that
a later review, comparison, retry, recovery, or trend report needs. Do not move uncommitted evidence into a repository
directory merely because it must outlive its creating invocation.

Record the cleanup owner and the last consumer before retaining an uncommitted artifact. Keep invocation-local data
below `${CAT_WORK}/temp`. Keep resumable-session data and other durable uncommitted evidence in a named directory
directly below `${CAT_WORK}`. Delete either kind when its last consumer has recorded a terminal result and no retained
artifact still refers to it; its creating invocation may finish earlier. A live owner may retain data only until its
own terminal cleanup. Do not leave uncommitted evidence merely because the workflow finished, and do not delete it
merely because one invocation finished.

Use the selected harness's `workflow-temp create <workflow-name>` launcher to create a CLI-owned invocation directory.
At the terminal boundary, invoke `workflow-temp delete <directory>` for work with no remaining consumer. To retain
uncommitted evidence, invoke `workflow-temp handoff <directory> <retained-directory-under-cat-work-temp>` and record the
returned location and cleanup owner. Do not delete or hand off a directory that a live process still owns.
