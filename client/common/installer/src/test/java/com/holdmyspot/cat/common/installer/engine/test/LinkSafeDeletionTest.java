package com.holdmyspot.cat.common.installer.engine.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.installer.engine.LinkSafeDeletion;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests that uninstall's deletion never deletes anything that a link inside or above the deleted tree points to.
 */
public final class LinkSafeDeletionTest
{
	/**
	 * Waits for nothing.
	 */
	private static final Waiting NO_WAIT = interval -> () ->
	{
	};

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

			LinkSafeDeletion.delete(home.resolve("plugin"), home, NO_WAIT);

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
					LinkSafeDeletion.delete(path, home, NO_WAIT);
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

			LinkSafeDeletion.delete(home.resolve("absent/plugin"), home, NO_WAIT);

			requireThat(Files.exists(home, NOFOLLOW_LINKS), "home").isTrue();
		}
	}

	/**
	 * A file that another program holds open for a moment does not make the deletion fail: the deletion waits and
	 * succeeds once the file is released.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 */
	@Test
	public void deletesAfterBriefLockIsReleased() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = Files.createDirectory(cleanup.add(testDirectory("deletion-brief-")).toRealPath().
				resolve("home"));
			Path folder = Files.createDirectories(home.resolve("plugin/nested"));
			try (HeldFile held = HeldFile.open(folder))
			{
				boolean blocked = held.blocksDeletion();
				List<Duration> waits = new ArrayList<>();
				// The first wait is the moment at which the other program lets go
				Waiting releasing = interval -> () ->
				{
					waits.add(interval);
					held.release();
				};

				LinkSafeDeletion.delete(home.resolve("plugin"), home, releasing);

				requireThat(Files.exists(home.resolve("plugin"), NOFOLLOW_LINKS), "plugin").isFalse();
				// A superuser is not blocked by a folder's permissions, so nothing waits for it
				int expectedWaits = 0;
				if (blocked)
					expectedWaits = 1;
				requireThat(waits.size(), "waits.size()").isEqualTo(expectedWaits);
			}
		}
	}

	/**
	 * A file that stays open makes the deletion fail with the original error once the retry deadline has passed, and
	 * the folder is kept.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 */
	@Test
	public void failsWithOriginalErrorWhileLockIsHeld() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = Files.createDirectory(cleanup.add(testDirectory("deletion-held-")).toRealPath().
				resolve("home"));
			Path folder = Files.createDirectories(home.resolve("plugin/nested"));
			try (HeldFile held = HeldFile.open(folder))
			{
				if (!held.blocksDeletion())
					return;
				List<Duration> waits = new ArrayList<>();
				Waiting recording = interval -> () -> waits.add(interval);
				try
				{
					LinkSafeDeletion.delete(home.resolve("plugin"), home, recording);
					throw new AssertionError("The deletion succeeded although a file was held");
				}
				catch (AccessDeniedException | DirectoryNotEmptyException _)
				{
					requireThat(Files.exists(folder, NOFOLLOW_LINKS), "folder").isTrue();
					Duration total = waits.stream().reduce(Duration.ZERO, Duration::plus);
					requireThat(total, "total").isEqualTo(LinkSafeDeletion.RETRY_DEADLINE);
				}
			}
		}
	}

	/**
	 * A file that blocks the deletion of its folder, in the way that the OS blocks it. On Windows an open handle from
	 * {@link RandomAccessFile}, which does not share deletion, blocks it. On POSIX an open handle never blocks a
	 * deletion, so a folder that is not writable stands in: deleting an entry in it fails with the same
	 * {@link AccessDeniedException}.
	 */
	private static final class HeldFile implements AutoCloseable
	{
		private final Path folder;
		private final RandomAccessFile handle;

		/**
		 * Creates a held file.
		 *
		 * @param folder the folder that holds the file
		 * @param handle the open handle, or null if the folder was made unwritable instead
		 */
		private HeldFile(Path folder, RandomAccessFile handle)
		{
			this.folder = folder;
			this.handle = handle;
		}

		/**
		 * Creates a file in a folder and blocks its deletion.
		 *
		 * @param folder an existing folder
		 * @return the held file
		 * @throws IOException if the file cannot be created or held
		 */
		static HeldFile open(Path folder) throws IOException
		{
			Path file = folder.resolve("held");
			if (Platform.current().isWindows())
			{
				return new HeldFile(folder, new RandomAccessFile(file.toFile(), "rw"));
			}
			Files.writeString(file, "held");
			Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"));
			return new HeldFile(folder, null);
		}

		/**
		 * Returns whether the deletion is really blocked.
		 *
		 * @return true if the OS really blocks the deletion (a superuser is not blocked by a folder's permissions)
		 */
		boolean blocksDeletion()
		{
			return handle != null || !Files.isWritable(folder);
		}

		/**
		 * Lets go of the file, so that its folder can be deleted. Releasing twice, or after the folder was deleted, is
		 * harmless.
		 *
		 * @throws IOException if the file cannot be released
		 */
		void release() throws IOException
		{
			if (handle != null)
				handle.close();
			else if (Files.exists(folder, NOFOLLOW_LINKS))
				Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
		}

		@Override
		public void close() throws IOException
		{
			release();
		}
	}
}
