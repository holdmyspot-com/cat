package com.holdmyspot.cat.common.installer.engine.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.activation.InstalledState;
import com.holdmyspot.cat.common.installer.engine.UpdateResult;
import com.holdmyspot.cat.common.installer.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import java.io.IOException;
import java.nio.file.Files;
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
	 * The migration registry of a release that registers only the per-harness layout migration.
	 */
	private static final String LAYOUT = "1.0.0\tper-harness-layout.sh\n";

	/**
	 * Creates the test.
	 */
	public DowngradeTest()
	{
	}

	/**
	 * Supplies migration registries of the active release 1.2 that register no data change after release 1.1: none at
	 * all, the layout migration, and a migration for release 1.1 itself, which release 1.1 already expects.
	 *
	 * @return the registries
	 */
	@DataProvider
	public Object[][] compatibleRegistries()
	{
		return new Object[][]{{"# No migration\n"}, {LAYOUT}, {LAYOUT + "1.1\tsplit-state.sh\n"}};
	}

	/**
	 * An install of an older release whose data structures the active release did not change installs that release
	 * like a newer one, and records the older release's data version.
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
				new ReleaseCatalog("1.1", LAYOUT).add("1.2", registry));
			fixture.installActive("1.2");

			UpdateResult result = fixture.install("v1.1");

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UPDATED);
			requireThat(result.version(), "version").isEqualTo(Optional.of(Releases.pluginVersion("1.1")));
			requireThat(result.previousVersion(), "previousVersion").
				isEqualTo(Optional.of(Releases.pluginVersion("1.2")));
			requireThat(fixture.provider.installed(fixture.home), "installed").
				isEqualTo(new InstalledState(Optional.of(Releases.pluginVersion("1.1")), true));
			requireThat(Files.readString(fixture.migrationVersion()), "dataVersion").isEqualTo("1.1\n");
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
				new ReleaseCatalog("1.1", LAYOUT).add("1.2", LAYOUT + "1.1\tearlier.sh\n1.2\tsplit-state.sh\n"));
			fixture.installActive("1.2");
			Map<String, String> before = Releases.snapshot(fixture.home);

			UpdateResult result = fixture.install("v1.1");

			requireThat(result.result(), "result").isEqualTo(Result.REFUSED);
			requireThat(result.message(), "message").isEqualTo("Release 1.1 is older than the active release 1.2, " +
				"and CAT's data structures changed between them (split-state.sh). They are not backward compatible, " +
				"so installing 1.1 would lose data.");
			requireThat(Releases.snapshot(fixture.home), "after").isEqualTo(before);
		}
	}

	/**
	 * A downgrade below release 1.0.0 ignores the per-harness layout migration, which every installation of this
	 * installer has already performed, and records the layout's data version rather than an older one.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void ignoresLayoutMigration() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("downgrade-layout-")),
				new ReleaseCatalog("0.9", "# No migration\n").add("1.1", LAYOUT));
			fixture.installActive("1.1");

			UpdateResult result = fixture.install("v0.9");

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UPDATED);
			requireThat(fixture.provider.installed(fixture.home).version(), "installed").
				isEqualTo(Optional.of(Releases.pluginVersion("0.9")));
			requireThat(Files.readString(fixture.migrationVersion()), "dataVersion").isEqualTo("1.0.0\n");
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
				new ReleaseCatalog("1.1", LAYOUT).add("1.2", LAYOUT).latest("1.1"));
			fixture.installActive("1.2");
			Map<String, String> before = Releases.snapshot(fixture.home);

			UpdateResult result = fixture.install(null);

			requireThat(result.result(), "result").isEqualTo(Result.REFUSED);
			requireThat(result.message(), "message").contains("1.1").contains("1.2");
			requireThat(Releases.snapshot(fixture.home), "after").isEqualTo(before);
		}
	}
}
