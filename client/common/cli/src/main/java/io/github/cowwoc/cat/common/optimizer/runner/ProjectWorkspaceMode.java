package io.github.cowwoc.cat.common.optimizer.runner;

/**
 * Selects the project filesystem visible to one native call.
 */
public enum ProjectWorkspaceMode
{
	/**
	 * The selected live Git worktree.
	 */
	ACTIVE_WORKTREE,
	/**
	 * A runner-owned copy of the selected project.
	 */
	ISOLATED_COPY
}
