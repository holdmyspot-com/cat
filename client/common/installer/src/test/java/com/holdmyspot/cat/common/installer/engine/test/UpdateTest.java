package com.holdmyspot.cat.common.installer.engine.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.InstalledState;
import com.holdmyspot.cat.common.installer.engine.UpdateResult;
import com.holdmyspot.cat.common.installer.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.testng.annotations.Test;

/**
 * Tests that install, once the harness has a CAT version, updates it by asking the harness to install the new
 * version into a new version folder, and that it never changes the version folder that the running installer belongs
 * to.
 */
public final class UpdateTest
{
	/**
	 * The migration registry of a release that registers no data migration.
	 */
	private static final String NO_MIGRATION = "# No migration\n";

	/**
	 * Creates the test.
	 */
	public UpdateTest()
	{
	}

	/**
	 * An update to a new build asks the harness to install that build once, into its own version folder, bound to the
	 * Java runtime in that folder, and leaves the version folder of the active version as it was.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void updateInstallsNewVersionFolder() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("update-version-folder-")), catalog());
			Path active = fixture.installActive("1.0");
			Map<String, String> before = Releases.snapshot(active);
			int registrations = fixture.integration.registered().size();

			UpdateResult result = fixture.install("v1.1", active);

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UPDATED);
			requireThat(result.version(), "version").isEqualTo(Optional.of(Releases.pluginVersion("1.1")));
			requireThat(result.previousVersion(), "previousVersion").
				isEqualTo(Optional.of(Releases.pluginVersion("1.0")));
			List<PluginVersion> registered = fixture.integration.registered();
			requireThat(registered.subList(registrations, registered.size()), "registered").
				isEqualTo(List.of(PluginVersion.parse(Releases.pluginVersion("1.1"))));
			Path updated = fixture.versionFolder("1.1");
			requireThat(Files.readString(updated.resolve("bin/VERSION")), "updatedVersion").
				isEqualTo(Releases.pluginVersion("1.1"));
			requireThat(Files.readString(updated.resolve("bin/runtime-binding")), "binding").
				isEqualTo(updated.resolve("bin/runtime") + "\n");
			requireThat(Releases.snapshot(active), "active").isEqualTo(before);
			InstalledState installed = fixture.provider.installed(fixture.home);
			requireThat(installed.version(), "installed").isEqualTo(Optional.of(Releases.pluginVersion("1.1")));
			requireThat(installed.consistent(), "consistent").isTrue();
		}
	}

	/**
	 * An update moves, replaces or rewrites no file or directory under the version folder that the running installer
	 * runs from.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void runningImageIsNotMoved() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("update-running-image-")), catalog());
			Path active = fixture.installActive("1.0");
			Map<String, String> before = identities(active);

			UpdateResult result = fixture.install("v1.1", active);

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UPDATED);
			requireThat(identities(active), "identities").isEqualTo(before);
		}
	}

	/**
	 * After an update, the permission entries that install adds name the installer in the new version folder, and the
	 * installation records them.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void entriesNameNewVersionFolder() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("update-entries-")), catalog());
			Path active = fixture.installActive("1.0");
			fixture.integration.adding(Map.of("settings.json", List.of("updated-entry")));

			UpdateResult result = fixture.install("v1.1", active);

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UPDATED);
			Path installer = fixture.versionFolder("1.1").resolve("bin/installer");
			requireThat(fixture.integration.callsWithSubjects(), "calls").contains("installPermissions " + installer);
			requireThat(Files.readString(fixture.permissionRecord()), "record").contains("updated-entry");
		}
	}

	/**
	 * When the harness fails to install the new version, the harness still reports the active version as consistently
	 * installed, and the permission entries and their record stay as they were.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void failedHarnessInstallLeavesActiveVersion() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("update-failed-register-")), catalog());
			fixture.integration.adding(Map.of("settings.json", List.of("active-entry")));
			Path active = fixture.installActive("1.0");
			String record = Files.readString(fixture.permissionRecord());
			fixture.integration.fail("register");

			UpdateResult result = fixture.install("v1.1", active);

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.FAILED);
			InstalledState installed = fixture.provider.installed(fixture.home);
			requireThat(installed.version(), "installed").isEqualTo(Optional.of(Releases.pluginVersion("1.0")));
			requireThat(installed.consistent(), "consistent").isTrue();
			requireThat(Files.readString(fixture.permissionRecord()), "record").isEqualTo(record);
		}
	}

	/**
	 * Install refuses to install a version into the version folder that the running installer runs from, which would
	 * replace the running installer's files, and changes nothing.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void refusesVersionFolderOfRunningInstaller() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("update-own-folder-")), catalog());
			Path active = fixture.installActive("1.0");
			// The harness no longer reports the active version as consistent, so install repairs it
			Files.writeString(active.resolve("bin/VERSION"), "damaged");
			Map<String, String> before = Releases.snapshot(fixture.home);
			int calls = fixture.integration.calls().size();

			UpdateResult result = fixture.install("v1.0", active);

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.REFUSED);
			requireThat(result.message(), "message").contains(active.toString());
			requireThat(Releases.snapshot(fixture.home), "home").isEqualTo(before);
			requireThat(fixture.integration.calls().size(), "calls").isEqualTo(calls);
		}
	}

	/**
	 * Install from an extracted plugin archive into a harness that has another CAT version updates it like a signed
	 * release: the harness installs the archive's version into its own version folder.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void archiveUpdatesInstalledVersion() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("update-archive-")), catalog());
			Path active = fixture.installActive("1.0");
			Map<String, String> before = Releases.snapshot(active);
			Path archive = fixture.extract("1.1");

			UpdateResult result = fixture.engine().installFrom(archive, fixture.request(archive));

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UPDATED);
			requireThat(fixture.provider.installed(fixture.home).version(), "installed").
				isEqualTo(Optional.of(Releases.pluginVersion("1.1")));
			requireThat(Files.readString(fixture.versionFolder("1.1").resolve("bin/VERSION")), "updated").
				isEqualTo(Releases.pluginVersion("1.1"));
			requireThat(Releases.snapshot(active), "active").isEqualTo(before);
		}
	}

	/**
	 * Returns signed releases 1.0 and 1.1 of one key, neither of which registers a data migration.
	 *
	 * @return the releases
	 * @throws IOException if creating a release fails
	 */
	private static ReleaseCatalog catalog() throws IOException
	{
		return new ReleaseCatalog("1.0", NO_MIGRATION).add("1.1", NO_MIGRATION);
	}

	/**
	 * Returns the identity of every file and directory under a directory: its file key, modification time and, for a
	 * regular file, its content, so that a moved, replaced or rewritten entry changes the result.
	 *
	 * @param root the directory
	 * @return each entry's relative path mapped to its identity
	 * @throws IOException if the tree cannot be read
	 */
	private static Map<String, String> identities(Path root) throws IOException
	{
		Map<String, String> entries = new TreeMap<>();
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.toList())
			{
				BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
					LinkOption.NOFOLLOW_LINKS);
				StringBuilder identity = new StringBuilder().append(attributes.fileKey()).append(' ').
					append(attributes.lastModifiedTime());
				if (attributes.isRegularFile())
					identity.append(' ').append(HexFormat.of().formatHex(Files.readAllBytes(path)));
				entries.put(root.relativize(path).toString(), identity.toString());
			}
		}
		return entries;
	}
}
