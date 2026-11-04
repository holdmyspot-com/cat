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
import java.nio.file.Path;
import java.util.List;
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
				new ReleaseCatalog("1.1", "# No migration\n"));
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
	 * Installing the signed build that is already active and consistent installs nothing and registers that build with
	 * the harness again, so that a plugin that the harness's own command removed is registered again.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void sameBuildRegistersAgain() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("full-version-same-")),
				new ReleaseCatalog("1.1", "# No migration\n"));
			Path active = fixture.installActive("1.1");
			Map<String, String> before = Releases.snapshot(active);
			int calls = fixture.integration.calls().size();

			UpdateResult result = fixture.install("v1.1");

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UP_TO_DATE);
			List<String> later = fixture.integration.calls().subList(calls, fixture.integration.calls().size());
			requireThat(later, "laterCalls").isEqualTo(List.of("register"));
			requireThat(fixture.integration.registered().getLast(), "registered").
				isEqualTo(PluginVersion.parse(Releases.pluginVersion("1.1")));
			requireThat(Releases.snapshot(active), "active").isEqualTo(before);
		}
	}
}
