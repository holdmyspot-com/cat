package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.Collection;
import java.util.regex.Pattern;

/**
 * Recognizes expected outcomes whose normalized text consists of a bare value.
 */
public final class BareValue
{
	/**
	 * The maximum number of whitespace-separated tokens in a bare value.
	 */
	private static final int MAXIMUM_BARE_TOKENS = 3;
	/**
	 * Matches punctuation that separates clauses or sentences, including a period followed by whitespace or ending
	 * the outcome. An internal period in a number or name is allowed.
	 */
	private static final Pattern SENTENCE_PUNCTUATION = Pattern.compile("[,;!?]|\\.(?:\\s|$)");

	/**
	 * Indicates whether an expected outcome is a bare value. The normalized form has at most
	 * {@value #MAXIMUM_BARE_TOKENS} whitespace-separated tokens. It has none of {@code ,}, {@code ;}, {@code !} or
	 * {@code ?}, and no {@code .} followed by whitespace or at the end of the outcome.
	 *
	 * @param expectedOutcome an expected outcome
	 * @return {@code true} if the outcome is nonblank and meets the token and punctuation limits
	 * @throws NullPointerException if {@code expectedOutcome} is null
	 */
	public static boolean isBareValue(String expectedOutcome)
	{
		requireThat(expectedOutcome, "expectedOutcome").isNotNull();

		String normalized = Split.normalize(expectedOutcome);
		if (normalized.isEmpty() || SENTENCE_PUNCTUATION.matcher(normalized).find())
			return false;
		return normalized.split(" ").length <= MAXIMUM_BARE_TOKENS;
	}

	/**
	 * Indicates whether every expected outcome of a prompt test is a bare value.
	 *
	 * @param expectedOutcomes the expected outcomes of every test case of a prompt test
	 * @return {@code true} if there is at least one outcome and every outcome is a bare value
	 * @throws NullPointerException if {@code expectedOutcomes} is null or a checked outcome is null
	 */
	public static boolean areAllBareValues(Collection<String> expectedOutcomes)
	{
		requireThat(expectedOutcomes, "expectedOutcomes").isNotNull();

		return !expectedOutcomes.isEmpty() && expectedOutcomes.stream().allMatch(BareValue::isBareValue);
	}

	/**
	 * Prevents instantiation.
	 */
	private BareValue()
	{
	}
}
