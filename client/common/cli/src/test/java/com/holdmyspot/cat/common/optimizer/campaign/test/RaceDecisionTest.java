package com.holdmyspot.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.RaceDecision;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/** Exercises candidate dropping and stopping from adopted representative outcomes. */
public final class RaceDecisionTest
{
	/** Equal prefixes ignore a late reversal in a contender's outcomes. */
	// Requirement: R-D20
	@Test
	public void comparesEqualPrefixes()
	{
		List<Verdict> losingPrefix = outcomes(50, Verdict.FAIL, 50, Verdict.PASS);
		List<Verdict> winningPrefix = outcomes(50, Verdict.PASS);
		RaceDecision.Result result = RaceDecision.evaluate(List.of(
			new RaceDecision.Racer(1, Map.of("cell", losingPrefix), 1, false, null),
			new RaceDecision.Racer(2, Map.of("cell", winningPrefix), 1, false, null)), Map.of(), List.of("cell"));
		requireThat(result.dropped().keySet(), "dropped").isEqualTo(java.util.Set.of(1));
		requireThat(result.winner(), "winner").isEqualTo(2);
	}

	/** A racer drops only when no other addressed cell's equal prefix clearly favours it. */
	// Requirement: R-D20
	@Test
	public void contradictoryCellsKeepBothRacers()
	{
		List<Verdict> pass = outcomes(50, Verdict.PASS);
		List<Verdict> fail = outcomes(50, Verdict.FAIL);
		RaceDecision.Result result = RaceDecision.evaluate(List.of(
			new RaceDecision.Racer(1, Map.of("a", pass, "b", fail), 1, false, null),
			new RaceDecision.Racer(2, Map.of("a", fail, "b", pass), 1, false, null)), Map.of(), List.of("a", "b"));
		requireThat(result.dropped(), "dropped").isEmpty();
		requireThat(result.winner(), "winner").isNull();
	}

	/** At the per-cell cap, fewer total failures outrank changed lines and attempt order. */
	// Requirement: R-D20
	@Test
	public void capPrefersFewestFailures()
	{
		RaceDecision.Result result = RaceDecision.evaluate(List.of(
			capRacer(7, 1, Map.of(
				"a", outcomes(99, Verdict.PASS, 1, Verdict.FAIL),
				"b", outcomes(97, Verdict.PASS, 3, Verdict.FAIL))),
			capRacer(9, 9, Map.of(
				"a", outcomes(98, Verdict.PASS, 2, Verdict.FAIL),
				"b", outcomes(99, Verdict.PASS, 1, Verdict.FAIL)))), Map.of(), List.of("a", "b"));
		requireThat(result.dropped(), "dropped").isEmpty();
		requireThat(result.noWinner(), "noWinner").isFalse();
		requireThat(result.winner(), "winner").isEqualTo(9);
	}

	/** Equal failure totals select the racer with fewer changed lines. */
	// Requirement: R-D20
	@Test
	public void capPrefersSmallerEdit()
	{
		List<Verdict> outcomes = outcomes(99, Verdict.PASS, 1, Verdict.FAIL);
		RaceDecision.Result result = RaceDecision.evaluate(List.of(
			capRacer(2, 2, Map.of("a", outcomes, "b", outcomes)),
			capRacer(3, 1, Map.of("a", outcomes, "b", outcomes))), Map.of(), List.of("a", "b"));
		requireThat(result.dropped(), "dropped").isEmpty();
		requireThat(result.noWinner(), "noWinner").isFalse();
		requireThat(result.winner(), "winner").isEqualTo(3);
	}

	/** Equal failures and changed lines select the numerically earlier attempt. */
	// Requirement: R-D20
	@Test
	public void capPrefersEarlierAttempt()
	{
		List<Verdict> outcomes = outcomes(99, Verdict.PASS, 1, Verdict.FAIL);
		RaceDecision.Result result = RaceDecision.evaluate(List.of(
			capRacer(9, 2, Map.of("a", outcomes, "b", outcomes)),
			capRacer(7, 2, Map.of("a", outcomes, "b", outcomes))), Map.of(), List.of("a", "b"));
		requireThat(result.dropped(), "dropped").isEmpty();
		requireThat(result.noWinner(), "noWinner").isFalse();
		requireThat(result.winner(), "winner").isEqualTo(7);
	}

