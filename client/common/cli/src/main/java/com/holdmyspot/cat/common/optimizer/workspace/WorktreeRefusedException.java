package com.holdmyspot.cat.common.optimizer.workspace;

import java.io.IOException;

/**
 * Thrown when an operation on the registration of a campaign's optimization worktree is refused before it changes
 * anything, such as when no request server serves the session that would have to carry it out. The message says why
 * and what the user can do.
 */
public final class WorktreeRefusedException extends IOException
{
	private static final long serialVersionUID = 1L;

	/**
	 * Creates an exception.
	 *
	 * @param message why the operation is refused and what the user can do
	 */
	public WorktreeRefusedException(String message)
	{
		super(message);
	}
}
