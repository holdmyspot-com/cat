package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.cat.common.optimizer.campaign.Builder.counts;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests {@link TrialAdoption}: representative outcomes enter counts in assignment order, and outcomes received
 * after a decision do not change it.
 */
public final class AdoptionTest
{
	/**
	 * Creates the test.
	 */
	public AdoptionTest()
	{
	}

	/**
	 * Verifies that a development outcome waits for an earlier-assigned outcome of the same cell, purpose, series
	 * and snapshot.
	 */
	@Test
	public void laterOutcomeWaitsForEarlier()
	{
		// Requirement: R-E5
		Campaign campaign = new Builder().confirmed().working(1).development("safety", 10, 0).build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		TrialKey first = TransitionSteps.last(campaign);
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		TrialKey second = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(first));
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(second));

		campaign = TransitionSteps.complete(campaign, second, Verdict.PASS);
		requireThat(campaign.development().count("safety"), "afterSecond").isEqualTo(counts(10, 0));
		requireThat(campaign.trials().size(), "unadopted").isEqualTo(2);

		campaign = TransitionSteps.complete(campaign, first, Verdict.FAIL);
		requireThat(campaign.development().count("safety"), "afterFirst").isEqualTo(counts(11, 1));
		requireThat(campaign.development().count("safety").batchFailures(), "batchFailures").
			isEqualTo(List.of(1));
		requireThat(campaign.trials(), "trials").isEmpty();
	}

	/**
	 * Verifies that two cells of the same purpose and snapshot number their trials separately from 1, and adoption
	 * follows each cell's numbering even when assignments to the two cells are interleaved.
	 */
	@Test
	public void eachGroupNumbersFromOne()
	{
		// Requirement: R-E5
		Campaign campaign = new Builder().confirmed().working(1).build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		TrialKey firstSafety = TransitionSteps.last(campaign);
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "formatting");
		TrialKey firstFormatting = TransitionSteps.last(campaign);
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		TrialKey secondSafety = TransitionSteps.last(campaign);
		requireThat(firstSafety.sequence(), "firstSafety").isEqualTo(1L);
		requireThat(firstFormatting.sequence(), "firstFormatting").isEqualTo(1L);
		requireThat(secondSafety.sequence(), "secondSafety").isEqualTo(2L);

		campaign = TransitionSteps.run(campaign, secondSafety, Verdict.FAIL);
		campaign = TransitionSteps.run(campaign, firstFormatting, Verdict.PASS);
		requireThat(campaign.development().count("formatting"), "formatting").isEqualTo(counts(1, 0));
		requireThat(campaign.development().count("safety"), "waiting").isEqualTo(CellCounts.EMPTY);

		campaign = TransitionSteps.run(campaign, firstSafety, Verdict.PASS);
		requireThat(campaign.development().count("safety"), "safety").isEqualTo(counts(1, 1));
		requireThat(campaign.trials(), "trials").isEmpty();
	}

	/**
	 * Verifies that a development outcome in one cell does not wait for a running development trial in another cell.
	 */
	@Test
	public void otherCellDoesNotWait()
	{
		// Requirement: R-E5
		Campaign campaign = new Builder().confirmed().working(1).build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		TrialKey safety = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(safety));
		campaign = TransitionSteps.adopt(campaign, Purpose.DEVELOPMENT, "formatting", Verdict.PASS);
		requireThat(campaign.development().count("formatting"), "formatting").isEqualTo(counts(1, 0));
		requireThat(campaign.development().count("safety"), "safety").isEqualTo(CellCounts.EMPTY);
	}

	/**
	 * Verifies that a cell's failure judged before its earlier-assigned certifying pass is discarded once that pass
	 * certifies the cell.
	 */
	@Test
	public void postDecisionOutcomeIsIgnored()
	{
		// Requirement: R-E5
		Campaign campaign = new Builder().confirmed().working(1).certificationAttempt(1).
			cell("formatting", 99, 0).cell("safety", 10, 0).build();
		campaign = TransitionSteps.assign(campaign, Purpose.FORMAL, "formatting");
		TrialKey deciding = TransitionSteps.last(campaign);
		campaign = TransitionSteps.assign(campaign, Purpose.FORMAL, "formatting");
		TrialKey late = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(deciding));
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(late));
		campaign = TransitionSteps.complete(campaign, late, Verdict.FAIL);
		campaign = TransitionSteps.complete(campaign, deciding, Verdict.PASS);

		CertificationAttempt attempt = campaign.attempt(1);
		requireThat(attempt.count("formatting"), "formatting").isEqualTo(counts(100, 0));
		requireThat(DecisionTable.cellDecision(attempt.procedure().boundaries(), attempt.count("formatting")),
			"decision").isEqualTo(DecisionTable.CellDecision.CERTIFIED);
		requireThat(attempt.failures(), "failures").isEmpty();
		requireThat(campaign.trials(), "trials").isEmpty();
	}

	/**
	 * Verifies that a trial whose candidate was replaced is dropped, while the replacement's counts start at zero.
	 */
	@Test
	public void replacedCandidateOutcomeIsDropped()
	{
		// Requirement: R-E5
		Campaign campaign = new Builder().confirmed().working(1).development("safety", 10, 0).build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		TrialKey old = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(old));
		Campaign replaced = new Builder().confirmed().working(2).series(2).trial(campaign.trials().getFirst()).
			build();
		replaced = TransitionSteps.complete(replaced, old, Verdict.FAIL);
		requireThat(replaced.development().count("safety"), "counts").isEqualTo(CellCounts.EMPTY);
		requireThat(replaced.trials(), "trials").isEmpty();
	}
}
