package io.github.cowwoc.cat.common.optimizer.workspace;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.InventoryScanner;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * File-system helpers that copy and scan trees without following symbolic links.
 */
final class FileTree
{
	/**
	 * Prevents instantiation.
	 */
	private FileTree()
	{
	}

	/**
	 * Copies a file, a link or a directory tree. Links are copied as links, and files that are neither regular nor
	 * links, such as pipes, are skipped.
	 *
	 * @param source the path to copy
	 * @param destination where the copy goes
	 * @param omitted accepts the absolute source paths whose subtrees are not copied
	 * @throws IOException if a file cannot be copied
	 */
	static void copy(Path source, Path destination, Predicate<Path> omitted) throws IOException
	{
		Files.walkFileTree(source, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException
			{
				if (omitted.test(directory))
					return FileVisitResult.SKIP_SUBTREE;
				Files.createDirectories(destination.resolve(source.relativize(directory)));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
			{
				if (omitted.test(file))
					return FileVisitResult.CONTINUE;
				Path copy = destination.resolve(source.relativize(file));
				Files.createDirectories(copy.getParent());
				if (attributes.isSymbolicLink())
				{
					Files.deleteIfExists(copy);
					Files.createSymbolicLink(copy, Files.readSymbolicLink(file));
				}
				else if (attributes.isRegularFile())
				{
					Files.copy(file, copy, REPLACE_EXISTING);
					Files.setPosixFilePermissions(copy, Files.getPosixFilePermissions(file, NOFOLLOW_LINKS));
				}
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/**
	 * Builds a directory under a temporary name beside it and renames it into place, so that no reader sees it half
	 * built. When a concurrent build of the same directory finishes first, its result is kept.
	 *
	 * @param directory the directory to build, which must not exist
	 * @param build writes the directory's contents into the empty directory that it receives
	 * @throws IOException if the directory cannot be built
	 */
	static void build(Path directory, DirectoryBuilder build) throws IOException
	{
		Path parent = Files.createDirectories(directory.getParent());
		Path partial = Files.createTempDirectory(parent, "partial-");
		try
		{
			build.writeTo(partial);
			try
			{
				Files.move(partial, directory, ATOMIC_MOVE);
			}
			catch (FileSystemException e)
			{
				// A concurrent request built the same directory first if it exists now
				if (!Files.isDirectory(directory))
					throw e;
			}
		}
		finally
		{
			TemporaryDirectories.deleteRecursively(partial);
		}
	}

	/**
	 * Writes the contents of a directory that {@link #build(Path, DirectoryBuilder)} renames into place.
	 */
	@FunctionalInterface
	interface DirectoryBuilder
	{
		/**
		 * Writes the contents.
		 *
		 * @param directory the empty directory to write into
		 * @throws IOException if a file cannot be written
		 */
		void writeTo(Path directory) throws IOException;
	}

	/**
	 * Scans a project directory and a directory that mirrors external files into one inventory.
	 *
	 * @param root the root that the inventory reports
	 * @param project the directory that holds the project's files
	 * @param external the directory that mirrors external files under their absolute paths, minus the file-system
	 *   root; it need not exist
	 * @return the inventory, which keys external files by their absolute paths
	 * @throws IOException if a directory cannot be read
	 */
	static FileInventory scan(Path root, Path project, Path external) throws IOException
	{
		Map<Path, FileEntry> entries = new HashMap<>(InventoryScanner.scan(project, Set.of()).entries());
		if (Files.isDirectory(external, NOFOLLOW_LINKS))
		{
			for (Map.Entry<Path, FileEntry> entry : InventoryScanner.scan(external, Set.of()).entries().entrySet())
				entries.put(root.getRoot().resolve(entry.getKey()), entry.getValue());
		}
		return new FileInventory(root, entries);
	}

	/**
	 * Returns where a mirror directory holds a key of an inventory.
	 *
	 * @param project the directory that holds the project's files
	 * @param external the directory that mirrors external files
	 * @param key a key of the inventory
	 * @return {@code project} resolved against a relative key, or {@code external} resolved against an absolute key
	 */
	static Path locate(Path project, Path external, Path key)
	{
		if (key.isAbsolute())
			return external.resolve(key.getRoot().relativize(key));
		return project.resolve(key);
	}
}
