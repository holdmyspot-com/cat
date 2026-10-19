package com.holdmyspot.cat.common.optimizer.campaign;

/**
 * A condition that a measurement identity records, other than the candidate snapshot and time.
 * <p>
 * Every condition other than the runner and CAT versions is material: a known change to any of them makes earlier
 * results historical and pauses the campaign before the next model call. The runner and CAT versions are recorded and
 * reported for information only; the session environment version stands for what they can change in a session.
 */
public enum MeasurementCondition
{
	/**
	 * The confirmed contract's hash.
	 */
	CONTRACT,
	/**
	 * The generator's identity.
	 */
	GENERATOR,
	/**
	 * The version of the judge that the harness's sessions run, which stands for the judge's role profile, prompt,
	 * rubric, decision rule and result schema, and the evidence section rendered for the same harness events.
	 */
	JUDGE,
	/**
	 * The trial model as observed in harness events.
	 */
	MODEL,
	/**
	 * The trial reasoning configuration as observed in harness events.
	 */
	REASONING,
	/**
	 * The tool-output token limit that the trial session started under, recorded only when the trial's cell limits
	 * tool output.
	 */
	TOOL_OUTPUT_LIMIT,
	/**
	 * The model provider.
	 */
	PROVIDER,
	/**
	 * The harness version.
	 */
	HARNESS_VERSION,
	/**
	 * The session environment version that the harness's session port declares. CAT changes it only in a release
	 * that changes what a session's model receives or how its actions take effect.
	 */
	SESSION_ENVIRONMENT_VERSION,
	/**
	 * The runner version, recorded for information only.
	 */
	RUNNER_VERSION,
	/**
	 * The CAT version, recorded for information only.
	 */
	CAT_VERSION,
	/**
	 * The loader configuration, which decides which prompt files load. It cannot change within a campaign.
	 */
	LOADER_CONFIGURATION,
	/**
	 * The prompt sources observed loading, including links, resolved targets and staged identities.
	 */
	PROMPT_SOURCES,
	/**
	 * The identities and versions of directly invoked host tools.
	 */
	HOST_TOOLS,
	/**
	 * External or MCP source identities.
	 */
	EXTERNAL_SOURCES,
	/**
	 * The names of the plugins that every trial installs, in configuration order.
	 */
	TRIAL_PLUGINS,
	/**
	 * The base digest of each trial plugin.
	 */
	PLUGIN_BASES,
	/**
	 * Where each trial plugin's base came from: the build command and output directory of a plugin that the project
	 * builds, or the user's installed copy.
	 */
	PLUGIN_BUILDS,
	/**
	 * The project directories and files whose staged versions trials write over each project-built plugin's base,
	 * with the paths that receive them.
	 */
	PLUGIN_MAPPINGS;

	/**
	 * Returns the condition's name in words for reports.
	 *
	 * @return the readable name
	 */
	public String displayName()
	{
		return switch (this)
		{
			case CONTRACT -> "contract";
			case GENERATOR -> "generator";
			case JUDGE -> "judge version";
			case MODEL -> "trial model";
			case REASONING -> "trial reasoning";
			case TOOL_OUTPUT_LIMIT -> "trial tool-output token limit";
			case PROVIDER -> "model provider";
			case HARNESS_VERSION -> "harness version";
			case SESSION_ENVIRONMENT_VERSION -> "session environment version";
			case RUNNER_VERSION -> "runner version";
			case CAT_VERSION -> "CAT version";
			case LOADER_CONFIGURATION -> "loader configuration";
			case PROMPT_SOURCES -> "prompt sources";
			case HOST_TOOLS -> "host tools";
			case EXTERNAL_SOURCES -> "external sources";
			case TRIAL_PLUGINS -> "trial plugins";
			case PLUGIN_BASES -> "trial plugin bases";
			case PLUGIN_BUILDS -> "trial plugin builds";
			case PLUGIN_MAPPINGS -> "trial plugin mappings";
		};
	}

	/**
	 * Indicates whether a known change to the condition is material.
	 *
	 * @return {@code true} if a change makes earlier results historical and pauses the campaign; {@code false} if the
	 *   condition is recorded for information only
	 */
	public boolean isMaterial()
	{
		return switch (this)
		{
			case RUNNER_VERSION, CAT_VERSION -> false;
			default -> true;
		};
	}

	/**
	 * Indicates whether the condition's value depends on the harness that runs the sessions, so that another
	 * harness cannot observe the value that a result records.
	 *
	 * @return {@code true} for the provider, the harness and session environment versions, the judge version, the
	 *   trial model, reasoning and tool-output token limit, and the trial plugins, which the prompt test configuration
	 *   lists for each harness
	 */
	public boolean isHarnessSpecific()
	{
		return switch (this)
		{
			case PROVIDER, HARNESS_VERSION, SESSION_ENVIRONMENT_VERSION, JUDGE, MODEL, REASONING,
				TOOL_OUTPUT_LIMIT, TRIAL_PLUGINS, PLUGIN_BASES, PLUGIN_BUILDS, PLUGIN_MAPPINGS -> true;
			default -> false;
		};
	}
}
