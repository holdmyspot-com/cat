package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignTransitions;
import io.github.cowwoc.cat.common.optimizer.campaign.Candidate;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.PendingDecision;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.InventoryDiff;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.PathChange;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.StringJoiner;

/**
 * Verifies that the existing optimization worktree, where CAT writes the working candidate's project files, holds
 * only what CAT wrote there: at every {@code run} and before a promoted candidate replaces the working
 * one.
 */
final class WorktreeCheck
{
	/**
	 * Prevents instantiation.
	 */
	private WorktreeCheck()
	{
	}

	/**
	 * Verifies the optimization worktree of an active campaign. A worktree that holds the working candidate passes.
	 * A worktree that still holds the candidate that the working one replaced receives the working candidate. Any
	 * other worktree has an unowned change, which pauses the campaign.
	 *
	 * @param context the driver's context
	 * @param campaign an active campaign
	 * @param previous the working candidate before the latest adoptions
	 * @return the campaign, paused if the worktree has an unowned change
	 * @throws IOException if the worktree cannot be scanned or written, or an overlay cannot be read
	 */
	static Campaign verify(DriverContext context, Campaign campaign, Candidate previous) throws IOException
	{
		assert campaign.lifecycle() == Lifecycle.ACTIVE : campaign.lifecycle();
		FileInventory worktree = context.workspace().scanWorktree();
		Candidate current = campaign.development().working();
		Overlay working = context.overlays().require(current.snapshot());
		if (holds(worktree, working))
			return campaign;
		if (!previous.snapshot().equals(current.snapshot()) &&
			holds(worktree, context.overlays().require(previous.snapshot())))
		{
			context.workspace().materialize(working);
			return campaign;
		}
		for (PendingDecision decision : campaign.pendingDecisions())
		{
			if (decision instanceof PendingDecision.UnownedChange)
				return campaign;
		}
		return CampaignTransitions.apply(campaign, new CampaignEvent.DetectUnownedChange(describe(worktree, working)));
	}

	/**
	 * Indicates whether a worktree holds a candidate's project files.
	 *
	 * @param worktree the worktree's inventory
	 * @param candidate the candidate
	 * @return {@code true} if every project file matches
	 */
	static boolean holds(FileInventory worktree, Overlay candidate)
	{
		return projectEntries(worktree).equals(projectEntries(candidate.snapshot()));
	}

	/**
	 * Describes how a worktree differs from the candidate it should hold.
	 *
	 * @param worktree the worktree's inventory
	 * @param expected the candidate that the worktree should hold
	 * @return the description
	 */
	static String describe(FileInventory worktree, Overlay expected)
	{
		Path root = expected.fork().root();
		StringJoiner paths = new StringJoiner(", ");
		for (PathChange change : InventoryDiff.between(new FileInventory(root, projectEntries(expected.snapshot())),
			new FileInventory(root, projectEntries(worktree))).changes())
		{
			paths.add(change.path().toString());
		}
		return "The optimization worktree has changes that CAT did not make: " + paths;
	}

	/**
	 * Returns the entries of an inventory that lie inside the project. The optimization worktree holds only these; the
	 * external targets exist once, outside every worktree.
	 *
	 * @param inventory the inventory
	 * @return the entries keyed by a relative path
	 */
	private static Map<Path, FileEntry> projectEntries(FileInventory inventory)
	{
		Map<Path, FileEntry> entries = new HashMap<>();
		for (Entry<Path, FileEntry> entry : inventory.entries().entrySet())
		{
			if (!entry.getKey().isAbsolute())
				entries.put(entry.getKey(), entry.getValue());
		}
		return entries;
	}
}
