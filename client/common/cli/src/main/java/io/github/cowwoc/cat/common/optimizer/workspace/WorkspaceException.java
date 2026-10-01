package io.github.cowwoc.cat.common.optimizer.workspace;

import java.io.IOException;

/**
 * Thrown when the user must change the project before a workspace operation can succeed, such as committing a dirty
 * base worktree or resolving a symbolic link that cannot be staged.
 */
public final class WorkspaceException extends IOException
{
	private static final long serialVersionUID = 1L;

	/**
	 * Creates an exception.
	 *
	 * @param message a message that tells the user what to change
	 */
	public WorkspaceException(String message)
	{
		super(message);
	}
}
