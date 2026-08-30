package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.ToolScope;

import java.io.IOException;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests fixed-budget benchmark policy.
 */
public final class BenchmarkTest
{
	private static final int MAXIMUM_SUITE_RUNS = 100;

	/**
	 * Creates the test fixture.
	 */
	public BenchmarkTest()
	{
	}

	/**
	 * Configures the evaluator-wide limit that benchmark policy consumes.
	 */
	@BeforeClass
	public void configure()
	{
		Benchmark.configure(() -> MAXIMUM_SUITE_RUNS);
	}

	/**
	 * Verifies the benchmark policy accepts its smallest valid fixed trial budget.
	 *
	 * @throws IOException if the budget is unexpectedly rejected
	 */
	@Test
	public void acceptsPositiveTrialBudget() throws IOException
	{
		Benchmark.validateTrialCount(1);
	}

	/**
	 * Verifies the benchmark policy rejects a trial budget that cannot identify a whole benchmark trial.
	 *
	 * @throws IOException if the expected rejection cannot be evaluated
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*Paired benchmark trial count must be between 1 and.*")
	public void rejectsNonpositiveTrialBudget() throws IOException
	{
		Benchmark.validateTrialCount(0);
	}

	/**
	 * Verifies a fixed benchmark remains in progress until it receives every reserved trial.
	 *
	 * @throws IOException if the policy cannot read the fixture record
	 */
	@Test
	public void retainsFixedTrialBudget() throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			ObjectNode benchmark = scope.json().createObjectNode();
		benchmark.put("mode", "benchmark");
		benchmark.put("fixed_trial_budget", 2);

		requireThat(Benchmark.inProgress(benchmark, 1, false), "inProgress").isTrue();
		requireThat(Benchmark.inProgress(benchmark, 2, false), "completed").isFalse();
		requireThat(Benchmark.inProgress(benchmark, 1, true), "infrastructureFailure").isFalse();
			requireThat(Benchmark.trialLimit(benchmark), "trialLimit").isEqualTo(2);
		}
	}

	/**
	 * Verifies an ordinary certification record retains the evaluator-wide trial limit.
	 *
	 * @throws IOException if the policy cannot read the fixture record
	 */
	@Test
	public void usesEvaluatorLimitForOrdinaryRecord() throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			ObjectNode certification = scope.json().createObjectNode();
		certification.put("mode", "certification");

		requireThat(Benchmark.inProgress(certification, 1, false), "inProgress").isFalse();
			requireThat(Benchmark.trialLimit(certification), "trialLimit").isEqualTo(MAXIMUM_SUITE_RUNS);
		}
	}
}
