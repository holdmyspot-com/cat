package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.runner.HarnessProjectRepository;

import java.nio.file.Path;
import java.util.Map;

/**
 * Exposes {@link HarnessProjectRepository}'s isolated Git environment to the module's tests.
 */
@FunctionalInterface
public interface HarnessProjectRepositoryAccess
{
	/**
	 * Returns the environment that isolates the baseline's Git commands from the user's configuration.
	 *
	 * @param parentEnvironment the environment resolved by the production entry point
	 * @param gitHome           the run-owned directory that Git uses as its home
	 * @return the Git environment
	 */
	Map<String, String> environment(Map<String, String> parentEnvironment, Path gitHome);
}
