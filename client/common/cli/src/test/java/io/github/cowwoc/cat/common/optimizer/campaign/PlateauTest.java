package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.cat.common.optimizer.campaign.CampaignBuilder.targeted;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests {@link Plateau} and the development rounds that lead to it.
 */
public final class PlateauTest
{
	private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
	private static final Sha256 ATTEMPT = CampaignBuilder.snapshot("attempt");

	/**
	 * Creates the test.
	 */
	public PlateauTest()
	{
	}

	/**
	 * Returns a round.
	 *
	 * @param formatting the attempts used on {@code formatting}
	 * @param safety the attempts used on {@code safety}
	 * @return the round
	 */
	private static Round round(int formatting, int safety)
	{
		Map<String, Integer> attempts = new LinkedHashMap<>();
		attempts.put("formatting", formatting);
		attempts.put("safety", safety);
		return new Round(attempts);
	}

	/**
	 * Verifies that a plateau requires every failing category to use both of its author attempts.
	 */
	@Test
	public void plateauRequiresCompleteRound()
	{
		// Requirement: R-D6
		requireThat(Plateau.isReached(round(2, 2)), "complete").isTrue();
		requireThat(Plateau.isReached(round(2, 1)), "safetyShort").isFalse();
		requireThat(Plateau.isReached(round(1, 2)), "formattingShort").isFalse();
		requireThat(Plateau.isReached(new Round(Map.of("safety", 0))), "fresh").isFalse();
	}

	/**
	 * Returns a campaign whose round has one author attempt left, on {@code safety}.
	 *
	 * @return the campaign
	 */
	private static Campaign lastAttempt()
	{
		TrialInstance logs = targeted("safety", "logs");
		return new CampaignBuilder().confirmed().working(1).development("safety", 60, 1).
			development("formatting", 60, 1).failure(logs).regression(logs, Verdict.FAIL).round(round(2, 1)).
			authorAttempts(3).build();
	}

	/**
	 * Verifies that a complete round without a promotion establishes a plateau.
	 */
	@Test
	public void completeRoundWithoutPromotionIsPlateau()
	{
		// Requirement: R-D6
		Campaign campaign = CampaignTransitions.apply(lastAttempt(),
			new CampaignEvent.SubmitAuthorAttempt("safety", ATTEMPT));
		AuthorAttempt author = campaign.development().author();
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, author.number(), ATTEMPT,
			author.runs().getFirst().instance(), Verdict.FAIL);

		requireThat(campaign.outcome(), "outcome").isEqualTo(Outcome.PLATEAU);
		requireThat(campaign.pending(), "pending").isEqualTo(new PendingDecision.PlateauReached());
		requireThat(campaign.choices(), "choices").
			containsExactly(List.of(Choice.ACCEPT_WITHOUT_CERTIFICATION, Choice.STOP));
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEmpty();
	}

	/**
	 * Verifies that an infrastructure failure or an interruption of an author attempt's run never completes the
	 * round.
	 */
	@Test
	public void interruptionNeverEstablishesPlateau()
	{
		// Requirement: R-D6
		Campaign campaign = CampaignTransitions.apply(lastAttempt(),
			new CampaignEvent.SubmitAuthorAttempt("safety", ATTEMPT));
		AuthorAttempt author = campaign.development().author();
		campaign = TransitionSteps.assign(campaign, Purpose.PROMOTION, author.number(), ATTEMPT,
			author.runs().getFirst().instance());
		AuthorAttempt assigned = campaign.development().author();
		TrialKey key = TransitionSteps.last(campaign);
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Start(key));
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Fail(key, RunFailure.INFRASTRUCTURE,
			"provider error", NOW, null));
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Start(key));
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Fail(key, RunFailure.INTERRUPTED,
			"slice ended", NOW, null));

		requireThat(campaign.outcome(), "outcome").isNull();
		requireThat(campaign.pending(), "pending").isNull();
		requireThat(campaign.development().round(), "round").isEqualTo(round(2, 1));
		requireThat(campaign.development().author(), "author").isEqualTo(assigned);
	}

	/**
	 * Verifies that a promotion ends the round without a plateau.
	 */
	@Test
	public void promotionEndsRoundWithoutPlateau()
	{
		// Requirement: R-D6
		Campaign campaign = CampaignTransitions.apply(lastAttempt(),
			new CampaignEvent.SubmitAuthorAttempt("safety", ATTEMPT));
		AuthorAttempt author = campaign.development().author();
		for (PromotionRun run : author.runs())
		{
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, author.number(), ATTEMPT, run.instance(),
				Verdict.PASS);
		}
		requireThat(campaign.outcome(), "outcome").isNull();
		requireThat(campaign.pending(), "pending").isNull();
		requireThat(campaign.development().round(), "round").isNull();
		requireThat(campaign.development().working().snapshot(), "working").isEqualTo(ATTEMPT);
	}

	/**
	 * Verifies that each category gets at most 2 author attempts per round.
	 */
	@Test(expectedExceptions = IllegalStateException.class)
	public void categoryGetsTwoAttempts()
	{
		// Requirement: R-D6
		CampaignTransitions.apply(lastAttempt(), new CampaignEvent.SubmitAuthorAttempt("formatting", ATTEMPT));
	}
}
