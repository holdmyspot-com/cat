package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;

/**
 * One concrete trial that a generator drew, with everything an assignment record keeps.
 *
 * @param cellId the cell that the trial belongs to, which is its statistical unit
 * @param categoryId the category that the trial belongs to
 * @param testId the test definition that the trial instantiates
 * @param choices the selected value of each of the test's dimensions, in the test's dimension order
 * @param turns the complete turn sequence with every placeholder replaced
 * @param expectedBehaviour what a compliant response does
 * @param generator the identity of the generator that drew the instance
 * @param streamKind the stream that supplied the draw
 * @param streamPosition the stream position at which the draw began
 * @param targeted {@code true} if the trial is a rerun, promotion run or regression run, which never counts as
 *   representative or formal evidence
 */
public record TrialInstance(String cellId, String categoryId, String testId, List<VariationChoice> choices,
	List<String> turns, String expectedBehaviour, String generator, DrawStream.Kind streamKind, long streamPosition,
	boolean targeted)
{
	/**
	 * Creates an instance.
	 *
	 * @param cellId the cell that the trial belongs to, which is its statistical unit
	 * @param categoryId the category that the trial belongs to
	 * @param testId the test definition that the trial instantiates
	 * @param choices the selected value of each of the test's dimensions, in the test's dimension order
	 * @param turns the complete turn sequence with every placeholder replaced
	 * @param expectedBehaviour what a compliant response does
	 * @param generator the identity of the generator that drew the instance
	 * @param streamKind the stream that supplied the draw
	 * @param streamPosition the stream position at which the draw began
	 * @param targeted {@code true} if the trial is targeted
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if a text is blank, {@code turns} is empty, or {@code streamPosition} is
	 *   negative
	 */
	public TrialInstance
	{
		requireThat(cellId, "cellId").isNotBlank();
		requireThat(categoryId, "categoryId").isNotBlank();
		requireThat(testId, "testId").isNotBlank();
		requireThat(choices, "choices").isNotNull();
		requireThat(turns, "turns").isNotEmpty();
		requireThat(expectedBehaviour, "expectedBehaviour").isNotBlank();
		requireThat(generator, "generator").isNotBlank();
		requireThat(streamKind, "streamKind").isNotNull();
		requireThat(streamPosition, "streamPosition").isNotNegative();
		choices = List.copyOf(choices);
		turns = List.copyOf(turns);
	}

	/**
	 * Indicates whether the trial counts toward representative and formal evidence.
	 *
	 * @return {@code true} if the instance is not targeted
	 */
	public boolean isRepresentative()
	{
		return !targeted;
	}
}
