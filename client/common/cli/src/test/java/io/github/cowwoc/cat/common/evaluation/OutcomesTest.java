package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.testng.annotations.Test;

/**
 * Tests whole-suite outcome aggregation.
 */
public final class OutcomesTest
{
	/**
	 * Creates the test fixture.
	 */
	public OutcomesTest()
	{
	}

	/**
	 * Verifies failed and fully passed trials become complete outcomes while partial trials remain unreported.
	 */
	@Test
	public void collapsesCompleteTrialsOnly()
	{
		Map<Integer, Map<String, AttemptStatus>> statuses = new TreeMap<>();
		statuses.put(1, Map.of("one", AttemptStatus.PASSED, "two", AttemptStatus.FAILED));
		statuses.put(2, Map.of("one", AttemptStatus.PASSED, "two", AttemptStatus.PASSED));
		statuses.put(3, Map.of("one", AttemptStatus.PASSED));

		requireThat(Outcomes.complete(Set.of("one", "two"), statuses), "outcomes").containsExactly(
			new Outcomes.Result[]{new Outcomes.Result(1, false), new Outcomes.Result(2, true)});
	}
}
