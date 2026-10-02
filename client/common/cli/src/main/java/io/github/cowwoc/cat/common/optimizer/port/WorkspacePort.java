package io.github.cowwoc.cat.common.optimizer.port;

import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Prepares the directories that nested sessions work in, and keeps the optimization worktree in step with the working
 * candidate.
 * <ul>
 *   <li>A staged source is a directory without symbolic links that holds one candidate's files. The runner copies it
 *   for each trial session.</li>
 *   <li>An authoring copy is a directory that holds the working candidate's files for one author session to edit.
 *   Scanning it after the session yields the author's changes.</li>
 *   <li>The optimization worktree is CAT's detached Git worktree of the base, which holds the working candidate's
 *   files.</li>
 * </ul>
 */
public interface WorkspacePort
{
	/**
	 * Returns a staged source for a candidate, building it if it does not exist.
	 *
	 * @param candidate the candidate's overlay on the fork
	 * @return the directory that trial sessions copy
	 * @throws NullPointerException if {@code candidate} is null
	 * @throws IOException if the source cannot be built
	 */
	Path stage(Overlay candidate) throws IOException;

	/**
	 * Deletes a candidate's staged source. Deleting a source that does not exist does nothing.
	 *
	 * @param candidate the candidate's identity
	 * @throws NullPointerException if {@code candidate} is null
	 * @throws IOException if the source cannot be deleted
	 */
	void deleteStaged(Sha256 candidate) throws IOException;

	/**
	 * Prepares a copy of the working candidate for an author session.
	 *
	 * @param working the working candidate's overlay on the fork
	 * @return the directory that the author edits
	 * @throws NullPointerException if {@code working} is null
	 * @throws IOException if the copy cannot be prepared
	 */
	Path prepareAuthoringCopy(Overlay working) throws IOException;

	/**
	 * Scans an authoring copy after its author session ends.
	 *
	 * @param copy a directory that {@link #prepareAuthoringCopy(Overlay)} returned
	 * @return the copy's inventory
	 * @throws NullPointerException if {@code copy} is null
	 * @throws IOException if the copy cannot be read
	 */
	FileInventory scanAuthoringCopy(Path copy) throws IOException;

	/**
	 * Scans the effects of an author session that ran on an authoring copy, under the rules that
	 * {@link #scanAuthoringCopy(Path)} applies to the copy. Comparing the copy's inventory with the effects' inventory
	 * yields the author's delta.
	 *
	 * @param copy a directory that {@link #prepareAuthoringCopy(Overlay)} returned, which the session's project source
	 *   was
	 * @param effects the directory that holds the session's project effects
	 * @return the effects' inventory
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if the effects cannot be read
	 */
	FileInventory scanAuthorEffects(Path copy, Path effects) throws IOException;

	/**
	 * Deletes an authoring copy. Deleting a copy that does not exist does nothing.
	 *
	 * @param copy a directory that {@link #prepareAuthoringCopy(Overlay)} returned
	 * @throws NullPointerException if {@code copy} is null
	 * @throws IOException if the copy cannot be deleted
	 */
	void deleteAuthoringCopy(Path copy) throws IOException;

	/**
	 * Scans the optimization worktree, for example to detect a change that CAT did not make.
	 *
	 * @return the worktree's inventory
	 * @throws IOException if the worktree cannot be read
	 */
	FileInventory scanWorktree() throws IOException;

	/**
	 * Writes a candidate's files into the optimization worktree, for example before delivery.
	 *
	 * @param candidate the candidate's overlay on the fork
	 * @throws NullPointerException if {@code candidate} is null
	 * @throws IOException if the worktree cannot be updated
	 */
	void materialize(Overlay candidate) throws IOException;
}
