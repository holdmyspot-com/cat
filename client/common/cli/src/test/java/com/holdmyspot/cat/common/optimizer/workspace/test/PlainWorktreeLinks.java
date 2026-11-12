package com.holdmyspot.cat.common.optimizer.workspace.test;

import com.holdmyspot.cat.common.optimizer.workspace.WorktreeLinks;

/**
 * The links of an optimization worktree that is a plain directory, for a project that is no Git repository. Such a
 * worktree names no repository, so it has no link to repair.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class PlainWorktreeLinks implements WorktreeLinks
{
	/**
	 * The links.
	 */
	public static final PlainWorktreeLinks INSTANCE = new PlainWorktreeLinks();

	/**
	 * Prevents instantiation outside this class.
	 */
	private PlainWorktreeLinks()
	{
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * A plain directory needs no links, so it always keeps them.
	 */
	@Override
	public boolean repair()
	{
		return true;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Never called, because {@link #repair()} always succeeds.
	 */
	@Override
	public void recreate()
	{
		throw new AssertionError("A plain worktree is never recreated");
	}
}
