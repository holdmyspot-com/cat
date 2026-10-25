package com.holdmyspot.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.AssumptionCheck.LevelRate;
import com.holdmyspot.cat.common.optimizer.campaign.AssumptionCheck.Observation;
import com.holdmyspot.cat.common.optimizer.campaign.AssumptionCheck;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.Outcome;
import com.holdmyspot.cat.common.optimizer.campaign.Transitions;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.statistics.ExploratoryBounds;

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
	 * Verifies that a recorded invalid-assumptions outcome ends the attempt without certification.
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
