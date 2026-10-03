package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.report.OptimizationReport;
import io.github.cowwoc.cat.common.optimizer.report.OptimizationReport.InputComparison;

import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests the measurement conditions that {@link OptimizationEvidence} retains for token comparisons.
 */
public final class OptimizationEvidenceTest
{
	/**
	 * Completed token observations remain under their measurement identity when the campaign starts another one.
	 */
	@Test
	public void tokenSamplesStayWithTheirIdentity()
	{
		// Requirement: R-M4, R-E18
		Campaign campaign = new Builder().confirmed().working(1).build();
		MeasurementIdentity earlier = campaign.identity();
		campaign = completeTokens(campaign, Purpose.DEVELOPMENT, 10L);
		campaign = completeTokens(campaign, Purpose.BASELINE, 20L);
		campaign = Transitions.apply(campaign,
			new CampaignEvent.ChangeMeasurement(Builder.confirmedIdentity("model-b")));
		campaign = Transitions.apply(campaign, CampaignEvent.Choose.of(Choice.NEW_IDENTITY));
		campaign = completeTokens(campaign, Purpose.DEVELOPMENT, 100L);
		campaign = completeTokens(campaign, Purpose.BASELINE, 200L);

		InputComparison comparison = findSafety(OptimizationReport.of(campaign));
		requireThat(comparison.workingMedian(), "workingMedian").isEqualTo(100L);
		requireThat(comparison.incumbentMedian(), "incumbentMedian").isEqualTo(200L);
		requireThat(comparison.workingOutcomes(), "workingOutcomes").isEqualTo(1);
		requireThat(comparison.incumbentOutcomes(), "incumbentOutcomes").isEqualTo(1);
		requireThat(comparison.unavailable(), "unavailable").isNull();

		Campaign historical = new Builder().confirmed().working(1).identity(earlier).
			optimization(campaign.optimization()).build();
		InputComparison historicalComparison = findSafety(OptimizationReport.of(historical));
		requireThat(historicalComparison.workingMedian(), "historicalWorkingMedian").isEqualTo(10L);
		requireThat(historicalComparison.incumbentMedian(), "historicalIncumbentMedian").isEqualTo(20L);
	}

	/**
	 * Input-token medians remain available when neither snapshot records its measurement conditions.
	 */
	@Test
	public void unobservedConditionsAllowTokenComparison()
	{
		// Requirement: R-M4, R-E18
		OptimizationEvidence evidence = new OptimizationEvidence(List.of(), List.of(), Map.of(), Map.of(
			MeasurementIdentity.EMPTY, Map.of(
				Builder.snapshot("candidate-1"), Map.of("safety", new TrialInputs(1, List.of(10L))),
				Builder.INCUMBENT, Map.of("safety", new TrialInputs(1, List.of(20L))))));
		Campaign campaign = new Builder().confirmed().working(1).identity(MeasurementIdentity.EMPTY).
			optimization(evidence).build();

		InputComparison comparison = findSafety(OptimizationReport.of(campaign));
		requireThat(comparison.workingMedian(), "workingMedian").isEqualTo(10L);
		requireThat(comparison.incumbentMedian(), "incumbentMedian").isEqualTo(20L);
		requireThat(comparison.unavailable(), "unavailable").isNull();
		requireThat(comparison.workingOutcomes(), "workingOutcomes").isEqualTo(1);
		requireThat(comparison.incumbentOutcomes(), "incumbentOutcomes").isEqualTo(1);
	}

	/**
	 * Input-token medians remain available when the snapshots' measurement conditions differ.
	 */
	@Test
	public void differingConditionsAllowTokenComparison()
	{
		// Requirement: R-M4, R-E18
		MeasurementIdentity current = Builder.confirmedIdentity("model-a");
		MeasurementIdentity earlier = Builder.confirmedIdentity("model-b").replaceWith(
			new MeasurementIdentity(Map.of(MeasurementCondition.REASONING, "different-reasoning")));
		OptimizationEvidence evidence = OptimizationEvidence.EMPTY.
			addTrial(current, Builder.snapshot("candidate-1"), "safety", 10L).
			addTrial(earlier, Builder.INCUMBENT, "safety", 20L);
		Campaign campaign = new Builder().confirmed().working(1).identity(current).
			baselineIdentity(earlier).optimization(evidence).build();

		InputComparison comparison = findSafety(OptimizationReport.of(campaign));
		requireThat(comparison.workingMedian(), "workingMedian").isEqualTo(10L);
		requireThat(comparison.incumbentMedian(), "incumbentMedian").isEqualTo(20L);
		requireThat(comparison.unavailable(), "unavailable").isNull();
		requireThat(comparison.workingOutcomes(), "workingOutcomes").isEqualTo(1);
		requireThat(comparison.incumbentOutcomes(), "incumbentOutcomes").isEqualTo(1);
	}

	/**
	 * Completes one representative trial under the campaign's recorded conditions.
	 *
	 * @param campaign the campaign
	 * @param purpose the trial arm
	 * @param tokens the completed input usage
	 * @return the updated campaign
	 */
	private static Campaign completeTokens(Campaign campaign, Purpose purpose, Long tokens)
	{
		Campaign assigned = TransitionSteps.assign(campaign, purpose, "safety");
		TrialKey key = TransitionSteps.last(assigned);
		Campaign started = Transitions.apply(assigned, new CampaignEvent.Start(key));
		return Transitions.apply(started, new CampaignEvent.Complete(key, Verdict.PASS, "",
			started.identity(), tokens, List.of()));
	}

	/**
	 * Finds the fixture's safety cell input comparison.
	 *
	 * @param report the optimization report
	 * @return the safety cell comparison
	 * @throws AssertionError if the report has no safety cell
	 */
	private static InputComparison findSafety(OptimizationReport report)
	{
		for (InputComparison comparison : report.inputs())
		{
			if (comparison.cell().equals("safety"))
				return comparison;
		}
		throw new AssertionError("No safety input comparison");
	}
}
