package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import org.testng.annotations.Test;

/**
 * Tests pure certification statistical policy.
 */
public final class StatisticsTest
{
	/**
	 * Creates the test fixture.
	 */
	public StatisticsTest()
	{
	}

	/**
	 * Verifies complete passing trials certify the required compliance rate once their lower confidence bound reaches it.
	 */
	@Test
	public void certifiesCompletePassingTrials()
	{
		Statistics.Parameters parameters = new Statistics.Parameters(0.95, 0.95, 0.85, 0.05, 0.05, 100);

		Statistics.Result result = Statistics.evaluate(parameters, 96, 96, false, false);

		requireThat(result.overallDecision(), "result.overallDecision()").isEqualTo(Statistics.OverallDecision.PASS);
		requireThat(result.lowerConfidenceBound(), "lowerConfidenceBound").isGreaterThanOrEqualTo(0.95);
	}

	/**
	 * Verifies a retained routing failure rejects a run even when completed outcomes otherwise pass.
	 */
	@Test
	public void routingFailureOverridesPassingOutcomes()
	{
		Statistics.Parameters parameters = new Statistics.Parameters(0.95, 0.95, 0.85, 0.05, 0.05, 100);

		Statistics.Result result = Statistics.evaluate(parameters, 96, 96, false, true);

		requireThat(result.overallDecision(), "result.overallDecision()").isEqualTo(Statistics.OverallDecision.FAIL);
	}

	/**
	 * Verifies repeated decisions retain no state from an earlier calculation.
	 */
	@Test
	public void repeatsDecisionsWithLocalState()
	{
		Statistics.Parameters parameters = new Statistics.Parameters(0.95, 0.95, 0.85, 0.05, 0.05, 100);

		Statistics.Result failed = Statistics.evaluate(parameters, 100, 0, false, false);
		requireThat(failed.overallDecision(), "failed.overallDecision()").isEqualTo(Statistics.OverallDecision.FAIL);

		Statistics.Result result = Statistics.evaluate(parameters, 96, 96, false, false);

		requireThat(result.overallDecision(), "result.overallDecision()").isEqualTo(Statistics.OverallDecision.PASS);
	}
}
