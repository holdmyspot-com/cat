# Cleaning Up Your Working Files

## Design Goals

- An agent deletes each temporary file or directory, clone, worktree, branch, and build output that it created for its
	own work, or that it was handed together with the duty to delete it, as soon as it has no reason to believe that
	anything will still need it, so that none of them remains after the merge, abandonment, step, work item, or task that
	last needed it.
- An agent keeps each of those artifacts that something still needs, such as work still meant to be merged or reported,
	evidence that an undelivered report or the user relies on, or anything a live process or agent uses, and never
	deletes an artifact that another session or the user owns.

## Guidance

This rule covers what you create through your own tool calls while working:

- temporary files and directories, such as briefs, probe inputs, saved command output, and downloads;
- clones and worktrees, and the branches you create for them;
- build output inside those clones and worktrees;
- files and folders you create in your session directory, the `.cat/sessions/<session id>/` directory that contains
	`${CAT_TEMP}`.

It also covers an artifact that another agent or the user handed to you together with the duty to delete it, such as a
delegate's clone that you are to integrate. Note each artifact's path when you create or receive it, so that you can
later tell what is yours.

Check your artifacts right after you merge a clone's or worktree's changes, when a step or work item finishes, when you
abandon work, and before you hand back your result or send your final reply. At the last of these, check every artifact
of the task. Delete each one that nothing still needs:

- A clone or worktree, with the branch you created for it, once every change on it that is to be kept is in its base
	branch: you merged or cherry-picked each of its commits, or `git log <base>..<branch>` prints nothing. Remove a
	worktree with `git worktree remove <path>` and its branch with `git branch -d <branch>`; delete a clone's directory.
- A clone, worktree, branch, and the scratch files of work that is abandoned: aborted, superseded, or rejected, with no
	plan to merge it. Use `git branch -D <branch>` for a branch whose commits are not merged.
- Build output in a clone or worktree that you keep, once no remaining step of yours uses it, unless your brief or the
	agent that receives the clone asks you to keep it.
- Scratch inputs and outputs, once the step that used them has finished and no later step reads them.

Keep an artifact while any of these holds, and delete it once none does:

- It holds work that is still meant to be merged or reported, such as uncommitted changes or commits that are not yet in
	the base branch.
- A report that you have not yet delivered cites it, or you told the user or another agent that they can inspect it.
- A background process, delegate, or other session that is still running uses it or was given its path.

When an artifact must outlive your work, such as a clone of unmerged work that you hand back, name its path in your
hand-back or final reply, say what still needs it, and say who deletes it. The agent that integrates a handed-back clone
deletes it after merging its changes.

Delete only what you created or were handed with the duty to delete. Never delete another session's directory, a clone,
worktree, or branch that the user or another agent created, or a file that the user put in the project, even when it
looks temporary or abandoned. Leave the files that CAT writes in your session directory. When you cannot tell whether
you created an artifact, leave it and name it in your report.
