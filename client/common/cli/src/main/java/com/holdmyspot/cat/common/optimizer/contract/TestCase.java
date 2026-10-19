package com.holdmyspot.cat.common.optimizer.contract;

import com.holdmyspot.cat.common.Identifier;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A prompt test with one value for each of its variables, together with the result that a compliant reply gives.
 * Two test cases with the same values and expected outcome are the same test case.
 *
 * @param values          the value of each variable, by name, in the scenario's variable order
 * @param expectedOutcome the result that a compliant reply gives, such as the date that the turn asks for
 */
public record TestCase(Map<String, TestValue> values, String expectedOutcome)
{
	/**
	 * The reserved name that stands for a test case's expected outcome in placeholders and assertion identifiers.
	 */
	public static final String EXPECTED_OUTCOME = "expected_outcome";

	/**
	 * Creates a test case.
	 *
	 * @param values          the value of each variable, by name, in the scenario's variable order
	 * @param expectedOutcome the result that a compliant reply gives, such as the date that the turn asks for
	 * @throws NullPointerException     if any of the arguments are null or {@code values} contains a null
	 * @throws IllegalArgumentException if {@code values} is empty, a name is not lowercase words joined by underscores,
	 *                                  or {@code expectedOutcome} is blank
	 */
	public TestCase
	{
		requireThat(values, "values").isNotEmpty();
		requireThat(expectedOutcome, "expectedOutcome").isNotBlank();
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
}
