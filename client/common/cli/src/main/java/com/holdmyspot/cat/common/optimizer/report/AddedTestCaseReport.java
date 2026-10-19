package com.holdmyspot.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.contract.AddedTestCase;
import com.holdmyspot.cat.common.optimizer.contract.TestValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One test case that CAT added to the development part of a test after a certification attempt ended with formal
 * failures.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param attempt         the index of the certification attempt after which the test case was added
 * @param testId          the identifier of the test that the test case belongs to
 * @param values          each variable's value as {@link TestValue#describe()} gives it, in the test's variable order
 * @param expectedOutcome the result that a compliant reply gives for these values
 */
public record AddedTestCaseReport(int attempt, String testId, Map<String, String> values, String expectedOutcome)
{
	/**
	 * Creates a report of one test case.
	 *
	 * @param attempt         the index of the certification attempt after which the test case was added
	 * @param testId          the identifier of the test that the test case belongs to
	 * @param values          each variable's value as {@link TestValue#describe()} gives it, in the test's variable order
	 * @param expectedOutcome the result that a compliant reply gives for these values
	 * @throws NullPointerException     if {@code testId}, {@code values} or {@code expectedOutcome} are null
	 * @throws IllegalArgumentException if {@code attempt} is not positive, {@code testId} or {@code expectedOutcome}
	 *                                  are blank, or {@code values} is empty
	 */
	public AddedTestCaseReport
	{
		requireThat(attempt, "attempt").isPositive();
		requireThat(testId, "testId").isNotBlank();
		requireThat(values, "values").isNotEmpty();
		requireThat(expectedOutcome, "expectedOutcome").isNotBlank();
		values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
	}

	/**
	 * Returns the test cases added to the development part of the current lineage's tests.
	 *
	 * @param campaign the campaign
	 * @return the test cases in the order they were added, or an empty list before a contract is confirmed
	 * @throws NullPointerException if {@code campaign} is null
	 */
	public static List<AddedTestCaseReport> of(Campaign campaign)
	{
		requireThat(campaign, "campaign").isNotNull();
		if (!campaign.isConfirmed())
			return List.of();
		List<AddedTestCaseReport> reports = new ArrayList<>();
		for (AddedTestCase added : campaign.lineage().added().testCases())
		{
			Map<String, String> values = new LinkedHashMap<>();
			for (Map.Entry<String, TestValue> value : added.testCase().values().entrySet())
				values.put(value.getKey(), value.getValue().describe());
			reports.add(new AddedTestCaseReport(added.attempt(), added.testId(), values,
				added.testCase().expectedOutcome()));
		}
		return reports;
	}
}
