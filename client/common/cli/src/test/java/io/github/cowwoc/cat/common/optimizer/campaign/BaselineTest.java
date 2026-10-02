package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.cat.common.optimizer.campaign.Builder.counts;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.statistics.CapCalibration;
import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;

import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests {@link Baseline} and how the campaign keeps the incumbent's baseline.
 */
public final class BaselineTest
{
	/**
	 * Creates the test.
	 */
	public BaselineTest()
	{
	}

	/**
	 * Verifies the deficiency decision for two categories in the first baseline: 11 failures of 50 prove it and 10
	 * do not.
	 */
	@Test
	public void deficiencyAtElevenOfFifty()
	{
		// Requirement: R-D7
		requireThat(Baseline.isProvenDeficient(counts(39, 11), 1, 2), "11of50").isTrue();
		requireThat(Baseline.isProvenDeficient(counts(40, 10), 1, 2), "10of50").isFalse();
		requireThat(Baseline.isProvenDeficient(counts(39, 11), 2, 2), "secondBaseline").isFalse();
	}

	/**
	 * Verifies that every category needs 50 trials.
	 */
	@Test
	public void everyCellNeedsFifty()
	{
		// Requirement: R-D7
		requireThat(Baseline.target(counts(0, 0), 1, 2), "empty").isEqualTo(50);
		requireThat(Baseline.target(counts(30, 10), 1, 2), "partial").isEqualTo(50);
		requireThat(Baseline.target(counts(48, 2), 1, 2), "96Percent").isEqualTo(50);
	}

	/**
	 * Verifies that a category observed below 95% and not proven deficient needs 50 more trials, and that one at
	 * 95% or proven deficient does not.
	 */
	@Test
	public void unprovenDeficitNeedsFiftyMore()
	{
		// Requirement: R-D7
		requireThat(Baseline.target(counts(48, 2), 1, 2), "96Percent").isEqualTo(50);
		requireThat(Baseline.target(counts(57, 3), 1, 2), "95Percent").isEqualTo(50);
		requireThat(Baseline.target(counts(47, 3), 1, 2), "94Percent").isEqualTo(100);
		requireThat(Baseline.target(counts(40, 10), 1, 2), "unproven").isEqualTo(100);
		requireThat(Baseline.target(counts(39, 11), 1, 2), "proven").isEqualTo(50);
	}

	/**
	 * Verifies that the baseline is met only when every category reaches its target.
	 */
	@Test
	public void metWhenEveryCellReachesTarget()
	{
		// Requirement: R-D7
		MeasurementIdentity identity = Builder.confirmedIdentity("model-a");
		requireThat(Baseline.isMet(new IncumbentBaseline(1, identity, Map.of("formatting", counts(50, 0), "safety",
			counts(50, 0))), ContractFixtures.contract()), "both").isTrue();
		requireThat(Baseline.isMet(new IncumbentBaseline(1, identity, Map.of("formatting", counts(50, 0), "safety",
			counts(49, 0))), ContractFixtures.contract()), "short").isFalse();
		requireThat(Baseline.isMet(new IncumbentBaseline(1, identity, Map.of("formatting", counts(50, 0))),
			ContractFixtures.contract()), "missing").isFalse();
		requireThat(Baseline.isMet(new IncumbentBaseline(1, identity, Map.of("formatting", counts(50, 0), "safety",
			counts(95, 5))), ContractFixtures.contract()), "extendedMet").isTrue();
		requireThat(Baseline.isMet(new IncumbentBaseline(1, identity, Map.of("formatting", counts(50, 0), "safety",
			counts(94, 5))), ContractFixtures.contract()), "extendedShort").isFalse();
	}

