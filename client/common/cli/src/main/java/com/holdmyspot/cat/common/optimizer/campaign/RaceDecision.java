package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.statistics.ExploratoryBounds;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Makes the exploratory comparisons and stopping decision for a candidate race. */
public final class RaceDecision
{
	/** The maximum representative prefix sampled for each racer and cell. */
	public static final int CELL_LIMIT = 100;

	private RaceDecision()
	{
	}

	/**
	 * Evaluates one race after adopted outcomes. Comparisons use equal prefixes, independent of completion order.
	 *
	 * @param racers the contenders and their outcomes, in attempt order
	 * @param predecessor the retained working-candidate outcomes by cell
	 * @param cells the addressed development cells, in contract order
	 * @return the dropped attempt numbers and their reasons, and a winner when the race has ended
	 */
	public static Result evaluate(List<Racer> racers, Map<String, List<Verdict>> predecessor, List<String> cells)
	{
		requireThat(racers, "racers").isNotEmpty();
		requireThat(predecessor, "predecessor").isNotNull();
		requireThat(cells, "cells").isNotEmpty();
		Map<Integer, String> dropped = new LinkedHashMap<>();
		for (Racer b : racers)
		{
			if (b.dropped())
			{
				dropped.put(b.attempt(), b.dropReason());
				continue;
			}
			String reason = belowPredecessor(b, predecessor, cells);
			if (reason == null)
			{
				for (Racer a : racers)
				{
					if (a == b || a.dropped())
						continue;
					reason = dominated(b, a, cells);
					if (reason != null)
						break;
				}
			}
			if (reason != null)
				dropped.put(b.attempt(), reason);
		}
		List<Racer> remaining = racers.stream().
			filter(racer -> !racer.dropped() && !dropped.containsKey(racer.attempt())).toList();
		if (remaining.size() == 1)
			return new Result(dropped, remaining.getFirst().attempt(), false);
		if (remaining.isEmpty())
			return new Result(dropped, null, true);
		boolean capReached = remaining.stream().allMatch(racer -> cells.stream().allMatch(cell ->
			racer.count(cell).outcomes() >= CELL_LIMIT));
		if (!capReached)
			return new Result(dropped, null, false);
		Racer winner = remaining.stream().min(Comparator.comparingInt((Racer racer) -> failures(racer, cells)).
			thenComparingInt(Racer::changedLines).thenComparingInt(Racer::attempt)).orElseThrow();
		return new Result(dropped, winner.attempt(), false);
	}

	private static String belowPredecessor(Racer b, Map<String, List<Verdict>> predecessor, List<String> cells)
	{
		for (String cell : cells)
		{
			List<Verdict> base = predecessor.getOrDefault(cell, List.of());
			CellCounts current = b.count(cell);
			if (base.isEmpty() || current.outcomes() == 0)
				continue;
			int m = Math.min(base.size(), current.outcomes());
			CellCounts basePrefix = counts(base.subList(0, m));
			CellCounts racerPrefix = b.prefix(cell, m);
			if (ExploratoryBounds.wilson(racerPrefix.passes(), m).upper() <
				ExploratoryBounds.wilson(basePrefix.passes(), m).lower())
				return "below working candidate in " + cell + " at m=" + m;
		}
		return null;
	}

	private static CellCounts counts(List<Verdict> verdicts)
	{
		int passes = (int) verdicts.stream().filter(verdict -> verdict == Verdict.PASS).count();
		return new CellCounts(passes, verdicts.size() - passes, Racer.batches(verdicts));
	}

	private static String dominated(Racer b, Racer a, List<String> cells)
	{
		boolean aBetter = false;
		boolean bBetter = false;
		for (String cell : cells)
		{
			CellCounts ac = a.count(cell);
			CellCounts bc = b.count(cell);
			int m = Math.min(ac.outcomes(), bc.outcomes());
			if (m == 0)
				continue;
			CellCounts ap = a.prefix(cell, m);
			CellCounts bp = b.prefix(cell, m);
			if (ExploratoryBounds.wilson(ap.passes(), m).lower() >
				ExploratoryBounds.wilson(bp.passes(), m).upper())
				aBetter = true;
			if (ExploratoryBounds.wilson(bp.passes(), m).lower() >
				ExploratoryBounds.wilson(ap.passes(), m).upper())
				bBetter = true;
		}
		if (aBetter && !bBetter)
			return "dominated by attempt " + a.attempt();
		return null;
	}

	private static int failures(Racer racer, List<String> cells)
	{
		return cells.stream().mapToInt(cell -> racer.count(cell).failures()).sum();
	}

	/** A race contender's representative outcomes. */
	public record Racer(int attempt, Map<String, List<Verdict>> outcomes, int changedLines, boolean dropped,
		String dropReason)
	{
		public Racer
		{
			requireThat(attempt, "attempt").isGreaterThan(0);
			requireThat(outcomes, "outcomes").isNotNull();
			requireThat(changedLines, "changedLines").isNotNegative();
			if (dropped)
				requireThat(dropReason, "dropReason").isNotBlank();
			Map<String, List<Verdict>> copy = new LinkedHashMap<>();
			outcomes.forEach((cell, verdicts) -> copy.put(cell, List.copyOf(verdicts)));
			outcomes = Map.copyOf(copy);
		}

		public CellCounts count(String cell)
		{
			List<Verdict> verdicts = outcomes.getOrDefault(cell, List.of());
			int passes = (int) verdicts.stream().filter(verdict -> verdict == Verdict.PASS).count();
			return new CellCounts(passes, verdicts.size() - passes, batches(verdicts));
		}

		public CellCounts prefix(String cell, int size)
		{
			List<Verdict> verdicts = outcomes.getOrDefault(cell, List.of()).subList(0, size);
			int passes = (int) verdicts.stream().filter(verdict -> verdict == Verdict.PASS).count();
			return new CellCounts(passes, verdicts.size() - passes, batches(verdicts));
		}

		private static List<Integer> batches(List<Verdict> verdicts)
		{
			List<Integer> result = new ArrayList<>();
			for (int start = 0; start < verdicts.size(); start += CellCounts.BATCH_SIZE)
			{
				int end = Math.min(start + CellCounts.BATCH_SIZE, verdicts.size());
				result.add((int) verdicts.subList(start, end).stream().filter(verdict -> verdict == Verdict.FAIL).count());
			}
			return result;
		}
	}

	/** A race's newly established drops and terminal result. */
	public record Result(Map<Integer, String> dropped, Integer winner, boolean noWinner)
	{
		public Result
		{
			dropped = Map.copyOf(dropped);
			requireThat(winner == null || !noWinner, "winner").isTrue();
		}
	}
}
