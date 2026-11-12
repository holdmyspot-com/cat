package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One prompt test's progress through the completed development rounds that CAT counts to detect stalled development.
 * <p>
 * A completed round makes progress for the test when, after it, the working candidate's development estimate in any
 * of the test's development cells exceeds the best value in {@code bestEstimates}, or more of the test's development
 * cells meet the commit gate's count conditions than {@code bestMetCells}. Progress resets {@code rounds}; a round
 * without progress adds the cells' estimates to it, and {@link #ROUNDS} such rounds in a row stall the test.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param bestEstimates the best development estimate that each development cell of the test had before the rounds in
 *   {@code rounds} began, by cell identifier
 * @param bestMetCells the most development cells of the test that met the commit gate's count conditions before
 *   those rounds began
 * @param rounds each cell's development estimate after each completed round without progress, oldest round first
 */
public record StallProgress(Map<String, Double> bestEstimates, int bestMetCells, List<Map<String, Double>> rounds)
{
	/**
	 * The number of consecutive completed rounds without progress that stall a test, and the number of rounds that
	 * continuing development runs before CAT asks again.
	 */
	public static final int ROUNDS = 5;

	/**
	 * Creates the progress.
	 *
	 * @param bestEstimates the best development estimate that each development cell of the test had before the
	 *   rounds in {@code rounds} began, by cell identifier
	 * @param bestMetCells the most development cells of the test that met the commit gate's count conditions
	 *   before those rounds began
	 * @param rounds each cell's development estimate after each completed round without progress, oldest round first
	 * @throws NullPointerException if an argument or a cell identifier is null
	 * @throws IllegalArgumentException if {@code bestMetCells} is negative, an estimate is outside [0, 1], or
	 *   {@code rounds} holds more than {@link #ROUNDS} rounds
	 */
	public StallProgress
	{
		bestEstimates = sorted(bestEstimates, "bestEstimates");
		requireThat(bestMetCells, "bestMetCells").isNotNegative();
		requireThat(rounds, "rounds").isNotNull();
		requireThat(rounds.size(), "rounds.size()").isLessThanOrEqualTo(ROUNDS);
		List<Map<String, Double>> copy = new ArrayList<>(rounds.size());
		for (Map<String, Double> round : rounds)
			copy.add(sorted(round, "round"));
		rounds = Collections.unmodifiableList(copy);
	}

	/**
	 * Returns an unmodifiable copy of estimates ordered by cell identifier.
	 *
	 * @param estimates the estimates by cell identifier
	 * @param name the name of the estimates, for error messages
	 * @return the copy
	 * @throws NullPointerException if {@code estimates} or a key is null
	 * @throws IllegalArgumentException if an estimate is outside [0, 1]
	 */
	private static Map<String, Double> sorted(Map<String, Double> estimates, String name)
	{
		requireThat(estimates, name).isNotNull();
		Map<String, Double> copy = new TreeMap<>();
		for (Map.Entry<String, Double> entry : estimates.entrySet())
		{
			requireThat(entry.getValue(), name + "[" + entry.getKey() + "]").isBetween(0.0, true, 1.0, true);
			copy.put(entry.getKey(), entry.getValue());
		}
		return Collections.unmodifiableMap(copy);
	}

	/**
	 * Indicates whether the test's development stalled.
	 *
	 * @return {@code true} if {@link #ROUNDS} consecutive completed rounds made no progress
	 */
	public boolean isStalled()
	{
		return rounds.size() == ROUNDS;
	}

	/**
	 * Returns this progress after a completed round. A round that made progress, which raised a cell's development
	 * estimate above its best value or the number of cells that meet the commit gate's count conditions above its
	 * best value, ends the run of rounds without progress and raises the best values. Any other round joins that run,
	 * unless the test already stalled.
	 *
	 * @param estimates the development estimate of each of the test's development cells after the round, by cell
	 *   identifier
	 * @param metCells the number of the test's development cells that meet the commit gate's count conditions after
	 *   the round
	 * @return the progress after the round
	 * @throws NullPointerException if {@code estimates} is null
	 * @throws IllegalArgumentException if {@code metCells} is negative
	 */
	public StallProgress afterRound(Map<String, Double> estimates, int metCells)
	{
		requireThat(metCells, "metCells").isNotNegative();
		if (isStalled())
			return this;
		StallProgress observed = new StallProgress(estimates, metCells, List.of());
		boolean progress = metCells > bestMetCells;
		for (Map.Entry<String, Double> entry : observed.bestEstimates.entrySet())
		{
			if (entry.getValue() > bestEstimates.getOrDefault(entry.getKey(), 0.0))
				progress = true;
		}
		if (!progress)
		{
			List<Map<String, Double>> extended = new ArrayList<>(rounds);
			extended.add(observed.bestEstimates);
			return new StallProgress(bestEstimates, bestMetCells, extended);
		}
		Map<String, Double> best = new TreeMap<>(bestEstimates);
		for (Map.Entry<String, Double> entry : observed.bestEstimates.entrySet())
			best.merge(entry.getKey(), entry.getValue(), Math::max);
		return new StallProgress(best, Math.max(bestMetCells, metCells), List.of());
	}

	/**
	 * Returns this progress with the count of rounds without progress back at zero, keeping the best values.
	 *
	 * @return the progress
	 */
	public StallProgress withoutRounds()
	{
		return new StallProgress(bestEstimates, bestMetCells, List.of());
	}
}
