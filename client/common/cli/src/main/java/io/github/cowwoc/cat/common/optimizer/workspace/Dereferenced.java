package io.github.cowwoc.cat.common.optimizer.workspace;

import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SpecialFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SymbolicLink;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.InventoryScanner;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.workspace.LinkReport.Status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The files of one inventory as a trial or a prompt author sees them, with every symbolic link replaced by a copy of
 * its resolved target.
 * <p>
 * The tree is computed over the inventory, without touching the file system. A link to a directory expands to the
 * directory's recorded files, nested links included. Git metadata never appears, and neither do CAT's session
 * directories, because a trial agent must not read other trials or formal assignments.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
final class Dereferenced
{
	private static final Path PROJECT = Path.of("");
	private final FileInventory inventory;
	private final Map<Path, SortedSet<Path>> children = new HashMap<>();
	private final List<Placement> placements = new ArrayList<>();
	private final SortedMap<Path, Status> problems = new TreeMap<>();
	private final Set<Path> reached = new HashSet<>();

	/**
	 * A regular file at a path of the dereferenced tree.
	 *
	 * @param destination the file's path in the dereferenced tree
	 * @param source the key of the recorded file whose content and mode the file holds
	 * @param file the recorded file
	 */
	record Placement(Path destination, Path source, RegularFile file)
	{
	}

	/**
	 * Creates the tree of an inventory.
	 *
	 * @param inventory the inventory
	 */
	private Dereferenced(FileInventory inventory)
	{
		this.inventory = inventory;
	}

	/**
	 * Computes the dereferenced tree of an inventory and the links that cannot be dereferenced.
	 *
	 * @param inventory the inventory
	 * @return the tree
	 */
	static Dereferenced of(FileInventory inventory)
	{
		Dereferenced tree = new Dereferenced(inventory);
		for (Path key : inventory.entries().keySet())
		{
			if (!InventoryScanner.isOmitted(key))
				tree.index(key);
		}
		tree.walkDirectory(PROJECT, PROJECT, new HashSet<>());
		// Links that the project's files do not reach, such as links inside a recorded external directory, are still
		// checked for cycles and missing targets, but the files that they would place are discarded
		int placed = tree.placements.size();
		for (Map.Entry<Path, FileEntry> entry : inventory.entries().entrySet())
		{
			Path key = entry.getKey();
			if (entry.getValue() instanceof SymbolicLink && !InventoryScanner.isOmitted(key) &&
				!tree.reached.contains(key))
			{
				tree.expandLink(key, PROJECT, new HashSet<>());
			}
		}
		tree.placements.subList(placed, tree.placements.size()).clear();
		return tree;
	}

	/**
	 * Records a key and its ancestors in the directory index.
	 *
	 * @param key a key of the inventory
	 */
	private void index(Path key)
	{
		Path child = key;
		while (true)
		{
			Path parent = child.getParent();
			if (parent == null)
			{
				// The file system root has no parent, and a single name lies directly in the project directory
				if (child.isAbsolute())
					return;
				parent = PROJECT;
			}
			if (!children.computeIfAbsent(parent, _ -> new TreeSet<>()).add(child))
				return;
			if (parent.equals(PROJECT))
				return;
			child = parent;
		}
	}

	/**
	 * Adds the files of a directory.
	 *
	 * @param directory the directory's key
	 * @param destination the directory's path in the dereferenced tree
	 * @param expanding the keys of the directories that this walk is inside
	 */
	private void walkDirectory(Path directory, Path destination, Set<Path> expanding)
	{
		expanding.add(directory);
		for (Path child : children.getOrDefault(directory, Collections.emptySortedSet()))
		{
			Path childDestination = destination.resolve(child.getFileName());
			FileEntry entry = inventory.entries().get(child);
			switch (entry)
			{
				case null -> walkDirectory(child, childDestination, expanding);
				case RegularFile file -> placements.add(new Placement(childDestination, child, file));
				case SymbolicLink _ -> expandLink(child, childDestination, expanding);
				case SpecialFile _ ->
				{
					// A pipe or device has no content to copy
				}
			}
		}
		expanding.remove(directory);
	}

	/**
	 * Replaces a link by a copy of its resolved target, or records why it cannot.
	 *
	 * @param link the link's key
	 * @param destination the link's path in the dereferenced tree
	 * @param expanding the keys of the directories that this walk is inside
	 */
	private void expandLink(Path link, Path destination, Set<Path> expanding)
	{
		reached.add(link);
		Optional<Path> resolved = inventory.resolve(link);
		if (resolved.isEmpty())
		{
			problems.putIfAbsent(link, Status.CYCLE);
			return;
		}
		Path target = resolved.get();
		if (InventoryScanner.isOmitted(target))
		{
			problems.putIfAbsent(link, Status.MISSING_TARGET);
			return;
		}
		FileEntry entry = inventory.entries().get(target);
		if (entry instanceof RegularFile file)
		{
			placements.add(new Placement(destination, target, file));
			return;
		}
		if (entry != null)
			return;
		if (!target.equals(PROJECT) && !children.containsKey(target))
		{
			problems.putIfAbsent(link, Status.MISSING_TARGET);
			return;
		}
		if (expanding.contains(target))
		{
			problems.putIfAbsent(link, Status.CYCLE);
			return;
		}
		walkDirectory(target, destination, expanding);
	}

	/**
	 * Returns the links that cannot be dereferenced.
	 *
	 * @return each such link's key and the reason, in key order
	 */
	SortedMap<Path, Status> problems()
	{
		return Collections.unmodifiableSortedMap(problems);
	}

	/**
	 * Requires that every link can be dereferenced.
	 *
	 * @throws WorkspaceException if a link forms a cycle or leads to a missing target
	 */
	void requireResolvable() throws WorkspaceException
	{
		if (problems.isEmpty())
			return;
		StringBuilder message = new StringBuilder(256).
			append("The prompt system has symbolic links that cannot be staged. Resolve them and propose again:");
		for (Map.Entry<Path, Status> problem : problems.entrySet())
		{
			Path link = problem.getKey();
			message.append("\n  ").append(link);
			if (problem.getValue() == Status.CYCLE)
				message.append(": link cycle");
			else
				message.append(" -> ").append(inventory.resolve(link).orElse(link)).append(": missing target");
		}
		throw new WorkspaceException(message.toString());
	}

	/**
	 * Writes the tree into a directory.
	 *
	 * @param tree the fork tree that holds the recorded files' content
	 * @param overlay the candidate whose snapshot this tree was computed from; its edits replace recorded content
	 * @param directory the directory to fill
	 * @param excluded  the paths of the tree to leave out, with everything below them
	 * @throws IOException if a file cannot be written
	 */
	void writeTo(ForkTree tree, Overlay overlay, Path directory, Set<Path> excluded) throws IOException
	{
		for (Placement placement : placements)
		{
			if (excluded.stream().anyMatch(placement.destination()::startsWith))
				continue;
			Path file = directory.resolve(placement.destination());
			Files.createDirectories(file.getParent());
			tree.writeFile(overlay, placement.source(), placement.file(), file);
		}
	}
}
