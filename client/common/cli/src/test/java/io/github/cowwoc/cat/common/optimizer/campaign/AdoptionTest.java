package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.cat.common.optimizer.campaign.CampaignBuilder.counts;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests that outcomes are adopted in assignment order and that post-decision outcomes never change a decision.
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
	 * Verifies that an outcome waits for every earlier-assigned outcome of its category and purpose.
	 */
	@Test
	public void laterOutcomeWaitsForEarlier()
	{
		// Requirement: R-E5
		Campaign campaign = new CampaignBuilder().confirmed().working(1).development("safety", 10, 0).build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		TrialKey first = TransitionSteps.last(campaign);
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		TrialKey second = TransitionSteps.last(campaign);
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Start(first));
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Start(second));

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
	 * Verifies that each category, purpose and snapshot numbers its trials from 1, and that adoption follows that
	 * numbering even when another group's trials are assigned in between.
	 */
	@Test
	public void eachGroupNumbersFromOne()
	{
		// Requirement: R-E5
		Campaign campaign = new CampaignBuilder().confirmed().working(1).build();
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
		requireThat(campaign.development().count("safety"), "waiting").isEqualTo(CategoryCounts.EMPTY);

		campaign = TransitionSteps.run(campaign, firstSafety, Verdict.PASS);
		requireThat(campaign.development().count("safety"), "safety").isEqualTo(counts(1, 1));
		requireThat(campaign.trials(), "trials").isEmpty();
	}

	/**
	 * Verifies that the order applies only within a category and purpose.
	 */
	@Test
	public void otherCategoryDoesNotWait()
	{
		// Requirement: R-E5
		Campaign campaign = new CampaignBuilder().confirmed().working(1).build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		TrialKey safety = TransitionSteps.last(campaign);
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Start(safety));
		campaign = TransitionSteps.adopt(campaign, Purpose.DEVELOPMENT, "formatting", Verdict.PASS);
		requireThat(campaign.development().count("formatting"), "formatting").isEqualTo(counts(1, 0));
		requireThat(campaign.development().count("safety"), "safety").isEqualTo(CategoryCounts.EMPTY);
	}

	/**
	 * Verifies that an outcome that arrives after its category certified is a post-decision observation that never
	 * changes the decision.
	 */
	@Test
	public void postDecisionOutcomeIsIgnored()
	{
		// Requirement: R-E5
		Campaign campaign = new CampaignBuilder().confirmed().working(1).certificationAttempt(1).
			category("formatting", 99, 0).category("safety", 10, 0).build();
		campaign = TransitionSteps.assign(campaign, Purpose.FORMAL, "formatting");
		TrialKey deciding = TransitionSteps.last(campaign);
		campaign = TransitionSteps.assign(campaign, Purpose.FORMAL, "formatting");
		TrialKey late = TransitionSteps.last(campaign);
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Start(deciding));
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Start(late));
		campaign = TransitionSteps.complete(campaign, late, Verdict.FAIL);
		campaign = TransitionSteps.complete(campaign, deciding, Verdict.PASS);

		CertificationAttempt attempt = campaign.attempt(1);
		requireThat(attempt.count("formatting"), "formatting").isEqualTo(counts(100, 0));
		requireThat(DecisionTable.categoryDecision(attempt.procedure().boundaries(), attempt.count("formatting")),
			"decision").isEqualTo(DecisionTable.CategoryDecision.CERTIFIED);
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
		Campaign campaign = new CampaignBuilder().confirmed().working(1).development("safety", 10, 0).build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		TrialKey old = TransitionSteps.last(campaign);
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Start(old));
		Campaign replaced = new CampaignBuilder().confirmed().working(2).series(2).trial(campaign.trials().getFirst()).
			build();
		replaced = TransitionSteps.complete(replaced, old, Verdict.FAIL);
		requireThat(replaced.development().count("safety"), "counts").isEqualTo(CategoryCounts.EMPTY);
		requireThat(replaced.trials(), "trials").isEmpty();
	}
}
