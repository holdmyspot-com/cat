package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.Cell;
import com.holdmyspot.cat.common.optimizer.inventory.Hunks;
import com.holdmyspot.cat.common.optimizer.statistics.ExploratoryBounds;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Chooses concurrent development attempts using the existing exploratory estimate and promotion rule.
 */
public final class AttemptSelection
{
	/**
	 * The development estimate below which the working candidate is weak in a cell.
	 */
	private static final double WEAK_ESTIMATE = 0.8;

	/**
	 * Prevents instantiation.
	 */
	private AttemptSelection()
	{
	}

	/**
	 * Tests every development cell of the addressed tests, including cells with no development outcomes.
	 *
	 * @param counts the current working candidate's development counts
	 * @param developmentCells the development cells, in contract order
	 * @param addressed the development cells that the round addresses
	 * @return whether any relevant development Wilson lower bound is below {@link #WEAK_ESTIMATE}
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static boolean isWeak(Map<String, CellCounts> counts, List<Cell> developmentCells, List<String> addressed)
	{
		requireThat(counts, "counts").isNotNull();
		requireThat(developmentCells, "developmentCells").isNotNull();
		requireThat(addressed, "addressed").isNotNull();
		Set<String> tests = new HashSet<>();
		for (Cell cell : developmentCells)
		{
			if (addressed.contains(cell.id()))
				tests.add(cell.testId());
		}
		for (Cell cell : developmentCells)
		{
			if (!tests.contains(cell.testId()))
				continue;
			CellCounts outcomes = counts.getOrDefault(cell.id(), CellCounts.EMPTY);
			if (ExploratoryBounds.wilson(outcomes.passes(), outcomes.outcomes()).lower() < WEAK_ESTIMATE)
				return true;
		}
		return false;
	}

	/**
	 * Selects the attempt with the most passing triggering and first-pass regression replays, then fewer changed lines,
	 * then earlier launch. Paired replays are excluded because only an attempt that failed a regression replay receives
	 * them, starting with the test cases it failed, so counting them would favour that attempt.
	 *
	 * @param accepted the edited contenders that the promotion rule accepted
	 * @return the winner, or empty when no attempt is accepted
	 * @throws NullPointerException if {@code accepted} is null
	 */
	public static Optional<PromptAuthorAttempt> select(List<PromptAuthorAttempt> accepted)
	{
		requireThat(accepted, "accepted").isNotNull();
		return accepted.stream().min(
				Comparator.comparingLong((PromptAuthorAttempt attempt) -> rankedPassingReplays(attempt.replays())).reversed().
					thenComparingInt(AttemptSelection::changedLines).thenComparingInt(PromptAuthorAttempt::number));
	}

	/**
	 * Returns whether at least two accepted attempts tie for the leading promotion score.
	 *
	 * @param accepted the accepted attempts
	 * @return whether the best score is tied
	 */
	static boolean hasTiedLeaders(List<PromptAuthorAttempt> accepted)
	{
		if (accepted.size() < 2)
			return false;
		long best = accepted.stream().mapToLong(attempt -> rankedPassingReplays(attempt.replays())).max().orElseThrow();
		return accepted.stream().filter(attempt -> rankedPassingReplays(attempt.replays()) == best).count() > 1;
	}

	/**
	 * Counts the passing replays that rank a weak round's winner: the attempt's triggering replays and first-pass
	 * regression replays.
	 *
	 * @param replays the attempt's promotion replays
	 * @return the ranked passing replay count
	 */
	private static long rankedPassingReplays(List<PromotionReplay> replays)
	{
		return replays.stream().filter(replay -> isRanked(replay) && replay.verdict() == Verdict.PASS).count();
	}

	/**
	 * Indicates whether a promotion replay counts toward a weak round's winner ranking.
	 *
	 * @param replay the replay
	 * @return {@code true} for a triggering replay or a first-pass regression replay
	 */
	private static boolean isRanked(PromotionReplay replay)
	{
		return replay.role() == PromotionReplay.Role.TRIGGERING || replay.role() == PromotionReplay.Role.REGRESSION;
	}

	/**
	 * Counts an attempt's own passing promotion replays, including its paired replays, as the status shows them. The
	 * working candidate's reference replays and paired replays measure the working candidate, not the attempt, so they
	 * are excluded.
	 *
	 * @param replays the attempt's promotion replays
	 * @return the passing replay count
	 * @throws NullPointerException if {@code replays} is null
	 */
	public static long passingReplays(List<PromotionReplay> replays)
	{
		requireThat(replays, "replays").isNotNull();
		return replays.stream().filter(replay -> isOwn(replay) && replay.verdict() == Verdict.PASS).count();
	}

	/**
	 * Counts an attempt's own planned promotion replays, excluding the working candidate's reference replays and paired
	 * replays.
	 *
	 * @param replays the attempt's promotion replays
	 * @return the planned replay count
	 * @throws NullPointerException if {@code replays} is null
	 */
	public static long plannedReplays(List<PromotionReplay> replays)
	{
		requireThat(replays, "replays").isNotNull();
		return replays.stream().filter(AttemptSelection::isOwn).count();
	}

	/**
	 * Counts an attempt's own promotion replays that have an outcome, excluding the working candidate's reference replays
	 * and paired replays.
	 *
	 * @param replays the attempt's promotion replays
	 * @return the judged replay count
	 * @throws NullPointerException if {@code replays} is null
	 */
	public static long judgedReplays(List<PromotionReplay> replays)
	{
		requireThat(replays, "replays").isNotNull();
		return replays.stream().filter(replay -> isOwn(replay) && replay.verdict() != null).count();
	}

	/**
	 * Indicates whether a promotion replay measures the attempt rather than the working candidate.
	 *
	 * @param replay the replay
	 * @return {@code false} for a working reference replay or paired replay
	 */
	private static boolean isOwn(PromotionReplay replay)
	{
		return !replay.role().measuresWorking();
	}

	/**
	 * Counts original added and removed lines using the shared hunk comparison.
	 *
	 * @param attempt the original contender
	 * @return its changed line count
	 */
	static int changedLines(PromptAuthorAttempt attempt)
	{
		return attempt.minimality().files().stream().mapToInt(Hunks::changedLines).sum();
	}
}
