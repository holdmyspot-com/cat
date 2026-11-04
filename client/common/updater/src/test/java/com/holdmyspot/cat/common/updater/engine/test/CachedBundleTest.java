package com.holdmyspot.cat.common.updater.engine.test;

import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.updater.engine.UpdateResult;
import com.holdmyspot.cat.common.updater.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Exercises the reuse of a release's bundle that the installation keeps, instead of downloading the release again.
 */
public final class CachedBundleTest
{
	/**
	 * The migration registry of a release that registers only the per-harness layout migration.
	 */
	private static final String LAYOUT = "1.0.0\tper-harness-layout.sh\n";
	/**
	 * The plugin archive of release 1.1.
	 */
	private static final String PLUGIN_ARCHIVE = "cat-harness-a-v1.1-linux-x64.tar.gz";
	/**
	 * The updater archive of release 1.1.
	 */
	private static final String UPDATER_ARCHIVE = "cat-updater-v1.1-linux-x64.tar.gz";

	/**
	 * Going back to the release whose bundle an update replaced and kept reuses that bundle, verified against its
	 * signed manifest and the identity recorded when it was installed, without contacting the release source.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void downgradesOfflineToKeptBundle() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("cached-offline-"));
			Path data = root.resolve("data");
			ReleaseCatalog catalog = catalog();
			Path home = updatedTwice(root, data, catalog);
			catalog.goOffline();
			int before = catalog.requests().size();

			UpdateResult result = DowngradeTest.engine(data, home, catalog, "1.0").update("v1.1",
				new RecordingIntegration().session());

			requireThat(result.result(), "result").isEqualTo(Result.UPDATED);
			requireThat(catalog.requests().subList(before, catalog.requests().size()), "requests").isEmpty();
			requireThat(Files.readString(home.resolve("live/version")), "plugin").isEqualTo("1.1");
			requireThat(Files.readString(data.resolve("runtime.next/release")), "stagedRuntime").
				isEqualTo("runtime 1.1");
			requireThat(Files.readString(data.resolve("1.1/VERSION")), "updater").isEqualTo("1.1\n");
			LauncherSwap.finish(data);
			requireThat(Files.readString(data.resolve("runtime/release")), "runtime").isEqualTo("runtime 1.1");
			requireThat(Files.readString(data.resolve("runtime.previous/release")), "keptRuntime").
				isEqualTo("runtime 1.2");
		}
	}

	/**
	 * Supplies a file of the kept bundle to change, and the request with which the update then obtains that part of
	 * the release again.
	 *
	 * @return the file relative to the data directory, and the request
	 */
	@DataProvider
	public Object[][] tamperedFiles()
	{
		return new Object[][]{
			{"runtime.previous/release", "download " + UPDATER_ARCHIVE},
			{"1.1/app/updater.jar", "download " + UPDATER_ARCHIVE},
			{"releases/1.1/" + PLUGIN_ARCHIVE, "download " + PLUGIN_ARCHIVE},
			{"releases/1.1/cat-release-manifest.json", "manifest v1.1"}};
	}

	/**
	 * A kept bundle whose file no longer matches what the updater installed, or whose cached manifest or archive no
	 * longer verifies, is not reused: the update downloads the release and installs the downloaded copy.
	 *
	 * @param file    the changed file, relative to the data directory
	 * @param request the request that obtains the release again
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test(dataProvider = "tamperedFiles")
	public void downloadsInPlaceOfTamperedBundle(String file, String request) throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("cached-tampered-"));
			Path data = root.resolve("data");
			ReleaseCatalog catalog = catalog();
			Path home = updatedTwice(root, data, catalog);
			Files.writeString(data.resolve(file), "tampered");
			int before = catalog.requests().size();

			UpdateResult result = DowngradeTest.engine(data, home, catalog, "1.0").update("v1.1",
				new RecordingIntegration().session());

			requireThat(result.result(), "result").isEqualTo(Result.UPDATED);
			requireThat(catalog.requests().subList(before, catalog.requests().size()), "requests").contains(request);
			requireThat(Files.readString(home.resolve("live/version")), "plugin").isEqualTo("1.1");
			requireThat(Files.readString(data.resolve("runtime.next/release")), "stagedRuntime").
				isEqualTo("runtime 1.1");
			requireThat(Files.readString(data.resolve("1.1/app/updater.jar")), "updater").
				isEqualTo("Never executed by the updater");
		}
	}

	/**
	 * Installing the plugin again after it was removed reuses the active release's kept plugin archive without
	 * contacting the release source.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void repairReusesActiveRelease() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("cached-repair-"));
			Path data = root.resolve("data");
			ReleaseCatalog catalog = catalog();
			Path home = DowngradeTest.active(root, data, "1.0");
			update(data, home, catalog, "v1.1");
			TemporaryDirectories.deleteRecursively(home.resolve("live"));
			catalog.goOffline();
			int before = catalog.requests().size();

			UpdateResult result = DowngradeTest.engine(data, home, catalog, "1.0").install(null,
				new RecordingIntegration().session());

			requireThat(result.result(), "result").isEqualTo(Result.INSTALLED);
			requireThat(catalog.requests().subList(before, catalog.requests().size()), "requests").isEmpty();
			requireThat(Files.readString(home.resolve("live/version")), "plugin").isEqualTo("1.1");
		}
	}

	/**
	 * Returns a source of releases 1.1 and 1.2, which change no data structures.
	 *
	 * @return the source
	 * @throws IOException if creating a release fails
	 */
	private static ReleaseCatalog catalog() throws IOException
	{
		return new ReleaseCatalog("1.1", LAYOUT).add("1.2", LAYOUT);
	}

	/**
	 * Creates an installation of release 1.0 and updates it to release 1.1 and then 1.2, so that it keeps the bundle
	 * of release 1.1.
	 *
	 * @param root    the test directory
	 * @param data    the installation's updater data directory
	 * @param catalog the release source
	 * @return the harness home
	 * @throws IOException    if the fixture cannot be created
	 * @throws TrustException if the test policy is invalid
	 */
	private static Path updatedTwice(Path root, Path data, ReleaseCatalog catalog) throws IOException, TrustException
	{
		Path home = DowngradeTest.active(root, data, "1.0");
		update(data, home, catalog, "v1.1");
		update(data, home, catalog, "v1.2");
		requireThat(Files.readString(data.resolve("runtime.previous/release")), "keptRuntime").
			isEqualTo("runtime 1.1");
		return home;
	}

	/**
	 * Updates an installation to a release and finishes the runtime replacement as the stable launcher does.
	 *
	 * @param data    the installation's updater data directory
	 * @param home    the harness home
	 * @param catalog the release source
	 * @param tag     the release tag
	 * @throws IOException    if the update fails
	 * @throws TrustException if the test policy is invalid
	 */
	private static void update(Path data, Path home, ReleaseCatalog catalog, String tag)
		throws IOException, TrustException
	{
		UpdateResult result = DowngradeTest.engine(data, home, catalog, "1.0").update(tag,
			new RecordingIntegration().session());
		requireThat(result.result(), "result").withContext(result.message(), "message").isEqualTo(Result.UPDATED);
		LauncherSwap.finish(data);
		requireThat(Files.readString(data.resolve("current")), "current").isEqualTo(tag.substring(1) + "\n");
	}
}
