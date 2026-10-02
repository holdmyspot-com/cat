package io.github.cowwoc.cat.common.optimizer.cli;

import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.port.FakeWorkspacePort;
import io.github.cowwoc.cat.common.optimizer.port.WorkspacePort;
import io.github.cowwoc.cat.common.optimizer.workspace.Fork;
import io.github.cowwoc.cat.common.optimizer.workspace.ForkView;
import io.github.cowwoc.cat.common.optimizer.workspace.Forks;
import io.github.cowwoc.cat.common.optimizer.workspace.WorkspaceException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link Forks} that hold their inventory in memory and run no Git. The fork holds the files that the test names as
 * tracked, plus the participants that were added; a file that the test does not name is "ignored".
 * <p>
 * <b>Thread Safety:</b> This class is not thread-safe.
 */
final class InMemoryForks implements Forks
{
	private final Path root;
	private final Set<Path> tracked;
	private final Map<Path, FileInventory> inventories = new HashMap<>();
	private final List<Set<Path>> added = new ArrayList<>();
	private boolean dirty;
	private boolean excludeAdded;

	/**
	 * Creates the forks of a project.
	 *
	 * @param root the project
	 * @param tracked the files, relative to the project, that Git tracks
	 */
	InMemoryForks(Path root, Set<Path> tracked)
	{
		this.root = root;
		this.tracked = Set.copyOf(tracked);
	}

	/**
	 * Makes the next {@code create} refuse the base as having uncommitted changes.
	 */
	void makeDirty()
	{
		dirty = true;
	}

	/**
	 * Models the creation result that {@link Fork} records when
	 * Git does not already ignore CAT's work directory, as verified by its workspace tests.
	 */
	void addExcludeOnCreate()
	{
		excludeAdded = true;
	}

	/**
	 * Returns the participants that {@code addParticipants} received, one set per call.
	 *
	 * @return the sets, in call order
	 */
	List<Set<Path>> added()
	{
		return List.copyOf(added);
	}

	/**
	 * Supplies a captured inventory, including symbolic-link entries that {@link Fork}'s scanner records.
	 *
	 * @param campaign the campaign storage directory
	 * @param inventory the captured files and links supplied to subsequent fork reads
	 */
	void setInventory(Path campaign, FileInventory inventory)
	{
		inventories.put(campaign, inventory);
	}

	@Override
	public ForkView create(Path base, Path campaign, Set<Path> participants) throws IOException
	{
		if (dirty)
			throw new WorkspaceException("The base worktree " + base + " has staged, unstaged or untracked changes.");
		Set<Path> paths = new LinkedHashSet<>(tracked);
		paths.addAll(participants);
		return new InMemoryFork(campaign, scan(Map.of(), paths), excludeAdded);
	}

	@Override
	public ForkView open(Path base, Path campaign) throws IOException
	{
		FileInventory inventory = inventories.get(campaign);
		if (inventory == null)
			throw new WorkspaceException("The campaign directory " + campaign + " holds no fork.");
		return new InMemoryFork(campaign, inventory, false);
	}

	@Override
	public boolean exists(Path campaign)
	{
		return inventories.containsKey(campaign);
	}

	/**
	 * Builds an inventory of files from the project's directory.
	 *
	 * @param existing the entries that the inventory already holds
	 * @param paths the paths to add
	 * @return the inventory
	 * @throws IOException if a file cannot be read
	 */
	private FileInventory scan(Map<Path, FileEntry> existing, Set<Path> paths) throws IOException
	{
		Map<Path, FileEntry> entries = new LinkedHashMap<>(existing);
		for (Path path : paths)
		{
			Path location = path;
			if (!path.isAbsolute())
				location = root.resolve(path);
			if (Files.notExists(location, LinkOption.NOFOLLOW_LINKS))
			{
				throw new WorkspaceException("The participating path " + path + " does not exist, so it cannot be " +
					"recorded in the fork.");
			}
			entries.put(path, new RegularFile(new FileContent(Files.readAllBytes(location)).hash(),
				PosixFilePermissions.fromString("rw-r--r--")));
		}
		return new FileInventory(root, entries);
	}

	/**
	 * A fork that holds an inventory and a fake workspace.
	 */
	private final class InMemoryFork implements ForkView
	{
		private final Path campaign;
		private final FileInventory inventory;
		private final FakeWorkspacePort workspace;
		private final boolean excludeAdded;

		/**
		 * Creates a fork and registers its inventory.
		 *
		 * @param campaign the campaign storage directory
		 * @param inventory the recorded files
		 * @param excludeAdded whether creation added the local exclude
		 */
		InMemoryFork(Path campaign, FileInventory inventory, boolean excludeAdded)
		{
			this.campaign = campaign;
			this.excludeAdded = excludeAdded;
			this.inventory = inventory;
			this.workspace = new FakeWorkspacePort(campaign, inventory, root);
			inventories.put(campaign, inventory);
		}

		@Override
		public boolean excludeAdded()
		{
			return excludeAdded;
		}

		@Override
		public FileInventory inventory()
		{
			return inventory;
		}

		@Override
		public WorkspacePort workspace()
		{
			return workspace;
		}

		@Override
		public ForkView addParticipants(Path base, Set<Path> participants) throws IOException
		{
			added.add(Set.copyOf(participants));
			Set<Path> missing = new LinkedHashSet<>(participants);
			missing.removeIf(path -> inventory.entries().containsKey(path));
			return new InMemoryFork(campaign, scan(inventory.entries(), missing), excludeAdded);
		}
	}
}
