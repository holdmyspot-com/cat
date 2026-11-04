package com.holdmyspot.cat.common.updater.engine.test;

import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.updater.engine.LinkSafeDeletion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests that uninstall's deletion never deletes anything that a link inside or above the deleted tree points to.
 */
public final class LinkSafeDeletionTest
{
	/**
	 * Creates the test.
	 */
	public LinkSafeDeletionTest()
	{
	}

	/**
	 * A symbolic link inside the deleted tree is deleted as an entry, and the directory it points to is kept.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 */
	@Test
	public void deletesInnerLinkWithoutFollowingIt() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("deletion-inner-")).toRealPath();
			Path home = Files.createDirectory(root.resolve("home"));
			Path outside = Files.createDirectory(root.resolve("outside"));
			Files.writeString(outside.resolve("keep"), "user file");
			Path tree = Files.createDirectories(home.resolve("plugin/nested"));
			Files.writeString(tree.resolve("file"), "plugin file");
			Files.createSymbolicLink(tree.resolve("link"), outside);

			LinkSafeDeletion.delete(home.resolve("plugin"), home);

			requireThat(Files.exists(home.resolve("plugin"), NOFOLLOW_LINKS), "plugin").isFalse();
			requireThat(Files.readString(outside.resolve("keep")), "kept").isEqualTo("user file");
		}
	}

	/**
	 * A path that is itself a symbolic link, or that is reached through a linked ancestor, is refused and nothing is
	 * deleted.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 */
	@Test
	public void refusesLinkedPathAndAncestor() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("deletion-refused-")).toRealPath();
			Path home = Files.createDirectory(root.resolve("home"));
			Path outside = Files.createDirectories(root.resolve("outside/plugin"));
			Files.writeString(outside.resolve("keep"), "user file");
			Files.createSymbolicLink(home.resolve("plugin"), outside);
			Files.createSymbolicLink(home.resolve("parent"), outside.getParent());

			for (Path path : new Path[]{home.resolve("plugin"), home.resolve("parent/plugin")})
			{
				try
				{
					LinkSafeDeletion.delete(path, home);
					throw new AssertionError("The linked path was deleted: " + path);
				}
				catch (LinkSafeDeletion.Refused e)
				{
					requireThat(e.getMessage(), "message").contains(path.toString());
				}
			}
			requireThat(Files.readString(outside.resolve("keep")), "kept").isEqualTo("user file");
			requireThat(Files.isSymbolicLink(home.resolve("plugin")), "linkKept").isTrue();
		}
	}

	/**
	 * A missing path is already deleted.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void acceptsMissingPath() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = Files.createDirectory(cleanup.add(testDirectory("deletion-missing-")).toRealPath().
				resolve("home"));

			LinkSafeDeletion.delete(home.resolve("absent/plugin"), home);

			requireThat(Files.exists(home, NOFOLLOW_LINKS), "home").isTrue();
		}
	}
}
