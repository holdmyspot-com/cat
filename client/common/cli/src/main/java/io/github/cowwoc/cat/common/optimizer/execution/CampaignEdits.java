package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.inventory.FileDelta;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.UnifiedDiff;
import io.github.cowwoc.cat.common.optimizer.port.WorkspacePort;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares the saved confirmed and working prompt files for an author brief.
 */
final class CampaignEdits
{
	/**
	 * Prevents instantiation.
	 */
	private CampaignEdits()
	{
	}

	/**
	 * Reads prompt changes from the saved snapshots, including files under participating directories and link targets.
	 *
	 * @param workspace reads recorded file bytes
	 * @param incumbent the confirmed prompt system
	 * @param working the working candidate
	 * @param sources the participating prompt files or directories
	 * @return the unified diff, empty for unchanged prompt content
	 * @throws IOException if snapshot bytes cannot be read or differ from their recorded hashes
	 */
	static String of(WorkspacePort workspace, Overlay incumbent, Overlay working, Set<Path> sources)
		throws IOException
	{
		if (incumbent.equals(working))
			return "";
		Set<Path> paths = new TreeSet<>(targets(incumbent.snapshot(), sources));
		paths.addAll(targets(working.snapshot(), sources));
		List<FileDelta> changes = new ArrayList<>();
		for (Path path : paths)
		{
			RegularFile beforeEntry = (RegularFile) incumbent.snapshot().entries().get(path);
			RegularFile afterEntry = (RegularFile) working.snapshot().entries().get(path);
			if (Objects.equals(beforeEntry, afterEntry))
				continue;
			FileContent before = workspace.readFile(incumbent, path).orElse(null);
			FileContent after = workspace.readFile(working, path).orElse(null);
			requireRecorded(path, beforeEntry, before);
			requireRecorded(path, afterEntry, after);
			if (Objects.equals(before, after))
				continue;
			Set<PosixFilePermission> permissions;
			if (afterEntry == null)
				permissions = beforeEntry.permissions();
			else
				permissions = afterEntry.permissions();
			changes.add(new FileDelta(path, before, after, permissions));
		}
		return UnifiedDiff.of(changes);
	}

	/**
	 * Finds regular-file targets under each prompt source, resolving source directories and individual file links.
	 *
	 * @param inventory the candidate's inventory
	 * @param sources the prompt sources
	 * @return their resolved regular files
	 */
	private static Set<Path> targets(FileInventory inventory, Set<Path> sources)
	{
		Set<Path> paths = new HashSet<>();
		for (Path source : sources)
		{
			Path resolved = inventory.resolve(source).orElse(source);
			for (Path path : inventory.entries().keySet())
			{
				if (!path.startsWith(source) && !path.startsWith(resolved))
					continue;
				Path target = inventory.resolve(path).orElse(path);
				if (inventory.entries().get(target) instanceof RegularFile)
					paths.add(target);
			}
		}
		return Set.copyOf(paths);
	}

	/**
	 * Checks that bytes match the saved snapshot rather than a changed live file.
	 *
	 * @param path the file
	 * @param entry the saved entry, or null when absent
	 * @param content the read bytes, or null when absent
	 * @throws IOException if the bytes do not match
	 */
	private static void requireRecorded(Path path, RegularFile entry, FileContent content) throws IOException
	{
		if (entry == null && content == null)
			return;
		if (entry == null || content == null || !entry.contentHash().equals(content.hash()))
			throw new IOException("The recorded snapshot bytes do not match " + path);
	}
}