	/**
	 * Identifies which addressed cell of which racer has one fewer outcome.
	 *
	 * @return the per-racer and per-cell short-count cases
	 */
	@DataProvider(name = "shortCapCells")
	public Object[][] shortCapCells()
	{
		return new Object[][] {
			{7, "a"},
			{7, "b"},
			{9, "a"},
			{9, "b"}
		};
	}

	/**
	 * Every racer and addressed cell must reach the per-cell cap before ranking.
	 *
	 * @param shortAttempt the attempt with one fewer outcome
	 * @param shortCell    the addressed cell with one fewer outcome
	 */
	// Requirement: R-D20
	@Test(dataProvider = "shortCapCells")
	public void capWaitsForEveryRacerCell(int shortAttempt, String shortCell)
	{
		List<Verdict> shortCellOutcomes = outcomes(98, Verdict.PASS, 1, Verdict.FAIL);
		List<Verdict> completeCellOutcomes = outcomes(99, Verdict.PASS, 1, Verdict.FAIL);
		Map<String, List<Verdict>> completeOutcomes = Map.of("a", completeCellOutcomes, "b", completeCellOutcomes);
		boolean shortCellA = shortCell.equals("a");
		Map<String, List<Verdict>> shortOutcomes;
		if (shortCellA)
			shortOutcomes = Map.of("a", shortCellOutcomes, "b", completeCellOutcomes);
		else
			shortOutcomes = Map.of("a", completeCellOutcomes, "b", shortCellOutcomes);

		Map<String, List<Verdict>> attemptSevenOutcomes;
		Map<String, List<Verdict>> attemptNineOutcomes;
		if (shortAttempt == 7)
		{
			attemptSevenOutcomes = shortOutcomes;
			attemptNineOutcomes = completeOutcomes;
		}
		else
		{
			attemptSevenOutcomes = completeOutcomes;
			attemptNineOutcomes = shortOutcomes;
		}
		RaceDecision.Result incomplete = RaceDecision.evaluate(List.of(
			capRacer(7, 2, attemptSevenOutcomes), capRacer(9, 2, attemptNineOutcomes)), Map.of(), List.of("a", "b"));
		requireThat(incomplete.dropped(), "dropped").isEmpty();
		requireThat(incomplete.noWinner(), "noWinner").isFalse();
		requireThat(incomplete.winner(), "winner").isNull();

		RaceDecision.Result completed = RaceDecision.evaluate(List.of(
			capRacer(7, 2, completeOutcomes), capRacer(9, 2, completeOutcomes),
			new RaceDecision.Racer(3, Map.of(), 2, true, "already dropped")), Map.of(), List.of("a", "b"));
		requireThat(completed.noWinner(), "noWinner").isFalse();
		requireThat(completed.winner(), "winner").isEqualTo(7);
	}

	/**
	 * Creates an undropped racer with the supplied attempt evidence.
	 *
	 * @param attempt      the attempt number
	 * @param changedLines the number of changed lines
	 * @param outcomes     the outcomes for each addressed cell
	 * @return the undropped racer
	 */
	private static RaceDecision.Racer capRacer(int attempt, int changedLines, Map<String, List<Verdict>> outcomes)
	{
		return new RaceDecision.Racer(attempt, outcomes, changedLines, false, null);
	}

	/**
	 * Creates a list containing the same verdict.
	 *
	 * @param count the number of outcomes
	 * @param verdict the repeated verdict
	 * @return the outcomes
	 */
	private static List<Verdict> outcomes(int count, Verdict verdict)
	{
		return IntStream.range(0, count).mapToObj(_ -> verdict).toList();
	}

	/**
	 * Creates two consecutive outcome groups.
	 *
	 * @param firstCount the first group's size
	 * @param first the first group's verdict
	 * @param secondCount the second group's size
	 * @param second the second group's verdict
	 * @return the concatenated outcomes
	 */
	private static List<Verdict> outcomes(int firstCount, Verdict first, int secondCount, Verdict second)
	{
		return java.util.stream.Stream.concat(outcomes(firstCount, first).stream(),
			outcomes(secondCount, second).stream()).toList();
	}
}
