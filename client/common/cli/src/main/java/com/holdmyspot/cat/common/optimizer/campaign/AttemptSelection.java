package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.Cell;
import com.holdmyspot.cat.common.optimizer.inventory.Hunks;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
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
	 * Selects the accepted edited attempt with the most passes, then fewer changed lines, then earlier launch.
	 *
	 * @param attempts the fully tested contenders
	 * @param working the unchanged working snapshot
	 * @param regressionSet the existing working-candidate regression evidence
	 * @param workingOutcomes the working candidate's adopted records by test case
	 * @return the winner, or empty when no edited attempt is accepted
	 */
	public static Optional<PromptAuthorAttempt> select(List<PromptAuthorAttempt> attempts, Sha256 working,
		List<RegressionTestCase> regressionSet, List<TestCaseOutcomes> workingOutcomes)
	{
		requireThat(attempts, "attempts").isNotNull();
		requireThat(working, "working").isNotNull();
		requireThat(regressionSet, "regressionSet").isNotNull();
		requireThat(workingOutcomes, "workingOutcomes").isNotNull();
		return attempts.stream().filter(attempt -> !attempt.snapshot().equals(working) &&
			Promotion.decide(attempt, regressionSet, workingOutcomes) == Promotion.Decision.PROMOTE).min(
				Comparator.comparingLong((PromptAuthorAttempt attempt) -> passingRuns(attempt.runs())).reversed().
					thenComparingInt(AttemptSelection::changedLines).thenComparingInt(PromptAuthorAttempt::number));
	}

	/**
	 * Counts an attempt's own passing promotion runs. The working candidate's reference runs measure the working
	 * candidate, not the attempt, so they are excluded.
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
	 * Counts an attempt's own planned promotion runs, excluding the working candidate's reference runs.
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
	 * Indicates whether a promotion run measures the attempt rather than the working candidate.
	 *
	 * @param run the run
	 * @return {@code false} for a working reference run
	 */
	private static boolean isOwn(PromotionRun run)
	{
		return run.role() != PromotionRun.Role.WORKING_REFERENCE;
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
