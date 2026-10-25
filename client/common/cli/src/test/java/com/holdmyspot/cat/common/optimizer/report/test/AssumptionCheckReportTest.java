package com.holdmyspot.cat.common.optimizer.report.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.AssumptionCheck.LevelRate;
import com.holdmyspot.cat.common.optimizer.campaign.AssumptionCheck.Observation;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.Outcome;
import com.holdmyspot.cat.common.optimizer.campaign.Transitions;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.campaign.test.Builder;
import com.holdmyspot.cat.common.optimizer.report.AssumptionReport;
import com.holdmyspot.cat.common.optimizer.report.ReportEvidence;
import com.holdmyspot.cat.common.optimizer.report.StatusReport;
import com.holdmyspot.cat.common.optimizer.statistics.ExploratoryBounds;

import java.util.List;
import java.util.HashSet;
import java.util.Set;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the report side of the assumption review: {@link AssumptionReport} and its appearance in
 * {@link StatusReport}.
 */
public final class AssumptionCheckReportTest
{
	/**
	 * Creates the test.
	 */
	public AssumptionCheckReportTest()
	{
	}

	/**
	 * Returns evidence with observations.
	 *
	 * @param observations the observations
	 * @return the evidence
	 */
	private static ReportEvidence evidence(List<Observation> observations)
	{
		return new ReportEvidence(observations, false, false, null);
	}

	/**
	 * Verifies that the descriptive pass rates are grouped by concurrency level.
	 */
	@Test
	public void passRatesByConcurrencyLevel()
	{
		// Requirement: R-P2
		// level 1: 2 passes in 3 trials; level 4: 2 passes in 2 trials
		AssumptionReport report = AssumptionReport.of(evidence(List.of(new Observation(4, Verdict.PASS),
			new Observation(1, Verdict.PASS), new Observation(1, Verdict.FAIL), new Observation(4, Verdict.PASS),
			new Observation(1, Verdict.PASS))));
		requireThat(report.passRatesByConcurrency(), "rates").isEqualTo(List.of(
			new LevelRate(1, 2, 3, ExploratoryBounds.wilson(2, 3)),
			new LevelRate(4, 2, 2, ExploratoryBounds.wilson(2, 2))));
	}

	/**
	 * Status JSON reports descriptive concurrency rates without claiming that dependence was checked.
	 */
	@Test
	public void statusAssumptionsAreDescriptive()
	{
		// Requirement: R-P2
		Campaign campaign = new Builder().confirmed().working(1).readyDevelopment().readyBaseline().build();
		StatusReport report = StatusReport.of(campaign, evidence(List.of(new Observation(1, Verdict.PASS))));
		JsonNode assumptions = report.toJson(JsonMapper.builder().build()).get("assumptions");
		requireThat(new HashSet<>(assumptions.propertyNames()), "assumptionFields").
			isEqualTo(Set.of("pass_rates_by_concurrency", "descriptive"));
		requireThat(assumptions.get("descriptive").asBoolean(), "descriptive").isTrue();
		requireThat(assumptions.get("pass_rates_by_concurrency").get(0).get("passes").asInt(), "passes").isEqualTo(1);
		requireThat(report.outcome(), "outcome").isNull();
	}

	/**
	 * Verifies that status preserves a recorded invalid-assumptions outcome and shows descriptive rates.
	 */
	@Test
	public void statusReportsInvalidAssumptions()
	{
		// Requirement: R-P2
		Campaign campaign = new Builder().confirmed().working(1).certificationAttempt(1).
			cell("safety", 30, 0).build();
		campaign = Transitions.apply(campaign,
			new CampaignEvent.ReportUnavailable(Outcome.INVALID_STATISTICAL_ASSUMPTIONS));
		ReportEvidence observations = evidence(List.of(new Observation(1, Verdict.PASS)));
		StatusReport report = StatusReport.of(campaign, observations);
		requireThat(report.outcome(), "outcome").isEqualTo(Outcome.INVALID_STATISTICAL_ASSUMPTIONS.label());
		requireThat(report.assumptions().passRatesByConcurrency().size(), "rates").isEqualTo(1);

		Campaign ongoing = new Builder().confirmed().working(1).readyDevelopment().readyBaseline().build();
		StatusReport independent = StatusReport.of(ongoing, observations);
		requireThat(independent.outcome(), "independentOutcome").isNull();
	}
}
