package com.holdmyspot.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.StallProgress;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests which completed development rounds count as making progress for a prompt test.
 */
public final class StallProgressTest
{
	/**
	 * Creates the test.
	 */
	public StallProgressTest()
	{
	}

	/**
	 * Returns the progress of a test with two cells before any round is counted.
	 *
	 * @return the progress
	 */
	private static StallProgress start()
	{
		return new StallProgress(Map.of("big", 0.80, "small", 0.70), 1, List.of());
	}

	/**
	 * Verifies that a round whose estimates equal or fall below the best values is counted, with every cell's
	 * estimate, and is not progress.
	 */
	@Test
	public void roundWithoutProgressIsCounted()
	{
		StallProgress progress = start().afterRound(Map.of("big", 0.80, "small", 0.60), 1);

		requireThat(progress.rounds(), "rounds").isEqualTo(List.of(Map.of("big", 0.80, "small", 0.60)));
		requireThat(progress.bestEstimates(), "best").isEqualTo(Map.of("big", 0.80, "small", 0.70));
		requireThat(progress.bestMetCells(), "bestMetCells").isEqualTo(1);
		requireThat(progress.isStalled(), "stalled").isFalse();
	}

	/**
	 * Verifies that a round after which any one cell's estimate exceeds its best value ends the run, raises that
	 * cell's best value and keeps the other's.
	 */
	@Test
	public void higherEstimateInAnyCellIsProgress()
	{
		StallProgress progress = start().afterRound(Map.of("big", 0.75, "small", 0.60), 1).
			afterRound(Map.of("big", 0.78, "small", 0.71), 1);

		requireThat(progress.rounds(), "rounds").isEmpty();
		requireThat(progress.bestEstimates(), "best").isEqualTo(Map.of("big", 0.80, "small", 0.71));
	}

	/**
	 * Verifies that a round after which more cells meet the commit gate's count conditions than ever before is
	 * progress even when no estimate rose.
	 */
	@Test
	public void moreMetCellsIsProgress()
	{
		StallProgress progress = start().afterRound(Map.of("big", 0.70, "small", 0.60), 2);

		requireThat(progress.rounds(), "rounds").isEmpty();
		requireThat(progress.bestMetCells(), "bestMetCells").isEqualTo(2);
		requireThat(progress.bestEstimates(), "best").isEqualTo(Map.of("big", 0.80, "small", 0.70));
	}

	/**
	 * Verifies that a test stalls after {@value StallProgress#ROUNDS} counted rounds in a row, that a later round
	 * changes nothing, and that continuing starts the count again from zero while keeping the best values.
	 */
	@Test
	public void stallsAfterFiveRoundsAndContinueResets()
	{
		StallProgress progress = start();
		for (int i = 0; i < StallProgress.ROUNDS - 1; ++i)
			progress = progress.afterRound(Map.of("big", 0.60, "small", 0.50), 0);
		requireThat(progress.isStalled(), "stalledAfterFour").isFalse();

		progress = progress.afterRound(Map.of("big", 0.60, "small", 0.50), 0);
		requireThat(progress.isStalled(), "stalled").isTrue();
		requireThat(progress.rounds().size(), "rounds").isEqualTo(StallProgress.ROUNDS);
		requireThat(progress.afterRound(Map.of("big", 0.99, "small", 0.99), 3), "afterStall").isEqualTo(progress);

		StallProgress continued = progress.withoutRounds();
		requireThat(continued.rounds(), "continuedRounds").isEmpty();
		requireThat(continued.bestEstimates(), "continuedBest").isEqualTo(start().bestEstimates());
		requireThat(continued.bestMetCells(), "continuedMetCells").isEqualTo(1);
	}

	/**
	 * Verifies that a cell with no best value yet counts as having best value 0, so that its first estimate above 0
	 * is progress.
	 */
	@Test
	public void unseenCellStartsAtZero()
	{
		StallProgress progress = new StallProgress(Map.of(), 0, List.of()).afterRound(Map.of("big", 0.10), 0);

		requireThat(progress.rounds(), "rounds").isEmpty();
		requireThat(progress.bestEstimates(), "best").isEqualTo(Map.of("big", 0.10));
	}

	/**
	 * Verifies that more than {@value StallProgress#ROUNDS} rounds are refused.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void refusesTooManyRounds()
	{
		List<Map<String, Double>> rounds = new ArrayList<>();
		for (int i = 0; i <= StallProgress.ROUNDS; ++i)
			rounds.add(Map.of("big", 0.5));
		new StallProgress(Map.of("big", 0.5), 0, rounds);
	}

	/**
	 * Verifies that an estimate above 1 is refused.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void refusesEstimateOutsideRange()
	{
		new StallProgress(Map.of("big", 1.5), 0, List.of());
	}
}
