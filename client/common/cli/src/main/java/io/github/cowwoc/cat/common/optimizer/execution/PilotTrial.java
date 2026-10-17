package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The record of one guard pilot trial: a control trial of a development test case, decided by the proposed built-in
 * tools and judge.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param values the value of each variable of the test case, by variable name, in declaration order: the text of a
 *   text value, and the call of a generated value
 * @param expectedOutcome the result that a compliant reply gives
 * @param assertions indicates whether the trial passed each assertion that it decided, by assertion identifier. A
 *   failed built-in tool ends the trial without a judge, so the assertions that the judge decides are absent.
 * @param record the judge's rationale, or the record of the failed built-in tools
 */
public record PilotTrial(Map<String, String> values, String expectedOutcome, Map<String, Boolean> assertions,
	String record)
{
	/**
	 * Creates a trial record.
	 *
	 * @param values the value of each variable of the test case, by variable name, in declaration order: the text of
	 *   a text value, and the call of a generated value
	 * @param expectedOutcome the result that a compliant reply gives
	 * @param assertions whether the trial passed each assertion that it decided, by assertion identifier
	 * @param record the judge's rationale, or the record of the failed built-in tools
	 * @throws NullPointerException if any of the arguments are null, or a map holds null
	 * @throws IllegalArgumentException if {@code expectedOutcome} or {@code record} are blank, or {@code assertions}
	 *   is empty
	 */
	public PilotTrial
	{
		requireThat(values, "values").isNotNull();
		requireThat(expectedOutcome, "expectedOutcome").isNotBlank();
		requireThat(assertions, "assertions").isNotEmpty();
		requireThat(record, "record").isNotBlank();
		values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
		assertions = Collections.unmodifiableMap(new LinkedHashMap<>(assertions));
		for (Map.Entry<String, String> value : values.entrySet())
		{
			requireThat(value.getKey(), "values.key").isNotNull();
			requireThat(value.getValue(), "values.get(name)").withContext(value.getKey(), "name").isNotNull();
		}
		for (Map.Entry<String, Boolean> assertion : assertions.entrySet())
		{
			requireThat(assertion.getKey(), "assertions.key").isNotNull();
			requireThat(assertion.getValue(), "assertions.get(name)").withContext(assertion.getKey(), "name").isNotNull();
		}
	}
}
