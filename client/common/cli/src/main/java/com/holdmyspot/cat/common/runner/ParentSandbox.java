package com.holdmyspot.cat.common.runner;

import java.io.PrintWriter;
import java.util.Map;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Recognizes a parent Codex sandbox that has network off, in which a runner could not start a session that reaches a
 * model.
 */
public final class ParentSandbox
{
	/**
	 * The variable that Codex sets to {@code 1} in the commands that its sandbox runs when the sandbox has network off.
	 */
	public static final String NETWORK_DISABLED_VARIABLE = "CODEX_SANDBOX_NETWORK_DISABLED";

	/**
	 * Refuses a launch from a Codex sandbox that has network off.
	 *
	 * @param harness     the harness whose runner the launcher stands for
	 * @param environment the launcher's environment
	 * @param errors      receives the reason for a refusal
	 * @return {@code true} if the launch is refused
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code harness} is blank
	 */
	public static boolean refusesNetworkOff(String harness, Map<String, String> environment, PrintWriter errors)
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(environment, "environment").isNotNull();
		requireThat(errors, "errors").isNotNull();
		if (!"1".equals(environment.get(NETWORK_DISABLED_VARIABLE)))
			return false;
		errors.println(harness + "-runner refuses to start a session: the Codex sandbox that runs it has network access " +
			"off, so the session could not reach its model. Set sandbox_workspace_write.network_access = true in " +
			"Codex's config.toml and start a new Codex session.");
		return true;
	}

	/**
	 * Prevents construction.
	 */
	private ParentSandbox()
	{
	}
}
