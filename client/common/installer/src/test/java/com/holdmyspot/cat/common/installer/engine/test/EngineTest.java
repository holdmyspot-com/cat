package com.holdmyspot.cat.common.installer.engine.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.InstalledState;
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
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests the installer's routine decisions about signed releases: which release it accepts, what a first install
 * records, and how concurrent operations on one harness are serialized.
 */
public final class EngineTest
{
	/**
	 * The migration registry of a release that registers no data migration.
	 */
	private static final String NO_MIGRATION = "# No migration\n";

	/**
	 * Creates the test.
	 */
	public EngineTest()
	{
	}

	/**
	 * A signed manifest whose version differs from the requested tag is refused before anything is downloaded or
	 * installed.
	 *
	 * @throws IOException    if creating the fixture fails
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void refusesAnotherRequestedVersion() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("engine-other-version-")),
				new ReleaseCatalog("1.1", NO_MIGRATION));
			ReleaseSource source = new ReleaseSource()
			{
				@Override
				public String latestTag()
				{
					return "v1.1";
				}

				@Override
				public byte[] manifest(String tag) throws IOException
				{
					return fixture.catalog.manifest("v1.1");
				}

				@Override
				public byte[] signature(String tag) throws IOException
				{
					return fixture.catalog.signature("v1.1");
				}

				@Override
				public void download(String tag, String assetName, long expectedSize, Path destination)
				{
					throw new AssertionError("A mismatched manifest must be refused before downloading an archive");
				}
			};

			UpdateResult result = fixture.engine(source, Clock.systemUTC(), interval -> () ->
			{
			}, Duration.ZERO, false).install("v1.2", fixture.request(fixture.root.resolve("download/plugin")));

			requireThat(result.result(), "result").isEqualTo(Result.REFUSED);
			requireThat(result.message(), "message").contains("1.2").contains("1.1");
			requireThat(fixture.integration.registered(), "registered").isEmpty();
		}
	}

	/**
	 * A first install of a verified release asks the harness to install it into its version folder, bound to the Java
	 * runtime in that folder; its release registers no data migration, so no data version is recorded.
	 *
	 * @throws IOException    if fixture creation or inspection fails
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void installsVerifiedRelease() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("engine-install-")),
				new ReleaseCatalog("1.1", NO_MIGRATION));

			UpdateResult result = fixture.install("v1.1");

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.INSTALLED);
			requireThat(result.result().exitStatus(), "exitStatus").isEqualTo(0);
			requireThat(result.version(), "version").isEqualTo(Optional.of(Releases.pluginVersion("1.1")));
			requireThat(fixture.integration.registered(), "registered").
				isEqualTo(List.of(PluginVersion.parse(Releases.pluginVersion("1.1"))));
			Path folder = fixture.versionFolder("1.1");
			requireThat(Files.readString(folder.resolve("bin/VERSION")), "pluginVersion").
				isEqualTo(Releases.pluginVersion("1.1"));
			requireThat(Files.readString(folder.resolve("bin/runtime-binding")), "binding").
				isEqualTo(folder.resolve("bin/runtime") + "\n");
			requireThat(fixture.dataVersion(), "migrationVersion").isEmpty();
		}
	}

	/**
	 * Each time the harness installs a release into its version folder, install has the provider place CAT's
	 * companion files from that folder.
	 *
	 * @throws IOException    if fixture creation or inspection fails
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void installsCompanionsFromVersionFolder() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("engine-companions-")),
				new ReleaseCatalog("1.0", NO_MIGRATION).add("1.1", NO_MIGRATION));
			fixture.installActive("1.0");

			UpdateResult result = fixture.install("v1.1");

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UPDATED);
			requireThat(fixture.provider.companions(), "companions").
				isEqualTo(List.of(fixture.versionFolder("1.0"), fixture.versionFolder("1.1")));
		}
	}

	/**
	 * Supplies defects in authenticated metadata, archive structure and staged plugin metadata.
	 *
	 * @return archive defect names
	 */
	@DataProvider
	public Object[][] defects()
	{
		return new Object[][]{{"digest"}, {"size"}, {"unknownKey"}, {"manifest"}, {"truncated"}, {"top"},
			{"pluginVersion"}, {"binVersion"}, {"missingBinary"}, {"missingRegistry"},
			{"traversal"}, {"symlink"}, {"hardLink"}};
	}

