package io.github.cowwoc.cat.common.optimizer.workspace;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.cowwoc.cat.common.PortableFiles;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SymbolicLink;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.InventoryScanner;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.OverlayEntry.Write;
import io.github.cowwoc.cat.common.secrets.ForkTreeAccess;
import io.github.cowwoc.cat.common.secrets.ForkTreeSecrets;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The immutable copy of a fork's files in campaign storage.
 * <p>
 * The tree holds the bytes, modes and links of every path that the fork records. Files outside the project are
 * stored under their absolute paths, minus the file-system root. Later edits to the project or to an external file
 * never change the tree, so every candidate is an exact snapshot of the fork plus an overlay.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class ForkTree
{
	static
	{
		ForkTreeSecrets.setAccess(new ForkTreeAccess()
		{
			@Override
			public Path physical(ForkTree tree, Path key)
			{
				return tree.physical(key);
			}
		});
	}

	private static final String FORK = "fork";
	private final Path directory;
	private final Path project;
	private final Path external;
	private final FileInventory inventory;

	/**
	 * Creates a fork tree.
	 *
	 * @param directory the directory that holds the tree
	 * @param inventory the tree's inventory
	 */
	private ForkTree(Path directory, FileInventory inventory)
	{
		this.directory = directory;
		this.project = directory.resolve("project");
		this.external = directory.resolve("external");
		this.inventory = inventory;
	}

	/**
	 * Copies paths from a project into campaign storage and records them.
	 * <p>
	 * The existing targets outside the project that the recorded links reach are recorded too, and so are the targets
	 * that their links reach in turn. CAT's work directory and Git metadata are never copied. If the recording fails,
	 * no storage is left behind.
	 *
	 * @param base the project directory
	 * @param campaign the campaign storage directory, which this method creates if needed
	 * @param paths the paths to record: a path relative to {@code base} for a file, link or directory inside the
	 *   project, or an absolute path outside the project for an external file or directory
	 * @return the recorded tree
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if a relative path is not normalized or names Git metadata or CAT's work
	 *   directory, or if an absolute path lies inside {@code base}
	 * @throws WorkspaceException if a path does not exist
	 * @throws IOException if the files cannot be copied or the campaign already holds a fork tree
	 */
	public static ForkTree record(Path base, Path campaign, Set<Path> paths) throws IOException
	{
		requireThat(base, "base").isNotNull();
		requireThat(campaign, "campaign").isNotNull();
		requireThat(paths, "paths").isNotNull();
		Path normalizedBase = base.toAbsolutePath().normalize();
		Path normalizedCampaign = campaign.toAbsolutePath().normalize();
		for (Path path : paths)
			requireRecordable(normalizedBase, path);

		Path directory = normalizedCampaign.resolve(FORK);
		Files.createDirectories(normalizedCampaign);
		Files.createDirectory(directory);
		ForkTree empty = new ForkTree(directory, null);
		try
		{
			Files.createDirectories(empty.project);
			Files.createDirectories(empty.external);
			Predicate<Path> omitted = omittedSources(normalizedBase, normalizedCampaign);
			empty.copyPaths(normalizedBase, paths, omitted);
			ForkTree recorded = new ForkTree(directory, FileTree.scan(normalizedBase, empty.project, empty.external));
			return recorded.recordLinkTargets(normalizedBase, absolutePaths(paths), Set.of(), omitted, _ ->
			{
			});
		}
		catch (IOException | RuntimeException e)
		{
			try
			{
				TemporaryDirectories.deleteRecursively(directory);
			}
			catch (IOException suppressed)
			{
				e.addSuppressed(suppressed);
			}
			throw e;
		}
	}

	/**
	 * Copies further paths into an existing tree, and the targets outside the project that their links reach, as
	 * {@link #record(Path, Path, Set)} does. A path that the tree already holds, or that lies under a held directory,
	 * is left alone.
	 *
	 * @param base the project directory that the tree was recorded from
	 * @param paths the paths to record, named as in {@link #record(Path, Path, Set)}
	 * @param targets is told each target outside the project before it is copied
	 * @return the tree, rescanned
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if a path cannot be recorded
	 * @throws WorkspaceException if a path does not exist
	 * @throws IOException if the files cannot be copied
	 */
	ForkTree extend(Path base, Set<Path> paths, TargetListener targets) throws IOException
	{
		requireExtensible(base, paths);
		Path normalizedBase = base.toAbsolutePath().normalize();
		Predicate<Path> omitted = omittedSources(normalizedBase, directory.getParent());
		copyPaths(normalizedBase, paths.stream().filter(path -> !inventory.holds(path)).collect(Collectors.toSet()),
			omitted);
		ForkTree extended = new ForkTree(directory, FileTree.scan(normalizedBase, project, external));
		return extended.recordLinkTargets(normalizedBase, absolutePaths(paths), inventory.entries().keySet(), omitted,
			targets);
	}

	/**
	 * Requires that {@link #extend(Path, Set)} can record paths: each must be a recordable path, and each that the tree
	 * does not hold yet must exist.
	 *
	 * @param base the project directory that the tree was recorded from
	 * @param paths the paths to record, named as in {@link #record(Path, Path, Set)}
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if a path cannot be recorded
	 * @throws WorkspaceException if a path that the tree does not hold does not exist
	 */
	void requireExtensible(Path base, Set<Path> paths) throws WorkspaceException
	{
		requireThat(base, "base").isNotNull();
		requireThat(paths, "paths").isNotNull();
		Path normalizedBase = base.toAbsolutePath().normalize();
		for (Path path : paths)
		{
			requireRecordable(normalizedBase, path);
			if (!inventory.holds(path))
				requireExists(normalizedBase, path);
		}
	}

	/**
	 * Returns this tree with only some of its entries. Storage stays unchanged, so a later {@link #extend} still sees
	 * every copied path.
	 *
	 * @param kept accepts the keys of the entries to keep
	 * @return the restricted tree
	 */
	ForkTree restrict(Predicate<Path> kept)
	{
		Map<Path, FileEntry> entries = new TreeMap<>();
		for (Map.Entry<Path, FileEntry> entry : inventory.entries().entrySet())
		{
			if (kept.test(entry.getKey()))
				entries.put(entry.getKey(), entry.getValue());
		}
		return new ForkTree(directory, new FileInventory(inventory.root(), entries));
	}

	/**
	 * Returns this tree with only the entries that a predicate keeps and the entries that their symbolic links reach,
	 * in turn. Storage stays unchanged.
	 *
	 * @param kept accepts the keys of the entries to keep
	 * @return the restricted tree
	 */
	ForkTree reachableFrom(Predicate<Path> kept)
	{
		Map<Path, FileEntry> entries = new TreeMap<>();
		Deque<Path> links = new ArrayDeque<>();
		for (Map.Entry<Path, FileEntry> entry : inventory.entries().entrySet())
		{
			if (kept.test(entry.getKey()))
				hold(entry, entries, links);
		}
		while (!links.isEmpty())
		{
			Path link = links.removeFirst();
			SymbolicLink value = (SymbolicLink) entries.get(link);
			Path root = inventory.root();
			Path destination = root.resolve(link).getParent().resolve(value.target()).normalize();
			if (destination.startsWith(root))
				destination = root.relativize(destination);
			for (Map.Entry<Path, FileEntry> entry : inventory.entries().entrySet())
			{
				Path key = entry.getKey();
				if (entries.containsKey(key))
					continue;
				// The links on the destination's path, and every entry at or below it, are needed to reach it
				if (key.startsWith(destination) ||
					(destination.startsWith(key) && entry.getValue() instanceof SymbolicLink))
				{
					hold(entry, entries, links);
				}
			}
		}
		return new ForkTree(directory, new FileInventory(inventory.root(), entries));
	}

	/**
	 * Adds an entry to the entries that a restricted tree holds.
	 *
	 * @param entry the entry
	 * @param entries the held entries
	 * @param links the held links whose destinations are still to be followed
	 */
	private static void hold(Map.Entry<Path, FileEntry> entry, Map<Path, FileEntry> entries, Deque<Path> links)
	{
		entries.put(entry.getKey(), entry.getValue());
		if (entry.getValue() instanceof SymbolicLink)
			links.add(entry.getKey());
	}

	/**
	 * Copies paths from a project into this tree's storage.
	 *
	 * @param base the normalized project directory
	 * @param paths the paths to copy, named as in {@link #record(Path, Path, Set)}
	 * @param omitted accepts the absolute source paths whose subtrees are never copied
	 * @throws WorkspaceException if a path does not exist
	 * @throws IOException if the files cannot be copied
	 */
	private void copyPaths(Path base, Set<Path> paths, Predicate<Path> omitted) throws IOException
	{
		for (Path path : paths)
		{
			Path source = requireExists(base, path);
			FileTree.copy(source, FileTree.locate(project, external, path), omitted);
		}
	}

	/**
	 * Requires that a path to record exists.
	 *
	 * @param base the normalized project directory
	 * @param path the path, named as in {@link #record(Path, Path, Set)}
	 * @return the path's location
	 * @throws WorkspaceException if the path does not exist
	 */
	private static Path requireExists(Path base, Path path) throws WorkspaceException
	{
		Path source;
		if (path.isAbsolute())
			source = path;
		else
			source = base.resolve(path);
		if (Files.notExists(source, NOFOLLOW_LINKS))
		{
			throw new WorkspaceException("The participating path " + path + " does not exist, so it cannot be " +
				"recorded in the fork.");
		}
		return source;
	}

	/**
	 * Returns the test for the absolute source paths that recording never copies: the project's Git metadata and
	 * CAT's work directory, which {@link InventoryScanner#isOmitted(Path)} names, and the campaign storage directory.
	 *
	 * @param base the normalized project directory
	 * @param campaign the normalized campaign storage directory
	 * @return the test, which accepts each such path and every path below it
	 */
	private static Predicate<Path> omittedSources(Path base, Path campaign)
	{
		return source -> source.startsWith(campaign) ||
			(source.startsWith(base) && InventoryScanner.isOmitted(base.relativize(source)));
	}

	/**
	 * Returns the absolute paths among the paths to record, which name files and directories outside the project.
	 *
	 * @param paths the paths to record
	 * @return the absolute paths
	 */
	private static Set<Path> absolutePaths(Set<Path> paths)
	{
		return paths.stream().filter(Path::isAbsolute).collect(Collectors.toSet());
	}

	/**
	 * Copies the existing targets outside the project that the tree's links reach but the tree does not hold, then
	 * repeats for the links that those targets hold or are, until no link reaches another such target. Without the
	 * copies, staging would report a link that leaves the project as a link to a missing target, because staging reads
	 * only the tree.
	 * <p>
	 * A link whose links form a cycle, whose target lies inside the project or does not exist, or whose target cannot
	 * be read is left alone, so staging still reports it. A target that contains the project is left alone as well,
	 * because copying it would copy the project into its own fork.
	 *
	 * @param base the normalized project directory
	 * @param copied the absolute paths that this recording copied whole
	 * @param previous the keys that the tree held before this recording; a target with a key at or below it is held
	 * @param omitted accepts the absolute source paths whose subtrees are never copied
	 * @param listener is told each target before it is copied
	 * @return the tree, rescanned
	 * @throws IOException if a target cannot be copied
	 */
	private ForkTree recordLinkTargets(Path base, Set<Path> copied, Set<Path> previous, Predicate<Path> omitted,
		TargetListener listener) throws IOException
	{
		Set<Path> roots = new HashSet<>(copied);
		Set<Path> unreadable = new HashSet<>();
		ForkTree current = this;
		while (true)
		{
			Set<Path> targets = current.unheldTargets(base, roots, previous, unreadable);
			if (targets.isEmpty())
				return current;
			for (Path target : targets)
			{
				Path copy = FileTree.locate(project, external, target);
				listener.copying(target);
				try
				{
					FileTree.copy(target, copy, omitted);
					roots.add(target);
				}
				catch (AccessDeniedException _)
				{
					// An unreadable target stays unrecorded, so staging reports its link instead of the fork failing.
					// Deleting the partial copy also deletes the paths below the target that were copied earlier, so copy
					// those again.
					TemporaryDirectories.deleteRecursively(copy);
					for (Path root : roots)
					{
						if (root.startsWith(target))
							FileTree.copy(root, FileTree.locate(project, external, root), omitted);
					}
					unreadable.add(target);
				}
			}
			current = new ForkTree(directory, FileTree.scan(base, project, external));
		}
	}

	/**
	 * Returns the existing targets outside the project that this tree's links reach but the tree does not hold.
	 *
	 * @param base the normalized project directory
	 * @param roots the absolute paths that the current recording copied whole
	 * @param previous the keys that the tree held before the current recording
	 * @param unreadable the targets that could not be read
	 * @return the targets, in path order
	 */
	private SortedSet<Path> unheldTargets(Path base, Set<Path> roots, Set<Path> previous, Set<Path> unreadable)
	{
		SortedSet<Path> targets = new TreeSet<>();
		for (Map.Entry<Path, FileEntry> entry : inventory.entries().entrySet())
		{
			if (!(entry.getValue() instanceof SymbolicLink))
				continue;
			Optional<Path> resolved = inventory.resolve(entry.getKey());
			if (resolved.isEmpty())
				continue;
			Path target = resolved.get();
			if (!target.isAbsolute() || base.startsWith(target) || unreadable.contains(target) ||
				roots.stream().anyMatch(target::startsWith) || previous.stream().anyMatch(key -> key.startsWith(target)))
			{
				continue;
			}
			if (Files.exists(target, NOFOLLOW_LINKS))
				targets.add(target);
		}
		return targets;
	}

	/**
	 * Requires that a path can be recorded.
	 *
	 * @param base the project directory
	 * @param path the path
	 */
	private static void requireRecordable(Path base, Path path)
	{
		requireThat(path, "path").isNotNull();
		requireThat(path, "path").isEqualTo(path.normalize(), "normalized");
		requireThat(path.toString(), "path").isNotEmpty();
		if (path.isAbsolute())
		{
			if (path.startsWith(base))
			{
				throw new IllegalArgumentException("The external path " + path + " lies inside the project directory " +
					base + ", so name it relative to the project.");
			}
			return;
		}
		requireThat(path.getName(0).toString(), "path.getName(0)").isNotEqualTo("..");
		if (InventoryScanner.isOmitted(path))
		{
			throw new IllegalArgumentException("The path " + path + " lies in Git metadata or CAT's work directory, " +
				"which a fork never records.");
		}
	}

	/**
	 * Opens the tree that {@link #record(Path, Path, Set)} stored for a campaign.
	 *
	 * @param base the project directory that the tree was recorded from
	 * @param campaign the campaign storage directory
	 * @return the tree
	 * @throws NullPointerException if any of the arguments are null
	 * @throws WorkspaceException if the campaign holds no fork tree
	 * @throws IOException if the tree cannot be read
	 */
	public static ForkTree open(Path base, Path campaign) throws IOException
	{
		requireThat(base, "base").isNotNull();
		requireThat(campaign, "campaign").isNotNull();
		Path directory = campaign.toAbsolutePath().normalize().resolve(FORK);
		if (!Files.isDirectory(directory, NOFOLLOW_LINKS))
			throw new WorkspaceException("The campaign directory " + campaign + " holds no fork.");
		ForkTree empty = new ForkTree(directory, null);
		return new ForkTree(directory, FileTree.scan(base.toAbsolutePath().normalize(), empty.project, empty.external));
	}

	/**
	 * Returns the recorded files, modes and links.
	 *
	 * @return the inventory, rooted at the project directory that the tree was recorded from, keyed by project-relative
	 *   paths and by absolute paths for external files
	 */
	public FileInventory inventory()
	{
		return inventory;
	}

	/**
	 * Returns where the tree stores a recorded file.
	 *
	 * @param key a key of {@link #inventory()}
	 * @return the file that holds the recorded bytes
	 */
	Path physical(Path key)
	{
		return FileTree.locate(project, external, key);
	}

	/**
	 * Reads a regular file's bytes from its overlay or the immutable fork storage.
	 *
	 * @param candidate the saved candidate on this fork
	 * @param key the resolved regular-file path
	 * @return its recorded content
	 * @throws IOException if the recorded file cannot be read
	 */
	FileContent readFile(Overlay candidate, Path key) throws IOException
	{
		if (candidate.entries().get(key) instanceof Write write)
			return write.content();
		return new FileContent(Files.readAllBytes(physical(key)));
	}

	/**
	 * Writes a regular file of a candidate: the overlay's content if the overlay writes the file, otherwise the
	 * recorded content, with the mode that the candidate's snapshot records.
	 *
	 * @param candidate an overlay on this tree's fork
	 * @param key the file's key in the candidate's snapshot
	 * @param file the snapshot's entry at {@code key}
	 * @param location the file to create, whose parent directory exists
	 * @throws IOException if the file cannot be written
	 */
	void writeFile(Overlay candidate, Path key, RegularFile file, Path location) throws IOException
	{
		Files.write(location, readFile(candidate, key).bytes());
		PortableFiles.setMode(location, file.permissions());
	}

	/**
	 * Returns the directory that holds the tree.
	 *
	 * @return the directory
	 */
	Path directory()
	{
		return directory;
	}

	/**
	 * Requires that a candidate is an overlay on this tree's fork or on an earlier fork that this tree holds, as the
	 * candidates of a campaign's earlier lineages are after a confirmed revision moved it onto a larger fork.
	 *
	 * @param candidate the candidate
	 * @throws IllegalArgumentException if this tree's inventory lacks a file of the candidate's fork or records it
	 *   differently
	 */
	void requireSameFork(Overlay candidate)
	{
		for (Map.Entry<Path, FileEntry> entry : candidate.fork().entries().entrySet())
		{
			if (!inventory.entry(entry.getKey()).equals(Optional.of(entry.getValue())))
			{
				throw new IllegalArgumentException("The candidate is an overlay on a different fork than this " +
					"workspace.");
			}
		}
	}

	@Override
	public String toString()
	{
		return "ForkTree[" + directory + "]";
	}

	/**
	 * Is told each target outside the project that a recording is about to copy.
	 */
	@FunctionalInterface
	interface TargetListener
	{
		/**
		 * Called before a target is copied.
		 *
		 * @param target the absolute path of the target
		 * @throws IOException if the target cannot be noted
		 */
		void copying(Path target) throws IOException;
	}
}
