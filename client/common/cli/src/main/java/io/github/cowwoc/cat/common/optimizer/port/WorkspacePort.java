package io.github.cowwoc.cat.common.optimizer.port;

import io.github.cowwoc.cat.common.optimizer.contract.TrialPlugin;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * Prepares the directories that nested sessions work in, and keeps the optimization worktree in step with the working
 * candidate.
 * <ul>
 *   <li>A staged source is a directory without symbolic links that holds one candidate's files. The runner copies it
 *   for each trial session.</li>
 *   <li>An authoring copy is a directory that holds the working candidate's files for one prompt author session to
 *   edit. Scanning it after the session yields the prompt author's changes.</li>
 *   <li>The optimization worktree is CAT's detached Git worktree of the base, which holds the working candidate's
 *   files.</li>
 * </ul>
 */
public interface WorkspacePort
{
	/**
	 * Reads a file from a saved candidate, resolving its recorded symbolic links. Live project files are not read.
	 *
	 * @param candidate the saved overlay on the fork
	 * @param path the inventory path of the file
	 * @return the recorded bytes, or empty if the snapshot contains no regular file at that path
	 * @throws NullPointerException if an argument is null
	 * @throws IllegalArgumentException if the candidate belongs to another fork or the path is invalid
	 * @throws IOException if the recorded bytes cannot be read
	 */
	Optional<FileContent> readFile(Overlay candidate, Path path) throws IOException;

	/**
	 * Returns a staged source for a candidate, building it if it does not exist.
	 *
	 * @param candidate the candidate's overlay on the fork
	 * @param excluded  the project-relative paths to leave out of the source, with everything below them
	 * @return the directory that trial sessions copy
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if an excluded path is absolute
	 * @throws IOException if the source cannot be built
	 */
	Path stage(Overlay candidate, Set<Path> excluded) throws IOException;

	/**
	 * Deletes a candidate's staged source. Deleting a source that does not exist does nothing.
	 *
	 * @param candidate the candidate's identity
	 * @throws NullPointerException if {@code candidate} is null
	 * @throws IOException if the source cannot be deleted
	 */
	void deleteStaged(Sha256 candidate) throws IOException;

	/**
	 * Returns a trial plugin as the trials of a staged source install it, building it if it does not exist. A plugin
	 * that the project builds is a copy of its base with each file of the staged source that a mapping covers written
	 * over its mapped path, and then the removals applied; deleting the staged source's candidate deletes the copy.
	 * Any other plugin is its base copy.
	 *
	 * @param plugin the trial plugin
	 * @param source the staged source
	 * @param removals the dependents that the copy of a project-built plugin leaves out
	 * @return the plugin directory that each trial of {@code source} installs
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if the copy cannot be built, if the plugin's base copy no longer has the identity of its
	 *   base digest, or if the copy does not hold a dependent
	 */
	Path pluginCopy(TrialPlugin plugin, Path source, PluginRemovals removals) throws IOException;

	/**
	 * Copies a plugin directory into campaign storage as a trial plugin's base, unless storage holds a base with the
	 * same files already, so that later changes to the directory do not reach trials.
	 *
	 * @param directory the plugin directory: a build output in the optimization worktree, or the user's installed copy
	 * @return the digest of the base's files
	 * @throws NullPointerException if {@code directory} is null
	 * @throws IOException if the directory cannot be read or copied
	 */
	String copyPluginBase(Path directory) throws IOException;

	/**
	 * Returns where campaign storage holds a trial plugin's base.
	 *
	 * @param digest the digest of the base's files
	 * @return the absolute directory, which exists once {@link #copyPluginBase(Path)} has copied the base
	 * @throws NullPointerException if {@code digest} is null
	 */
	Path pluginBase(String digest);

	/**
	 * Returns the optimization worktree's directory, where CAT runs the build commands of the trial plugins that the
	 * project builds.
	 *
	 * @return the absolute directory
	 */
	Path worktree();

	/**
	 * Prepares a copy of the working candidate for a prompt author session.
	 *
	 * @param working the working candidate's overlay on the fork
	 * @return the directory that the prompt author edits
	 * @throws NullPointerException if {@code working} is null
	 * @throws IOException if the copy cannot be prepared
	 */
	Path prepareAuthoringCopy(Overlay working) throws IOException;

	/**
	 * Scans an authoring copy after its prompt author session ends.
	 *
	 * @param copy a directory that {@link #prepareAuthoringCopy(Overlay)} returned
	 * @return the copy's inventory
	 * @throws NullPointerException if {@code copy} is null
	 * @throws IOException if the copy cannot be read
	 */
	FileInventory scanAuthoringCopy(Path copy) throws IOException;

	/**
	 * Scans the effects of a prompt author session that ran on an authoring copy, under the rules that
	 * {@link #scanAuthoringCopy(Path)} applies to the copy. Comparing the copy's inventory with the effects' inventory
	 * yields the prompt author's delta.
	 *
	 * @param copy a directory that {@link #prepareAuthoringCopy(Overlay)} returned, which the session's project source
	 *   was
	 * @param effects the directory that holds the session's project effects
	 * @return the effects' inventory
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if the effects cannot be read
	 */
	FileInventory scanPromptAuthorEffects(Path copy, Path effects) throws IOException;

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
