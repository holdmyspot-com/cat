package com.holdmyspot.cat.common.optimizer.port;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The token counts that a harness reported for one nested session.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param input the input tokens, including cached ones
 * @param cachedInput the input tokens served from the provider's prompt cache
 * @param output the output tokens, including reasoning ones
 * @param reasoningOutput the output tokens spent on reasoning
 */
public record TokenUsage(long input, long cachedInput, long output, long reasoningOutput)
{
	/**
	 * Creates a usage record.
	 *
	 * @param input the input tokens, including cached ones
	 * @param cachedInput the input tokens served from the provider's prompt cache
	 * @param output the output tokens, including reasoning ones
	 * @param reasoningOutput the output tokens spent on reasoning
	 * @throws IllegalArgumentException if a count is negative
	 */
	public TokenUsage
	{
		requireThat(input, "input").isNotNegative();
		requireThat(cachedInput, "cachedInput").isNotNegative();
		requireThat(output, "output").isNotNegative();
		requireThat(reasoningOutput, "reasoningOutput").isNotNegative();
	}
}
