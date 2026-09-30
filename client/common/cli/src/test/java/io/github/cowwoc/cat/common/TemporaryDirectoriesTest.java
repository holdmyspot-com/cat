package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.FileVisitResult.CONTINUE;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import org.testng.annotations.Test;

/**
 * Tests recursive removal of owned temporary directories.
 */
public final class TemporaryDirectoriesTest
{
	/**
	 * Creates the test fixture.
	 */
	public TemporaryDirectoriesTest()
	{
	}

	/**
	 * Verifies a file or directory removed after traversal starts does not abort cleanup.
	 *
	 * @throws IOException if fixture creation or deletion fails
	 */
	@Test
	public void ignoresDisappearingEntries() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-deletion-"));
			Path existing = Files.createFile(root.resolve("existing"));
			BasicFileAttributes attributes = Files.readAttributes(existing, BasicFileAttributes.class);
			Path missingFile = root.resolve("missing-file");
			Path missingDirectory = root.resolve("missing-directory");
			FileVisitor<Path> visitor = TemporaryDirectories.deletionVisitor();

			requireThat(visitor.visitFile(missingFile, attributes), "missingFile").isEqualTo(CONTINUE);
			requireThat(visitor.visitFileFailed(missingFile, new NoSuchFileException(missingFile.toString())),
				"fileDisappearedBeforeVisit").isEqualTo(CONTINUE);
			requireThat(visitor.postVisitDirectory(missingDirectory, null), "missingDirectory").isEqualTo(CONTINUE);
			requireThat(visitor.postVisitDirectory(missingDirectory,
				new NoSuchFileException(missingDirectory.toString())), "directoryDisappearedDuringVisit").
				isEqualTo(CONTINUE);
		}
	}

	/**
	 * Verifies traversal failures unrelated to missing entries remain visible to the caller.
	 *
	 * @throws IOException if a different traversal error is correctly propagated
	 */
	@Test(expectedExceptions = AccessDeniedException.class)
	public void propagatesOtherTraversalFailures() throws IOException
	{
		Path directory = Path.of("inaccessible");
		TemporaryDirectories.deletionVisitor().visitFileFailed(directory,
			new AccessDeniedException(directory.toString()));
	}

	/**
	 * Verifies recursive cleanup removes an ordinary nested tree and accepts a missing root.
	 *
	 * @throws IOException if deletion fails
	 */
	@Test
	public void deletesNestedTreeAndAlreadyMissingRoot() throws IOException
	{
		Path root = Files.createTempDirectory("cat-deletion-");
		Path nested = Files.createDirectories(root.resolve("nested"));
		Files.writeString(nested.resolve("file"), "content");

		TemporaryDirectories.deleteRecursively(root);
		TemporaryDirectories.deleteRecursively(root);

		requireThat(Files.exists(root), "rootExists").isFalse();
	}
}