	/**
	 * Verifies that an incumbent baseline trial supplies its observed measurement identity to the baseline evidence.
	 */
	@Test
	public void baselineTrialRecordsIdentity()
	{
		// Requirement: R-M4, R-D7
		Campaign campaign = new Builder().confirmed().working(1).
			baselineIdentity(MeasurementIdentity.EMPTY).build();
		MeasurementIdentity observed = campaign.identity();
		campaign = TransitionSteps.adopt(campaign, Purpose.BASELINE, "formatting", Verdict.PASS);

		requireThat(campaign.baseline().identity(), "baselineIdentity").isEqualTo(observed);
	}

	/**
	 * Verifies that incumbent development trials supply measurement conditions to their shared baseline, while a
	 * promoted candidate's development trials do not fill missing incumbent conditions.
	 */
	@Test
	public void developmentKeepsBaselineIdentity()
	{
		// Requirement: R-M4, R-D7
		Campaign incumbent = new Builder().confirmed().baselineIdentity(MeasurementIdentity.EMPTY).build();
		MeasurementIdentity observed = incumbent.identity();
		incumbent = TransitionSteps.adopt(incumbent, Purpose.DEVELOPMENT, "formatting", Verdict.PASS);
		Campaign candidate = new Builder().confirmed().working(1).
			baselineIdentity(MeasurementIdentity.EMPTY).build();
		candidate = TransitionSteps.adopt(candidate, Purpose.DEVELOPMENT, "formatting", Verdict.PASS);

		requireThat(incumbent.baseline().identity(), "incumbentBaseline").isEqualTo(observed);
		requireThat(candidate.baseline().identity(), "candidateBaseline").isEqualTo(MeasurementIdentity.EMPTY);
	}

	/**
	 * Verifies that a commit requires the baseline.
	 */
	@Test(expectedExceptions = IllegalStateException.class)
	public void commitRequiresBaseline()
	{
		// Requirement: R-D7
		Campaign campaign = new Builder().confirmed().readyDevelopment().baseline("formatting", 50, 0).
			baseline("safety", 47, 3).build();
		Transitions.apply(campaign, new CampaignEvent.Commit(
			new CapCalibration(ErrorBudget.certificationLevel(1), 262), 3));
	}

	/**
	 * Verifies that the incumbent's development trials are its baseline.
	 */
	@Test
	public void incumbentDevelopmentTrialsAreBaseline()
	{
		// Requirement: R-D7
		Campaign campaign = new Builder().confirmed().build();
		campaign = TransitionSteps.adopt(campaign, Purpose.DEVELOPMENT, "formatting", Verdict.PASS);
		requireThat(campaign.baseline().count("formatting"), "baseline").isEqualTo(counts(1, 0));
		requireThat(campaign.development().count("formatting"), "development").isEqualTo(counts(1, 0));

		Campaign promoted = new Builder().confirmed().working(1).build();
		promoted = TransitionSteps.adopt(promoted, Purpose.DEVELOPMENT, "formatting", Verdict.PASS);
		requireThat(promoted.baseline().count("formatting"), "promotedBaseline").isEqualTo(CellCounts.EMPTY);
	}

	/**
	 * Verifies that the baseline is reused across candidates while the measurement identity is unchanged, and that a
	 * new measurement identity starts a new, empty baseline.
	 */
	@Test
	public void newIdentityStartsNewBaseline()
	{
		// Requirement: R-D7
		Campaign campaign = new Builder().confirmed().working(1).readyBaseline().build();
		campaign = TransitionSteps.adopt(campaign, Purpose.BASELINE, "safety", Verdict.PASS);
		requireThat(campaign.baseline().index(), "index").isEqualTo(1);
		requireThat(campaign.baseline().count("safety"), "reused").isEqualTo(counts(51, 0));

		campaign = Transitions.apply(campaign,
			new CampaignEvent.ChangeMeasurement(Builder.observed("model-b")));
		campaign = Transitions.apply(campaign, CampaignEvent.Choose.of(Choice.NEW_IDENTITY));
		requireThat(campaign.baseline().index(), "newIndex").isEqualTo(2);
		requireThat(campaign.baseline().count("safety"), "newCounts").isEqualTo(CellCounts.EMPTY);
	}
}
