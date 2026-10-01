package io.github.cowwoc.cat.common.optimizer.workspace;

import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.port.WorkspacePort;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/**
 * What the commands use of a campaign's fork: its recorded files, its workspace, and the extension of its
 * participants. {@link Fork} is the Git-backed implementation; tests supply an in-memory one.
 * <p>
 * <b>Thread Safety:</b> Implementations are not thread-safe.
 */
public interface ForkView
{
	/**
	 * Returns the recorded files.
	 *
	 * @return the fork's inventory
	 */
	FileInventory inventory();

	/**
	 * Returns the workspace that stages sources and manages the optimization worktree.
	 *
	 * @return the workspace
	 */
	WorkspacePort workspace();

	/**
	 * Records further participants in the fork. A participant that the fork already holds is left alone.
	 *
	 * @param base the base worktree that the fork was created from
	 * @param participants the paths to add: relative to the base for files inside the project, absolute for external
	 *   files
	 * @return the extended fork; the receiver stays unchanged
	 * @throws NullPointerException if any of the arguments are null
	 * @throws WorkspaceException if a path does not exist
	 * @throws IOException if the files cannot be copied or written
	 */
	ForkView addParticipants(Path base, Set<Path> participants) throws IOException;
}
