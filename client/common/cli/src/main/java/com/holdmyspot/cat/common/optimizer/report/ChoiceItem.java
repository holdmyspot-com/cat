package com.holdmyspot.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Choice;
import com.holdmyspot.cat.common.optimizer.execution.PromptAuthorConflict;

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
 * @param conflict the conflict answered by a resolution choice, otherwise {@code null}
 * @param resolution the 1-based resolution position, otherwise 0
 */
public record ChoiceItem(Choice choice, int attempt, boolean historical, int reviewPointAbove,
	PromptAuthorConflict conflict, int resolution)
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
	 * @param conflict the conflict answered by a resolution choice, otherwise {@code null}
	 * @param resolution the 1-based resolution position, otherwise 0
	 * @throws NullPointerException if {@code choice} or, for a resolution choice, {@code conflict} are null
	 * @throws IllegalArgumentException if {@code attempt} or {@code reviewPointAbove} are negative, resolution
	 *   choices name out-of-range positions, or ordinary choices carry conflict data
	 */
	public ChoiceItem
	{
		requireThat(choice, "choice").isNotNull();
		requireThat(attempt, "attempt").isNotNegative();
		requireThat(reviewPointAbove, "reviewPointAbove").isNotNegative();
		if (choice == Choice.RESOLUTION)
		{
			requireThat(conflict, "conflict").isNotNull();
			requireThat(resolution, "resolution").isPositive().isLessThanOrEqualTo(conflict.resolutions().size());
		}
		else
		{
			requireThat(conflict, "conflict").isNull();
			requireThat(resolution, "resolution").isZero();
		}
	}

	/**
	 * Creates an ordinary choice without a conflict resolution.
	 *
	 * @param choice the ordinary choice
	 * @param attempt the keep-improvement attempt index, otherwise 0
	 * @param historical indicates whether that attempt has an earlier measurement identity
	 * @param reviewPointAbove the outcomes a new review point must exceed, otherwise 0
	 * @throws NullPointerException if {@code choice} is null or is a resolution choice requiring conflict data
	 * @throws IllegalArgumentException if a count is negative
	 */
	public ChoiceItem(Choice choice, int attempt, boolean historical, int reviewPointAbove)
	{
		this(choice, attempt, historical, reviewPointAbove, null, 0);
	}

	/**
	 * Indicates whether this is the recommended resolution.
	 *
	 * @return {@code true} if the conflict recommends this resolution
	 */
	public boolean recommended()
	{
		return conflict != null && resolution == conflict.recommended();
	}

	/**
	 * Returns the recommendation's reason when this resolution is recommended.
	 *
	 * @return the reason, empty for other choices
	 */
	public String reason()
	{
		if (recommended())
			return conflict.reason();
		return "";
	}

	/**
	 * Returns the identifier that the command that passes an answer back accepts.
	 *
	 * @return {@code resolution_<n>} for a resolution, otherwise the choice name in lower case
	 */
	public String id()
	{
		if (choice == Choice.RESOLUTION)
			return "resolution_" + resolution;
		return EnumNames.lowerCase(choice);
	}

	/**
	 * Returns the text that the user sees.
	 *
	 * @return the resolution text, otherwise the choice name in lower case with spaces
	 */
	public String label()
	{
		if (choice == Choice.RESOLUTION)
			return conflict.resolutions().get(resolution - 1);
		return EnumNames.lowerCase(choice).replace('_', ' ');
	}
}
