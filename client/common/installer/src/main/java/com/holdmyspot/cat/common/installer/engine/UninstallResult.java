package com.holdmyspot.cat.common.installer.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The outcome of an uninstall.
 *
 * @param outcome      the operation's outcome
 * @param unregistered true if the plugin was unregistered from the harness; false if the harness program that setup
 *                     recorded was missing, so that the harness may still list the plugin, or if the uninstall did not
 *                     complete
 */
public record UninstallResult(UpdateResult outcome, boolean unregistered)
{
	/**
	 * Creates an outcome.
	 *
	 * @param outcome      the operation's outcome
	 * @param unregistered true if the plugin was unregistered from the harness, false if the harness program was
	 *                     missing
	 * @throws NullPointerException if {@code outcome} is null
	 */
	public UninstallResult
	{
		requireThat(outcome, "outcome").isNotNull();
	}
}
