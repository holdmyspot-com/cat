package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;

import java.util.ArrayList;
import java.util.List;

/**
 * A candidate's adopted outcomes on one trial's content, including representative and targeted runs.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param testCase the test case, matched independently of its draw position or targeting
 * @param passes the adopted passing outcomes on that test case
 * @param outcomes all adopted outcomes on that test case
 */
public record TestCaseOutcomes(DrawnTestCase testCase, int passes, int outcomes)
{
	/**
	 * Creates a candidate's outcome record on one test case.
	 *
	 * @param testCase the test case
	 * @param passes the passing outcomes
	 * @param outcomes all outcomes
	 * @throws NullPointerException if {@code testCase} is null
	 * @throws IllegalArgumentException if {@code outcomes} is negative or {@code passes} is outside [0, outcomes]
	 */
	public TestCaseOutcomes
	{
		requireThat(testCase, "testCase").isNotNull();
		requireThat(outcomes, "outcomes").isNotNegative();
		requireThat(passes, "passes").isBetween(0, true, outcomes, true);
	}

	/**
	 * Collects each representative failure and its adopted diagnostic reruns by test case.
	 *
	 * @param failures the candidate's retained failures
	 * @return the aggregated test case records
	 * @throws NullPointerException if {@code failures} or an element is null
	 */
	public static List<TestCaseOutcomes> fromFailures(List<Failure> failures)
	{
		requireThat(failures, "failures").isNotNull();

		List<TestCaseOutcomes> records = List.of();
		for (Failure failure : failures)
		{
			records = add(records, failure.testCase(), Verdict.FAIL);
			for (DiagnosticRerun rerun : failure.reruns())
				records = add(records, failure.testCase(), rerun.verdict());
		}
		return records;
	}

	/**
	 * Records an adopted outcome, combining trials of the same test case.
	 *
	 * @param records the candidate's existing test case records
	 * @param testCase the adopted trial's test case
	 * @param verdict the adopted outcome
	 * @return an immutable list containing the updated record in its original position, or appended for new content
	 * @throws NullPointerException if an argument or record is null
	 */
	public static List<TestCaseOutcomes> add(List<TestCaseOutcomes> records, DrawnTestCase testCase,
		Verdict verdict)
	{
		requireThat(records, "records").isNotNull();
		requireThat(testCase, "testCase").isNotNull();
		requireThat(verdict, "verdict").isNotNull();

		int passing = 0;
		if (verdict == Verdict.PASS)
			passing = 1;

		List<TestCaseOutcomes> updated = new ArrayList<>(records);
		for (int index = 0; index < updated.size(); ++index)
		{
			TestCaseOutcomes record = updated.get(index);
			if (!Promotion.isSameTestCase(record.testCase(), testCase))
				continue;
			updated.set(index, new TestCaseOutcomes(record.testCase(), record.passes() + passing,
				record.outcomes() + 1));
			return List.copyOf(updated);
		}
		updated.add(new TestCaseOutcomes(testCase, passing, 1));
		return List.copyOf(updated);
	}
}
