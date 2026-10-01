package io.github.cowwoc.cat.common.optimizer.workspace;

import java.io.IOException;
import java.nio.file.Path;
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
}
