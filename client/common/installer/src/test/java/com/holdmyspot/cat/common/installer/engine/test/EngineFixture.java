package com.holdmyspot.cat.common.installer.engine.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.engine.InstallRequest;
import com.holdmyspot.cat.common.installer.engine.ReleaseSource;
import com.holdmyspot.cat.common.installer.engine.UpdateEngine;
import com.holdmyspot.cat.common.installer.engine.UpdateResult;
import com.holdmyspot.cat.common.installer.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * A harness home whose harness installs each CAT version into its own version folder, with an engine over a catalog
 * of signed releases. The engine does not wait for the harness lock and runs as a user without administrative rights.
 */
final class EngineFixture
{
	/**
	 * The lowest release version that the fixture's policy approves.
	 */
	private static final String MINIMUM = "0.1";
	final Path root;
	final Path home;
	final ReleaseCatalog catalog;
	final RecordingIntegration integration = new RecordingIntegration();
	final RegisteredProvider provider;

	/**
	 * Creates the harness home.
	 *
	 * @param directory the test directory
	 * @param catalog   the releases
	 * @throws IOException if the home cannot be created
	 */
	EngineFixture(Path directory, ReleaseCatalog catalog) throws IOException
	{
		this(directory, catalog, new RegisteredProvider());
	}

	/**
	 * Creates the harness home with a provider that describes the harness.
	 *
	 * @param directory the test directory
	 * @param catalog   the releases
	 * @param provider  the harness's provider
	 * @throws IOException if the home cannot be created
	 */
	EngineFixture(Path directory, ReleaseCatalog catalog, RegisteredProvider provider) throws IOException
	{
		this.root = directory.toRealPath();
		this.home = Files.createDirectories(root.resolve("home"));
		this.catalog = catalog;
		this.provider = provider;
	}

	/**
	 * Returns the installer data directory of the harness.
	 *
	 * @return the directory
	 */
	Path data()
	{
		return provider.dataDirectory(home);
	}

	/**
	 * Installs a release into the harness, which has no CAT version yet.
	 *
	 * @param release the release version
	 * @return the version folder of the release
	 * @throws IOException    if the release is not installed
	 * @throws TrustException if the test policy is invalid
	 */
	Path installActive(String release) throws IOException, TrustException
	{
		UpdateResult result = install("v" + release);
		requireThat(result.result(), "installActive").withContext(result.message(), "message").
			isEqualTo(Result.INSTALLED);
		return versionFolder(release);
	}

	/**
	 * Installs a signed release with an installer that runs from outside the harness home, as the entry command's
	 * download does.
	 *
	 * @param tag the release tag, or null for the latest release
	 * @return the outcome
	 * @throws TrustException if the test policy is invalid
	 */
	UpdateResult install(String tag) throws TrustException
	{
		return install(tag, root.resolve("download/plugin"));
	}

	/**
	 * Installs a signed release.
	 *
	 * @param tag   the release tag, or null for the latest release
	 * @param image the plugin folder that the running installer runs from
	 * @return the outcome
	 * @throws TrustException if the test policy is invalid
	 */
	UpdateResult install(String tag, Path image) throws TrustException
	{
		return engine().install(tag, request(image));
	}

	/**
	 * Returns the request of an installer that runs from a plugin folder.
	 *
	 * @param image the plugin folder
	 * @return the request
	 */
	InstallRequest request(Path image)
	{
		return new InstallRequest(integration.session(), image, List.of(), root.resolve("user/.cat/locks"), _ -> false);
	}

	/**
	 * Returns the engine of the harness over the catalog.
	 *
	 * @return the engine
	 * @throws TrustException if the test policy is invalid
	 */
	UpdateEngine engine() throws TrustException
	{
		return engine(catalog, Clock.systemUTC(), interval -> () ->
		{
		}, Duration.ZERO, false);
	}

	/**
	 * Returns an engine of the harness whose policy trusts the catalog's key.
	 *
	 * @param source   the releases
	 * @param clock    the clock of the lock wait
	 * @param waiting  the pause between lock attempts
	 * @param lockWait how long to wait for the harness lock
	 * @param elevated true if the engine runs with administrative rights
	 * @return the engine
	 * @throws TrustException if the test policy is invalid
	 */
	UpdateEngine engine(ReleaseSource source, Clock clock, Waiting waiting, Duration lockWait, boolean elevated)
		throws TrustException
	{
		return new UpdateEngine(data(), catalog.policy(home, MINIMUM), source, provider, "linux-x64", clock, waiting,
			lockWait, _ -> elevated);
	}

	/**
	 * Returns the version folder of a release.
	 *
	 * @param release the release version
	 * @return the folder
	 */
	Path versionFolder(String release)
	{
		return new RecordingIntegration.Layout(home).versionFolder(PluginVersion.parse(
			Releases.pluginVersion(release)));
	}

	/**
	 * Makes the harness report a plugin version as installed, with a version folder that states it, as if an earlier
	 * install had installed it.
	 *
	 * @param version the full plugin version
	 * @return the version folder
	 * @throws IOException if the files cannot be written
	 */
	Path registerExisting(String version) throws IOException
	{
		Path folder = new RecordingIntegration.Layout(home).versionFolder(PluginVersion.parse(version));
		Files.writeString(Files.createDirectories(folder.resolve("bin")).resolve("VERSION"), version);
		Files.writeString(RecordingIntegration.registeredVersion(home), version);
		return folder;
	}

	/**
	 * Returns the record of the installed permission entries.
	 *
	 * @return the record
	 */
	Path permissionRecord()
	{
		return data().resolve("state/permissions/harness-a.json");
	}

	/**
	 * Returns the file that records the harness's data migration version.
	 *
	 * @return the file
	 */
	Path migrationVersion()
	{
		return provider.migrationVersionFile(home);
	}

	/**
	 * Extracts the plugin archive of a release, as the entry command does before it runs the archive's installer.
	 *
	 * @param release the release version
	 * @return the archive's top directory
	 * @throws IOException if the archive cannot be extracted
	 */
	Path extract(String release) throws IOException
	{
		return catalog.extract(release, Files.createDirectories(root.resolve("extracted-" + release)));
	}
}
