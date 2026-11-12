package com.holdmyspot.cat.common.optimizer.workspace;

import java.io.IOException;

/**
 * Keeps the optimization worktree linked to the base's repository. Git links a worktree to its repository by absolute
 * paths in both directions, so moving or copying the project leaves the worktree naming the old location.
 * <p>
 * <b>Thread Safety:</b> Implementations must be thread-safe.
 */
public interface WorktreeLinks
{
	/**
	 * Repairs the links between the optimization worktree and the base's repository when they name another location.
	 *
	 * @return {@code true} if the links name the worktree and the base's repository afterwards; {@code false} if the
	 *   worktree names no registration that the base's repository can give it
	 * @throws IOException if the base's Git fails or the links cannot be read or written
	 */
	boolean repair() throws IOException;

	/**
	 * Replaces the optimization worktree with a new one, registered with the base's repository at the base's
	 * {@code HEAD}. The caller then writes the files that the worktree must hold.
	 *
	 * @throws IOException if the worktree cannot be deleted or Git cannot add the new one
	 */
	void recreate() throws IOException;
}
