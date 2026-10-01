package io.github.cowwoc.cat.common.optimizer.workspace;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SymbolicLink;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
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
	private static final String FORK = "fork";
	private static final Path GIT_METADATA = Path.of(".git");
	private static final Path WORK_DIRECTORY = Path.of(".cat/work");
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
			Set<Path> omitted = Set.of(normalizedBase.resolve(GIT_METADATA), normalizedBase.resolve(WORK_DIRECTORY),
				normalizedCampaign);
			for (Path path : paths)
			{
				Path source;
				if (path.isAbsolute())
					source = path;
				else
					source = normalizedBase.resolve(path);
				if (Files.notExists(source, NOFOLLOW_LINKS))
				{
					throw new WorkspaceException("The participating path " + path + " does not exist, so it cannot be " +
						"recorded in the fork.");
				}
				FileTree.copy(source, FileTree.locate(empty.project, empty.external, path), omitted);
			}
			ForkTree recorded = new ForkTree(directory, FileTree.scan(normalizedBase, empty.project, empty.external));
			return recorded.recordLinkTargets(normalizedBase, absolutePaths(paths), Set.of(), omitted);
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
	 * @return the tree, rescanned
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if a path cannot be recorded
	 * @throws WorkspaceException if a path does not exist
	 * @throws IOException if the files cannot be copied
	 */
	ForkTree extend(Path base, Set<Path> paths) throws IOException
	{
		requireThat(base, "base").isNotNull();
		requireThat(paths, "paths").isNotNull();
		Path normalizedBase = base.toAbsolutePath().normalize();
		for (Path path : paths)
			requireRecordable(normalizedBase, path);
		Set<Path> omitted = Set.of(normalizedBase.resolve(GIT_METADATA), normalizedBase.resolve(WORK_DIRECTORY),
			directory.getParent());
		for (Path path : paths)
		{
			if (inventory.entries().keySet().stream().anyMatch(key -> key.startsWith(path)))
				continue;
			Path source;
			if (path.isAbsolute())
				source = path;
			else
				source = normalizedBase.resolve(path);
			if (Files.notExists(source, NOFOLLOW_LINKS))
			{
				throw new WorkspaceException("The participating path " + path + " does not exist, so it cannot be " +
					"recorded in the fork.");
			}
			FileTree.copy(source, FileTree.locate(project, external, path), omitted);
		}
		ForkTree extended = new ForkTree(directory, FileTree.scan(normalizedBase, project, external));
		return extended.recordLinkTargets(normalizedBase, absolutePaths(paths), inventory.entries().keySet(), omitted);
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
	 * @param omitted absolute source paths whose subtrees are never copied
	 * @return the tree, rescanned
	 * @throws IOException if a target cannot be copied
	 */
	private ForkTree recordLinkTargets(Path base, Set<Path> copied, Set<Path> previous, Set<Path> omitted)
		throws IOException
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
		if (isNeverRecorded(path))
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
	 * Returns the directory that holds the tree.
	 *
	 * @return the directory
	 */
	Path directory()
	{
		return directory;
	}

	/**
	 * Requires that a candidate is an overlay on this tree's fork.
	 *
	 * @param candidate the candidate
	 * @throws IllegalArgumentException if the candidate's fork differs from this tree's inventory
	 */
	void requireSameFork(Overlay candidate)
	{
		if (!candidate.fork().equals(inventory))
			throw new IllegalArgumentException("The candidate is an overlay on a different fork than this workspace.");
	}

	/**
	 * Indicates whether a path lies in Git metadata or CAT's work directory, which a fork never records.
	 *
	 * @param path a path relative to the project directory
	 * @return {@code true} if the fork never records the path
	 */
	static boolean isNeverRecorded(Path path)
	{
		return path.startsWith(GIT_METADATA) || path.startsWith(WORK_DIRECTORY);
	}

	@Override
	public String toString()
	{
		return "ForkTree[" + directory + "]";
	}
}
