package com.holdmyspot.cat.common.installer.engine.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.activation.InstalledState;
import com.holdmyspot.cat.common.installer.engine.UpdateEngine;
import com.holdmyspot.cat.common.installer.engine.UpdateResult;
import com.holdmyspot.cat.common.installer.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Tests that install and update activate a signed release's plugin under its full plugin version, the release and the
 * build, in a harness that names each plugin's folder by that version.
 */
public final class PluginVersionFolderTest
{
	/**
	 * The full plugin version that the signed fixture release 1.1 names.
	 */
	private static final String PLUGIN_VERSION = Releases.pluginVersion("1.1");

	/**
	 * Creates the test.
	 */
	public PluginVersionFolderTest()
	{
	}

	/**
	 * Installing a signed release puts its plugin in the folder that its full plugin version names, and the harness
	 * then reports that version as consistently installed.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void installUsesFullVersion() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("full-version-install-"));
			Path home = Files.createDirectories(root.resolve("home"));
			Path data = root.resolve("data");
			Releases.installation(data, "1.1");

			UpdateResult result = engine(data, home).install("v1.1", new RecordingIntegration().session());

			requireThat(result.result(), "result").isEqualTo(Result.INSTALLED);
			requireThat(result.version(), "version").isEqualTo(Optional.of(PLUGIN_VERSION));
			requireThat(new VersionFolderProvider().installed(home), "installed").
				isEqualTo(new InstalledState(Optional.of(PLUGIN_VERSION), true));
		}
	}

	/**
	 * Updating from an earlier release to a signed release puts the new plugin in the folder that its full plugin
	 * version names, and the harness then reports that version as consistently installed.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void updateUsesFullVersion() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("full-version-update-"));
			Path home = plugin(root, "1.0");
			Path data = root.resolve("data");
			Releases.installation(data, "1.0");

			UpdateResult result = engine(data, home).update("v1.1", new RecordingIntegration().session());

			requireThat(result.result(), "result").isEqualTo(Result.UPDATED);
			requireThat(result.version(), "version").isEqualTo(Optional.of(PLUGIN_VERSION));
			requireThat(new VersionFolderProvider().installed(home), "installed").
				isEqualTo(new InstalledState(Optional.of(PLUGIN_VERSION), true));
		}
	}

	/**
	 * Updating to a build of the active release that differs from the active build installs the signed build, because
	 * a release that is published again from another commit is another plugin.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void updateReplacesOtherBuildOfRelease() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("full-version-rebuild-"));
			Path home = plugin(root, "1.1+b000000000000");
			Path data = root.resolve("data");
			Releases.installation(data, "1.1");

			UpdateResult result = engine(data, home).update("v1.1", new RecordingIntegration().session());

			requireThat(result.result(), "result").isEqualTo(Result.UPDATED);
			requireThat(result.previousVersion(), "previousVersion").isEqualTo(Optional.of("1.1+b000000000000"));
			requireThat(new VersionFolderProvider().installed(home), "installed").
				isEqualTo(new InstalledState(Optional.of(PLUGIN_VERSION), true));
		}
	}

	/**
	 * Updating to the signed release whose build is already active and consistent changes nothing.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void sameBuildIsUpToDate() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("full-version-same-"));
			Path home = plugin(root, PLUGIN_VERSION);
			Path data = root.resolve("data");
			Releases.installation(data, "1.1");
			Path live = home.resolve("live/version");
			FileTime before = Files.getLastModifiedTime(live);

			UpdateResult result = engine(data, home).update("v1.1", new RecordingIntegration().session());

			requireThat(result.result(), "result").isEqualTo(Result.UP_TO_DATE);
			requireThat(Files.getLastModifiedTime(live), "liveModified").isEqualTo(before);
		}
	}

	/**
	 * Creates a harness home whose plugin, and the folder that its version names, state a version.
	 *
	 * @param root    the test directory
	 * @param version the plugin version
	 * @return the harness home
	 * @throws IOException if the plugin cannot be written
	 */
	private static Path plugin(Path root, String version) throws IOException
	{
		Path home = root.resolve("home");
		Files.writeString(Files.createDirectories(home.resolve("live")).resolve("version"), version);
		Files.writeString(Files.createDirectories(home.resolve("versions").resolve(version)).resolve("version"),
			version);
		return home;
	}

	/**
	 * Creates an engine for the version-folder harness that does not wait for the lock and runs as a user without
	 * administrative rights.
	 *
	 * @param data the installation's installer data directory
	 * @param home the harness home
	 * @return the engine
	 * @throws IOException    if the release fixture cannot be created
	 * @throws TrustException if the test policy is invalid
	 */
	private static UpdateEngine engine(Path data, Path home) throws IOException, TrustException
	{
		Releases source = new Releases("1.1", "");
		return new UpdateEngine(data, source.policy(home), source, new VersionFolderProvider(), "linux-x64",
			Clock.systemUTC(), interval -> () -> {}, Duration.ZERO, _ -> false);
	}
}
