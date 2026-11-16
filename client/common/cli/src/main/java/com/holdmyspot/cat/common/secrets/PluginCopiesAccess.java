package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.PluginRemovals;
import com.holdmyspot.cat.common.optimizer.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Exposes the plugin-copying operations that {@link Workspace} registers for a storage directory.
 */
@FunctionalInterface
public interface PluginCopiesAccess
{
	/**
	 * The copying operations of one storage directory.
	 */
	interface Copies
	{
		/**
		 * Returns a trial plugin copy for a staged source and its removals.
		 *
		 * @param plugin the trial plugin
		 * @param source the staged source
		 * @param removals the dependents left out of the copy
		 * @return the installed plugin base or built plugin copy
		 * @throws IOException if the copy cannot be made or its base or dependent is missing
		 */
		Path copy(TrialPlugin plugin, Path source, PluginRemovals removals) throws IOException;

		/**
		 * Copies a plugin directory as a trial plugin's base.
		 *
		 * @param directory the plugin directory
		 * @return the digest of the base's files
		 * @throws IOException if the directory cannot be read or copied, or if its files change during the copy
		 */
		String copyBase(Path directory) throws IOException;

		/**
		 * Returns the copied base directory for its digest.
		 *
		 * @param digest the digest of the base's files
		 * @return the absolute directory
		 */
		Path base(String digest);

		/**
		 * Deletes the plugin copies for a candidate's staged sources.
		 *
		 * @param candidate the candidate's identity
		 * @throws IOException if a copy cannot be deleted
		 */
		void delete(Sha256 candidate) throws IOException;
	}

	/**
	 * Creates copying operations for the supplied storage directory.
	 *
	 * @param storage the directory that holds plugin copies and copied plugin bases
	 * @return the copying operations for the directory
	 */
	Copies create(Path storage);
}
