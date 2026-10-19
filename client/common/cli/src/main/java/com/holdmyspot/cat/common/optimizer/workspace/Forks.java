package com.holdmyspot.cat.common.optimizer.workspace;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Creates and opens the forks of campaigns, so that a test replaces Git with an in-memory fork.
 * <p>
 * <b>Thread Safety:</b> Implementations are not thread-safe.
 */
public interface Forks
{
	/**
	 * Forks a project.
	 *
	 * @param base the base worktree
	 * @param campaign the campaign storage directory
	 * @param participants the paths to record beyond the tracked files
	 * @return the fork
	 * @throws WorkspaceException if the base has uncommitted changes or a participant does not exist
	 * @throws IOException if the fork cannot be created
	 */
	ForkView create(Path base, Path campaign, Set<Path> participants) throws IOException;

	/**
	 * Lists the files that Git tracks in a project.
	 *
	 * @param base the base worktree
	 * @return the files, relative to {@code base}
	 * @throws IOException if Git fails
	 */
	Set<Path> tracked(Path base) throws IOException;

	/**
	 * Returns the Git diff of paths in a project against its {@code HEAD}, including files that {@code HEAD} lacks.
	 * The project's index is left unchanged.
	 *
	 * @param base the base worktree
	 * @param paths the paths to compare, relative to {@code base}
	 * @return the diff, or an empty string if no path differs from {@code HEAD}
	 * @throws IOException if Git fails
	 */
	String diff(Path base, List<Path> paths) throws IOException;

	/**
	 * Opens the fork that a campaign holds.
	 *
	 * @param base the base worktree
	 * @param campaign the campaign storage directory
	 * @return the fork
	 * @throws WorkspaceException if the campaign holds no fork
	 * @throws IOException if the fork cannot be read
	 */
	ForkView open(Path base, Path campaign) throws IOException;

	/**
	 * Indicates whether a campaign holds a fork.
	 *
	 * @param campaign the campaign storage directory
	 * @return {@code true} if the campaign holds an optimization worktree
	 */
	boolean exists(Path campaign);

	/**
	 * Removes the optimization worktree that a campaign holds. Removing a worktree that does not exist does nothing.
	 *
	 * @param base the base worktree
	 * @param campaign the campaign storage directory
	 * @throws IOException if the worktree cannot be removed
	 */
	void remove(Path base, Path campaign) throws IOException;
}
