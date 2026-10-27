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
	 * Selects the attempt with the most passing triggering and first-pass regression runs, then fewer changed lines,
	 * then earlier launch. Paired reruns are excluded because only an attempt that failed a regression run receives
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
				Comparator.comparingLong((PromptAuthorAttempt attempt) -> rankedPassingRuns(attempt.runs())).reversed().
					thenComparingInt(AttemptSelection::changedLines).thenComparingInt(PromptAuthorAttempt::number));
	}

	/**
	 * Counts the passing runs that rank a weak round's winner: the attempt's triggering runs and first-pass regression
	 * runs.
	 *
	 * @param runs the attempt's promotion runs
	 * @return the ranked passing run count
	 */
	private static long rankedPassingRuns(List<PromotionRun> runs)
	{
		return runs.stream().filter(run -> isRanked(run) && run.verdict() == Verdict.PASS).count();
	}

	/**
	 * Indicates whether a promotion run counts toward a weak round's winner ranking.
	 *
	 * @param run the run
	 * @return {@code true} for a triggering run or a first-pass regression run
	 */
	private static boolean isRanked(PromotionRun run)
	{
		return run.role() == PromotionRun.Role.TRIGGERING || run.role() == PromotionRun.Role.REGRESSION;
	}

	/**
	 * Counts an attempt's own passing promotion runs, including its paired reruns, as the status shows them. The working
	 * candidate's reference runs and paired reruns measure the working candidate, not the attempt, so they are
	 * excluded.
	 *
	 * @param runs the attempt's promotion runs
	 * @return the passing run count
	 * @throws NullPointerException if {@code runs} is null
	 */
	public static long passingRuns(List<PromotionRun> runs)
	{
		requireThat(runs, "runs").isNotNull();
		return runs.stream().filter(run -> isOwn(run) && run.verdict() == Verdict.PASS).count();
	}

	/**
	 * Counts an attempt's own planned promotion runs, excluding the working candidate's reference runs and paired
	 * reruns.
	 *
	 * @param runs the attempt's promotion runs
	 * @return the planned run count
	 * @throws NullPointerException if {@code runs} is null
	 */
	public static long plannedRuns(List<PromotionRun> runs)
	{
		requireThat(runs, "runs").isNotNull();
		return runs.stream().filter(AttemptSelection::isOwn).count();
	}

	/**
	 * Counts an attempt's own promotion runs that have an outcome, excluding the working candidate's reference runs
	 * and paired reruns.
	 *
	 * @param runs the attempt's promotion runs
	 * @return the judged run count
	 * @throws NullPointerException if {@code runs} is null
	 */
	public static long judgedRuns(List<PromotionRun> runs)
	{
		requireThat(runs, "runs").isNotNull();
		return runs.stream().filter(run -> isOwn(run) && run.verdict() != null).count();
	}

	/**
	 * Indicates whether a promotion run measures the attempt rather than the working candidate.
	 *
	 * @param run the run
	 * @return {@code false} for a working reference run or paired rerun
	 */
	private static boolean isOwn(PromotionRun run)
	{
		return !run.role().measuresWorking();
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
