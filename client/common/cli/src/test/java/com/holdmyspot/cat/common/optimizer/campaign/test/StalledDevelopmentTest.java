package com.holdmyspot.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.Choice;
import com.holdmyspot.cat.common.optimizer.campaign.DiagnosticRerun;
import com.holdmyspot.cat.common.optimizer.campaign.Failure;
import com.holdmyspot.cat.common.optimizer.campaign.Impact;
import com.holdmyspot.cat.common.optimizer.campaign.KeepImprovement;
import com.holdmyspot.cat.common.optimizer.campaign.Lifecycle;
import com.holdmyspot.cat.common.optimizer.campaign.Outcome;
import com.holdmyspot.cat.common.optimizer.campaign.PendingDecision;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorAttempt;
import com.holdmyspot.cat.common.optimizer.campaign.PromotionRun;
import com.holdmyspot.cat.common.optimizer.campaign.Purpose;
import com.holdmyspot.cat.common.optimizer.campaign.Round;
import com.holdmyspot.cat.common.optimizer.campaign.Scheduler;
import com.holdmyspot.cat.common.optimizer.campaign.StallProgress;
import com.holdmyspot.cat.common.optimizer.campaign.Transitions;
import com.holdmyspot.cat.common.optimizer.campaign.Triage;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests that development pauses for the user's choice after {@value StallProgress#ROUNDS} completed rounds in a row
 * make no progress on a prompt test.
 */
public final class StalledDevelopmentTest
{
	private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
	private static final PendingDecision.DevelopmentStalled SAFETY_STALLED =
		new PendingDecision.DevelopmentStalled(List.of("safety"));

	/**
	 * Creates the test.
	 */
	public StalledDevelopmentTest()
	{
	}

	/**
	 * Returns a campaign whose cells have no failures yet. A cell whose development estimate is below 0.8 makes a
	 * round weak, and a weak round runs concurrent prompt author attempts, so {@code passes} is large enough that ten
	 * rounds' failures do not make a round weak.
	 *
	 * @param passes the passes in each cell
	 * @return the campaign
	 */
	private static Campaign developing(int passes)
	{
		return new Builder().confirmed().working(1).development("safety", passes, 0).
			development("formatting", passes, 0).development("plain_list", passes, 0).window(4).build();
	}

	/**
	 * Runs one development round on {@code safety} that ends without a promotion: a blocking failure starts it and
	 * the prompt author's two attempts both fail their promotion run.
	 *
	 * @param campaign the campaign, which has no round in progress
	 * @return the campaign after the round
	 */
	private static Campaign unproductiveRound(Campaign campaign)
	{
		campaign = TransitionSteps.fail(campaign, "safety", new Triage(Impact.BLOCKING, "The reply stopped."));
		requireThat(campaign.development().round(), "round").isNotNull();
		campaign = rejectAttempt(campaign);
		requireThat(campaign.development().round(), "roundAfterFirstAttempt").isNotNull();
		return rejectAttempt(campaign);
	}

	/**
	 * Submits one prompt author attempt on {@code safety} and fails its promotion run.
	 *
	 * @param campaign the campaign
	 * @return the campaign after the attempt's rejection
	 */
	private static Campaign rejectAttempt(Campaign campaign)
	{
		// Each attempt authors a different snapshot, so that none repeats an earlier one
		Sha256 snapshot = Builder.snapshot("attempt" + campaign.optimization().attempts().size());
		Campaign result = TransitionSteps.submit(campaign, "safety", snapshot, List.of());
		while (result.development().promptAuthor() != null)
		{
			PromptAuthorAttempt promptAuthor = result.development().promptAuthor();
			PromotionRun run = promptAuthor.runs().stream().filter(element -> element.verdict() == null).findFirst().
				orElseThrow();
			result = TransitionSteps.adopt(result, Purpose.PROMOTION, promptAuthor.number(), snapshot,
				run.testCase(), Verdict.FAIL);
		}
		return result;
	}

	/**
	 * Runs {@code rounds} unproductive rounds.
	 *
	 * @param campaign the campaign
	 * @param rounds the number of rounds
	 * @return the campaign after the rounds
	 */
	private static Campaign unproductiveRounds(Campaign campaign, int rounds)
	{
		for (int i = 0; i < rounds; ++i)
			campaign = unproductiveRound(campaign);
		return campaign;
	}

	/**
	 * Verifies that four rounds without progress do not pause the campaign and the fifth does, with the decision
	 * naming the stalled test and no new work scheduled.
	 */
	@Test
	public void fiveRoundsWithoutProgressPause()
	{
		Campaign campaign = unproductiveRounds(developing(100), StallProgress.ROUNDS - 1);
		requireThat(campaign.pending(), "pendingAfterFour").isNull();
		requireThat(campaign.development().stalls().get("safety").rounds().size(), "roundsAfterFour").isEqualTo(4);

		campaign = unproductiveRound(campaign);

		requireThat(campaign.pending(), "pending").isEqualTo(SAFETY_STALLED);
		requireThat(campaign.lifecycle(), "lifecycle").isEqualTo(Lifecycle.ACTIVE);
		requireThat(campaign.choices(), "choices").isEqualTo(List.of(Choice.CONTINUE_DEVELOPMENT, Choice.STOP));
		requireThat(campaign.development().stalls().get("safety").rounds().size(), "rounds").
			isEqualTo(StallProgress.ROUNDS);
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEmpty();
		requireThat(campaign.development().stalls().containsKey("formatting"), "formattingTracked").isFalse();
	}

