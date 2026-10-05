package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;

/**
 * A key-value combination that a deriver wrote for the development part of a test, to reproduce a formal failure
 * group of a certification attempt. It joins development once it reproduces the group. Every value is new to its
 * dimension, so the combination belongs to no part of the confirmed split.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param attempt the index of the certification attempt whose formal failures led to the combination
 * @param testId the identifier of the test definition
 * @param values the value of each key dimension, in the test's key-dimension order; each value's probability is
 *   its share of the combinations that the deriver wrote for the same round
 */
public record AddedCombination(int attempt, String testId, List<VariationValue> values)
{
	/**
	 * Creates an added combination.
	 *
	 * @param attempt the index of the certification attempt whose formal failures led to the combination
	 * @param testId the identifier of the test definition
	 * @param values the value of each key dimension, in the test's key-dimension order
	 * @throws NullPointerException if {@code testId} or {@code values} are null
	 * @throws IllegalArgumentException if {@code attempt} is not positive, {@code testId} is blank or
	 *   {@code values} is empty
	 */
	public AddedCombination
	{
		requireThat(attempt, "attempt").isPositive();
		requireThat(testId, "testId").isNotBlank();
		requireThat(values, "values").isNotEmpty();
		values = List.copyOf(values);
	}
}
