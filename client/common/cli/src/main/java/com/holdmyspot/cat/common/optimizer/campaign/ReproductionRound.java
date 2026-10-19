package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.AddedTestCase;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;

import java.util.List;

/**
 * One round of a failure group's reproduction: trials of the working candidate on the round's test cases in one cell.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param cellId the cell that the round's trials run in
 * @param testCases the test cases that the round tries, with their counts; empty while the round awaits the contract
 *   author's test cases
 */
public record ReproductionRound(String cellId, List<TriedTestCase> testCases)
{
	/**
	 * Creates a round.
	 *
	 * @param cellId the cell that the round's trials run in
	 * @param testCases the test cases that the round tries, with their counts; empty while the round awaits the
	 *   contract author's test cases
	 * @throws NullPointerException if any of the arguments are null or {@code testCases} contains null
	 * @throws IllegalArgumentException if {@code cellId} is blank
	 */
	public ReproductionRound
	{
		requireThat(cellId, "cellId").isNotBlank();
		testCases = List.copyOf(testCases);
	}

	/**
	 * Indicates whether the round awaits the contract author's test cases.
	 *
	 * @return {@code true} if the round has no test case
	 */
	public boolean awaitsValues()
	{
		return testCases.isEmpty();
	}

	/**
	 * Returns the round's adopted trials.
	 *
	 * @return the trials of every test case
	 */
	public int trials()
	{
		int trials = 0;
		for (TriedTestCase testCase : testCases)
			trials += testCase.trials();
		return trials;
	}

	/**
	 * Returns the round's trials that failed the group's requirement through the group's behavior.
	 *
	 * @return the failures of every test case
	 */
	public int failures()
	{
		int failures = 0;
		for (TriedTestCase testCase : testCases)
			failures += testCase.failures();
		return failures;
	}

	/**
	 * Returns the test cases that the round tries, without their counts.
	 *
	 * @return the test cases
	 */
	public List<AddedTestCase> added()
	{
		return testCases.stream().map(TriedTestCase::testCase).toList();
	}

	/**
	 * Finds the round's test case that a trial ran.
	 *
	 * @param test the definition of the trial's test
	 * @param drawn the trial
	 * @return the test case's position in {@link #testCases()}, or {@code -1} if the trial ran none of the round's test
	 *   cases
	 * @throws NullPointerException if any of the arguments are null
	 */
	public int indexOf(PromptTest test, DrawnTestCase drawn)
	{
		requireThat(test, "test").isNotNull();
		requireThat(drawn, "drawn").isNotNull();
		if (!test.id().equals(drawn.testId()))
			return -1;
		for (int i = 0; i < testCases.size(); ++i)
		{
			AddedTestCase added = testCases.get(i).testCase();
			if (added.testId().equals(test.id()) && added.testCase().equals(drawn.testCase()))
				return i;
		}
		return -1;
	}
}
