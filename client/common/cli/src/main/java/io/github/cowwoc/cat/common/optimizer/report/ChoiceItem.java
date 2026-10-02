package io.github.cowwoc.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Choice;

/**
 * One answer that the user may give now. The command that passes an answer back accepts only listed choices.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param choice the choice
 * @param attempt the certification index of the attempt that {@link Choice#KEEP_IMPROVEMENT} delivers, otherwise 0
 * @param historical {@code true} if the attempt was measured under an earlier measurement identity
 * @param reviewPointAbove for {@link Choice#MORE_EVIDENCE}, the outcomes that the next review point must exceed,
 *   otherwise 0
 */
public record ChoiceItem(Choice choice, int attempt, boolean historical, int reviewPointAbove)
{
	/**
	 * Creates a choice item.
	 *
	 * @param choice the choice
	 * @param attempt the certification index of the attempt that {@link Choice#KEEP_IMPROVEMENT} delivers,
	 *   otherwise 0
	 * @param historical {@code true} if the attempt was measured under an earlier measurement identity
	 * @param reviewPointAbove for {@link Choice#MORE_EVIDENCE}, the outcomes that the next review point must exceed,
	 *   otherwise 0
	 * @throws NullPointerException if {@code choice} is null
	 * @throws IllegalArgumentException if {@code attempt} or {@code reviewPointAbove} are negative
	 */
	public ChoiceItem
	{
		requireThat(choice, "choice").isNotNull();
		requireThat(attempt, "attempt").isNotNegative();
		requireThat(reviewPointAbove, "reviewPointAbove").isNotNegative();
	}

	/**
	 * Returns the identifier that the command that passes an answer back accepts.
	 *
	 * @return the choice's name in lower case, with hyphens between words
	 */
	public String id()
	{
		return words('-');
	}

	/**
	 * Returns the text that the user sees.
	 *
	 * @return the choice's name in lower case, with spaces between words
	 */
	public String label()
	{
		return words(' ');
	}

	/**
	 * Returns the choice's name in lower case.
	 *
	 * @param separator the character between words
	 * @return the words of the name, joined by {@code separator}
	 */
	private String words(char separator)
	{
		return EnumNames.lowerCase(choice).replace('_', separator);
	}
}
