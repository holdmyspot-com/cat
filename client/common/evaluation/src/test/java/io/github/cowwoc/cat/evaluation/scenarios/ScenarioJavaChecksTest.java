package io.github.cowwoc.cat.evaluation.scenarios;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import io.github.cowwoc.cat.evaluation.ScenarioJavaChecks;
import org.testng.annotations.Test;

/**
 * Tests conventionally discovered Java checks.
 */
public final class ScenarioJavaChecksTest
{
	/**
	 * Creates the test fixture.
	 */
	public ScenarioJavaChecksTest()
	{
	}

	/**
	 * Verifies TestNG check execution receives evaluator-owned retained evidence and returns its assertion result.
	 *
	 * @throws IOException if TestNG check execution fails
	 */
	@Test
	public void runsConventionallyNamedCheck() throws IOException
	{
		Map<String, ScenarioJavaChecks.Result> results = ScenarioJavaChecks.run(PassingChecks.class.getName(),
			"ordinary-change", "/retained/project", "/retained/executor-result.json");

		requireThat(results.keySet(), "results.keySet()").containsExactly(List.of("readsRetainedEvidence"));
		requireThat(results.get("readsRetainedEvidence").passed(), "result.passed()").isTrue();
	}

	/**
	 * Verifies missing conventionally named checks are rejected.
	 *
	 * @throws IOException if TestNG check execution fails unexpectedly
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*no discovered Java checks.*")
	public void rejectsMissingCheck() throws IOException
	{
		ScenarioJavaChecks.run("io.github.cowwoc.cat.evaluation.scenarios.MissingScenarioTest", "missing",
			"/retained/project", "/retained/executor-result.json");
	}

	/**
	 * Verifies duplicate Java method names cannot produce ambiguous evaluator assertions.
	 *
	 * @throws IOException if TestNG check execution fails unexpectedly
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*ambiguous Java checks.*")
	public void rejectsAmbiguousCheck() throws IOException
	{
		ScenarioJavaChecks.run(DuplicateMethods.class.getName(), "ambiguous", "/retained/project",
			"/retained/executor-result.json");
	}
}
