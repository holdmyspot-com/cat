package com.holdmyspot.cat.common.optimizer.hooks;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.proposal.HookReport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Judges the hooks that run before the model acts from how each one ended in the user's sessions and in a trial's
 * workspace. A hook that succeeds in the user's sessions and fails in trials fails the check; any other hook whose
 * output differs is a difference that the proposal lists.
 */
public final class HookComparison
{
	/**
	 * The number of lines that a difference or a failure shows.
	 */
	private static final int SHOWN_LINES = 3;

	/**
	 * Prevents instantiation.
	 */
	private HookComparison()
	{
	}

	/**
	 * Compares how each hook ended in the user's sessions and in a trial's workspace. A difference shows at most three
	 * lines of each output from the first line that differs; a failure names how the hook ended in trials and the last
	 * three lines of its error output.
	 *
	 * @param runs how each hook ended in both places, in the order that the hooks ran
	 * @return the hooks that succeed in the user's sessions and fail in trials, each as a sentence fragment that
	 *   follows the hook's name, and the report of the hooks that ran
	 * @throws NullPointerException if {@code runs} is null
	 */
	public static Verdict compare(List<Run> runs)
	{
		requireThat(runs, "runs").isNotNull();
		List<String> failures = new ArrayList<>();
		List<HookReport.HookDifference> differences = new ArrayList<>();
		for (Run run : runs)
		{
			if (run.session().succeeded() && !run.trial().succeeded())
			{
				failures.add(run.hook() + " " + run.trial().describeFailure());
				continue;
			}
			List<String> sessionLines = run.session().output().lines().toList();
			List<String> trialLines = run.trial().output().lines().toList();
			int first = 0;
			while (first < sessionLines.size() && first < trialLines.size() &&
				sessionLines.get(first).equals(trialLines.get(first)))
			{
				++first;
			}
			if (first < sessionLines.size() || first < trialLines.size())
			{
				differences.add(new HookReport.HookDifference(run.hook(), from(trialLines, first),
					from(sessionLines, first)));
			}
		}
		return new Verdict(failures, new HookReport(runs.size(), differences));
	}

	/**
	 * Returns the lines that a difference shows.
	 *
	 * @param lines the hook's output
	 * @param first the index of the first line that differs
	 * @return at most {@link #SHOWN_LINES} lines from {@code first}
	 */
	private static List<String> from(List<String> lines, int first)
	{
		if (first >= lines.size())
			return List.of();
		return lines.subList(first, Math.min(lines.size(), first + SHOWN_LINES));
	}

	/**
	 * How a hook ended.
	 *
	 * @param exitStatus the hook's exit status, or empty if it did not end before its timeout
	 * @param output     the hook's standard output
	 * @param error      the hook's error output
	 * @param timeout    how long the harness lets the hook run
	 */
	public record Outcome(Optional<Integer> exitStatus, String output, String error, Duration timeout)
	{
		/**
		 * Creates an outcome.
		 *
		 * @param exitStatus the hook's exit status, or empty if it did not end before its timeout
		 * @param output     the hook's standard output
		 * @param error      the hook's error output
		 * @param timeout    how long the harness lets the hook run
		 * @throws NullPointerException if any of the arguments are null
		 */
		public Outcome
		{
			requireThat(exitStatus, "exitStatus").isNotNull();
			requireThat(output, "output").isNotNull();
			requireThat(error, "error").isNotNull();
			requireThat(timeout, "timeout").isNotNull();
		}

		/**
		 * Returns whether the hook succeeded.
		 *
		 * @return {@code true} if it exited with status 0
		 */
		private boolean succeeded()
		{
			return exitStatus.isPresent() && exitStatus.get() == 0;
		}

		/**
		 * Describes how the hook failed, with the end of its error output.
		 *
		 * @return the end of a sentence that starts with the hook's name
		 */
		private String describeFailure()
		{
			String failure;
			if (exitStatus.isEmpty())
				failure = "does not end within " + timeout.toSeconds() + " seconds in a trial's workspace";
			else
				failure = "exits with status " + exitStatus.get() + " in a trial's workspace";
			List<String> lines = error.strip().lines().toList();
			if (lines.isEmpty())
				return failure + ", with no error output";
			List<String> end = lines.subList(Math.max(0, lines.size() - SHOWN_LINES), lines.size());
			return failure + ", and its error output ends with: " + String.join(" / ", end);
		}
	}

	/**
	 * How one hook ended in the user's sessions and in a trial's workspace.
	 *
	 * @param hook    a sentence fragment that names the hook, its event and the file that declares it
	 * @param session how the hook ended as the user's sessions run it
	 * @param trial   how the hook ended in a trial's workspace
	 */
	public record Run(String hook, Outcome session, Outcome trial)
	{
		/**
		 * Creates a run.
		 *
		 * @param hook    a sentence fragment that names the hook, its event and the file that declares it
		 * @param session how the hook ended as the user's sessions run it
		 * @param trial   how the hook ended in a trial's workspace
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if {@code hook} is blank
		 */
		public Run
		{
			requireThat(hook, "hook").isNotBlank();
			requireThat(session, "session").isNotNull();
			requireThat(trial, "trial").isNotNull();
		}
	}

	/**
	 * What the comparison found.
	 *
	 * @param failures each hook that succeeds in the user's sessions and fails in trials, as a sentence fragment
	 * @param report   the number of hooks that ran and those whose output differs
	 */
	public record Verdict(List<String> failures, HookReport report)
	{
		/**
		 * Creates a verdict.
		 *
		 * @param failures each hook that succeeds in the user's sessions and fails in trials, as a sentence fragment
		 * @param report   the number of hooks that ran and those whose output differs
		 * @throws NullPointerException if any of the arguments are null
		 */
		public Verdict
		{
			requireThat(failures, "failures").isNotNull();
			requireThat(report, "report").isNotNull();
			failures = List.copyOf(failures);
		}
	}
}
