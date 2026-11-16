package com.holdmyspot.cat.common.optimizer.workspace;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Adds, recreates, removes and repairs Git's registration of a campaign's optimization worktree, which lies in the
 * {@code worktree} directory of the campaign directory. Git records the registration in the {@code worktrees}
 * directory of the project's repository.
 * <p>
 * <b>Thread Safety:</b> Implementations must be thread-safe.
 */
public interface WorktreeRegistrar
{
	/**
	 * Prunes the registrations of worktrees that are gone, then adds the campaign's worktree, detached at the
	 * project's {@code HEAD}, creating the directory that holds the registrations if the repository has none.
	 *
	 * @param project  the project, which is the top-level directory of its repository
	 * @param campaign the campaign directory
	 * @throws NullPointerException      if any of the arguments are null
	 * @throws WorktreeRefusedException  if the operation is refused before it changes anything
	 * @throws IOException               if Git fails
	 */
	void add(Path project, Path campaign) throws IOException;

	/**
	 * Deletes the campaign's worktree, prunes the registrations of worktrees that are gone, and adds the worktree again
	 * as {@link #add(Path, Path)} does. The caller then writes the files that the worktree must hold.
	 *
	 * @param project  the project, which is the top-level directory of its repository
	 * @param campaign the campaign directory
	 * @throws NullPointerException      if any of the arguments are null
	 * @throws WorktreeRefusedException  if the operation is refused before it changes anything
	 * @throws IOException               if the worktree cannot be deleted or Git fails
	 */
	void recreate(Path project, Path campaign) throws IOException;

	/**
	 * Removes the campaign's worktree and Git's registration of it. The links of a worktree whose {@code .git} file
	 * exists are repaired first, as {@link #repair(Path, Path)} does, so that Git removes the registration that the
	 * project's repository holds. A worktree that does not exist leaves only the prune of the registrations of
	 * worktrees that are gone.
	 *
	 * @param project  the project, which is the top-level directory of its repository
	 * @param campaign the campaign directory
	 * @throws NullPointerException      if any of the arguments are null
	 * @throws WorktreeRefusedException  if the operation is refused before it changes anything
	 * @throws IOException               if the worktree cannot be deleted or Git fails
	 */
	void remove(Path project, Path campaign) throws IOException;

	/**
	 * Repairs the links between the campaign's worktree and the project's repository when they name another location:
	 * the worktree's {@code .git} file and its registration's {@code gitdir} file. Only the registration that the
	 * {@code .git} file names is repaired, and only when its {@code gitdir} names the {@code .git} file of the same
	 * campaign's worktree, below the same session, at whichever location the project had.
	 *
	 * @param project  the project, which is the top-level directory of its repository
	 * @param campaign the campaign directory
	 * @return {@code true} if the links name the worktree and the project's repository afterwards; {@code false} if
	 *   the worktree names no registration that the repository can give it, so that only
	 *   {@link #recreate(Path, Path)} reconnects it
	 * @throws NullPointerException      if any of the arguments are null
	 * @throws WorktreeRefusedException  if the operation is refused before it changes anything
	 * @throws IOException               if Git fails or the links cannot be read or written
	 */
	boolean repair(Path project, Path campaign) throws IOException;
}
