package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.List;

/**
 * Test cases of a contract's tests beyond the confirmed ones: those that joined the development part after they
 * reproduced the formal failures of certification attempts, or those that reproduction trials try. They are campaign
 * state outside the confirmed contract: they change neither the held-out part nor the contract's identity.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param testCases the test cases, in the order they were added
 */
public record AddedTestCases(List<AddedTestCase> testCases)
{
	/**
	 * No added test cases.
	 */
	public static final AddedTestCases NONE = new AddedTestCases(List.of());

	/**
	 * Creates added test cases.
	 *
	 * @param testCases the test cases, in the order they were added
	 * @throws NullPointerException if {@code testCases} is null or contains null
	 */
	public AddedTestCases
	{
		testCases = List.copyOf(testCases);
	}

	/**
	 * Returns the test cases added to a test.
	 *
	 * @param testId the identifier of the prompt test
	 * @return the test cases, in the order they were added
	 * @throws NullPointerException if {@code testId} is null
	 */
	public List<AddedTestCase> of(String testId)
	{
		requireThat(testId, "testId").isNotNull();
		return testCases.stream().filter(testCase -> testCase.testId().equals(testId)).toList();
	}

	/**
	 * Returns these test cases with more test cases.
	 *
	 * @param added the test cases to add
	 * @return the test cases
	 * @throws NullPointerException if {@code added} is null or contains null
	 */
	public AddedTestCases plus(List<AddedTestCase> added)
	{
		List<AddedTestCase> all = new ArrayList<>(testCases);
		all.addAll(added);
		return new AddedTestCases(all);
	}
}