	/**
	 * A refused release is never installed: the harness keeps its active version and its data migration version.
	 *
	 * @param defect the release defect
	 * @throws IOException    if fixture I/O fails
	 * @throws TrustException if policy construction fails
	 */
	@Test(dataProvider = "defects")
	public void refusesUnsafeRelease(String defect) throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("engine-refusal-")),
				new ReleaseCatalog("1.0", NO_MIGRATION));
			fixture.registerExisting(Releases.pluginVersion("1.0"));
			Optional<String> migrationVersion = fixture.dataVersion();
			fixture.catalog.addDefective("1.1", defect);

			UpdateResult result = fixture.install("v1.1");

			requireThat(result.result(), "resultFor" + defect).withContext(result.message(), "message").
				isEqualTo(Result.REFUSED);
			InstalledState installed = fixture.provider.installed(fixture.home);
			requireThat(installed.version(), "installed").isEqualTo(Optional.of(Releases.pluginVersion("1.0")));
			requireThat(fixture.integration.registered(), "registered").isEmpty();
			requireThat(fixture.dataVersion(), "migrationVersion").isEqualTo(migrationVersion);
		}
	}

	/**
	 * A data migration version file that a symbolic-link ancestor redirects is refused before the harness installs
	 * anything, and nothing is written where the link points.
	 *
	 * @throws IOException    if fixture I/O fails
	 * @throws TrustException if policy construction fails
	 */
	@Test
	public void refusesLinkedMigrationAncestor() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("engine-marker-link-")).toRealPath();
			RegisteredProvider provider = new RegisteredProvider()
			{
				@Override
				public Path migrationVersionFile(Path home)
				{
					return home.resolve("marker-parent/migration-version");
				}
			};
			EngineFixture fixture = new EngineFixture(root, new ReleaseCatalog("1.1", NO_MIGRATION), provider);
			Path outside = Files.createDirectory(root.resolve("outside"));
			Files.createSymbolicLink(fixture.home.resolve("marker-parent"), outside);

			UpdateResult result = fixture.install("v1.1");

			requireThat(result.result(), "result").isEqualTo(Result.REFUSED);
			requireThat(fixture.integration.registered(), "registered").isEmpty();
			requireThat(Files.exists(outside.resolve("migration-version")), "outsideMarker").isFalse();
		}
	}

	/**
	 * Reading status reports the harness's version and creates no installer state.
	 *
	 * @throws IOException    if fixture I/O fails
	 * @throws TrustException if policy construction fails
	 */
	@Test
	public void statusIsReadOnly() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("engine-status-")),
				new ReleaseCatalog("1.1", NO_MIGRATION));
			Files.writeString(RecordingIntegration.registeredVersion(fixture.home), Releases.pluginVersion("1.1"));

			UpdateResult status = fixture.engine().status();

			requireThat(status.result(), "result").isEqualTo(Result.STATUS);
			requireThat(status.version(), "version").isEqualTo(Optional.of(Releases.pluginVersion("1.1")));
			requireThat(Files.exists(fixture.data()), "stateCreated").isFalse();
		}
	}

	/**
	 * A concurrent engine cannot enter transport or installation while the first engine holds the harness lock, waits
	 * for it no longer than its deadline, and stops waiting when interrupted.
	 *
	 * @throws Exception if fixture I/O or bounded worker completion fails
	 */
	@Test
	public void serializesConcurrentOperations() throws Exception
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ExecutorService workers = Executors.newSingleThreadExecutor())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("engine-lock-")),
				new ReleaseCatalog("1.1", NO_MIGRATION));
			ReleaseCatalog release = fixture.catalog;
			CountDownLatch entered = new CountDownLatch(1);
			CountDownLatch releaseFirst = new CountDownLatch(1);
			ReleaseSource blocking = new ReleaseSource()
			{
				@Override
				public String latestTag()
				{
					return "v1.1";
				}

				@Override
				public byte[] manifest(String tag) throws IOException
				{
					entered.countDown();
					try
					{
						if (!releaseFirst.await(2, TimeUnit.SECONDS))
							throw new IOException("The test did not release the held operation");
					}
					catch (InterruptedException e)
					{
						Thread.currentThread().interrupt();
						throw new IOException("Interrupted test transport", e);
					}
					return release.manifest(tag);
				}

				@Override
				public byte[] signature(String tag) throws IOException
				{
					return release.signature(tag);
				}

				@Override
				public void download(String tag, String name, long size, Path destination) throws IOException
				{
					release.download(tag, name, size, destination);
				}
			};
			Path image = fixture.root.resolve("download/plugin");
			UpdateEngine first = fixture.engine(blocking, Clock.systemUTC(), interval -> () ->
			{
			}, Duration.ZERO, false);
			UpdateEngine second = fixture.engine(release, Clock.systemUTC(), interval -> () ->
			{
				throw new AssertionError("A zero wait must not pause");
			}, Duration.ZERO, false);
			Future<UpdateResult> running = workers.submit(() -> first.install("v1.1", fixture.request(image)));
			try
			{
				requireThat(entered.await(2, TimeUnit.SECONDS), "enteredTransport").isTrue();
				requireThat(second.install("v1.1", fixture.request(image)).result(), "contendingResult").
					isEqualTo(Result.LOCKED);
				AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);
				Clock clock = new LockClock(now, ZoneOffset.UTC);
				List<Duration> pauses = new ArrayList<>();
				Duration deadline = Duration.ofMinutes(5);
				UpdateEngine timed = fixture.engine(release, clock, interval -> () ->
				{
					pauses.add(interval);
					if (pauses.size() == 2)
						now.set(Instant.EPOCH.plus(deadline).minusMillis(20));
					else
						now.updateAndGet(time -> time.plus(interval));
				}, deadline, false);
				requireThat(timed.install("v1.1", fixture.request(image)).result(), "deadlineResult").
					isEqualTo(Result.LOCKED);
				requireThat(pauses, "lockPauses").isEqualTo(List.of(Duration.ofMillis(50), Duration.ofMillis(50),
					Duration.ofMillis(20)));
				UpdateEngine interrupted = fixture.engine(release, clock, interval -> Pause.interruptible(() ->
				{
					throw new InterruptedException("Interrupted lock wait");
				}), deadline, false);
				try
				{
					requireThat(interrupted.install("v1.1", fixture.request(image)).result(), "interruptedResult").
						isEqualTo(Result.LOCKED);
					requireThat(Thread.currentThread().isInterrupted(), "interruptPreserved").isTrue();
				}
				finally
				{
					Thread.interrupted();
				}
				requireThat(fixture.integration.registered(), "registeredBeforeRelease").isEmpty();
			}
			finally
			{
				releaseFirst.countDown();
			}
			requireThat(running.get(2, TimeUnit.SECONDS).result(), "firstResult").isEqualTo(Result.INSTALLED);
		}
	}

	/**
	 * Shares manually advanced instants across zone views so lock deadlines require no wall-clock wait.
	 */
	private static final class LockClock extends Clock
	{
		private final AtomicReference<Instant> now;
		private final ZoneId zone;

		/**
		 * Creates a view of the controlled instant.
		 *
		 * @param now  the shared instant
		 * @param zone the view's zone
		 */
		private LockClock(AtomicReference<Instant> now, ZoneId zone)
		{
			this.now = now;
			this.zone = zone;
		}

		@Override
		public ZoneId getZone()
		{
			return zone;
		}

		@Override
		public Clock withZone(ZoneId zone)
		{
			return new LockClock(now, zone);
		}

		@Override
		public Instant instant()
		{
			return now.get();
		}
	}
}