	/**
	 * Verifies that a round after which a cell's estimate exceeds its best value ends the run of rounds without
	 * progress, so that the pause needs {@value StallProgress#ROUNDS} further rounds.
	 */
	@Test
	public void progressResetsCount()
	{
		Campaign campaign = unproductiveRounds(developing(60), 3);
		// The 60 passes raise the estimate above the one that the first round began with
		for (int i = 0; i < 60; ++i)
			campaign = TransitionSteps.adopt(campaign, Purpose.DEVELOPMENT, "safety", Verdict.PASS);
		requireThat(campaign.pending(), "pendingBeforeFourth").isNull();

		campaign = unproductiveRound(campaign);
		requireThat(campaign.pending(), "pendingAfterProgress").isNull();
		requireThat(campaign.development().stalls().get("safety").rounds(), "roundsAfterProgress").isEmpty();

		campaign = unproductiveRounds(campaign, StallProgress.ROUNDS - 1);
		requireThat(campaign.pending(), "pendingAfterFourMore").isNull();
		campaign = unproductiveRound(campaign);
		requireThat(campaign.pending(), "pendingAfterFiveMore").isEqualTo(SAFETY_STALLED);
	}

	/**
	 * Verifies that continuing development resolves the pause, starts the count again from zero, and pauses again
	 * only after {@value StallProgress#ROUNDS} more rounds without progress.
	 */
	@Test
	public void continueRunsFiveMoreRounds()
	{
		Campaign stalled = unproductiveRounds(developing(100), StallProgress.ROUNDS);
		requireThat(stalled.pending(), "pending").isEqualTo(SAFETY_STALLED);

		Campaign campaign = Transitions.apply(stalled, CampaignEvent.Choose.of(Choice.CONTINUE_DEVELOPMENT));

		requireThat(campaign.pending(), "pendingAfterContinue").isNull();
		requireThat(campaign.lifecycle(), "lifecycle").isEqualTo(Lifecycle.ACTIVE);
		requireThat(campaign.development().stalls().get("safety").rounds(), "rounds").isEmpty();
		campaign = unproductiveRounds(campaign, StallProgress.ROUNDS - 1);
		requireThat(campaign.pending(), "pendingAfterFourMore").isNull();
		campaign = unproductiveRound(campaign);
		requireThat(campaign.pending(), "pendingAfterFiveMore").isEqualTo(SAFETY_STALLED);
	}

	/**
	 * Verifies that stopping at the pause cancels the campaign and leaves the decision pending for a later resume.
	 */
	@Test
	public void stopCancelsAndKeepsDecision()
	{
		Campaign stalled = unproductiveRounds(developing(100), StallProgress.ROUNDS);

		Campaign campaign = Transitions.apply(stalled, CampaignEvent.Choose.of(Choice.STOP));

		requireThat(campaign.lifecycle(), "lifecycle").isEqualTo(Lifecycle.CANCELLED);
		requireThat(campaign.pending(), "pending").isEqualTo(SAFETY_STALLED);
	}

	/**
	 * Verifies that keeping the best proven candidate is offered at the pause only when a certification attempt
	 * qualifies for it, and that keeping it ends the campaign without certification.
	 */
	@Test
	public void keepImprovementOfferedOnlyWhenQualified()
	{
		Campaign without = unproductiveRounds(withAttempt(59), StallProgress.ROUNDS);
		requireThat(KeepImprovement.offers(without), "offersWithout").isEmpty();
		requireThat(without.choices(), "choicesWithout").
			isEqualTo(List.of(Choice.CONTINUE_DEVELOPMENT, Choice.STOP));

		Campaign with = unproductiveRounds(withAttempt(60), StallProgress.ROUNDS);
		requireThat(with.pending(), "pending").isEqualTo(SAFETY_STALLED);
		requireThat(with.choices(), "choicesWith").
			isEqualTo(List.of(Choice.CONTINUE_DEVELOPMENT, Choice.KEEP_IMPROVEMENT, Choice.STOP));

		Campaign kept = Transitions.apply(with, new CampaignEvent.Choose(Choice.KEEP_IMPROVEMENT, 1, 0));
		requireThat(kept.lifecycle(), "lifecycle").isEqualTo(Lifecycle.FINISHED);
		requireThat(kept.outcome(), "outcome").isEqualTo(Outcome.IMPROVED_NOT_CERTIFIED);
		requireThat(kept.pending(), "pendingAfterKeep").isNull();
	}

