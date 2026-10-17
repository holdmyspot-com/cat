package io.github.cowwoc.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.AssumptionCheck.LevelRate;
import io.github.cowwoc.cat.common.optimizer.campaign.AssumptionCheck.Observation;
import io.github.cowwoc.cat.common.optimizer.campaign.AssumptionCheck;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.campaign.Transitions;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.statistics.ExploratoryBounds;

import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests {@link AssumptionCheck}.
 */
public final class AssumptionCheckTest
{
	/**
	 * Creates the test.
	 */
	public AssumptionCheckTest()
	{
	}

	/**
	 * Verifies that pass rates are grouped by concurrency level in ascending order, with exploratory intervals.
	 */
	@Test
	public void passRatesByConcurrencyLevel()
	{
		// Requirement: R-P2
		List<LevelRate> rates = AssumptionCheck.passRatesByConcurrency(List.of(new Observation(4, Verdict.PASS),
			new Observation(1, Verdict.PASS), new Observation(1, Verdict.FAIL), new Observation(4, Verdict.PASS),
			new Observation(1, Verdict.PASS)));
		requireThat(rates, "rates").isEqualTo(List.of(new LevelRate(1, 2, 3, ExploratoryBounds.wilson(2, 3)),
			new LevelRate(4, 2, 2, ExploratoryBounds.wilson(2, 2))));
		requireThat(AssumptionCheck.passRatesByConcurrency(List.of()), "empty").isEmpty();
	}

	/**
	 * Verifies that a trial that read protected state is dependent, and that a path outside it is not.
	 */
	@Test
	public void protectedAccessIsDependence()
	{
		// Requirement: R-P2
		List<Path> protectedRoots = List.of(Path.of("/project/.cat/sessions/optimizer/c1").toAbsolutePath());
		requireThat(AssumptionCheck.isDependent(
			List.of(Path.of("/project/.cat/sessions/optimizer/c1/trials.json").toAbsolutePath()), protectedRoots), "inside").
			isTrue();
		requireThat(AssumptionCheck.isDependent(List.of(Path.of("/project/.cat/sessions/optimizer/c1").toAbsolutePath()),
			protectedRoots), "root").isTrue();
		requireThat(AssumptionCheck.isDependent(List.of(Path.of("/project/.cat/sessions/optimizer/c10/x").toAbsolutePath()),
			protectedRoots), "sibling").isFalse();
		requireThat(AssumptionCheck.isDependent(List.of(Path.of("/project/src/a.md").toAbsolutePath()), protectedRoots),
			"outside").isFalse();
		requireThat(AssumptionCheck.isDependent(
			List.of(Path.of("/project/.cat/sessions/optimizer/c1/../c1/x").toAbsolutePath()), protectedRoots),
			"unnormalized").isTrue();
	}

	/**
	 * Verifies that detected dependence ends the attempt {@code invalid statistical assumptions}.
	 */
	@Test
	public void dependenceInvalidatesAttempt()
	{
		// Requirement: R-P2
		Campaign campaign = new Builder().confirmed().working(1).certificationAttempt(1).
			cell("safety", 30, 0).build();
		campaign = Transitions.apply(campaign,
			new CampaignEvent.ReportUnavailable(Outcome.INVALID_STATISTICAL_ASSUMPTIONS));
		requireThat(campaign.attempt(1).outcome(), "outcome").isEqualTo(Outcome.INVALID_STATISTICAL_ASSUMPTIONS);
		requireThat(campaign.outcome().label(), "label").
			isEqualTo("certification unavailable: invalid statistical assumptions");
	}
}
