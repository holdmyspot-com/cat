package com.holdmyspot.cat.common.installer.engine.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.InstalledState;
import com.holdmyspot.cat.common.installer.engine.UpdateResult;
import com.holdmyspot.cat.common.installer.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Tests that install tells plugins apart by their full plugin version, the release and the commit that it was built
 * from, so that another build of the active release is another plugin.
 */
public final class PluginVersionFolderTest
{
	/**
	 * Another build of release 1.1 than the one that the signed fixture release names.
	 */
	private static final String OTHER_BUILD = "1.1" + PluginVersion.BUILD_SEPARATOR + "000000000000";
	/**
	 * The commit of a later publication of a release under the same tag.
	 */
	private static final String REPUBLISHED = "fedcba987654";
	/**
	 * The migration registry of a release that registers no data migration.
	 */
	private static final String NO_MIGRATION = "# No migration\n";

	/**
	 * Creates the test.
	 */
	public PluginVersionFolderTest()
	{
	}

	/**
	 * Installing a build of the active release that differs from the active build installs the signed build into its
	 * own version folder and leaves the active build's folder as it was.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void updateReplacesOtherBuildOfRelease() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("full-version-rebuild-")),
				new ReleaseCatalog("1.1", NO_MIGRATION));
			Path other = fixture.registerExisting(OTHER_BUILD);
			Map<String, String> before = Releases.snapshot(other);

			UpdateResult result = fixture.install("v1.1");

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UPDATED);
			requireThat(result.previousVersion(), "previousVersion").isEqualTo(Optional.of(OTHER_BUILD));
			requireThat(fixture.provider.installed(fixture.home), "installed").
				isEqualTo(new InstalledState(Optional.of(Releases.pluginVersion("1.1")), true));
			requireThat(Releases.snapshot(other), "otherBuild").isEqualTo(before);
		}
	}

	/**
	 * Installing the signed build that is already active and consistent reports that CAT is up to date without asking
	 * the harness to do anything, and changes nothing in the active build's folder.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void sameBuildInstallsNothing() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("full-version-same-")),
				new ReleaseCatalog("1.1", NO_MIGRATION));
			Path active = fixture.installActive("1.1");
			Map<String, String> before = Releases.snapshot(active);
			int calls = fixture.integration.calls().size();

			UpdateResult result = fixture.install("v1.1");

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UP_TO_DATE);
			requireThat(fixture.integration.calls().subList(calls, fixture.integration.calls().size()),
				"laterCalls").isEmpty();
			requireThat(Releases.snapshot(active), "active").isEqualTo(before);
		}
	}

	/**
	 * When a release is published again from another commit, installing the latest release installs the new build of
	 * the active release into its own version folder.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void updatesToRepublishedBuild() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("full-version-republished-")),
				new ReleaseCatalog("1.1", NO_MIGRATION));
			fixture.installActive("1.1");
			fixture.catalog.republish("1.1", REPUBLISHED, NO_MIGRATION);

			UpdateResult result = fixture.install(null);

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UPDATED);
			requireThat(result.version(), "version").isEqualTo(Optional.of(Releases.pluginVersion("1.1", REPUBLISHED)));
			requireThat(result.previousVersion(), "previousVersion").
				isEqualTo(Optional.of(Releases.pluginVersion("1.1")));
			requireThat(fixture.provider.installed(fixture.home), "installed").
				isEqualTo(new InstalledState(Optional.of(Releases.pluginVersion("1.1", REPUBLISHED)), true));
		}
	}

	/**
	 * Builds of one release are not ordered: once the release's tag serves the earlier build again, installing the
	 * release moves back from the later build to it.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void returnsToEarlierBuild() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("full-version-back-")),
				new ReleaseCatalog("1.1", NO_MIGRATION));
			fixture.installActive("1.1");
			fixture.catalog.republish("1.1", REPUBLISHED, NO_MIGRATION);
			UpdateResult forward = fixture.install("v1.1");
			requireThat(forward.result(), "forward").withContext(forward.message(), "message").
				isEqualTo(Result.UPDATED);
			fixture.catalog.republish("1.1", ReleaseFixtures.BUILD, NO_MIGRATION);

			UpdateResult result = fixture.install("v1.1");

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UPDATED);
			requireThat(result.previousVersion(), "previousVersion").
				isEqualTo(Optional.of(Releases.pluginVersion("1.1", REPUBLISHED)));
			requireThat(fixture.provider.installed(fixture.home), "installed").
				isEqualTo(new InstalledState(Optional.of(Releases.pluginVersion("1.1")), true));
		}
	}
}
