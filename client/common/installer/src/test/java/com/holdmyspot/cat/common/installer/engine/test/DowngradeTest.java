package com.holdmyspot.cat.common.installer.engine.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.activation.InstalledState;
import com.holdmyspot.cat.common.installer.engine.UpdateResult;
import com.holdmyspot.cat.common.installer.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Exercises an install of a release older than the active one, which the installer accepts only while CAT's data
 * structures did not change between the two releases.
 */
public final class DowngradeTest
{
	/**
	 * The migration registry of a release that registers no data migration.
	 */
	private static final String NO_MIGRATION = "# No migration\n";

	/**
	 * Creates the test.
	 */
	public DowngradeTest()
	{
	}

	/**
	 * Supplies migration registries of the active release 1.2 that register no data change after release 1.1: none at
	 * all, and a migration for release 1.1 itself, which release 1.1 already expects.
	 *
	 * @return the registries
	 */
	@DataProvider
	public Object[][] compatibleRegistries()
	{
		return new Object[][]{{NO_MIGRATION}, {"1.1\tsplit-state.sh\n"}};
	}

	/**
	 * An install of an older release whose data structures the active release did not change installs that release
	 * like a newer one, and, running no migration, leaves the recorded data version unchanged.
	 *
	 * @param registry the active release's migration registry
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test(dataProvider = "compatibleRegistries")
	public void downgradesWithoutDataChange(String registry) throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("downgrade-allowed-")),
				new ReleaseCatalog("1.1", NO_MIGRATION).add("1.2", registry));
			fixture.installActive("1.2");
			Optional<String> dataVersion = fixture.dataVersion();

			UpdateResult result = fixture.install("v1.1");

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UPDATED);
			requireThat(result.version(), "version").isEqualTo(Optional.of(Releases.pluginVersion("1.1")));
			requireThat(result.previousVersion(), "previousVersion").
				isEqualTo(Optional.of(Releases.pluginVersion("1.2")));
			requireThat(fixture.provider.installed(fixture.home), "installed").
				isEqualTo(new InstalledState(Optional.of(Releases.pluginVersion("1.1")), true));
			requireThat(fixture.dataVersion(), "dataVersion").isEqualTo(dataVersion);
		}
	}

	/**
	 * An install of an older release is refused without changes when the active release registers a migration after
	 * the older release, up to and including the active release, and the message names that migration.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void refusesDowngradeAcrossDataChange() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("downgrade-refused-")),
				new ReleaseCatalog("1.1", NO_MIGRATION).add("1.2", "1.1\tearlier.sh\n1.2\tsplit-state.sh\n"));
			fixture.installActive("1.2");
			Map<String, String> before = Releases.snapshot(fixture.home);

			UpdateResult result = fixture.install("v1.1");

			requireThat(result.result(), "result").isEqualTo(Result.REFUSED);
			requireThat(result.message(), "message").isEqualTo("Release 1.1 is older than the active release 1.2, " +
				"and CAT's data structures changed between them (split-state.sh). They are not backward compatible, " +
				"so installing 1.1 would lose data; run install with the active release 1.2 or a later one " +
				"instead.");
			requireThat(Releases.snapshot(fixture.home), "after").isEqualTo(before);
		}
	}

	/**
	 * An install that names no release never moves to an older one, even when the latest release is older than the
	 * active one, and changes nothing.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void latestNeverDowngrades() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("downgrade-latest-")),
				new ReleaseCatalog("1.1", NO_MIGRATION).add("1.2", NO_MIGRATION).latest("1.1"));
			fixture.installActive("1.2");
			Map<String, String> before = Releases.snapshot(fixture.home);

			UpdateResult result = fixture.install(null);

			requireThat(result.result(), "result").isEqualTo(Result.REFUSED);
			requireThat(result.message(), "message").contains("1.1").contains("1.2");
			requireThat(Releases.snapshot(fixture.home), "after").isEqualTo(before);
		}
	}
}
