package com.holdmyspot.cat.common.updater.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.updater.activation.HarnessCli;
import com.holdmyspot.cat.common.updater.activation.HarnessIntegration;

/**
 * Registers an activated plugin with its harness through the harness program that setup recorded.
 *
 * @param integration the harness's integration
 * @param cli         runs the recorded harness program
 */
public record Registration(HarnessIntegration integration, HarnessCli cli)
{
	/**
	 * Creates a registration.
	 *
	 * @param integration the harness's integration
	 * @param cli         runs the recorded harness program
	 * @throws NullPointerException if {@code integration} or {@code cli} are null
	 */
	public Registration
	{
		requireThat(integration, "integration").isNotNull();
		requireThat(cli, "cli").isNotNull();
	}
}
