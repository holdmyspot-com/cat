package io.github.cowwoc.cat.common.optimizer.inventory;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SpecialFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SymbolicLink;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Records the files of a project directory and of the external paths it reaches, without following symbolic links.
 * <p>
 * The scan records every regular file with its content hash and mode, every symbolic link with its stored destination,
 * and every other non-directory entry as a special file. Files that Git ignores are recorded too. The scan omits the
 * Git metadata at {@code .git} and CAT's work directory at {@code .cat/work}, which change on every run.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class InventoryScanner
{
	/**
	 * The project paths that every scan omits: Git metadata and CAT's work directory.
	 */
	private static final List<Path> OMITTED = List.of(Path.of(".git"), Path.of(".cat/work"));
	private static final int BUFFER_SIZE = 8192;

	/**
	 * Prevents instantiation.
	 */
	private InventoryScanner()
	{
	}

	/**
	 * Scans a project directory and external paths.
	 *
	 * @param root the project directory
	 * @param externalPaths the absolute paths of the files and directories outside the project that the fork reaches,
	 *                      such as the destinations of the project's symbolic links; a path that does not exist is
	 *                      omitted
	 * @return the inventory, rooted at the absolute, normalized {@code root}
	 * @throws NullPointerException if any of the arguments are null or {@code externalPaths} contains null
	 * @throws IllegalArgumentException if {@code root} is not a directory, or if an external path is relative or lies
	 *                                  inside {@code root}
	 * @throws IOException if a file cannot be read
	 */
	public static FileInventory scan(Path root, Set<Path> externalPaths) throws IOException
	{
		requireThat(root, "root").isNotNull();
		requireThat(externalPaths, "externalPaths").isNotNull();
		Path project = root.toAbsolutePath().normalize();
		if (!Files.isDirectory(project, LinkOption.NOFOLLOW_LINKS))
			throw new IllegalArgumentException("The project directory " + project + " is not a directory.");
		for (Path externalPath : externalPaths)
		{
			requireThat(externalPath, "externalPath").isAbsolute();
			if (externalPath.normalize().startsWith(project))
			{
				throw new IllegalArgumentException("The external path " + externalPath + " lies inside the project " +
					"directory " + project + ".");
			}
		}

		Map<Path, FileEntry> entries = new HashMap<>();
		walk(project, project, entries);
		for (Path externalPath : externalPaths)
		{
			Path normalized = externalPath.normalize();
			if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS))
				walk(project, normalized, entries);
		}
		return new FileInventory(project, entries);
	}

	/**
	 * Records every non-directory entry at or below a path.
	 *
	 * @param project the absolute, normalized project directory
	 * @param start the absolute, normalized path to walk
	 * @param entries the map to record entries into, keyed by their inventory path
	 * @throws IOException if a file cannot be read
	 */
	private static void walk(Path project, Path start, Map<Path, FileEntry> entries) throws IOException
	{
		Files.walkFileTree(start, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
			{
				if (isOmitted(FileInventory.keyOf(project, dir)))
					return FileVisitResult.SKIP_SUBTREE;
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
			{
				Path key = FileInventory.keyOf(project, file);
				if (!isOmitted(key))
					entries.put(key, entryOf(file, attrs));
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/**
	 * Indicates whether a path lies in the project's Git metadata at {@code .git} or CAT's work directory at
	 * {@code .cat/work}, which every scan omits and a fork never records.
	 *
	 * @param path a path relative to the project directory, or an absolute path outside it
	 * @return {@code true} if {@code path} is or lies below an omitted directory
	 * @throws NullPointerException if {@code path} is null
	 */
	public static boolean isOmitted(Path path)
	{
		requireThat(path, "path").isNotNull();
		return OMITTED.stream().anyMatch(path::startsWith);
	}

	/**
	 * Records one non-directory entry without following symbolic links.
	 *
	 * @param path the entry's absolute path
	 * @param attrs the entry's attributes, read without following symbolic links
	 * @return the entry
	 * @throws IOException if the entry cannot be read
	 */
	private static FileEntry entryOf(Path path, BasicFileAttributes attrs) throws IOException
	{
		if (attrs.isSymbolicLink())
			return new SymbolicLink(Files.readSymbolicLink(path));
		if (!attrs.isRegularFile())
			return new SpecialFile();
		MessageDigest digest = Sha256.newDigest();
		byte[] buffer = new byte[BUFFER_SIZE];
		try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS))
		{
			while (true)
			{
				int count = in.read(buffer);
				if (count == -1)
					break;
				digest.update(buffer, 0, count);
			}
		}
		return new RegularFile(Sha256.of(digest), Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
	}
}
