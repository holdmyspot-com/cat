package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests {@link ReviewCheckpoint}.
 */
public final class ReviewCheckpointTest
{
	private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

	/**
	 * Creates the test.
	 */
	public ReviewCheckpointTest()
	{
	}

	/**
	 * Verifies that the checkpoint is due at 200 unresolved trials and not at 199.
	 */
	@Test
	public void dueAtTwoHundredUnresolvedTrials()
	{
		// Requirement: R-D5
		requireThat(ReviewCheckpoint.isDue(new Builder().confirmed().
			development("formatting", 197, List.of(0, 0, 0, 2)).build().development(), "formatting"), "199").
			isFalse();
		requireThat(ReviewCheckpoint.isDue(new Builder().confirmed().
			development("formatting", 198, List.of(0, 0, 0, 2)).build().development(), "formatting"), "200").
			isTrue();
		requireThat(ReviewCheckpoint.isDue(new Builder().confirmed().
			development("formatting", 198, List.of(0, 0, 1, 1)).build().development(), "formatting"), "ready").
			isFalse();
	}

	/**
	 * Verifies that an extension moves the checkpoint to its next review point.
	 */
	@Test
	public void extensionMovesCheckpoint()
	{
		// Requirement: R-D5
		Builder builder = new Builder().confirmed().reviewPoint("formatting", 300);
		requireThat(ReviewCheckpoint.isDue(builder.development("formatting", 297, List.of(0, 0, 0, 0, 0, 2)).
			build().development(), "formatting"), "299").isFalse();
		requireThat(ReviewCheckpoint.isDue(builder.development("formatting", 298, List.of(0, 0, 0, 0, 0, 2)).
			build().development(), "formatting"), "300").isTrue();
	}

	/**
	 * Returns a campaign whose {@code formatting} category reaches its checkpoint with its next outcome.
	 *
	 * @return the campaign
	 */
	private static Campaign beforeCheckpoint()
	{
		return new Builder().confirmed().development("formatting", 197, List.of(0, 0, 0, 2)).
			development("safety", 150, 0).slowStart(new SlowStart(4, 0)).build();
	}

	/**
	 * Verifies that reaching the checkpoint stops the category and offers more evidence, targeted optimization or
	 * stopping.
	 */
	@Test
	public void checkpointOffersChoices()
	{
		// Requirement: R-D5
		Campaign campaign = TransitionSteps.adopt(beforeCheckpoint(), Purpose.DEVELOPMENT, "formatting",
			Verdict.PASS);
		requireThat(campaign.pending(), "pending").isEqualTo(new PendingDecision.CheckpointReached("formatting",
			200));
		requireThat(campaign.choices(), "choices").containsExactly(List.of(Choice.MORE_EVIDENCE,
			Choice.TARGETED_OPTIMIZATION, Choice.STOP));
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEmpty();
	}

	/**
	 * Verifies that more evidence records the next review point and resumes the category.
	 */
	@Test
	public void moreEvidenceRecordsNextReviewPoint()
	{
		// Requirement: R-D5
		Campaign campaign = TransitionSteps.adopt(beforeCheckpoint(), Purpose.DEVELOPMENT, "formatting",
			Verdict.PASS);
		campaign = Transitions.apply(campaign, CampaignEvent.Choose.moreEvidence(300));
		requireThat(campaign.pending(), "pending").isNull();
		requireThat(campaign.development().reviewPoint("formatting"), "reviewPoint").isEqualTo(300);
		List<Admission> plan = Scheduler.plan(campaign, NOW, 8);
		requireThat(plan.isEmpty(), "plan.isEmpty()").isFalse();
	}

	/**
	 * Verifies that the next review point must lie beyond the category's current outcomes.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void reviewPointMustAdvance()
	{
		// Requirement: R-D5
		Campaign campaign = TransitionSteps.adopt(beforeCheckpoint(), Purpose.DEVELOPMENT, "formatting",
			Verdict.PASS);
		Transitions.apply(campaign, CampaignEvent.Choose.moreEvidence(200));
	}

	/**
	 * Verifies that targeted optimization starts a round over the failing categories.
	 */
	@Test
	public void targetedOptimizationStartsRound()
	{
		// Requirement: R-D5
		Campaign campaign = TransitionSteps.adopt(beforeCheckpoint(), Purpose.DEVELOPMENT, "formatting",
			Verdict.PASS);
		campaign = Transitions.apply(campaign, CampaignEvent.Choose.of(Choice.TARGETED_OPTIMIZATION));
		requireThat(campaign.pending(), "pending").isNull();
		requireThat(campaign.development().round(), "round").isEqualTo(new Round(Map.of("formatting", 0)));
	}

	/**
	 * Verifies that a category at its review point is not scheduled while other categories are.
	 */
	@Test
	public void checkpointStopsOnlyThatCell()
	{
		// Requirement: R-D5
		Campaign campaign = new Builder().confirmed().development("formatting", 198, List.of(0, 0, 0, 2)).
			development("safety", 98, 0).slowStart(new SlowStart(4, 0)).build();
		List<Admission> plan = Scheduler.plan(campaign, NOW, 8);
		requireThat(plan.isEmpty(), "plan.isEmpty()").isFalse();
		for (Admission admission : plan)
			requireThat(admission.cell(), "category").isEqualTo("safety");
	}
}
