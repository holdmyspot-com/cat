package io.github.cowwoc.cat.common.optimizer.report.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.AssumptionCheck.LevelRate;
import io.github.cowwoc.cat.common.optimizer.campaign.AssumptionCheck.Observation;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.campaign.test.Builder;
import io.github.cowwoc.cat.common.optimizer.report.AssumptionReport;
import io.github.cowwoc.cat.common.optimizer.report.ReportEvidence;
import io.github.cowwoc.cat.common.optimizer.report.StatusReport;
import io.github.cowwoc.cat.common.optimizer.statistics.ExploratoryBounds;

import java.nio.file.Path;
import java.util.List;
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
	private static final Path PROTECTED = Path.of("/project/.cat/sessions/optimizer/c1").toAbsolutePath();

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
		return new ReportEvidence(observations, accessed, List.of(PROTECTED), false, false, null);
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
			new Observation(1, Verdict.PASS)), List.of(Path.of("/project/src/a.md").toAbsolutePath())));
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
			List.of(Path.of("/project/.cat/sessions/optimizer/c1/trials.json").toAbsolutePath())));
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
		Campaign campaign = new Builder().confirmed().working(1).readyDevelopment().readyBaseline().build();
		ReportEvidence dependent = evidence(List.of(new Observation(1, Verdict.PASS)),
			List.of(Path.of("/project/.cat/sessions/optimizer/c1/x").toAbsolutePath()));
		StatusReport report = StatusReport.of(campaign, dependent);
		requireThat(report.outcome(), "outcome").isEqualTo(Outcome.INVALID_STATISTICAL_ASSUMPTIONS.label());
		requireThat(report.assumptions().passRatesByConcurrency().size(), "rates").isEqualTo(1);

		StatusReport independent = StatusReport.of(campaign, evidence(List.of(new Observation(1, Verdict.PASS)),
			List.of()));
		requireThat(independent.outcome(), "independentOutcome").isNull();
	}
}
