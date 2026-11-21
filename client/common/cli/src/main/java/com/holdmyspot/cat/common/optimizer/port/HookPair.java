package com.holdmyspot.cat.common.optimizer.port;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * One startup hook as resolved for the production and trial comparison workspaces.
 *
 * @param production the invocation selected for the production workspace
 * @param trial the invocation selected for the trial workspace
 */
public record HookPair(HookInvocation production, HookInvocation trial)
{
	/**
	 * Creates a pair of resolved hook invocations.
	 *
	 * @param production the invocation selected for the production workspace
	 * @param trial the invocation selected for the trial workspace
	 * @throws NullPointerException if either invocation is null
	 */
	public HookPair
	{
		requireThat(production, "production").isNotNull();
		requireThat(trial, "trial").isNotNull();
	}
}
