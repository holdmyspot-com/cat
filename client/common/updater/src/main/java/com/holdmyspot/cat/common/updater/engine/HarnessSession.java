package com.holdmyspot.cat.common.updater.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.updater.activation.HarnessCli;
import com.holdmyspot.cat.common.updater.activation.HarnessIntegration;

/**
 * The handle through which the updater makes every call to a harness integration: registering an activated plugin,
 * unregistering it and changing the harness's permission entries, through the harness program that setup recorded.
 *
 * @param integration the harness's integration
 * @param cli         runs the recorded harness program
 * @param replacer    replaces the harness configuration files that the integration writes
 */
public record HarnessSession(HarnessIntegration integration, HarnessCli cli, FileReplacer replacer)
{
	/**
	 * Creates a harness session.
	 *
	 * @param integration the harness's integration
	 * @param cli         runs the recorded harness program
	 * @param replacer    replaces the harness configuration files that the integration writes
	 * @throws NullPointerException if any of the arguments are null
	 */
	public HarnessSession
	{
		requireThat(integration, "integration").isNotNull();
		requireThat(cli, "cli").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
	}
}
