package io.github.cowwoc.cat.common.optimizer.workspace;

import io.github.cowwoc.cat.common.SessionWorkDirectory;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.port.WorkspacePort;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * What the commands use of a campaign's fork: its recorded files, its workspace, the extension of its participants,
 * and the earlier forks that the campaign ran on. {@link Fork} is the Git-backed implementation; tests supply an
 * in-memory one.
 * <p>
 * <b>Thread Safety:</b> Implementations are not thread-safe.
 */
public interface ForkView
{
	/**
	 * The line that creating a fork appends to the repository's local exclude when CAT's work directory is not
	 * already ignored.
	 */
	String EXCLUDE_ENTRY = "/" + SessionWorkDirectory.WORK_ROOT + "/";

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
	 * Indicates whether creating this fork appended CAT's work directory to the repository's local exclude.
	 * Opening an existing fork returns {@code false}, so the notice belongs only to the command that created it.
	 *
	 * @return {@code true} if creation appended the line
	 */
	boolean excludeAdded();

	/**
	 * Makes the fork's files hold exactly the given participants beside the paths that the fork was created with. A
	 * participant that the fork has not recorded yet is copied from the base. A participant that an earlier call
	 * recorded and this call omits leaves the fork's files, but its copy is kept, so a later call that names it again
	 * restores that copy rather than reading the live file. A participant that the fork was created with is left
	 * alone.
	 *
	 * @param base the base worktree that the fork was created from
	 * @param participants the paths to hold: relative to the base for files inside the project, absolute for external
	 *   files
	 * @return the fork with the selected participants; the receiver stays unchanged, so any overlay on its inventory
	 *   must be saved again for the returned fork's inventory
	 * @throws NullPointerException if any of the arguments are null
	 * @throws WorkspaceException if a path that the fork has not recorded does not exist
	 * @throws IOException if the files cannot be copied or written
	 */
	ForkView selectParticipants(Path base, Set<Path> participants) throws IOException;

	/**
	 * Returns a fork that holds this fork's files and the given participants, for a revision after confirmation. A
	 * participant that the fork has not recorded yet is copied from the base, and a recorded one keeps its recorded
	 * copy. Nothing leaves the fork. The campaign's current fork and the optimization worktree stay unchanged until
	 * {@link #adopt()} makes the returned fork current.
	 *
	 * @param base the base worktree that the fork was created from
	 * @param participants the paths to add: relative to the base for files inside the project, absolute for external
	 *   files
	 * @return the larger fork
	 * @throws NullPointerException if any of the arguments are null
	 * @throws WorkspaceException if a path that the fork has not recorded does not exist
	 * @throws IOException if the files cannot be copied or written
	 */
	ForkView extend(Path base, Set<Path> participants) throws IOException;

	/**
	 * Returns a fork that holds this fork's files and the given participants that an earlier call recorded. Nothing
	 * is recorded, so a participant that no call recorded stays out of the returned fork.
	 *
	 * @param participants the paths to add, named as for {@link #extend(Path, Set)}
	 * @return the fork
	 * @throws NullPointerException if {@code participants} is null
	 */
	ForkView including(Set<Path> participants);

	/**
	 * Makes this fork the campaign's current fork, which {@link Forks#open(Path, Path)} returns from then on. The fork
	 * that was current stays available through {@link #version(Sha256)}. The optimization worktree is left alone.
	 *
	 * @throws IOException if the fork's selection cannot be written
	 */
	void adopt() throws IOException;

	/**
	 * Looks up the files of a fork that the campaign has run on: this fork, the campaign's current fork, or an earlier
	 * fork that {@link #adopt()} replaced.
	 *
	 * @param identity the fork's identity
	 * @return the fork's files, or empty if the campaign never ran on such a fork
	 * @throws NullPointerException if {@code identity} is null
	 * @throws IOException if the stored forks cannot be read
	 */
	Optional<FileInventory> version(Sha256 identity) throws IOException;
}
