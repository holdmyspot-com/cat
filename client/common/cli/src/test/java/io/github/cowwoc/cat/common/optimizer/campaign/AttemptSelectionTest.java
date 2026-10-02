package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.inventory.FileDelta;
import io.github.cowwoc.cat.common.optimizer.contract.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import org.testng.annotations.Test;
import java.util.List;

/**
 * Tests the number of author attempts and selection of a weak round's winner.
 */
public final class AttemptSelectionTest
{
	/**
	 * Creates the test.
	 */
	public AttemptSelectionTest()
	{
	}

	/**
	 * Verifies that two rejected attempts do not complete a round whose working candidate is weak.
	 */
	@Test
	public void weakRoundReceivesThreeAttempts()
	{
		// Requirement: R-D12
		Campaign campaign = new Builder().confirmed().working(1).build();
		campaign = TransitionSteps.adopt(campaign, Purpose.DEVELOPMENT, "safety", Verdict.FAIL);
		campaign = TransitionSteps.adopt(campaign, Purpose.DIAGNOSTIC, campaign.development().series(),
			campaign.development().working().snapshot(), campaign.development().diagnosis().pending().getFirst(),
			Verdict.FAIL);
		Sha256 working = campaign.development().working().snapshot();
		for (int attempt = 0; attempt < 2; ++attempt)
			campaign = TransitionSteps.submit(campaign, "safety", working, List.of());
		requireThat(campaign.outcome(), "roundOutcome").isNull();
		requireThat(campaign.development().round().nextCell().orElseThrow(), "thirdAttempt").isEqualTo("safety");
	}

	/**
	 * Verifies that every cell above the threshold keeps the sequential two-attempt policy.
	 */
	@Test
	public void strongRoundKeepsSequentialAttempts()
	{
		// Requirement: R-D12
		Campaign campaign = new Builder().confirmed().working(1).development("safety", 60, 1).
			development("formatting", 100, 0).round(TrialAdoption.failingRound(
				Map.of("safety", Builder.counts(60, 1), "formatting", Builder.counts(100, 0)),
				ContractFixtures.contract(), null)).build();
		for (int attempt = 0; attempt < 2; ++attempt)
			campaign = TransitionSteps.submit(campaign, "safety",
				campaign.development().working().snapshot(), List.of());
		requireThat(campaign.outcome(), "roundOutcome").isEqualTo(Outcome.PLATEAU);
	}

	/**
	 * Verifies that an unsampled profile in an addressed category makes the round weak.
	 */
	@Test
	public void cellWithoutOutcomeMakesRoundWeak()
	{
		// Requirement: R-D12
		Map<String, CellCounts> counts = Map.of(ContractFixtures.BIG_CELL, Builder.counts(100, 1));
		Campaign campaign = new Builder().confirmed(ContractFixtures.twoCellContract(), 1).working(1).
			development(ContractFixtures.BIG_CELL, 100, 1).round(TrialAdoption.failingRound(counts,
				ContractFixtures.twoCellContract(), null)).build();
		for (int attempt = 0; attempt < 2; ++attempt)
			campaign = TransitionSteps.submit(campaign,
				ContractFixtures.BIG_CELL, campaign.development().working().snapshot(), List.of());
		requireThat(campaign.outcome(), "roundOutcome").isNull();
	}

	/**
	 * Verifies that the accepted contender with more passing runs wins.
	 */
	@Test
	public void mostPassingRunsWins()
	{
		// Requirement: R-D12
		Campaign campaign = selection(List.of(1, 1, 1), List.of(Verdict.FAIL, Verdict.PASS, Verdict.FAIL));
		requireThat(campaign.development().author().number(), "winner").isEqualTo(2);
	}

	/**
	 * Verifies that equally successful contenders are ranked by fewer added and removed lines.
	 */
	@Test
	public void fewerChangedLinesBreaksTie()
	{
		// Requirement: R-D12
		Campaign campaign = selection(List.of(3, 1, 2), List.of(Verdict.PASS, Verdict.PASS, Verdict.PASS));
		requireThat(campaign.development().author().number(), "winner").isEqualTo(2);
	}

	/**
	 * Verifies that the earlier attempt wins when passing runs and changed lines tie.
	 */
	@Test
	public void earlierAttemptBreaksTie()
	{
		// Requirement: R-D12
		Campaign campaign = selection(List.of(1, 1, 1), List.of(Verdict.PASS, Verdict.PASS, Verdict.PASS));
		requireThat(campaign.development().author().number(), "winner").isEqualTo(1);
	}

	/**
	 * Verifies that an attempt's passing runs exclude the working candidate's reference runs, which measure the working
	 * candidate rather than the attempt.
	 */
	@Test
	public void referenceRunsDoNotCountForAttempt()
	{
		// Requirement: R-D12
		Sha256 snapshot = Builder.snapshot("attempt1");
		AuthorAttempt attempt = new AuthorAttempt(1, "safety", snapshot, List.of(
			new PromotionRun(PromotionRun.Role.TRIGGERING, Builder.targeted("safety", "cache"),
				new TrialKey(Purpose.PROMOTION, 1, snapshot, "safety", 1), Verdict.PASS),
			new PromotionRun(PromotionRun.Role.WORKING_REFERENCE, Builder.targeted("safety", "logs"),
				new TrialKey(Purpose.PROMOTION, 1, snapshot, "safety", 2), Verdict.PASS)),
			Minimality.start(List.of(), snapshot));
		requireThat(AttemptSelection.passingRuns(attempt.runs()), "passingRuns").isEqualTo(1L);
	}

