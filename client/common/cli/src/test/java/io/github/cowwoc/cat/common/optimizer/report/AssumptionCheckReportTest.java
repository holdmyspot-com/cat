package io.github.cowwoc.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.AssumptionCheck.LevelRate;
import io.github.cowwoc.cat.common.optimizer.campaign.AssumptionCheck.Observation;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignBuilder;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.port.TokenUsage;
import io.github.cowwoc.cat.common.optimizer.statistics.ExploratoryBounds;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests the report side of the assumption review: {@link AssumptionReport} and its appearance in
 * {@link StatusReport}.
 */
public final class AssumptionCheckReportTest
{
	/**
	 * The campaign's protected root.
	 */
	private static final Path PROTECTED = Path.of("/project/.cat/work/optimizer/c1");

	/**
	 * Creates the test.
	 */
	public AssumptionCheckReportTest()
	{
	}

	/**
	 * Returns evidence with observations and paths.
	 *
	 * @param observations the observations
	 * @param accessed the paths that trials accessed
	 * @return the evidence
	 */
	private static ReportEvidence evidence(List<Observation> observations, List<Path> accessed)
	{
		return new ReportEvidence(observations, Map.of(), accessed, List.of(PROTECTED), false, false, null);
	}

	/**
	 * Verifies that the pass rates are descriptive, grouped by concurrency level, and imply no consequence.
	 */
	@Test
	public void passRatesByConcurrencyLevel()
	{
		// Requirement: R-P2
		// level 1: 2 passes in 3 trials; level 4: 2 passes in 2 trials
		AssumptionReport report = AssumptionReport.of(evidence(List.of(new Observation(4, Verdict.PASS),
			new Observation(1, Verdict.PASS), new Observation(1, Verdict.FAIL), new Observation(4, Verdict.PASS),
			new Observation(1, Verdict.PASS)), List.of(Path.of("/project/src/a.md"))));
		requireThat(report.passRatesByConcurrency(), "rates").isEqualTo(List.of(
			new LevelRate(1, 2, 3, ExploratoryBounds.wilson(2, 3)),
			new LevelRate(4, 2, 2, ExploratoryBounds.wilson(2, 2))));
		requireThat(report.dependenceDetected(), "dependenceDetected").isFalse();
		requireThat(report.consequence(), "consequence").isNull();
	}

	/**
	 * Verifies that a trial that accessed protected state gives {@code invalid statistical assumptions}.
	 */
	@Test
	public void dependenceGivesInvalidAssumptions()
	{
		// Requirement: R-P2
		AssumptionReport report = AssumptionReport.of(evidence(List.of(),
			List.of(Path.of("/project/.cat/work/optimizer/c1/trials.json"))));
		requireThat(report.dependenceDetected(), "dependenceDetected").isTrue();
		requireThat(report.consequence(), "consequence").isEqualTo(Outcome.INVALID_STATISTICAL_ASSUMPTIONS);
		requireThat(report.passRatesByConcurrency(), "rates").isEmpty();
	}

	/**
	 * Verifies that the status reports the outcome when dependence is detected, and shows the rates.
	 */
	@Test
	public void statusReportsInvalidAssumptions()
	{
		// Requirement: R-P2
		Campaign campaign = new CampaignBuilder().confirmed().working(1).readyDevelopment().readyBaseline().build();
		ReportEvidence dependent = evidence(List.of(new Observation(1, Verdict.PASS)),
			List.of(Path.of("/project/.cat/work/optimizer/c1/x")));
		StatusReport report = StatusReport.of(campaign, dependent);
		requireThat(report.outcome(), "outcome").isEqualTo(Outcome.INVALID_STATISTICAL_ASSUMPTIONS.label());
		requireThat(report.assumptions().passRatesByConcurrency().size(), "rates").isEqualTo(1);

		StatusReport independent = StatusReport.of(campaign, evidence(List.of(new Observation(1, Verdict.PASS)),
			List.of()));
		requireThat(independent.outcome(), "independentOutcome").isNull();
	}

	/**
	 * Verifies that token statistics use nearest-rank percentiles per role and are omitted without usage.
	 */
	@Test
	public void tokenStatisticsByRole()
	{
		// Requirement: R-E12
		// TRIAL: ten sessions with input 10, 20, ..., 100: sorted rank ceil(0.5*10) = 5 -> 50; ceil(0.9*10) = 9 -> 90
		//        cached 1..10 -> 5 and 9; output 2..20 -> 10 and 18; reasoning 1..10 -> 5 and 9
		// JUDGE: three sessions with input 5, 1, 3: sorted [1, 3, 5]; ceil(0.5*3) = 2 -> 3; ceil(0.9*3) = 3 -> 5
		List<TokenUsage> trial = new java.util.ArrayList<>();
		for (int i = 10; i >= 1; --i)
			trial.add(new TokenUsage(i * 10L, i, i * 2L, i));
		List<TokenUsage> judge = List.of(new TokenUsage(5, 0, 0, 0), new TokenUsage(1, 0, 0, 0),
			new TokenUsage(3, 0, 0, 0));
		TokenStatistics statistics = TokenStatistics.of(Map.of(SessionRole.TRIAL, trial, SessionRole.JUDGE, judge,
			SessionRole.AUTHOR, List.of()));
		requireThat(statistics.roles().keySet(), "roles").isEqualTo(java.util.Set.of(SessionRole.TRIAL,
			SessionRole.JUDGE));
		TokenStatistics.RoleUsage trialUsage = statistics.roles().get(SessionRole.TRIAL);
		requireThat(trialUsage.sessions(), "trial.sessions").isEqualTo(10);
		requireThat(trialUsage.input(), "trial.input").isEqualTo(new TokenStatistics.Percentiles(50, 90));
		requireThat(trialUsage.cachedInput(), "trial.cached").isEqualTo(new TokenStatistics.Percentiles(5, 9));
		requireThat(trialUsage.output(), "trial.output").isEqualTo(new TokenStatistics.Percentiles(10, 18));
		requireThat(trialUsage.reasoningOutput(), "trial.reasoning").
			isEqualTo(new TokenStatistics.Percentiles(5, 9));
		requireThat(statistics.roles().get(SessionRole.JUDGE).input(), "judge.input").
			isEqualTo(new TokenStatistics.Percentiles(3, 5));
		requireThat(statistics.unavailable(), "unavailable").isNull();

		TokenStatistics none = TokenStatistics.of(Map.of());
		requireThat(none.roles(), "noneRoles").isEmpty();
		requireThat(none.unavailable(), "noneUnavailable").isNotNull();
	}
}
