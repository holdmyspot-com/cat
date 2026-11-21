package com.holdmyspot.cat.common.optimizer.port;

import java.util.List;
import java.util.Optional;

/**
 * The result of resolving an evaluator's startup hook comparison and inspecting its trial approvals.
 *
 * @param pairs the hooks eligible to run in both workspaces
 * @param unapproved the trial plugin hooks that lack verified approval, present only when approvals were inspected
 */
public record HookInspection(List<HookPair> pairs, Optional<List<String>> unapproved)
{
	/**
	 * Creates an immutable hook inspection.
	 *
	 * @param pairs the hooks eligible to run in both workspaces
	 * @param unapproved an optional list of trial plugin hooks without verified approval
	 * @throws NullPointerException if an argument or list element is null
	 */
	public HookInspection
	{
		pairs = List.copyOf(pairs);
		unapproved = unapproved.map(List::copyOf);
	}
}
