package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Identifies the exact model and reasoning effort supplied to one harness evaluation.
 *
 * @param model the non-blank provider model identifier
 * @param effort the supported reasoning effort
 */
public record HarnessModelProfile(String model, ReasoningEffort effort)
{
	/**
	 * Validates one typed harness model profile.
	 *
	 * @param model the non-blank provider model identifier
	 * @param effort the supported reasoning effort
	 */
	public HarnessModelProfile
	{
		requireThat(model, "model").isNotBlank();
		requireThat(effort, "effort").isNotNull();
	}

	/**
	 * Converts untyped model-profile fields at a file or command boundary into the supported domain.
	 *
	 * @param model the non-blank provider model identifier
	 * @param effort the exact non-blank harness effort spelling
	 * @return the validated model profile
	 * @throws IllegalArgumentException if either value is blank or {@code effort} is unsupported
	 */
	public static HarnessModelProfile fromExternalValues(String model, String effort)
	{
		return new HarnessModelProfile(model, ReasoningEffort.fromExternalValue(effort));
	}

	/**
	 * Returns the exact reasoning-effort value supplied to the harness adapter.
	 *
	 * @return the external reasoning-effort spelling
	 */
	public String externalEffort()
	{
		return effort.externalValue();
	}
}