	/**
	 * Returns a developing campaign with an ended certification attempt whose {@code formatting} cell has the given
	 * passes and no failures. The attempt proves {@code formatting} better only from 60 passes on, and certifies the
	 * other cells.
	 *
	 * @param formattingPasses the passes in {@code formatting}
	 * @return the campaign
	 */
	private static Campaign withAttempt(int formattingPasses)
	{
		return new Builder().confirmed().working(1).development("safety", 100, 0).development("formatting", 100, 0).
			development("plain_list", 100, 0).window(4).certificationAttempt(1).
			boundaries(Boundaries.decision(Boundaries.STANDARD_CAP, 100, 50, 6,
				List.of(Boundaries.comparison(Boundaries.STANDARD_CAP, 60, 40, 8),
					Boundaries.comparison(Boundaries.STANDARD_CAP, 1000, 0, Boundaries.STANDARD_CAP + 1),
					Boundaries.comparison(Boundaries.STANDARD_CAP, 150, 40, 8)))).
			cell("formatting", formattingPasses, 0).cell("plain_list", 100, 0).cell("safety", 100, 0).
			outcome(Outcome.INCONCLUSIVE).campaign().build();
	}

	/**
	 * Returns a campaign whose {@code safety} round has one rejected attempt left to complete, with {@code counted}
	 * earlier rounds without progress counted for {@code safety}.
	 *
	 * @param counted the number of counted rounds
	 * @return the campaign
	 */
	private static Campaign beforePromotion(int counted)
	{
		DrawnTestCase logs = Builder.targeted("safety", "logs");
		DiagnosticRerun failed = new DiagnosticRerun(Verdict.FAIL, "The same defect reproduced");
		List<Map<String, Double>> rounds = new ArrayList<>();
		for (int i = 0; i < counted; ++i)
			rounds.add(Map.of("safety", 0.9));
		return new Builder().confirmed().working(1).development("safety", 99, 1).development("formatting", 61, 0).
			failure(new Failure(logs, "The directory was removed", List.of(), null, List.of(failed, failed, failed),
				List.of(), Builder.FILES)).regression(logs, Verdict.FAIL).
			stalls("safety", new StallProgress(Map.of("safety", 0.99), 1, rounds)).
			round(new Round(Map.of("safety", 1))).build();
	}

	/**
	 * Promotes a passing attempt through the same durable transitions as the driver.
	 *
	 * @param campaign the campaign before the promotion
	 * @return the promoted campaign
	 */
	private static Campaign promote(Campaign campaign)
	{
		Sha256 snapshot = Builder.snapshot("promoted-candidate");
		Campaign submitted = TransitionSteps.submit(campaign, "safety", snapshot, List.of());
		DrawnTestCase testCase = campaign.development().failures().getFirst().testCase();
		int number = submitted.development().promptAuthor().number();
		for (int run = 0; run < 2; ++run)
			submitted = TransitionSteps.adopt(submitted, Purpose.PROMOTION, number, snapshot, testCase, Verdict.PASS);
		return Transitions.apply(submitted, new CampaignEvent.AdvanceMinimality(snapshot));
	}

	/**
	 * Verifies that a round that ends in a promotion makes progress for the test that it addressed: with four rounds
	 * counted, it would have been the fifth and paused the campaign, but it ends the run instead, and the promoted
	 * candidate's estimates become the test's best values.
	 */
	@Test
	public void promotionResetsCount()
	{
		Campaign promoted = promote(beforePromotion(StallProgress.ROUNDS - 1));

		requireThat(promoted.development().working().number(), "working").isEqualTo(2);
		requireThat(promoted.pending(), "pending").isNull();
		StallProgress progress = promoted.development().stalls().get("safety");
		requireThat(progress.rounds(), "rounds").isEmpty();
		requireThat(progress.bestEstimates(), "bestEstimates").isEqualTo(Map.of("safety", 0.0));
	}

	/**
	 * Verifies that reverting a promotion restores the test's counted rounds and best values from before the
	 * promotion's round, so that the restored round is counted once and the five-round limit counts from the restored
	 * count.
	 */
	@Test
	public void revertedPromotionRestoresCount()
	{
		Campaign before = beforePromotion(3);
		Campaign promoted = promote(before);
		requireThat(promoted.development().stalls().get("safety").rounds(), "roundsAfterPromotion").isEmpty();

		Campaign reverted = TransitionSteps.fail(promoted, "safety", new Triage(Impact.BLOCKING,
			"The reply deleted the logs and stopped."));

		requireThat(reverted.development().working(), "working").isEqualTo(before.development().working());
		requireThat(reverted.development().reverts().size(), "reverts").isEqualTo(1);
		requireThat(reverted.development().round(), "round").isNull();
		// The restored round ended without progress and is the fourth, not the first or the fifth
		StallProgress progress = reverted.development().stalls().get("safety");
		requireThat(progress.rounds().size(), "roundsAfterRevert").isEqualTo(4);
		requireThat(progress.bestEstimates(), "bestEstimates").isEqualTo(Map.of("safety", 0.99));
		requireThat(reverted.pending(), "pendingAfterRevert").isNull();

		Campaign next = unproductiveRound(reverted);
		requireThat(next.pending(), "pendingAfterFifth").isEqualTo(SAFETY_STALLED);
	}
}
