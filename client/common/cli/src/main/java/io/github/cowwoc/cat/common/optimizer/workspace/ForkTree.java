package io.github.cowwoc.cat.common.optimizer.workspace;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

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
	 * CAT's work directory and Git metadata are never copied. If the recording fails, no storage is left behind.
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
			return new ForkTree(directory, FileTree.scan(normalizedBase, empty.project, empty.external));
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
	 * Copies further paths into an existing tree. A path that the tree already holds, or that lies under a held
	 * directory, is left alone.
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
		return new ForkTree(directory, FileTree.scan(normalizedBase, project, external));
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
