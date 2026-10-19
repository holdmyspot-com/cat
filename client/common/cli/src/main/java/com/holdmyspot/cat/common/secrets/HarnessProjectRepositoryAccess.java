package com.holdmyspot.cat.common.secrets;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Exposes the Git commands and environment that create a run's baseline to the module's tests.
 */
public interface HarnessProjectRepositoryAccess
{
	/**
	 * Returns the commands that create the baseline, in order.
	 *
	 * @return the commands
	 */
	List<List<String>> commands();

	/**
	 * Returns the environment that isolates the baseline's Git commands from the user's configuration.
	 *
	 * @param parentEnvironment the environment resolved by the production entry point
	 * @param gitHome the run-owned directory that Git uses as its home
	 * @return the Git environment
	 */
	Map<String, String> environment(Map<String, String> parentEnvironment, Path gitHome);
}
