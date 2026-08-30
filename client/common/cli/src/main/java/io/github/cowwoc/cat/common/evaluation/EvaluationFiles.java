package io.github.cowwoc.cat.common.evaluation;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Copies evaluator-owned snapshots and fixtures without accepting conflicting or unsupported filesystem entries.
 */
public final class EvaluationFiles
{
	/**
	 * Prevents construction of this static file-copying utility.
	 */
	private EvaluationFiles()
	{
	}

	/**
	 * Copies a file to a snapshot location, accepting a repeated declaration only when its bytes match.
	 *
	 * @param source the declared source file
	 * @param destination the owned snapshot location
	 * @throws IOException if the destination conflicts or copying fails
	 */
	public static void copyFileIfAbsentOrIdentical(Path source, Path destination) throws IOException
	{
		if (!Files.exists(destination, NOFOLLOW_LINKS))
		{
			Files.copy(source, destination);
			return;
		}
		if (!Files.isRegularFile(destination, NOFOLLOW_LINKS) || Files.mismatch(source, destination) != -1)
			throw new IOException("Suite snapshot has conflicting declarations for " + destination);
	}

	/**
	 * Copies a fixture directory so one evaluation cannot change another evaluation's initial state.
	 *
	 * @param fixture the persistent source fixture
	 * @param workspace the initially absent destination workspace
	 * @throws IOException if copying fails or the fixture contains an unsupported entry
	 */
	public static void copyFixture(Path fixture, Path workspace) throws IOException
	{
		Files.walkFileTree(fixture, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException
			{
				Files.createDirectories(workspace.resolve(fixture.relativize(directory)));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
			{
				if (!attributes.isRegularFile())
					throw new IOException("Test fixture contains an unsupported entry: " + file);
				copyFileIfAbsentOrIdentical(file, workspace.resolve(fixture.relativize(file)));
				return FileVisitResult.CONTINUE;
			}
		});
	}
}
