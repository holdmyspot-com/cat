package com.holdmyspot.cat.common.installer.engine.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.engine.RuntimeSwap;
import com.holdmyspot.cat.common.installer.engine.RuntimeSwap.Operation;
import com.holdmyspot.cat.common.installer.engine.StagedRelease;
import com.holdmyspot.cat.common.installer.engine.UpdateEngine;
import com.holdmyspot.cat.common.installer.engine.UpdateResult;
import com.holdmyspot.cat.common.installer.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Exercises an update to a release older than the active one, which the installer accepts only while CAT's data
 * structures did not change between the two releases.
 */
public final class DowngradeTest
{
	/**
	 * The migration registry of a release that registers only the per-harness layout migration.
	 */
	private static final String LAYOUT = "1.0.0\tper-harness-layout.sh\n";

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
	 * An update to an older release whose data structures the active release did not change moves the whole bundle to
	 * that release, like an update to a newer one, and records the older release's data version.
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
			Path root = cleanup.add(testDirectory("downgrade-allowed-"));
			Path data = root.resolve("data");
			Path home = active(root, data, "1.2");
			ReleaseCatalog catalog = new ReleaseCatalog("1.1", LAYOUT).add("1.2", registry);

			UpdateResult result = engine(data, home, catalog, "1.0").update("v1.1",
				new RecordingIntegration().session());

			requireThat(result.result(), "result").isEqualTo(Result.UPDATED);
			requireThat(result.version(), "version").isEqualTo(Optional.of("1.1"));
			requireThat(result.previousVersion(), "previousVersion").isEqualTo(Optional.of("1.2"));
			requireThat(Files.readString(home.resolve("live/version")), "plugin").isEqualTo("1.1");
			requireThat(Files.readString(home.resolve("migration-version")), "dataVersion").isEqualTo("1.1\n");
			requireThat(Files.readString(data.resolve("runtime.next/release")), "stagedRuntime").
				isEqualTo("runtime 1.1");
			requireThat(engine(data, home, catalog, "1.0").pendingSwap(), "pendingSwap").
				isEqualTo(Optional.of(new RuntimeSwap(Operation.UPDATE, "1.2", "1.1")));
		}
	}

	/**
	 * An update to an older release is refused without changes when the active release registers a migration after
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
			Path root = cleanup.add(testDirectory("downgrade-refused-"));
			Path data = root.resolve("data");
			Path home = active(root, data, "1.2");
			ReleaseCatalog catalog = new ReleaseCatalog("1.1", LAYOUT).
				add("1.2", LAYOUT + "1.1\tearlier.sh\n1.2\tsplit-state.sh\n");
			Map<String, String> before = Releases.snapshot(root);

			UpdateResult result = engine(data, home, catalog, "1.0").update("v1.1",
				new RecordingIntegration().session());

			requireThat(result.result(), "result").isEqualTo(Result.REFUSED);
			requireThat(result.message(), "message").isEqualTo("Release 1.1 is older than the active release 1.2, " +
				"and CAT's data structures changed between them (split-state.sh). They are not backward compatible, " +
				"so installing 1.1 would lose data.");
			requireThat(Releases.snapshot(root), "after").isEqualTo(before);
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
			Path root = cleanup.add(testDirectory("downgrade-layout-"));
			Path data = root.resolve("data");
			Path home = active(root, data, "1.1");
			ReleaseCatalog catalog = new ReleaseCatalog("0.9", "# No migration\n").add("1.1", LAYOUT);

			UpdateResult result = engine(data, home, catalog, "0.9").update("v0.9",
				new RecordingIntegration().session());

			requireThat(result.result(), "result").isEqualTo(Result.UPDATED);
			requireThat(Files.readString(home.resolve("live/version")), "plugin").isEqualTo("0.9");
			requireThat(Files.readString(home.resolve("migration-version")), "dataVersion").isEqualTo("1.0.0\n");
		}
	}

	/**
	 * An update that names no release never moves to an older one, even when the latest release is older than the
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
			Path root = cleanup.add(testDirectory("downgrade-latest-"));
			Path data = root.resolve("data");
			Path home = active(root, data, "1.2");
			ReleaseCatalog catalog = new ReleaseCatalog("1.1", LAYOUT).add("1.2", LAYOUT).latest("1.1");
			Map<String, String> before = Releases.snapshot(root);

			UpdateResult result = engine(data, home, catalog, "1.0").update(null,
				new RecordingIntegration().session());

			requireThat(result.result(), "result").isEqualTo(Result.REFUSED);
			requireThat(result.message(), "message").contains("1.1").contains("1.2");
			requireThat(Releases.snapshot(root), "after").isEqualTo(before);
		}
	}

	/**
	 * Creates an installation whose whole bundle and plugin are at a release, with the data version that activating
	 * that release records.
	 *
	 * @param root    the test directory
	 * @param data    the installation's installer data directory
	 * @param version the active release
	 * @return the harness home
	 * @throws IOException    if the fixture cannot be written
	 * @throws TrustException if the version is malformed
	 */
	static Path active(Path root, Path data, String version) throws IOException, TrustException
	{
		Path home = BundleUpdateTest.plugin(root, version);
		Files.writeString(home.resolve("migration-version"), StagedRelease.migrationVersion(version) + "\n");
		Releases.installation(data, version);
		Files.createDirectories(data.resolve("state/harness-a"));
		return home;
	}

	/**
	 * Creates an engine for the fixture's harness that does not wait for the lock and runs as a user without
	 * administrative rights.
	 *
	 * @param data    the installation's installer data directory
	 * @param home    the harness home
	 * @param catalog the release source, whose key the policy trusts
	 * @param minimum the lowest release version that the policy approves
	 * @return the engine
	 * @throws TrustException if the policy is invalid
	 */
	static UpdateEngine engine(Path data, Path home, ReleaseCatalog catalog, String minimum) throws TrustException
	{
		return new UpdateEngine(data, catalog.policy(home, minimum), catalog, new LiveProvider(), "linux-x64",
			Clock.systemUTC(), interval -> () -> {}, Duration.ZERO, _ -> false);
	}
}
