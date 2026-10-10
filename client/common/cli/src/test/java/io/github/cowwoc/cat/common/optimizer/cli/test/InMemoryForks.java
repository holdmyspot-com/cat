package io.github.cowwoc.cat.common.optimizer.cli.test;

import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.port.WorkspacePort;
import io.github.cowwoc.cat.common.optimizer.port.test.FakeWorkspacePort;
import io.github.cowwoc.cat.common.optimizer.workspace.Fork;
import io.github.cowwoc.cat.common.optimizer.workspace.ForkView;
import io.github.cowwoc.cat.common.optimizer.workspace.Forks;
import io.github.cowwoc.cat.common.optimizer.workspace.WorkspaceException;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
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
import java.util.Optional;
import java.util.Set;

/**
 * {@link Forks} that hold their inventory in memory and run no Git. The fork holds the files that the test names as
 * tracked, plus the participants that the latest selection names; a file that the test does not name is "ignored".
 * A participant that a selection omits keeps its recorded entry, which a later selection restores. A fork that
 * {@code extend} returns becomes current only when it is adopted, and the fork it replaces stays available by identity.
 * <p>
 * <b>Thread Safety:</b> This class is not thread-safe.
 */
final class InMemoryForks implements Forks
{
	private final Path root;
	private final Set<Path> tracked;
	private final Map<Path, FileInventory> inventories = new HashMap<>();
	private final Map<Path, Map<Path, FileEntry>> recorded = new HashMap<>();
	private final Map<Path, Set<Path>> created = new HashMap<>();
	private final Map<Path, List<FileInventory>> versions = new HashMap<>();
	private final List<Set<Path>> selections = new ArrayList<>();
	private boolean dirty;
	private boolean excludeAdded;
	private boolean removalDenied;

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
	 * Makes every later {@code remove} fail as a file system that denies deleting the optimization worktree would.
	 */
	void denyRemoval()
	{
		removalDenied = true;
	}

	/**
	 * Returns the participants that {@code selectParticipants} received, one set per call.
	 *
	 * @return the sets, in call order
	 */
	List<Set<Path>> selections()
	{
		return List.copyOf(selections);
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
		FileInventory inventory = scan(Map.of(), paths);
		recorded.put(campaign, new LinkedHashMap<>(inventory.entries()));
		created.put(campaign, Set.copyOf(paths));
		return new InMemoryFork(campaign, inventory, excludeAdded, true);
	}

	@Override
	public ForkView open(Path base, Path campaign) throws IOException
	{
		FileInventory inventory = inventories.get(campaign);
		if (inventory == null)
			throw new WorkspaceException("The campaign directory " + campaign + " holds no fork.");
		return new InMemoryFork(campaign, inventory, false, true);
	}

	@Override
	public boolean exists(Path campaign)
	{
		return inventories.containsKey(campaign);
	}

	@Override
	public void remove(Path base, Path campaign) throws IOException
	{
		if (removalDenied)
			throw new AccessDeniedException(campaign.resolve("worktree").toString(), null, "Permission denied");
		inventories.remove(campaign);
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
		 * Creates a fork.
		 *
		 * @param campaign the campaign storage directory
		 * @param inventory the recorded files
		 * @param excludeAdded whether creation added the local exclude
		 * @param current whether the fork becomes the campaign's current fork; a prospective fork does not
		 */
		InMemoryFork(Path campaign, FileInventory inventory, boolean excludeAdded, boolean current)
		{
			this.campaign = campaign;
			this.excludeAdded = excludeAdded;
			this.inventory = inventory;
			this.workspace = new FakeWorkspacePort(campaign, inventory, root);
			if (current)
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
		public ForkView selectParticipants(Path base, Set<Path> participants) throws IOException
		{
			selections.add(Set.copyOf(participants));
			Map<Path, FileEntry> all = recorded.computeIfAbsent(campaign, _ -> new LinkedHashMap<>(inventory.entries()));
			Set<Path> creation = created.computeIfAbsent(campaign, _ -> Set.copyOf(inventory.entries().keySet()));
			Set<Path> missing = new LinkedHashSet<>(participants);
			missing.removeIf(all::containsKey);
			all.putAll(scan(Map.of(), missing).entries());

			Map<Path, FileEntry> held = new LinkedHashMap<>();
			for (Map.Entry<Path, FileEntry> entry : all.entrySet())
			{
				if (creation.contains(entry.getKey()) || participants.contains(entry.getKey()))
					held.put(entry.getKey(), entry.getValue());
			}
			return new InMemoryFork(campaign, new FileInventory(root, held), excludeAdded, true);
		}

		@Override
		public ForkView extend(Path base, Set<Path> participants) throws IOException
		{
			Map<Path, FileEntry> all = recorded.computeIfAbsent(campaign, _ -> new LinkedHashMap<>(inventory.entries()));
			Set<Path> missing = new LinkedHashSet<>(participants);
			missing.removeIf(all::containsKey);
			all.putAll(scan(Map.of(), missing).entries());
			return including(participants);
		}

		@Override
		public ForkView including(Set<Path> participants)
		{
			Map<Path, FileEntry> all = recorded.getOrDefault(campaign, Map.of());
			Map<Path, FileEntry> held = new LinkedHashMap<>(inventory.entries());
			for (Path participant : participants)
			{
				FileEntry entry = all.get(participant);
				if (entry != null)
					held.putIfAbsent(participant, entry);
			}
			return new InMemoryFork(campaign, new FileInventory(root, held), excludeAdded, false);
		}

		@Override
		public void adopt()
		{
			FileInventory current = inventories.get(campaign);
			if (current != null && !current.equals(inventory))
				versions.computeIfAbsent(campaign, _ -> new ArrayList<>()).add(current);
			inventories.put(campaign, inventory);
		}

		@Override
		public Optional<FileInventory> version(Sha256 identity)
		{
			List<FileInventory> candidates = new ArrayList<>(versions.getOrDefault(campaign, List.of()));
			candidates.add(inventory);
			FileInventory current = inventories.get(campaign);
			if (current != null)
				candidates.add(current);
			return candidates.stream().filter(candidate -> candidate.identity().equals(identity)).findFirst();
		}
	}
}
