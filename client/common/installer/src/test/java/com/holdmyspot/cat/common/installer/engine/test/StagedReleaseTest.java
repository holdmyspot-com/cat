package com.holdmyspot.cat.common.installer.engine.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.engine.StagedRelease;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link StagedRelease} against owned plugin directories without running any staged code.
 */
public final class StagedReleaseTest
{
	/**
	 * Creates a test instance.
	 */
	public StagedReleaseTest()
	{
	}

	/**
	 * Matching release metadata and an executable plugin-info file pass validation.
	 *
	 * @throws IOException if fixture files cannot be written or read
	 * @throws TrustException if the matching release is refused
	 */
	@Test
	public void acceptsMatchingRelease() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path tree = cleanup.add(ReleaseFixtures.testDirectory("staged-release-"));
			writeRelease(tree);
			StagedRelease.validate(tree, "1.1", "plugin.json", "linux-x64");
		}
	}

	/**
	 * A staged plugin that states the signed release's full plugin version passes validation.
	 *
	 * @throws IOException if fixture files cannot be written or read
	 * @throws TrustException if the matching plugin version is refused
	 */
	@Test
	public void acceptsMatchingPluginVersion() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path tree = cleanup.add(ReleaseFixtures.testDirectory("staged-release-"));
			writeRelease(tree, "1.1+b0123456789ab");
			StagedRelease.validate(tree, PluginVersion.parse("1.1+b0123456789ab"), "plugin.json", "linux-x64");
		}
	}

	/**
	 * A staged plugin of another build than the signed release names is refused.
	 *
	 * @throws IOException if fixture files cannot be written or read
	 * @throws TrustException if the plugin version is refused, as expected
	 */
	@Test(expectedExceptions = TrustException.class, expectedExceptionsMessageRegExp =
		".*must equal plugin version 1\\.1\\+b0123456789ab.*")
	public void refusesPluginOfAnotherBuild() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path tree = cleanup.add(ReleaseFixtures.testDirectory("staged-release-"));
			writeRelease(tree, "1.1+bfedcba987654");
			StagedRelease.validate(tree, PluginVersion.parse("1.1+b0123456789ab"), "plugin.json", "linux-x64");
		}
	}

	/**
	 * A staged plugin that states only the release, without the build that the signed release names, is refused.
	 *
	 * @throws IOException if fixture files cannot be written or read
	 * @throws TrustException if the plugin version is refused, as expected
	 */
	@Test(expectedExceptions = TrustException.class, expectedExceptionsMessageRegExp =
		".*must equal plugin version 1\\.1\\+b0123456789ab.*")
	public void refusesReleaseOnlyPluginOfSignedRelease() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path tree = cleanup.add(ReleaseFixtures.testDirectory("staged-release-"));
			writeRelease(tree);
			StagedRelease.validate(tree, PluginVersion.parse("1.1+b0123456789ab"), "plugin.json", "linux-x64");
		}
	}

	/**
	 * A local plugin tree, which no signed release describes, passes validation when it states any build of the
	 * release.
	 *
	 * @throws IOException if fixture files cannot be written or read
	 * @throws TrustException if the local tree is refused
	 */
	@Test
	public void acceptsLocalTreeOfAnyBuild() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path tree = cleanup.add(ReleaseFixtures.testDirectory("staged-release-"));
			writeRelease(tree, "1.1+bfedcba987654");
			StagedRelease.validate(tree, "1.1", "plugin.json", "linux-x64");
		}
	}

	/**
	 * A local plugin tree of another release is refused.
	 *
	 * @throws IOException if fixture files cannot be written or read
	 * @throws TrustException if the local tree is refused, as expected
	 */
	@Test(expectedExceptions = TrustException.class, expectedExceptionsMessageRegExp = ".*must equal release 1\\.1.*")
	public void refusesLocalTreeOfAnotherRelease() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path tree = cleanup.add(ReleaseFixtures.testDirectory("staged-release-"));
			writeRelease(tree, "1.2+b0123456789ab");
			StagedRelease.validate(tree, "1.1", "plugin.json", "linux-x64");
		}
	}

	/**
	 * A missing plugin-info file prevents staging from being accepted.
	 *
	 * @throws IOException if fixture files cannot be written or read
	 * @throws TrustException expected refusal for missing executable
	 */
	@Test(expectedExceptions = TrustException.class, expectedExceptionsMessageRegExp = ".*plugin-info.*")
	public void refusesMissingPluginInfo() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path tree = cleanup.add(ReleaseFixtures.testDirectory("staged-release-"));
			writeRelease(tree);
			Files.delete(tree.resolve("bin/plugin-info"));
			StagedRelease.validate(tree, "1.1", "plugin.json", "linux-x64");
		}
	}

	/**
	 * Supplies malformed, mismatched, repeated, absent, and non-string release versions.
	 *
	 * @return plugin manifest documents that cannot declare release 1.1
	 */
	@DataProvider
	public Object[][] invalidManifests()
	{
		return new Object[][]
		{
			{"{\"version\":\"1.0\"}"}, {"{\"version\":\"01.1\"}"}, {"{\"version\":1.1}"},
			{"{}"}, {"[]"}, {"{\"version\":\"1.1\"} {}"}, {"{\"version\":"}, {"{\"version\":\"1.1\",\"version\":\"1.1\"}"}
		};
	}

	/**
	 * Only a string with the exact release version passes the plugin manifest check.
	 *
	 * @param manifest invalid plugin manifest
	 * @throws IOException if fixture files cannot be written or read
	 * @throws TrustException expected refusal for invalid manifest
	 */
	@Test(dataProvider = "invalidManifests", expectedExceptions = TrustException.class)
	public void refusesInvalidManifest(String manifest) throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path tree = cleanup.add(ReleaseFixtures.testDirectory("staged-release-"));
			writeRelease(tree);
			Files.writeString(tree.resolve("plugin.json"), manifest, UTF_8);
			StagedRelease.validate(tree, "1.1", "plugin.json", "linux-x64");
		}
	}

	/**
	 * A runtime version from a different release is refused.
	 *
	 * @throws IOException if fixture files cannot be written or read
	 * @throws TrustException expected refusal for mismatched runtime
	 */
	@Test(expectedExceptions = TrustException.class, expectedExceptionsMessageRegExp = ".*bin/VERSION.*")
	public void refusesRuntimeVersionMismatch() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path tree = cleanup.add(ReleaseFixtures.testDirectory("staged-release-"));
			writeRelease(tree);
			Files.writeString(tree.resolve("bin/VERSION"), "1.0\n", UTF_8);
			StagedRelease.validate(tree, "1.1", "plugin.json", "linux-x64");
		}
	}

	/**
	 * A staged link cannot expose a sibling outside the release directory.
	 *
	 * @throws IOException if fixture files or symbolic links cannot be written or read
	 * @throws TrustException expected refusal for escaping symbolic link
	 */
	@Test(expectedExceptions = TrustException.class, expectedExceptionsMessageRegExp = ".*outside.*")
	public void refusesEscapingLink() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(ReleaseFixtures.testDirectory("staged-release-"));
			Path tree = Files.createDirectory(root.resolve("release"));
			writeRelease(tree);
			Files.writeString(root.resolve("outside"), "private", UTF_8);
			Files.createSymbolicLink(tree.resolve("escape"), Path.of("../outside"));
			StagedRelease.validate(tree, "1.1", "plugin.json", "linux-x64");
		}
	}

	/**
	 * Internal symbolic links do not prevent a matching release from being accepted.
	 *
	 * @throws IOException if fixture files or symbolic links cannot be written or read
	 * @throws TrustException if the internal symbolic link is refused
	 */
	@Test
	public void acceptsInternalLink() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path tree = cleanup.add(ReleaseFixtures.testDirectory("staged-release-"));
			writeRelease(tree);
			Files.createSymbolicLink(tree.resolve("internal"), Path.of("bin/VERSION"));
			StagedRelease.validate(tree, "1.1", "plugin.json", "linux-x64");
		}
	}

	/**
	 * A registry is mandatory even when no migration is needed.
	 *
	 * @throws IOException if fixture files cannot be written or read
	 * @throws TrustException expected refusal for absent migration registry
	 */
	@Test(expectedExceptions = TrustException.class, expectedExceptionsMessageRegExp = ".*registry.tsv.*")
	public void refusesAbsentRegistry() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path tree = cleanup.add(ReleaseFixtures.testDirectory("staged-release-"));
			writeRelease(tree);
			Files.delete(tree.resolve("install/migrations/registry.tsv"));
			StagedRelease.migrationsAfter(tree, ReleaseVersion.parse("1.0"), ReleaseVersion.parse("1.1"));
		}
	}

	/**
	 * The migrations after an older release, up to and including a newer one, are listed in registry order;
	 * comments, blank lines, earlier and later migrations are skipped.
	 *
	 * @throws IOException if fixture files cannot be written or read
	 * @throws TrustException if the registry is refused
	 */
	@Test
	public void listsMigrationsBetweenReleases() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path tree = cleanup.add(ReleaseFixtures.testDirectory("staged-release-"));
			writeRelease(tree);
			Files.writeString(tree.resolve("install/migrations/registry.tsv"),
				"# migrations\n\n1.0\told.sh\n1.1\tsplit.sh\n1.2\tmerge.sh\n1.3\tfuture.sh\n");
			requireThat(StagedRelease.migrationsAfter(tree, ReleaseVersion.parse("1.0"), ReleaseVersion.parse("1.2")),
				"migrations").isEqualTo(List.of("split.sh", "merge.sh"));
		}
	}

	/**
	 * Supplies registry records with missing columns, extra columns, an invalid version, or excessive length.
	 *
	 * @return malformed migration records
	 */
	@DataProvider
	public Object[][] invalidRegistryRecords()
	{
		return new Object[][]
		{
			{"1.1"}, {"1.1\t"}, {"1.1\tx.sh\textra"}, {"01.1\tx.sh"}, {"x".repeat(4097)}
		};
	}

	/**
	 * Malformed registry records are refused instead of hiding migrations.
	 *
	 * @param registry malformed migration registry
	 * @throws IOException if fixture files cannot be written or read
	 * @throws TrustException expected refusal for invalid migration metadata
	 */
	@Test(dataProvider = "invalidRegistryRecords", expectedExceptions = TrustException.class)
	public void refusesInvalidRegistry(String registry) throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path tree = cleanup.add(ReleaseFixtures.testDirectory("staged-release-"));
			writeRelease(tree);
			Files.writeString(tree.resolve("install/migrations/registry.tsv"), registry, UTF_8);
			StagedRelease.migrationsAfter(tree, ReleaseVersion.parse("1.0"), ReleaseVersion.parse("1.1"));
		}
	}

	/**
	 * A manifest above the one MiB metadata limit is refused before JSON parsing.
	 *
	 * @throws IOException if fixture files cannot be written or read
	 * @throws TrustException expected refusal for oversized metadata
	 */
	@Test(expectedExceptions = TrustException.class, expectedExceptionsMessageRegExp = ".*exceeds.*bytes.*")
	public void refusesOversizedManifest() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path tree = cleanup.add(ReleaseFixtures.testDirectory("staged-release-"));
			writeRelease(tree);
			Files.writeString(tree.resolve("plugin.json"), " ".repeat((1024 * 1024) + 1), UTF_8);
			StagedRelease.validate(tree, "1.1", "plugin.json", "linux-x64");
		}
	}

	/**
	 * A directory named plugin-info is refused even when the filesystem permits searching it.
	 *
	 * @throws IOException if fixture files cannot be written or read
	 * @throws TrustException expected refusal for nonregular executable
	 */
	@Test(expectedExceptions = TrustException.class, expectedExceptionsMessageRegExp = ".*plugin-info.*")
	public void refusesDirectoryPluginInfo() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path tree = cleanup.add(ReleaseFixtures.testDirectory("staged-release-"));
			writeRelease(tree);
			Files.delete(tree.resolve("bin/plugin-info"));
			Files.createDirectory(tree.resolve("bin/plugin-info"));
			StagedRelease.validate(tree, "1.1", "plugin.json", "linux-x64");
		}
	}

	/**
	 * A regular plugin-info file without execute permission is refused on filesystems with POSIX modes.
	 *
	 * @throws IOException if fixture files cannot be written or read
	 * @throws TrustException expected refusal for a nonexecutable file
	 */
	@Test(expectedExceptions = TrustException.class, expectedExceptionsMessageRegExp = ".*plugin-info.*")
	public void refusesNonExecutablePluginInfo() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path tree = cleanup.add(ReleaseFixtures.testDirectory("staged-release-"));
			writeRelease(tree);
			Path executable = tree.resolve("bin/plugin-info");
			if (!PortableFiles.hasModes(executable))
				throw new SkipException("This filesystem has no POSIX execute permissions");
			PortableFiles.setMode(executable, PosixFilePermissions.fromString("rw-------"));
			StagedRelease.validate(tree, "1.1", "plugin.json", "linux-x64");
		}
	}

	/**
	 * An absent extracted top directory is refused before any release metadata is read.
	 *
	 * @throws IOException if the fixture directory cannot be created or removed
	 * @throws TrustException expected refusal for an absent top directory
	 */
	@Test(expectedExceptions = TrustException.class, expectedExceptionsMessageRegExp = ".*top directory.*")
	public void refusesMissingTopDirectory() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(ReleaseFixtures.testDirectory("staged-release-"));
			StagedRelease.validate(root.resolve("absent"), "1.1", "plugin.json", "linux-x64");
		}
	}

	/**
	 * Windows staging accepts plugin-info.cmd without a POSIX launcher or POSIX execute permissions.
	 *
	 * @throws IOException if fixture files cannot be written or read
	 * @throws TrustException if the Windows release is refused
	 */
	@Test
	public void acceptsWindowsCommandLauncher() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path tree = cleanup.add(ReleaseFixtures.testDirectory("staged-release-windows-"));
			writeRelease(tree);
			Files.delete(tree.resolve("bin/plugin-info"));
			Files.writeString(tree.resolve("bin/plugin-info.cmd"), "@exit /b 0\r\n", UTF_8);
			PortableFiles.setMode(tree.resolve("bin/plugin-info.cmd"), PosixFilePermissions.fromString("rw-------"));
			StagedRelease.validate(tree, "1.1", "plugin.json", "windows-x64");
		}
	}

	/**
	 * Windows staging refuses a POSIX launcher when the required command launcher is absent.
	 *
	 * @throws IOException if fixture files cannot be written or read
	 * @throws TrustException expected refusal for the missing Windows launcher
	 */
	@Test(expectedExceptions = TrustException.class, expectedExceptionsMessageRegExp = ".*plugin-info.cmd.*")
	public void refusesPosixLauncherForWindows() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path tree = cleanup.add(ReleaseFixtures.testDirectory("staged-release-windows-"));
			writeRelease(tree);
			StagedRelease.validate(tree, "1.1", "plugin.json", "windows-x64");
		}
	}

	/**
	 * POSIX staging refuses a Windows launcher when the required POSIX launcher is absent.
	 *
	 * @throws IOException if fixture files cannot be written or read
	 * @throws TrustException expected refusal for the missing POSIX launcher
	 */
	@Test(expectedExceptions = TrustException.class, expectedExceptionsMessageRegExp = ".*plugin-info.*")
	public void refusesWindowsLauncherForPosix() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path tree = cleanup.add(ReleaseFixtures.testDirectory("staged-release-windows-"));
			writeRelease(tree);
			Files.delete(tree.resolve("bin/plugin-info"));
			Files.writeString(tree.resolve("bin/plugin-info.cmd"), "@exit /b 0\r\n", UTF_8);
			StagedRelease.validate(tree, "1.1", "plugin.json", "linux-x64");
		}
	}

	/**
	 * Creates an inert matching plugin with an empty migration registry.
	 *
	 * @param tree owned staged tree
	 * @throws IOException if fixture files cannot be created
	 */
	private static void writeRelease(Path tree) throws IOException
	{
		writeRelease(tree, "1.1");
	}

	/**
	 * Writes a staged release tree that states a version.
	 *
	 * @param tree    the tree
	 * @param version the version that its plugin manifest and {@code bin/VERSION} state
	 * @throws IOException if fixture files cannot be created
	 */
	private static void writeRelease(Path tree, String version) throws IOException
	{
		Files.createDirectories(tree.resolve("bin"));
		Files.createDirectories(tree.resolve("install/migrations"));
		Files.writeString(tree.resolve("plugin.json"), "{\"version\":\"" + version + "\"}", UTF_8);
		Files.writeString(tree.resolve("bin/VERSION"), version + "\n", UTF_8);
		Files.writeString(tree.resolve("install/migrations/registry.tsv"), "", UTF_8);
		Path executable = tree.resolve("bin/plugin-info");
		Files.writeString(executable, "inert fixture", UTF_8);
		PortableFiles.setMode(executable, PosixFilePermissions.fromString("rwx------"));
	}
}
