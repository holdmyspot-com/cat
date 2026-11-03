package com.holdmyspot.cat.common.optimizer.hooks.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.hooks.HookComparison;
import com.holdmyspot.cat.common.optimizer.hooks.HookComparison.Outcome;
import com.holdmyspot.cat.common.optimizer.hooks.HookComparison.Run;
import com.holdmyspot.cat.common.optimizer.hooks.HookComparison.Verdict;
import com.holdmyspot.cat.common.optimizer.proposal.HookReport;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Verifies how the hook check judges the hooks that it ran, from how each hook ended in the user's sessions and in a
 * trial's workspace. The outcomes are given, in place of the processes that the check starts.
 */
public final class HookComparisonTest
{
	private static final String HOOK = "the SessionStart hook `start.sh` of `.claude/settings.json`";
	private static final Duration TIMEOUT = Duration.ofSeconds(30);

	/**
	 * Creates a test instance.
	 */
	public HookComparisonTest()
	{
	}

	/**
	 * Verifies that a hook that succeeds in the user's sessions and exits with another status in a trial's workspace
	 * fails the check, named with its exit status and the last three lines of its error output, and is not also listed
	 * as a difference.
	 */
	@Test
	public void hookThatFailsInTrialsFails()
	{
		Run run = new Run(HOOK, ended(0, "context\n", ""),
			ended(1, "", "first\nsecond\nthird\ndocs/conventions.md: No such file or directory\n"));

		Verdict result = HookComparison.compare(List.of(run));

		requireThat(result.failures(), "failures").isEqualTo(List.of(HOOK + " exits with status 1 in a trial's " +
			"workspace, and its error output ends with: second / third / docs/conventions.md: No such file or " +
			"directory"));
		requireThat(result.report(), "report").isEqualTo(new HookReport(1, List.of()));
	}

	/**
	 * Verifies that a hook that does not end within its timeout in a trial's workspace, and writes no error output,
	 * fails the check, named with its timeout.
	 */
	@Test
	public void hookThatTimesOutInTrialsFails()
	{
		Run run = new Run(HOOK, ended(0, "", ""),
			new Outcome(Optional.empty(), "", "  \n", TIMEOUT));

		Verdict result = HookComparison.compare(List.of(run));

		requireThat(result.failures(), "failures").isEqualTo(List.of(HOOK + " does not end within 30 seconds in a " +
			"trial's workspace, with no error output"));
	}

	/**
	 * Verifies that a hook that also fails in the user's sessions does not fail the check, so that only a hook that
	 * trials break is refused, and that its output is still compared.
	 */
	@Test
	public void hookThatFailsInSessionsIsCompared()
	{
		Run run = new Run(HOOK, ended(1, "session\n", "error\n"), ended(2, "trial\n", "error\n"));

		Verdict result = HookComparison.compare(List.of(run));

		requireThat(result.failures(), "failures").isEmpty();
		requireThat(result.report().differences(), "differences").isEqualTo(List.of(
			new HookReport.HookDifference(HOOK, List.of("trial"), List.of("session"))));
	}

	/**
	 * Verifies that a hook whose output differs is listed with at most three lines of each output, from the first
	 * line that differs, so that the lines that both outputs share are not shown.
	 */
	@Test
	public void differenceStartsAtFirstDifferingLine()
	{
		Run run = new Run(HOOK, ended(0, "same\n/session/project\na\nb\nc\n", ""),
			ended(0, "same\n/trial/project\n", ""));

		Verdict result = HookComparison.compare(List.of(run));

		requireThat(result.failures(), "failures").isEmpty();
		requireThat(result.report(), "report").isEqualTo(new HookReport(1, List.of(new HookReport.HookDifference(HOOK,
			List.of("/trial/project"), List.of("/session/project", "a", "b")))));
	}

	/**
	 * Verifies that a hook whose output in a trial's workspace stops before its output in the user's sessions is listed
	 * with no output in trials.
	 */
	@Test
	public void shorterTrialOutputIsListed()
	{
		Run run = new Run(HOOK, ended(0, "same\nmore\n", ""), ended(0, "same\n", ""));

		Verdict result = HookComparison.compare(List.of(run));

		requireThat(result.report().differences(), "differences").isEqualTo(List.of(
			new HookReport.HookDifference(HOOK, List.of(), List.of("more"))));
	}

	/**
	 * Verifies that a hook that prints the same output in both places, such as a hook that reads only its plugin's
	 * files, passes the check and is not listed, while it still counts as a hook that ran.
	 */
	@Test
	public void identicalOutputIsNotListed()
	{
		Run plugin = new Run(HOOK, ended(0, "Plugin context.\n", ""),
			ended(0, "Plugin context.\n", ""));
		Run other = new Run("the SessionStart hook `other.sh` of `.claude/settings.json`",
			ended(0, "", ""), ended(0, "", ""));

		Verdict result = HookComparison.compare(List.of(plugin, other));

		requireThat(result.failures(), "failures").isEmpty();
		requireThat(result.report(), "report").isEqualTo(new HookReport(2, List.of()));
	}

	/**
	 * Returns how a hook ended when it exited.
	 *
	 * @param status the hook's exit status
	 * @param output the hook's standard output
	 * @param error  the hook's error output
	 * @return the outcome
	 */
	private static Outcome ended(int status, String output, String error)
	{
		return new Outcome(Optional.of(status), output, error, TIMEOUT);
	}
}
