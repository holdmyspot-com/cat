package com.holdmyspot.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.RaceDecision;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.testng.annotations.Test;

/** Exercises candidate dropping and stopping from adopted representative outcomes. */
public final class RaceDecisionTest
{
	/** Equal prefixes ignore a late reversal in a contender's outcomes. */
	// Requirement: G1
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
	// Requirement: G1
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

	/** At the per-cell cap, total failures, changed lines and attempt order select the winner. */
	// Requirement: G1
	@Test
	public void capSelectsFewestFailuresThenSmallestEdit()
	{
		RaceDecision.Result result = RaceDecision.evaluate(List.of(
			new RaceDecision.Racer(2, Map.of("cell", outcomes(99, Verdict.PASS, 1, Verdict.FAIL)), 2, false, null),
			new RaceDecision.Racer(3, Map.of("cell", outcomes(99, Verdict.PASS, 1, Verdict.FAIL)), 1, false, null)),
			Map.of(), List.of("cell"));
		requireThat(result.winner(), "winner").isEqualTo(3);
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
