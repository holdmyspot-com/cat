package com.holdmyspot.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;

/**
 * What running the hooks that run before the model acts showed, once in a trial's workspace and once as the user's
 * sessions run them.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param checked     the number of hooks that ran
 * @param differences the hooks whose output differs between the two runs, in the order that they ran
 */
public record HookReport(int checked, List<HookDifference> differences)
{
	/**
	 * The report of a campaign whose hooks were not run.
	 */
	public static final HookReport NONE = new HookReport(0, List.of());

	/**
	 * Creates a hook report.
	 *
	 * @param checked     the number of hooks that ran
	 * @param differences the hooks whose output differs between the two runs, in the order that they ran
	 * @throws NullPointerException     if {@code differences} is null
	 * @throws IllegalArgumentException if {@code checked} is negative or less than the number of differences
	 */
	public HookReport
	{
		requireThat(differences, "differences").isNotNull();
		requireThat(checked, "checked").isGreaterThanOrEqualTo(differences.size(), "differences.size()");
		differences = List.copyOf(differences);
	}

	/**
	 * A hook whose output differs between a trial's workspace and the user's sessions.
	 *
	 * @param hook    a sentence fragment that names the hook, its event and the file that declares it
	 * @param trial   the hook's output in a trial's workspace, from its first line that differs
	 * @param session the hook's output in the user's sessions, from its first line that differs
	 */
	public record HookDifference(String hook, List<String> trial, List<String> session)
	{
		/**
		 * Creates a hook difference.
		 *
		 * @param hook    a sentence fragment that names the hook, its event and the file that declares it
		 * @param trial   the hook's output in a trial's workspace, from its first line that differs
		 * @param session the hook's output in the user's sessions, from its first line that differs
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if {@code hook} is blank
		 */
		public HookDifference
		{
			requireThat(hook, "hook").isNotBlank();
			requireThat(trial, "trial").isNotNull();
			requireThat(session, "session").isNotNull();
			trial = List.copyOf(trial);
			session = List.copyOf(session);
		}
	}
}
