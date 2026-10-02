package io.github.cowwoc.cat.common.git.replay;

import java.io.IOException;
import java.util.List;

/**
 * Validates the source-repository facts captured by {@link History} before replay planning.
 */
final class PlanningState
{
	/**
	 * Prevents construction of static validation operations.
	 */
	private PlanningState()
	{
	}

	/**
	 * Rejects mutable rebase state before planning reads source history.
	 *
	 * @param rebasing whether Git reports a supported rebase state directory
	 * @throws IOException if Git is rebasing
	 */
	static void rejectRebase(boolean rebasing) throws IOException
	{
		if (rebasing)
			throw new IOException("Topic-replay planning cannot run while Git is rebasing");
	}

	/**
	 * Verifies the captured source tip still identifies the current history.
	 *
	 * @param expectedTip the captured source tip
	 * @param actualTip the current source tip
	 * @throws IOException if the source tip changed
	 */
	static void verifyTip(String expectedTip, String actualTip) throws IOException
	{
		if (!actualTip.equals(expectedTip))
		{
			throw new IOException("Topic-replay planning expected source tip " + expectedTip + " but found " + actualTip);
		}
	}

	/**
	 * Rejects tracked worktree or index changes that would make planning evidence stale.
	 *
	 * @param trackedChanges the parsed tracked porcelain status lines
	 * @throws IOException if any tracked change exists
	 */
	static void rejectTrackedChanges(List<String> trackedChanges) throws IOException
	{
		if (!trackedChanges.isEmpty())
			throw new IOException("Topic-replay planning requires no tracked worktree or index changes");
	}
}
