package com.holdmyspot.cat.common.installer.engine.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.engine.UpdateResult;
import com.holdmyspot.cat.common.installer.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import org.testng.annotations.Test;

/**
 * Tests that install deletes the version folders that the harness no longer needs: it keeps the folder of the active
 * version, of the version that the active one replaced and of the running installer, and deletes the others on every
 * install, including one that finds the harness up to date.
 */
public final class OldVersionFoldersTest
{
	/**
	 * The migration registry of a release that registers no data migration.
	 */
	private static final String NO_MIGRATION = "# No migration\n";
	/**
	 * The version of a folder that an earlier install left behind.
	 */
	private static final String LEFT_BEHIND = Releases.pluginVersion("0.9");

	/**
	 * Creates the test.
	 */
	public OldVersionFoldersTest()
	{
	}

	/**
	 * After two updates, the version folder of the first version is deleted, and the folders of the active version
	 * and of the version that it replaced stay.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void deletesVersionsBeforePrevious() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("old-folders-updates-")), catalog());
			Path first = fixture.installActive("1.0");
			requireUpdated(fixture.install("v1.1"));

			UpdateResult result = fixture.install("v1.2");

			requireUpdated(result);
			requireThat(result.message(), "message").isEqualTo("CAT was updated.");
			requireThat(Files.exists(first, NOFOLLOW_LINKS), "first").isFalse();
			requireThat(fixture.versionFolder("1.1"), "previous").isDirectory();
			requireThat(fixture.versionFolder("1.2"), "active").isDirectory();
		}
	}

	/**
	 * An install that finds the harness up to date still deletes a version folder that an earlier install left
	 * behind, keeps the folder of the version that the active one replaced, and leaves alone a folder whose name
	 * names no plugin version.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void upToDateInstallKeepsPrevious() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("old-folders-up-to-date-")),
				catalog());
			Path previous = fixture.installActive("1.0");
			requireUpdated(fixture.install("v1.1"));
			Path leftBehind = leftBehind(fixture);
			Path local = Files.createDirectories(leftBehind.resolveSibling("local"));
			Files.writeString(local.resolve("file"), "developer copy");

			UpdateResult result = fixture.install("v1.1");

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UP_TO_DATE);
			requireThat(result.message(), "message").isEqualTo("CAT is up to date.");
			requireThat(Files.exists(leftBehind, NOFOLLOW_LINKS), "leftBehind").isFalse();
			requireThat(previous, "previous").isDirectory();
			requireThat(Files.readString(local.resolve("file")), "local").isEqualTo("developer copy");
		}
	}

	/**
	 * The version that the active one replaced is the one that the harness ran before the last update, whatever the
	 * order of the versions or the age of their folders: after a move back from 1.1 to 1.0 and an update to 1.2, the
	 * folder of 1.0 stays, even though it is the oldest, and the folder of 1.1 is deleted.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void previousIsReplacedVersion() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("old-folders-replaced-")), catalog());
			Path later = fixture.installActive("1.1");
			requireUpdated(fixture.install("v1.0"));
			Path earlier = fixture.versionFolder("1.0");
			Files.setLastModifiedTime(earlier, FileTime.from(Instant.parse("2000-01-01T00:00:00Z")));

			requireUpdated(fixture.install("v1.2"));

			requireThat(earlier, "replaced").isDirectory();
			requireThat(Files.exists(later, NOFOLLOW_LINKS), "later").isFalse();
		}
	}

	/**
	 * The version folder that the running installer belongs to is never deleted, even when it is neither the active
	 * version's nor the replaced version's; a later install that runs from elsewhere deletes it.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void keepsRunningInstallerFolder() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("old-folders-running-")), catalog());
			Path running = fixture.installActive("1.0");
			requireUpdated(fixture.install("v1.1"));

			requireUpdated(fixture.install("v1.2", running));
			requireThat(running, "running").isDirectory();

			UpdateResult later = fixture.install("v1.2");
			requireThat(later.result(), "later").withContext(later.message(), "message").isEqualTo(Result.UP_TO_DATE);
			requireThat(Files.exists(running, NOFOLLOW_LINKS), "laterRunning").isFalse();
		}
	}

	/**
	 * A version folder that cannot be deleted, as on Windows while a process has one of its files open, is left in
	 * place, the install still succeeds and says which folder it left, and the next install deletes the folder.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void undeletableFolderIsLeftForLaterInstall() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("old-folders-undeletable-")),
				catalog());
			fixture.installActive("1.0");
			requireUpdated(fixture.install("v1.1"));
			Path leftBehind = leftBehind(fixture);
			Path file = leftBehind.resolve("bin/VERSION");
			UpdateResult result;
			// Stands for a file that a running harness session holds open: the file system refuses to delete it
			protect(file, true);
			try
			{
				result = fixture.install("v1.1");
			}
			finally
			{
				protect(file, false);
			}

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UP_TO_DATE);
			requireThat(result.message(), "message").startsWith("CAT is up to date. The old version folder " +
				leftBehind + " could not be deleted").endsWith("; the next install tries again.");
			requireThat(file, "leftBehind").isRegularFile();

			UpdateResult next = fixture.install("v1.1");
			requireThat(next.message(), "nextMessage").isEqualTo("CAT is up to date.");
			requireThat(Files.exists(leftBehind, NOFOLLOW_LINKS), "nextLeftBehind").isFalse();
		}
	}

	/**
	 * Deleting a version folder deletes a symbolic link inside it without deleting what the link points to, and a
	 * symbolic link named like a version folder is neither followed nor deleted.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void linksAreNotFollowed() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("old-folders-links-")), catalog());
			fixture.installActive("1.0");
			requireUpdated(fixture.install("v1.1"));
			Path outside = Files.createDirectories(fixture.root.resolve("outside"));
			Files.writeString(outside.resolve("keep"), "user file");
			Path leftBehind = leftBehind(fixture);
			Files.createSymbolicLink(leftBehind.resolve("link"), outside);
			Path linkedVersion = Files.createSymbolicLink(leftBehind.resolveSibling(Releases.pluginVersion("0.8")),
				outside);

			UpdateResult result = fixture.install("v1.1");

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UP_TO_DATE);
			requireThat(Files.exists(leftBehind, NOFOLLOW_LINKS), "leftBehind").isFalse();
			requireThat(Files.isSymbolicLink(linkedVersion), "linkedVersion").isTrue();
			requireThat(Files.readString(outside.resolve("keep")), "outside").isEqualTo("user file");
		}
	}

	/**
	 * Returns the catalog of releases 1.0, 1.1 and 1.2.
	 *
	 * @return the catalog
	 * @throws IOException if a release cannot be created
	 */
	private static ReleaseCatalog catalog() throws IOException
	{
		return new ReleaseCatalog("1.0", NO_MIGRATION).add("1.1", NO_MIGRATION).add("1.2", NO_MIGRATION);
	}

