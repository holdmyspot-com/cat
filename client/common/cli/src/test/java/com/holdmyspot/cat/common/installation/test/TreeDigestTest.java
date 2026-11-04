package com.holdmyspot.cat.common.installation.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.TreeDigest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests {@link TreeDigest}.
 */
public final class TreeDigestTest
{
	/**
	 * Two trees with the same files have the same digest, whatever order the files were created in.
	 *
	 * @throws IOException if a tree cannot be written or read
	 */
	@Test
	public void sameFilesHaveSameDigest() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path first = cleanup.add(Files.createTempDirectory("cat-tree-"));
			write(first, "a/one.txt", "one");
			write(first, "b/two.txt", "two");
			write(first, "three.txt", "three");
			Path second = cleanup.add(Files.createTempDirectory("cat-tree-"));
			write(second, "three.txt", "three");
			write(second, "b/two.txt", "two");
			write(second, "a/one.txt", "one");

			requireThat(TreeDigest.of(first), "first").isEqualTo(TreeDigest.of(second), "second");
		}
	}

	/**
	 * Changing one byte of one file changes the digest.
	 *
	 * @throws IOException if the tree cannot be written or read
	 */
	@Test
	public void changedByteChangesDigest() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path tree = cleanup.add(Files.createTempDirectory("cat-tree-"));
			write(tree, "a/one.txt", "one");
			write(tree, "two.txt", "two");
			String before = TreeDigest.of(tree);

			write(tree, "a/one.txt", "onf");

			requireThat(TreeDigest.of(tree), "after").isNotEqualTo(before, "before");
		}
	}

	/**
	 * A renamed file changes the digest, because the digest covers each file's path.
	 *
	 * @throws IOException if the tree cannot be written or read
	 */
	@Test
	public void renamedFileChangesDigest() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path tree = cleanup.add(Files.createTempDirectory("cat-tree-"));
			write(tree, "one.txt", "one");
			String before = TreeDigest.of(tree);

			Files.move(tree.resolve("one.txt"), tree.resolve("two.txt"));

			requireThat(TreeDigest.of(tree), "after").isNotEqualTo(before, "before");
		}
	}

	/**
	 * The excluded file's content does not affect the digest.
	 *
	 * @throws IOException if the tree cannot be written or read
	 */
	@Test
	public void excludedFileIsLeftOut() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path tree = cleanup.add(Files.createTempDirectory("cat-tree-"));
			write(tree, "one.txt", "one");
			Path excluded = write(tree, "conf/anchor", "first");
			String before = TreeDigest.of(tree, excluded);

			write(tree, "conf/anchor", "second");

			requireThat(TreeDigest.of(tree, excluded), "after").isEqualTo(before, "before");
		}
	}

	/**
	 * A missing tree is refused.
	 *
	 * @throws IOException if the tree is missing
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "The installed directory is missing: .*")
	public void refusesMissingTree() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("cat-tree-"));
			TreeDigest.of(parent.resolve("missing"));
		}
	}

	/**
	 * Writes a file below a tree, creating its parent directories.
	 *
	 * @param tree     the tree
	 * @param relative the file's path relative to the tree
	 * @param content  the file's content
	 * @return the file
	 * @throws IOException if the file cannot be written
	 */
	private static Path write(Path tree, String relative, String content) throws IOException
	{
		Path file = tree.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, UTF_8);
		return file;
	}
}
