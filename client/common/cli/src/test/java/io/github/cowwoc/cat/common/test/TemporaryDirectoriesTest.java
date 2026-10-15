package io.github.cowwoc.cat.common.test;

import io.github.cowwoc.cat.common.PortableFiles;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies that {@link TemporaryDirectories} deletes symbolic links rather than the files they point to, and deletes
 * read-only files.
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
	 * Verifies that a symbolic link whose target does not exist is deleted rather than skipped as missing.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void deletesDanglingLink() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("temporary-directories-"));
			Path link = Files.createSymbolicLink(root.resolve("link"), root.resolve("missing"));

			TemporaryDirectories.deleteRecursively(link);

			requireThat(Files.exists(link, LinkOption.NOFOLLOW_LINKS), "linkExists").isFalse();
		}
	}

	/**
	 * Verifies that deleting a directory that contains a symbolic link to another directory deletes the link and leaves
	 * the other directory's files in place.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void keepsLinkedDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("temporary-directories-"));
			Path outside = Files.createDirectories(root.resolve("outside"));
			Path kept = Files.writeString(outside.resolve("kept.txt"), "kept");
			Path deleted = Files.createDirectories(root.resolve("deleted"));
			Files.createSymbolicLink(deleted.resolve("link"), outside);

			TemporaryDirectories.deleteRecursively(deleted);

			requireThat(Files.exists(deleted, LinkOption.NOFOLLOW_LINKS), "deletedExists").isFalse();
			requireThat(Files.readString(kept), "kept").isEqualTo("kept");
		}
	}

	/**
	 * Verifies that a read-only file, such as a Git object file, is deleted on every file system, including Windows,
	 * which refuses to delete a file whose read-only attribute is set.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void deletesReadOnlyFile() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("temporary-directories-"));
			Path deleted = Files.createDirectories(root.resolve("deleted"));
			Path file = Files.writeString(deleted.resolve("object"), "content");
			if (PortableFiles.hasModes(file))
				PortableFiles.setMode(file, PosixFilePermissions.fromString("r--r--r--"));
			else
				Files.setAttribute(file, "dos:readonly", true);

			TemporaryDirectories.deleteRecursively(deleted);

			requireThat(Files.exists(deleted, LinkOption.NOFOLLOW_LINKS), "deletedExists").isFalse();
		}
	}
}