	/**
	 * Verifies that no accepted attempt means no winner and a plateau only after the complete round.
	 */
	@Test
	public void noAcceptedAttemptHasNoWinner()
	{
		// Requirement: R-D12
		Campaign campaign = contenders(List.of(1, 1, 1));
		for (int attempt = 1; attempt <= 3; ++attempt)
		{
			for (int run = 0; run < Promotion.TRIGGERING_RUNS; ++run)
				campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, attempt,
					Builder.snapshot("attempt" + attempt), Builder.targeted("safety", "cache"),
					Verdict.FAIL);
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, attempt,
				Builder.snapshot("attempt" + attempt), Builder.targeted("safety", "logs"), Verdict.PASS);
			if (attempt < 3)
				requireThat(campaign.outcome(), "incompleteRound").isNull();
		}
		requireThat(campaign.development().author(), "winner").isNull();
		requireThat(campaign.outcome(), "completedRound").isEqualTo(Outcome.PLATEAU);
	}

	/**
	 * Verifies that a missing outcome in an unaddressed category does not trigger concurrent attempts.
	 */
	@Test
	public void unaddressedEmptyCellIsNotWeak()
	{
		// Requirement: R-D12
		Round round = TrialAdoption.failingRound(Map.of("safety", Builder.counts(60, 1)),
			ContractFixtures.contract(), null);
		requireThat(round.weak(), "weak").isFalse();
		requireThat(round.maxAttempts(), "attempts").isEqualTo(2);
	}

	/**
	 * Verifies that failures under two profiles receive three attempts for their shared category.
	 */
	@Test
	public void failingProfilesShareThreeAttempts()
	{
		// Requirement: R-D12
		Round round = TrialAdoption.failingRound(Map.of(ContractFixtures.BIG_CELL, Builder.counts(0, 1),
			ContractFixtures.SMALL_CELL, Builder.counts(100, 1)), ContractFixtures.twoCellContract(), null);
		requireThat(round.attempts().keySet(), "addressedCategories").containsExactly(List.of(ContractFixtures.BIG_CELL));
		requireThat(round.maxAttempts(), "attemptsPerCategory").isEqualTo(3);
	}

	/**
	 * Creates a weak round with three edited contenders and one previously failing regression instance.
	 *
	 * @param sizes the number of changed source lines in each contender
	 * @return the campaign awaiting promotion trials
	 */
	private static Campaign contenders(List<Integer> sizes)
	{
		Campaign campaign = new Builder().confirmed().working(1).build();
		campaign = TransitionSteps.adopt(campaign, Purpose.DEVELOPMENT, "safety", Verdict.FAIL);
		TrialInstance failure = campaign.development().diagnosis().pending().getFirst();
		campaign = TransitionSteps.adopt(campaign, Purpose.DIAGNOSTIC, campaign.development().series(),
			campaign.development().working().snapshot(), failure, Verdict.FAIL);
		campaign = new Builder().confirmed().working(1).development("safety", 0, 1).
			failure(Builder.targeted("safety", "cache")).regression(Builder.targeted("safety", "logs"),
				Verdict.FAIL).round(campaign.development().round()).build();
		for (int index = 0; index < sizes.size(); ++index)
		{
			String original = "old\n".repeat(sizes.get(index));
			String edited = "new\n".repeat(sizes.get(index));
			campaign = TransitionSteps.submit(campaign, "safety",
				Builder.snapshot("attempt" + (index + 1)), List.of(new FileDelta(Path.of("CLAUDE.md"),
					new FileContent(original.getBytes(UTF_8)), new FileContent(edited.getBytes(UTF_8)),
					PosixFilePermissions.fromString("rw-r--r--"))));
		}
		return campaign;
	}

	/**
	 * Completes all contenders in reverse order, supplying their regression outcomes.
	 *
	 * @param sizes the number of changed source lines
	 * @param regressions each contender's regression outcome
	 * @return the campaign with its selected winner awaiting minimality
	 */
	private static Campaign selection(List<Integer> sizes, List<Verdict> regressions)
	{
		Campaign campaign = contenders(sizes);
		for (int attempt = 3; attempt >= 1; --attempt)
		{
			Sha256 snapshot = Builder.snapshot("attempt" + attempt);
			for (int run = 0; run < Promotion.TRIGGERING_RUNS; ++run)
				campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, attempt, snapshot,
					Builder.targeted("safety", "cache"), Verdict.PASS);
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, attempt, snapshot,
				Builder.targeted("safety", "logs"), regressions.get(attempt - 1));
		}
		return campaign;
	}
}
