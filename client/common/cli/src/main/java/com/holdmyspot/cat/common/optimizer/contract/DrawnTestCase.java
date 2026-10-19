package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;

/**
 * One trial that a generator drew, recording its test, test case, expanded turns and draw source.
 *
 * @param cellId the combination of prompt test and trial profile whose pass-rate measurement the trial belongs to
 * @param testId the prompt test that the trial runs
 * @param testCase the test case that the trial runs
 * @param turns the complete turn sequence with every placeholder replaced
 * @param expectedBehaviour what a compliant response does
 * @param generator the identity of the generator that drew the test case
 * @param streamKind the stream that supplied the draw
 * @param streamPosition the stream position at which the draw began
 * @param targeted {@code true} if the trial is a rerun, promotion run or regression run, which never counts as
 *   representative or formal evidence
 */
public record DrawnTestCase(String cellId, String testId, TestCase testCase,
	List<String> turns, String expectedBehaviour, String generator, DrawStream.Kind streamKind, long streamPosition,
	boolean targeted)
{
	/**
	 * Creates a test case.
	 *
	 * @param cellId the combination of prompt test and trial profile whose pass-rate measurement the trial belongs to
	 * @param testId the prompt test that the trial runs
	 * @param testCase the test case that the trial runs
	 * @param turns the complete turn sequence with every placeholder replaced
	 * @param expectedBehaviour what a compliant response does
	 * @param generator the identity of the generator that drew the test case
	 * @param streamKind the stream that supplied the draw
	 * @param streamPosition the stream position at which the draw began
	 * @param targeted {@code true} for a rerun, promotion run or regression run that cannot count as representative
	 *   or formal evidence
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if a text is blank, {@code turns} is empty, or {@code streamPosition} is
	 *   negative
	 */
	public DrawnTestCase
	{
		requireThat(cellId, "cellId").isNotBlank();
		requireThat(testId, "testId").isNotBlank();
		requireThat(testCase, "testCase").isNotNull();
		requireThat(turns, "turns").isNotEmpty();
		requireThat(expectedBehaviour, "expectedBehaviour").isNotBlank();
		requireThat(generator, "generator").isNotBlank();
		requireThat(streamKind, "streamKind").isNotNull();
		requireThat(streamPosition, "streamPosition").isNotNegative();
		turns = List.copyOf(turns);
	}

	/**
	 * Indicates whether the trial is not a rerun, promotion run or regression run.
	 *
	 * @return {@code true} if the test case is not targeted
	 */
	public boolean isRepresentative()
	{
		return !targeted;
	}
}
