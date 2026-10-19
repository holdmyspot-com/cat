package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Observed bytes and whitespace-separated words in a snapshot's participating prompt files.
 *
 * @param bytes the total bytes, or {@code null} when files could not be measured
 * @param words the total words, or {@code null} when text could not be measured
 * @param unavailable the reason a measurement is missing, or {@code null} when both are available
 */
public record PromptSize(Long bytes, Long words, String unavailable)
{
	/**
	 * The description before a snapshot has been measured.
	 */
	public static final PromptSize UNKNOWN = unavailable("Snapshot prompt files have not been measured.");

	/**
	 * Creates measurements.
	 *
	 * @param bytes the observed bytes, or {@code null}
	 * @param words the observed words, or {@code null}
	 * @param unavailable the reason a measurement is missing, or {@code null}
	 * @throws IllegalArgumentException if a count is negative or availability is inconsistent
	 */
	public PromptSize
	{
		if (bytes != null)
			requireThat(bytes.longValue(), "bytes").isNotNegative();
		if (words != null)
		{
			requireThat(bytes, "bytes").isNotNull();
			requireThat(words.longValue(), "words").isNotNegative();
			requireThat(unavailable, "unavailable").isNull();
		}
		else
			requireThat(unavailable, "unavailable").isNotBlank();
	}

	/**
	 * Describes why the snapshot could not be measured.
	 *
	 * @param reason the reason
	 * @return unavailable measurements
	 * @throws NullPointerException if the reason is null
	 * @throws IllegalArgumentException if the reason is blank
	 */
	public static PromptSize unavailable(String reason)
	{
		return new PromptSize(null, null, reason);
	}
}
