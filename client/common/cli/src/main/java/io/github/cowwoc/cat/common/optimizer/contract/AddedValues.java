package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.List;

/**
 * The key-value combinations that CAT added to the development part of a contract's tests after certification
 * attempts ended with formal failures. They are campaign state outside the confirmed contract: they change neither
 * the held-out part nor the contract's identity.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param combinations the added combinations, in the order they were added
 */
public record AddedValues(List<AddedCombination> combinations)
{
	/**
	 * No added combinations.
	 */
	public static final AddedValues NONE = new AddedValues(List.of());

	/**
	 * Creates added values.
	 *
	 * @param combinations the added combinations, in the order they were added
	 * @throws NullPointerException if {@code combinations} is null or contains null
	 */
	public AddedValues
	{
		combinations = List.copyOf(combinations);
	}

	/**
	 * Returns the combinations added to a test.
	 *
	 * @param testId the identifier of the test definition
	 * @return the combinations, in the order they were added
	 * @throws NullPointerException if {@code testId} is null
	 */
	public List<AddedCombination> of(String testId)
	{
		requireThat(testId, "testId").isNotNull();
		return combinations.stream().filter(combination -> combination.testId().equals(testId)).toList();
	}

	/**
	 * Returns these values with more combinations.
	 *
	 * @param added the combinations to add
	 * @return the values
	 * @throws NullPointerException if {@code added} is null or contains null
	 */
	public AddedValues plus(List<AddedCombination> added)
	{
		List<AddedCombination> all = new ArrayList<>(combinations);
		all.addAll(added);
		return new AddedValues(all);
	}
}