	/**
	 * Requires that an install updated the harness.
	 *
	 * @param result the install's outcome
	 */
	private static void requireUpdated(UpdateResult result)
	{
		requireThat(result.result(), "result").withContext(result.message(), "message").isEqualTo(Result.UPDATED);
	}

	/**
	 * Writes the version folder of a version that an earlier install left behind beside the harness's version folders.
	 *
	 * @param fixture the harness home
	 * @return the folder
	 * @throws IOException if the folder cannot be written
	 */
	private static Path leftBehind(EngineFixture fixture) throws IOException
	{
		Path folder = fixture.versionFolder("1.1").resolveSibling(LEFT_BEHIND);
		Files.writeString(Files.createDirectories(folder.resolve("bin")).resolve("VERSION"), LEFT_BEHIND);
		return folder;
	}

	/**
	 * Makes a file impossible to delete, or possible again: where the file system has POSIX permissions, its
	 * directory loses or regains the owner's write permission; otherwise, as on Windows, the file gets or loses the
	 * read-only attribute.
	 *
	 * @param file        the file
	 * @param undeletable true to make the file impossible to delete
	 * @throws IOException if the permissions or attributes cannot be changed
	 */
	private static void protect(Path file, boolean undeletable) throws IOException
	{
		Path directory = file.getParent();
		if (directory.getFileSystem().supportedFileAttributeViews().contains("posix"))
		{
			String mode;
			if (undeletable)
				mode = "r-x------";
			else
				mode = "rwx------";
			Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString(mode));
		}
		else
			Files.setAttribute(file, "dos:readonly", undeletable);
	}
}
