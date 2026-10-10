package io.github.cowwoc.cat.common.secrets;

import io.github.cowwoc.cat.common.optimizer.cli.CommandRefusedException;
import io.github.cowwoc.cat.common.optimizer.cli.OptimizerCommandScope;
import io.github.cowwoc.cat.common.optimizer.execution.SliceSettings;
import io.github.cowwoc.cat.common.optimizer.store.CampaignStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Exposes the project values that a command scope resolves to the module's tests.
 */
public interface OptimizerCommandScopeAccess
{
	/**
	 * Returns a scope's slice time budget: the 8-minute budget plus the 1-minute grace period that fits the
	 * skill's 10-minute tool timeout.
	 *
	 * @param scope the scope
	 * @return the budget
	 * @throws IllegalStateException if {@code scope} is closed
	 */
	SliceSettings sliceSettings(OptimizerCommandScope scope);

	/**
	 * Returns a scope's project root: the directory that holds {@code .git}, at or above the working directory.
	 *
	 * @param scope the scope
	 * @return the root
	 * @throws IllegalStateException if {@code scope} is closed
	 * @throws CommandRefusedException if the working directory is not inside a Git repository
	 * @throws IOException if the working directory cannot be resolved
	 */
	Path projectRoot(OptimizerCommandScope scope) throws CommandRefusedException, IOException;

	/**
	 * Returns the campaign store of a scope's project.
	 *
	 * @param scope the scope
	 * @return the store
	 * @throws IllegalStateException if {@code scope} is closed
	 * @throws CommandRefusedException if the working directory is not inside a Git repository
	 * @throws IOException if the working directory cannot be resolved
	 */
	CampaignStore store(OptimizerCommandScope scope) throws CommandRefusedException, IOException;

	/**
	 * Lists the {@code held_out} directories of the campaigns of a scope's project.
	 *
	 * @param scope the scope
	 * @return the real paths of those that exist
	 * @throws IllegalStateException if {@code scope} is closed
	 * @throws IOException if the working directory is not inside a Git repository or the campaigns cannot be
	 *   listed
	 */
	List<Path> heldOutDirectories(OptimizerCommandScope scope) throws IOException;
}
