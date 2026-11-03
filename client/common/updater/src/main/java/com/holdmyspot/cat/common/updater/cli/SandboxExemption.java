package com.holdmyspot.cat.common.updater.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Supplies the permission entries the user explicitly adds to let routine updates leave a harness sandbox.
 */
public interface SandboxExemption
{
	/**
	 * Returns the identifier of the harness whose permission syntax this provider supplies.
	 *
	 * @return the harness identifier
	 */
	String harness();

	/**
	 * Builds the permission entries for a stable updater launcher and the policy-recorded harness home.
	 *
	 * @param stableLauncher the absolute path of the installed stable updater launcher
	 * @param harnessHome    the absolute home directory recorded in the trust policy
	 * @return an unmodifiable map from each setting name or permission-file path to its entries
	 */
	Map<String, List<String>> entries(Path stableLauncher, Path harnessHome);
}
