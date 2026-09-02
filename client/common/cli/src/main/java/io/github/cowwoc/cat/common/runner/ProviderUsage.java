package io.github.cowwoc.cat.common.runner;

/**
 * Token usage reported by a model provider for one harness execution.
 *
 * @param available whether the provider reported usage
 * @param inputTokens input token count
 * @param cachedInputTokens cached input token count
 * @param outputTokens output token count
 */
public record ProviderUsage(boolean available, long inputTokens, long cachedInputTokens, long outputTokens)
{
	/**
	 * Creates usage when the provider returned all token counters.
	 *
	 * @param inputTokens input token count
	 * @param cachedInputTokens cached input token count
	 * @param outputTokens output token count
	 * @return available provider usage
	 */
	public static ProviderUsage of(long inputTokens, long cachedInputTokens, long outputTokens)
	{
		if (inputTokens < 0 || cachedInputTokens < 0 || outputTokens < 0 ||
			cachedInputTokens > inputTokens)
			throw new IllegalArgumentException("Provider token counts must be nonnegative and cached input must not " +
				"exceed input");
		return new ProviderUsage(true, inputTokens, cachedInputTokens, outputTokens);
	}

	/**
	 * Creates the value used when the provider did not expose usage.
	 *
	 * @return unavailable provider usage
	 */
	public static ProviderUsage unavailable()
	{
		return new ProviderUsage(false, 0, 0, 0);
	}

	/**
	 * Validates the closed usage value.
	 *
	 * @param available whether the provider reported usage
	 * @param inputTokens input token count
	 * @param cachedInputTokens cached input token count
	 * @param outputTokens output token count
	 */
	public ProviderUsage
	{
		if (inputTokens < 0 || cachedInputTokens < 0 || outputTokens < 0 || cachedInputTokens > inputTokens)
			throw new IllegalArgumentException("Provider token counts are invalid");
		if (!available && (inputTokens != 0 || cachedInputTokens != 0 || outputTokens != 0))
			throw new IllegalArgumentException("Unavailable provider usage must have zero counts");
	}
}
