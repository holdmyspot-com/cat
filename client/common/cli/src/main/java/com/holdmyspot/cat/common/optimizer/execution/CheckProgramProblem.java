package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A check program whose two runs on one guard pilot trial show that it cannot be trusted to decide later trials: the
 * runs had different outcomes, or a run took longer than half the time limit, so that a later trial on a loaded host
 * could reach the limit. Either one is a problem with the proposal that blocks its confirmation.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param assertionId the check-program assertion
 * @param position    the pilot trial's draw position
 * @param first       the first run
 * @param second      the second run
 */
public record CheckProgramProblem(String assertionId, int position, CheckProgram.Result first,
	CheckProgram.Result second)
{
	/**
	 * The longest run that is not a problem: half of the check program's time limit.
	 */
	public static final Duration LONGEST_RUN = CheckProgram.TIME_LIMIT.dividedBy(2);

	/**
	 * Finds the problems of one pilot trial's check programs.
	 *
	 * @param position the pilot trial's draw position
	 * @param runs     the two runs of each check program, keyed by assertion ID
	 * @return one problem for each program whose runs differ in outcome or of which a run took longer than
	 *   {@link #LONGEST_RUN}, in the iteration order of {@code runs}; empty if every program agreed with itself in time
	 * @throws NullPointerException     if {@code runs} is null or holds a null key, list or run, or a run whose outcome,
	 *   output or elapsed time is null
	 * @throws IllegalArgumentException if {@code position} is negative or a program did not run exactly twice
	 */
	public static List<CheckProgramProblem> find(int position, Map<String, List<CheckProgram.Result>> runs)
	{
		requireThat(position, "position").isNotNegative();
		requireThat(runs, "runs").isNotNull();
		List<CheckProgramProblem> problems = new ArrayList<>();
		for (Map.Entry<String, List<CheckProgram.Result>> entry : runs.entrySet())
		{
			String assertionId = entry.getKey();
			List<CheckProgram.Result> assertionRuns = entry.getValue();
			requireThat(assertionRuns, "runs.get(" + assertionId + ")").size().isEqualTo(2);
			CheckProgram.Result first = assertionRuns.getFirst();
			CheckProgram.Result second = assertionRuns.getLast();
			requireRun(first, "first");
			requireRun(second, "second");
			if (isProblem(first, second))
				problems.add(new CheckProgramProblem(assertionId, position, first, second));
		}
		return problems;
	}

	/**
	 * Returns how a run's outcome is shown to the user.
	 *
	 * @param outcome the outcome
	 * @return {@code pass}, {@code fail} or {@code program error}
	 * @throws NullPointerException if {@code outcome} is null
	 */
	public static String outcomeName(CheckProgram.Outcome outcome)
	{
		return switch (outcome)
		{
			case PASS -> "pass";
			case FAIL -> "fail";
			case ERROR -> "program error";
		};
	}

	/**
	 * Returns a duration in seconds, as it is shown to the user.
	 *
	 * @param duration the duration
	 * @return the number of seconds to millisecond precision, without trailing zeros, followed by {@code " s"}
	 * @throws NullPointerException if {@code duration} is null
	 */
	public static String seconds(Duration duration)
	{
		return BigDecimal.valueOf(duration.toMillis(), 3).stripTrailingZeros().toPlainString() + " s";
	}

	/**
	 * Creates a problem.
	 *
	 * @param assertionId the check-program assertion
	 * @param position    the pilot trial's draw position
	 * @param first       the first run
	 * @param second      the second run
	 * @throws NullPointerException     if any argument, or a run's outcome, output or elapsed time, is null
	 * @throws IllegalArgumentException if {@code assertionId} is blank, {@code position} is negative, or the runs
	 *   have the same outcome and neither took longer than {@link #LONGEST_RUN}
	 */
	public CheckProgramProblem
	{
		requireThat(assertionId, "assertionId").isNotBlank();
		requireThat(position, "position").isNotNegative();
		requireRun(first, "first");
		requireRun(second, "second");
		if (!isProblem(first, second))
		{
			throw new IllegalArgumentException("The runs of " + assertionId + " have the same outcome and neither " +
				"took longer than " + seconds(LONGEST_RUN));
		}
	}

	/**
	 * Indicates whether the two runs had different outcomes.
	 *
	 * @return {@code true} if they did
	 */
	public boolean outcomesDiffer()
	{
		return first.outcome() != second.outcome();
	}

	/**
	 * Indicates whether a run took longer than {@link #LONGEST_RUN}.
	 *
	 * @return {@code true} if either run did
	 */
	public boolean tookTooLong()
	{
		return isTooLong(first) || isTooLong(second);
	}

	/**
	 * Returns what is wrong with the runs, as a sentence that names the pilot trial and the assertion but not the
	 * outputs.
	 *
	 * @return the description, which ends with a period
	 */
	public String describe()
	{
		StringBuilder text = new StringBuilder(160).append("Pilot trial ").append(position + 1).
			append(", the check program of the assertion `").append(assertionId).append("`: ");
		boolean outcomesDiffer = outcomesDiffer();
		boolean tookTooLong = tookTooLong();
		if (outcomesDiffer)
		{
			text.append("its two runs had different outcomes (").append(outcomeName(first.outcome())).
				append(", then ").append(outcomeName(second.outcome())).append(')');
			if (tookTooLong)
				text.append(", and ");
		}
		if (tookTooLong)
		{
			text.append("a run took longer than half the time limit of ").append(seconds(CheckProgram.TIME_LIMIT)).
				append(" (").append(seconds(first.elapsed())).append(", then ").append(seconds(second.elapsed())).
				append(')');
		}
		return text.append('.').toString();
	}

	/**
	 * Validates one run.
	 *
	 * @param run  the run
	 * @param name the name of the run's parameter
	 * @throws NullPointerException if the run, its outcome, its output or its elapsed time is null
	 */
	private static void requireRun(CheckProgram.Result run, String name)
	{
		requireThat(run, name).isNotNull();
		requireThat(run.outcome(), name + ".outcome()").isNotNull();
		requireThat(run.observed(), name + ".observed()").isNotNull();
		requireThat(run.elapsed(), name + ".elapsed()").isNotNull();
	}

	/**
	 * Indicates whether two runs show a problem.
	 *
	 * @param first  the first run
	 * @param second the second run
	 * @return {@code true} if their outcomes differ or either took longer than {@link #LONGEST_RUN}
	 */
	private static boolean isProblem(CheckProgram.Result first, CheckProgram.Result second)
	{
		return first.outcome() != second.outcome() || isTooLong(first) || isTooLong(second);
	}

	/**
	 * Indicates whether a run took longer than {@link #LONGEST_RUN}.
	 *
	 * @param run the run
	 * @return {@code true} if it did
	 */
	private static boolean isTooLong(CheckProgram.Result run)
	{
		return run.elapsed().compareTo(LONGEST_RUN) > 0;
	}
}
