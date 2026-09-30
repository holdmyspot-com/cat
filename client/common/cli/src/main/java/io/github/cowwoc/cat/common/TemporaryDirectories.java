package io.github.cowwoc.cat.common;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns temporary directories created during one operation and recursively deletes them when closed.
 * <p>
 * Use this class in a try-with-resources block, adding each directory with {@link #add(Path)}.
 */
public final class TemporaryDirectories implements AutoCloseable
{
	private final List<Path> directories = new ArrayList<>();

	/**
	 * Creates an empty temporary-directory cleanup scope.
	 */
	public TemporaryDirectories()
	{
	}

	/**
	 * Adds a directly created temporary directory for removal when this scope closes.
	 *
	 * @param directory the directly created directory
	 * @return {@code directory}
	 */
	public Path add(Path directory)
	{
		assert directory != null;
		directories.add(directory);
		return directory;
	}

	/**
	 * Removes registered directories in reverse registration order.
	 *
	 * @throws IOException if a directory cannot be removed
	 */
	@Override
	public void close() throws IOException
	{
		for (int index = directories.size() - 1; index >= 0; --index)
			deleteRecursively(directories.get(index));
	}

	/**
	 * Deletes a directory and every file and directory below it.
	 *
	 * @param directory the directory to delete
	 * @throws IOException if the directory cannot be deleted
	 */
	public static void deleteRecursively(Path directory) throws IOException
	{
		assert directory != null;
		try
		{
			Files.walkFileTree(directory, deletionVisitor());
		}
		catch (NoSuchFileException _)
		{
			// The root may disappear between lookup and traversal.
		}
	}

	/**
	 * Deletes entries that still exist while preserving unrelated traversal failures.
	 *
	 * @return a visitor for one recursive deletion
	 */
	static FileVisitor<Path> deletionVisitor()
	{
		return new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
			{
				Files.deleteIfExists(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException
			{
				if (!(failure instanceof NoSuchFileException))
					throw failure;
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path visitedDirectory, IOException failure) throws IOException
			{
				if (failure != null && !(failure instanceof NoSuchFileException))
					throw failure;
				Files.deleteIfExists(visitedDirectory);
				return FileVisitResult.CONTINUE;
			}
		};
	}
}
