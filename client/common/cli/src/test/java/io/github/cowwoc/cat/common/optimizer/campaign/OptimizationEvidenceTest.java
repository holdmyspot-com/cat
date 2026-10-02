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
		Campaign campaign = new CampaignBuilder().confirmed().working(1).build();
		MeasurementIdentity earlier = campaign.identity();
		campaign = completeTokens(campaign, Purpose.DEVELOPMENT, 10L);
		campaign = completeTokens(campaign, Purpose.BASELINE, 20L);
		campaign = CampaignTransitions.apply(campaign,
			new CampaignEvent.ChangeMeasurement(CampaignBuilder.confirmedIdentity("model-b")));
		campaign = CampaignTransitions.apply(campaign, CampaignEvent.Choose.of(Choice.NEW_IDENTITY));
		campaign = completeTokens(campaign, Purpose.DEVELOPMENT, 100L);
		campaign = completeTokens(campaign, Purpose.BASELINE, 200L);

		InputComparison comparison = findSafety(OptimizationReport.of(campaign));
		requireThat(comparison.workingMedian(), "workingMedian").isEqualTo(100L);
		requireThat(comparison.incumbentMedian(), "incumbentMedian").isEqualTo(200L);
		requireThat(comparison.workingOutcomes(), "workingOutcomes").isEqualTo(1);
		requireThat(comparison.incumbentOutcomes(), "incumbentOutcomes").isEqualTo(1);
		requireThat(comparison.unavailable(), "unavailable").isNull();

		Campaign historical = new CampaignBuilder().confirmed().working(1).identity(earlier).
			optimization(campaign.optimization()).build();
		InputComparison historicalComparison = findSafety(OptimizationReport.of(historical));
		requireThat(historicalComparison.workingMedian(), "historicalWorkingMedian").isEqualTo(10L);
		requireThat(historicalComparison.incumbentMedian(), "historicalIncumbentMedian").isEqualTo(20L);
	}

	/**
	 * Equal incomplete identities leave input-token comparisons unavailable even when both medians exist.
	 */
	@Test
	public void missingConditionsBlockTokenComparison()
	{
		// Requirement: R-M4, R-E18
		OptimizationEvidence evidence = new OptimizationEvidence(List.of(), List.of(), Map.of(), Map.of(
			MeasurementIdentity.EMPTY, Map.of(
				CampaignBuilder.snapshot("candidate-1"), Map.of("safety", new TrialInputs(1, List.of(10L))),
				CampaignBuilder.INCUMBENT, Map.of("safety", new TrialInputs(1, List.of(20L))))));
		Campaign campaign = new CampaignBuilder().confirmed().working(1).identity(MeasurementIdentity.EMPTY).
			optimization(evidence).build();

		InputComparison comparison = findSafety(OptimizationReport.of(campaign));
		requireThat(comparison.workingMedian(), "workingMedian").isNull();
		requireThat(comparison.incumbentMedian(), "incumbentMedian").isNull();
		requireThat(comparison.unavailable(), "unavailable").contains("INDETERMINATE").
			contains(MeasurementCondition.MODEL.name()).contains(MeasurementCondition.HARNESS_VERSION.name());
		requireThat(comparison.workingOutcomes(), "workingOutcomes").isEqualTo(1);
		requireThat(comparison.incumbentOutcomes(), "incumbentOutcomes").isEqualTo(1);
	}

	/**
	 * Input-token comparisons name each condition that differs from the incumbent's recorded measurement.
	 */
	@Test
	public void differingConditionsBlockTokenComparison()
	{
		// Requirement: R-M4, R-E18
		MeasurementIdentity current = CampaignBuilder.confirmedIdentity("model-a");
		MeasurementIdentity earlier = CampaignBuilder.confirmedIdentity("model-b").replaceWith(
			new MeasurementIdentity(Map.of(MeasurementCondition.REASONING, "different-reasoning")));
		OptimizationEvidence evidence = OptimizationEvidence.EMPTY.
			addTrial(current, CampaignBuilder.snapshot("candidate-1"), "safety", 10L).
			addTrial(earlier, CampaignBuilder.INCUMBENT, "safety", 20L);
		Campaign campaign = new CampaignBuilder().confirmed().working(1).identity(current).
			baselineIdentity(earlier).optimization(evidence).build();

		InputComparison comparison = findSafety(OptimizationReport.of(campaign));
		requireThat(comparison.workingMedian(), "workingMedian").isNull();
		requireThat(comparison.incumbentMedian(), "incumbentMedian").isNull();
		requireThat(comparison.unavailable(), "unavailable").contains("NOT_COMPARABLE").
			contains(MeasurementCondition.MODEL.name()).contains(MeasurementCondition.REASONING.name());
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
		Campaign started = CampaignTransitions.apply(assigned, new CampaignEvent.Start(key));
		return CampaignTransitions.apply(started, new CampaignEvent.Complete(key, Verdict.PASS, "",
			started.identity(), tokens));
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
