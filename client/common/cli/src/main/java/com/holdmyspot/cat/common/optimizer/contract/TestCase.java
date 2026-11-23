package com.holdmyspot.cat.common.optimizer.contract;

import com.holdmyspot.cat.common.Identifier;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A prompt test with one value for each of its variables, together with the result that a compliant reply gives and
 * the kind of input boundary that it exercises.
 * <p>
 * Two test cases with the same values and expected outcome are the same test case, whatever their boundary classes:
 * tagging a test case, or changing its tag, leaves it the same test case. A test case of a values file of format
 * version 1, which predates boundary classes, is untagged.
 *
 * @param values          the value of each variable, by name, in the scenario's variable order
 * @param expectedOutcome the result that a compliant reply gives, such as the date that the turn asks for
 * @param boundaryClass   the kind of input boundary that the test case exercises, or an empty string if untagged
 */
public record TestCase(Map<String, TestValue> values, String expectedOutcome, String boundaryClass)
{
	/**
	 * The reserved name that stands for a test case's expected outcome in placeholders and assertion identifiers.
	 */
	public static final String EXPECTED_OUTCOME = "expected_outcome";

	/**
	 * Creates a test case without a boundary class.
	 *
	 * @param values          the value of each variable, by name, in the scenario's variable order
	 * @param expectedOutcome the result that a compliant reply gives, such as the date that the turn asks for
	 * @throws NullPointerException     if any of the arguments are null or {@code values} contains a null
	 * @throws IllegalArgumentException if {@code values} is empty, a name is not lowercase words joined by underscores,
	 *                                  or {@code expectedOutcome} is blank
	 */
	public TestCase(Map<String, TestValue> values, String expectedOutcome)
	{
		this(values, expectedOutcome, "");
	}

	/**
	 * Creates a test case.
	 *
	 * @param values          the value of each variable, by name, in the scenario's variable order
	 * @param expectedOutcome the result that a compliant reply gives, such as the date that the turn asks for
	 * @param boundaryClass   the kind of input boundary that the test case exercises, or an empty string if untagged
	 * @throws NullPointerException     if any of the arguments are null or {@code values} contains a null
	 * @throws IllegalArgumentException if {@code values} is empty, a name or a non-empty {@code boundaryClass} is not
	 *                                  lowercase words joined by underscores, or {@code expectedOutcome} is blank
	 */
	public TestCase
	{
		requireThat(values, "values").isNotEmpty();
		requireThat(expectedOutcome, "expectedOutcome").isNotBlank();
		requireThat(boundaryClass, "boundaryClass").isNotNull();
		if (!boundaryClass.isEmpty())
			requireThat(boundaryClass, "boundaryClass").matches(Identifier.SNAKE_CASE);
		for (Map.Entry<String, TestValue> entry : values.entrySet())
		{
			requireThat(entry.getKey(), "name").matches(Identifier.SNAKE_CASE);
			requireThat(entry.getValue(), "values.get(name)").withContext(entry.getKey(), "name").isNotNull();
		}
		values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
	}

	/**
	 * Returns each value as {@link TestValue#describe()} gives it: the text of a text value, and the call of a
	 * generated value.
	 *
	 * @return the description of each variable's value, by name, in the test case's order
	 */
	public Map<String, String> describedValues()
	{
		Map<String, String> described = new LinkedHashMap<>();
		for (Map.Entry<String, TestValue> value : values.entrySet())
			described.put(value.getKey(), value.getValue().describe());
		return described;
	}

	/**
	 * Returns this test case with another boundary class.
	 *
	 * @param boundaryClass the kind of input boundary that the test case exercises, or an empty string if untagged
	 * @return the test case
	 * @throws NullPointerException     if {@code boundaryClass} is null
	 * @throws IllegalArgumentException if {@code boundaryClass} is neither empty nor lowercase words joined by
	 *                                  underscores
	 */
	public TestCase withBoundaryClass(String boundaryClass)
	{
		return new TestCase(values, expectedOutcome, boundaryClass);
	}

	/**
	 * Indicates whether another object is the same test case: a test case with equal values and expected outcome,
	 * whatever its boundary class.
	 *
	 * @param other the other object
	 * @return {@code true} if {@code other} is the same test case
	 */
	@Override
	public boolean equals(Object other)
	{
		return other instanceof TestCase testCase && testCase.values.equals(values) &&
			testCase.expectedOutcome.equals(expectedOutcome);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(values, expectedOutcome);
	}
}
