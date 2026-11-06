package com.holdmyspot.cat.common.installer.engine.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.engine.UpdateResult;
import com.holdmyspot.cat.common.installer.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Exercises the reuse of a release that the installation keeps, instead of downloading the release again.
 */
public final class CachedReleaseTest
{
	/**
	 * The migration registry of a release that registers no data migration.
	 */
	private static final String NO_MIGRATION = "# No migration\n";
	/**
	 * The plugin archive of release 1.1.
	 */
	private static final String PLUGIN_ARCHIVE = "cat-harness-a-linux-x64-v1.1.tar.gz";

	/**
	 * Creates the test.
	 */
	public CachedReleaseTest()
	{
	}

	/**
	 * Going back, while the release source is unreachable, to the release that an update replaced installs the kept
	 * release, verified against its signed manifest, without downloading anything.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void downgradesOfflineToKeptRelease() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = updated(cleanup);
			fixture.catalog.goOffline();

			UpdateResult result = fixture.install("v1.1");

			// The unreachable source can supply nothing, so the update to 1.1 installed the kept release
			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UPDATED);
			requireThat(fixture.provider.installed(fixture.home).version(), "installed").
				isEqualTo(Optional.of(Releases.pluginVersion("1.1")));
		}
	}

	/**
	 * While the release source is unreachable, a kept release whose manifest or archive no longer verifies is not
	 * installed: the install fails to obtain the release and the harness keeps its active version.
	 *
	 * @param file the changed file, relative to the installer data directory
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test(dataProvider = "tamperedFiles")
	public void refusesTamperedReleaseOffline(String file) throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = updated(cleanup);
			Files.writeString(fixture.data().resolve(file), "tampered");
			fixture.catalog.goOffline();

			UpdateResult result = fixture.install("v1.1");

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.DOWNLOAD_FAILED);
			requireThat(fixture.provider.installed(fixture.home).version(), "installed").
				isEqualTo(Optional.of(Releases.pluginVersion("1.2")));
		}
	}

	/**
	 * Supplies a file of the kept release to change.
	 *
	 * @return the file relative to the installer data directory
	 */
	@DataProvider
	public Object[][] tamperedFiles()
	{
		return new Object[][]{{"releases/1.1/" + PLUGIN_ARCHIVE}, {"releases/1.1/cat-release-manifest.json"}};
	}

	/**
	 * A kept release whose manifest or archive no longer verifies is not reused: the install downloads the release
	 * and installs the downloaded copy, which replaces the changed file in the installation's kept release.
	 *
	 * @param file the changed file, relative to the installer data directory
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test(dataProvider = "tamperedFiles")
	public void downloadsInPlaceOfTamperedRelease(String file) throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = updated(cleanup);
			Files.writeString(fixture.data().resolve(file), "tampered");

			UpdateResult result = fixture.install("v1.1");

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UPDATED);
			Path expected = fixture.root.resolve("expected-1.1");
			fixture.catalog.writeKept("1.1", expected);
			Path kept = fixture.data().resolve(file);
			requireThat(Files.mismatch(expected.resolve(kept.getFileName()), kept), "mismatch").isEqualTo(-1L);
			requireThat(fixture.provider.installed(fixture.home).version(), "installed").
				isEqualTo(Optional.of(Releases.pluginVersion("1.1")));
		}
	}

	/**
	 * Creates a harness that ran release 1.1, which an earlier install of it kept, and was then updated to release
	 * 1.2, so that the installation keeps releases 1.1 and 1.2 exactly as the release source serves them.
	 *
	 * @param cleanup the test's temporary directories
	 * @return the fixture
	 * @throws IOException    if the fixture cannot be created
	 * @throws TrustException if the test policy is invalid
	 */
	private static EngineFixture updated(TemporaryDirectories cleanup) throws IOException, TrustException
	{
		EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("cached-release-")),
			new ReleaseCatalog("1.1", NO_MIGRATION).add("1.2", NO_MIGRATION));
		// Stands for the install of 1.1 that registered the version and kept the release
		fixture.registerExisting(Releases.pluginVersion("1.1"));
		fixture.catalog.writeKept("1.1", fixture.data().resolve("releases/1.1"));

		UpdateResult result = fixture.install("v1.2");

		requireThat(result.result(), "update").withContext(result.message(), "message").isEqualTo(Result.UPDATED);
		requireThat(Files.isRegularFile(fixture.data().resolve("releases/1.1/" + PLUGIN_ARCHIVE)), "kept").isTrue();
		Path expected = fixture.root.resolve("expected-1.2");
		fixture.catalog.writeKept("1.2", expected);
		try (Stream<Path> files = Files.list(expected))
		{
			for (Path file : files.toList())
			{
				requireThat(Files.mismatch(file, fixture.data().resolve("releases/1.2").resolve(file.getFileName())),
					"mismatch").withContext(file.getFileName(), "file").isEqualTo(-1L);
			}
		}
		return fixture;
	}
}
