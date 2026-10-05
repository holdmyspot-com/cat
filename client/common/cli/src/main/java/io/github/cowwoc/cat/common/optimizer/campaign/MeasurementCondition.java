package io.github.cowwoc.cat.common.optimizer.campaign;

/**
 * A condition that a measurement identity records, other than the candidate snapshot and time.
 * <p>
 * Every condition is material: a known change to any of them makes earlier results historical and pauses the
 * campaign before the next model call.
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
	 * The judge as observed in its sessions.
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
	 * The runner version.
	 */
	RUNNER_VERSION,
	/**
	 * The CAT version.
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
	EXTERNAL_SOURCES;

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
			case JUDGE -> "judge";
			case MODEL -> "trial model";
			case REASONING -> "trial reasoning";
			case TOOL_OUTPUT_LIMIT -> "trial tool-output token limit";
			case PROVIDER -> "model provider";
			case HARNESS_VERSION -> "harness version";
			case RUNNER_VERSION -> "runner version";
			case CAT_VERSION -> "CAT version";
			case LOADER_CONFIGURATION -> "loader configuration";
			case PROMPT_SOURCES -> "prompt sources";
			case HOST_TOOLS -> "host tools";
			case EXTERNAL_SOURCES -> "external sources";
		};
	}
}
