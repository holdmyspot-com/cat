package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persisted representative outcomes and common draws for an active race.
 *
 * @param racers the racers
 * @param cells the development cells
 * @param draws the shared draws by cell
 * @param predecessor the working candidate's retained outcomes
 * @param winner the winner, or null until decided
 * @param noWinner whether every racer was dropped
 */
public record RaceState(List<RaceDecision.Racer> racers, List<String> cells,
	Map<String, List<DrawnTestCase>> draws, Map<String, List<Verdict>> predecessor,
	Integer winner, boolean noWinner)
{
	/**
	 * Creates race state.
	 *
	 * @param racers the racers in prompt author attempt order
	 * @param cells the development cells under comparison
	 * @param draws the shared development draws by cell
	 * @param predecessor the working candidate's retained outcomes by cell
	 * @param winner the winning attempt, or null while unresolved or when there is no winner
	 * @param noWinner whether all racers were dropped
	 */
	public RaceState
	{
		requireThat(racers, "racers").isNotEmpty();
		requireThat(cells, "cells").isNotEmpty();
		requireThat(draws, "draws").isNotNull();
		requireThat(predecessor, "predecessor").isNotNull();
		requireThat(winner == null || !noWinner, "winner").isTrue();
		racers = List.copyOf(racers);
		cells = List.copyOf(cells);
		draws = copy(draws);
		predecessor = copyVerdicts(predecessor);
	}

	/** Adds a newly assigned shared draw for one cell. */
	public RaceState withDraw(String cell, DrawnTestCase testCase)
	{
		Map<String, List<DrawnTestCase>> updated = new LinkedHashMap<>(draws);
		List<DrawnTestCase> cases = new ArrayList<>(updated.getOrDefault(cell, List.of()));
		cases.add(testCase);
		updated.put(cell, cases);
		return new RaceState(racers, cells, updated, predecessor, winner, noWinner);
	}

	/** Returns the shared draw at a racer sequence position, if it has already been assigned. */
	public DrawnTestCase draw(String cell, long sequence)
	{
		List<DrawnTestCase> cases = draws.getOrDefault(cell, List.of());
		if (sequence > cases.size())
			return null;
		return cases.get(Math.toIntExact(sequence - 1));
	}

	/** Records a racer's next adopted verdict and evaluates equal-prefix drops. */
	public RaceState withOutcome(int attempt, String cell, Verdict verdict)
	{
		List<RaceDecision.Racer> updated = new ArrayList<>(racers.size());
		for (RaceDecision.Racer racer : racers)
		{
			Map<String, List<Verdict>> outcomes = new LinkedHashMap<>(racer.outcomes());
			if (racer.attempt() == attempt)
			{
				List<Verdict> cellOutcomes = new ArrayList<>(outcomes.getOrDefault(cell, List.of()));
				cellOutcomes.add(verdict);
				outcomes.put(cell, cellOutcomes);
			}
			updated.add(new RaceDecision.Racer(racer.attempt(), outcomes, racer.changedLines(), racer.dropped(),
				racer.dropReason()));
		}
		RaceDecision.Result decision = RaceDecision.evaluate(updated, predecessor, cells);
		List<RaceDecision.Racer> decided = new ArrayList<>(updated.size());
		for (RaceDecision.Racer racer : updated)
		{
			String reason = decision.dropped().get(racer.attempt());
			decided.add(new RaceDecision.Racer(racer.attempt(), racer.outcomes(), racer.changedLines(), reason != null,
				reason));
		}
		return new RaceState(decided, cells, draws, predecessor, decision.winner(), decision.noWinner());
	}

	/** Indicates whether the race has a terminal result. */
	public boolean isComplete()
	{
		return winner != null || noWinner;
	}

	/** Copies the draws map and its lists. */
	private static Map<String, List<DrawnTestCase>> copy(Map<String, List<DrawnTestCase>> source)
	{
		Map<String, List<DrawnTestCase>> copy = new LinkedHashMap<>();
		source.forEach((cell, cases) -> copy.put(cell, List.copyOf(cases)));
		return Map.copyOf(copy);
	}

	/** Copies the outcomes map and its lists. */
	private static Map<String, List<Verdict>> copyVerdicts(Map<String, List<Verdict>> source)
	{
		Map<String, List<Verdict>> copy = new LinkedHashMap<>();
		source.forEach((cell, outcomes) -> copy.put(cell, List.copyOf(outcomes)));
		return Map.copyOf(copy);
	}

	/** Returns whether the named attempt is still racing. */
	public boolean isRacing(int attempt)
	{
		return racers.stream().anyMatch(racer -> racer.attempt() == attempt && !racer.dropped());
	}
}
