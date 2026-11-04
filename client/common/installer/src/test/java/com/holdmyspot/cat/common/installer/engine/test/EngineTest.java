package com.holdmyspot.cat.common.installer.engine.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.keyPair;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.manifest;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.sign;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.trusted;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.installer.activation.ActivationStep;
import com.holdmyspot.cat.common.installer.activation.AddDirectory;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import com.holdmyspot.cat.common.installer.activation.ReplaceDirectory;
import com.holdmyspot.cat.common.installer.engine.ReleaseSource;
import com.holdmyspot.cat.common.installer.engine.StagedRelease;
import com.holdmyspot.cat.common.installer.engine.UpdateEngine;
import com.holdmyspot.cat.common.installer.engine.UpdateResult;
import com.holdmyspot.cat.common.installer.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;

/**
 * Exercises routine installer decisions using explicit fake homes and signed in-memory release data.
 */
public final class EngineTest
{
	/**
	 * A signed manifest whose version differs from the requested tag is refused with the installed bytes unchanged.
	 *
	 * @throws IOException if creating the fixture fails
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void refusesAnotherRequestedVersion() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("engine-"));
			Path home = root.resolve("home");
			Files.createDirectories(home.resolve("live"));
			Files.writeString(home.resolve("live/version"), "1.0");
			KeyPair key = keyPair();
			TrustPolicy policy = TrustPolicy.of(List.of(trusted("test-key", key)),
				URI.create("https://releases.example/cat/releases/download"), ReleaseVersion.parse("1.0"), 1,
				Map.of("harness-a", home));
			String manifest = manifest("1.1");
			ReleaseSource source = new MemorySource(manifest.getBytes(UTF_8), sign(manifest.getBytes(UTF_8), key));
			Releases.runtime(root.resolve("data"));
			UpdateEngine engine = new UpdateEngine(root.resolve("data"), policy, source, new LiveProvider(),
				"linux-x64", Clock.systemUTC(), interval -> () -> {});

			UpdateResult result = engine.update("v1.2", new RecordingIntegration().session());
			requireThat(result.result(), "result").isEqualTo(Result.REFUSED);
			requireThat(result.message(), "message").contains("1.2").contains("1.1");
			requireThat(Files.readString(home.resolve("live/version")), "liveVersion").isEqualTo("1.0");
		}
	}

	/**
	 * A verified archive replaces the live tree and retains the original installation.
	 *
	 * @throws IOException if fixture creation or inspection fails
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void activatesVerifiedArchive() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("engine-success-"));
			Path home = root.resolve("home");
			Files.createDirectories(home.resolve("live"));
			Files.writeString(home.resolve("live/version"), "1.0");
			Files.writeString(home.resolve("configuration"), "protected");
			Releases source = new Releases("1.1", "");
			Releases.installation(root.resolve("data"), "1.0");
			UpdateEngine engine = new UpdateEngine(root.resolve("data"), source.policy(home), source,
				new LiveProvider(), "linux-x64", Clock.systemUTC(), interval -> () -> {});

			UpdateResult result = engine.update("v1.1", new RecordingIntegration().session());

			requireThat(result.result(), "result").isEqualTo(Result.UPDATED);
			requireThat(result.result().exitStatus(), "exitStatus").isEqualTo(0);
			requireThat(Files.readString(home.resolve("live/version")), "liveVersion").isEqualTo("1.1");
			requireThat(Files.readString(home.resolve(".cat-previous-live/version")), "previousVersion").
				isEqualTo("1.0");
			requireThat(Files.readString(home.resolve("configuration")), "configuration").isEqualTo("protected");
			requireThat(Files.readString(home.resolve("migration-version")).strip(), "migrationVersion").
				isEqualTo("1.1");
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
			{"pluginVersion"}, {"binVersion"}, {"missingBinary"}, {"missingRegistry"}, {"migration"},
			{"traversal"}, {"symlink"}, {"hardLink"}};
	}

	/**
	 * Refused archives leave installation bytes and protected configuration intact.
	 *
	 * @param defect the release defect
	 * @throws IOException if fixture I/O fails
	 * @throws TrustException if policy construction fails
	 */
	@Test(dataProvider = "defects")
	public void refusesUnsafeRelease(String defect) throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("engine-refusal-"));
			Path home = root.resolve("home");
			Files.createDirectories(home.resolve("live"));
			Files.writeString(home.resolve("live/version"), "1.0");
			Files.writeString(home.resolve("configuration"), "protected");
			Releases source = new Releases("1.1", defect);
			Releases.runtime(root.resolve("data"));
			UpdateEngine engine = new UpdateEngine(root.resolve("data"), source.policy(home), source,
				new LiveProvider(), "linux-x64", Clock.systemUTC(), interval -> () -> {});
			UpdateResult result = engine.update("v1.1", new RecordingIntegration().session());
			requireThat(result.result(), "resultFor" + defect).isEqualTo(Result.REFUSED);
			requireThat(Files.readString(home.resolve("live/version")), "liveVersion").isEqualTo("1.0");
			requireThat(Files.readString(home.resolve("configuration")), "configuration").isEqualTo("protected");
			requireThat(Files.exists(home.resolve("migration-version")), "migrationMarkerCreated").isFalse();
		}
	}

	/**
	 * Setup over a harness whose plugin the shared installation of release 1.0 activated accepts a later release that
	 * registers the per-harness layout migration, and records that release as the harness's migration version.
	 *
	 * @throws IOException if fixture I/O fails
	 * @throws TrustException if policy construction fails
	 */
	@Test
	public void setupAppliesLayoutMigration() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("engine-layout-setup-"));
			Path home = root.resolve("home");
			Files.createDirectories(home.resolve("live"));
			Files.writeString(home.resolve("live/version"), "1.0");
			Files.writeString(home.resolve("migration-version"), "1.0\n");
			Releases source = new Releases("1.1", "layout");
			Releases.installation(root.resolve("data"), "1.1");
			UpdateEngine engine = new UpdateEngine(root.resolve("data"), source.policy(home), source,
				new LiveProvider(), "linux-x64", Clock.systemUTC(), interval -> () -> {});

			UpdateResult result = engine.setUp("1.1", new RecordingIntegration().session());

			requireThat(result.result(), "result").isEqualTo(Result.UPDATED);
			requireThat(Files.readString(home.resolve("live/version")), "liveVersion").isEqualTo("1.1");
			requireThat(Files.readString(home.resolve("migration-version")), "marker").isEqualTo("1.1\n");
		}
	}

	/**
	 * Setup still refuses a release that registers a data migration besides the per-harness layout migration, and
	 * leaves the harness's migration version unchanged.
	 *
	 * @throws IOException if fixture I/O fails
	 * @throws TrustException if policy construction fails
	 */
	@Test
	public void setupRefusesDataMigration() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("engine-layout-migration-"));
			Path home = root.resolve("home");
			Files.createDirectories(home.resolve("live"));
			Files.writeString(home.resolve("live/version"), "1.0");
			Files.writeString(home.resolve("migration-version"), "1.0\n");
			Releases source = new Releases("1.1", "layoutAndMigration");
			Releases.installation(root.resolve("data"), "1.1");
			UpdateEngine engine = new UpdateEngine(root.resolve("data"), source.policy(home), source,
				new LiveProvider(), "linux-x64", Clock.systemUTC(), interval -> () -> {});

			UpdateResult result = engine.setUp("1.1", new RecordingIntegration().session());

			requireThat(result.result(), "result").isEqualTo(Result.REFUSED);
			requireThat(result.message(), "message").contains("data migration 1.1");
			requireThat(Files.readString(home.resolve("live/version")), "liveVersion").isEqualTo("1.0");
			requireThat(Files.readString(home.resolve("migration-version")), "marker").isEqualTo("1.0\n");
		}
	}

	/**
	 * Release 1.0, activated by this installer, records the per-harness layout migration as applied, so that a routine
	 * update to a later release that registers that migration is accepted.
	 *
	 * @throws IOException if fixture I/O fails
	 * @throws TrustException if policy construction fails
	 */
	@Test
	public void updateAfterLayoutIsAccepted() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("engine-layout-update-"));
			Path home = root.resolve("home");
			Files.createDirectories(home.resolve("live"));
			Files.writeString(home.resolve("live/version"), "1.0");
			Releases first = new Releases("1.0", "");
			Releases.installation(root.resolve("data"), "1.0");
			UpdateEngine setup = new UpdateEngine(root.resolve("data"), first.policy(home), first,
				new LiveProvider(), "linux-x64", Clock.systemUTC(), interval -> () -> {});
			requireThat(setup.setUp("1.0", new RecordingIntegration().session()).result(), "setup").
				isEqualTo(Result.UPDATED);
			requireThat(Files.readString(home.resolve("migration-version")), "marker").
				isEqualTo(StagedRelease.LAYOUT_MIGRATION + "\n");
			Releases later = new Releases("1.1", "layout");
			UpdateEngine engine = new UpdateEngine(root.resolve("data"), later.policy(home), later,
				new LiveProvider(), "linux-x64", Clock.systemUTC(), interval -> () -> {});

			UpdateResult result = engine.update("v1.1", new RecordingIntegration().session());

			requireThat(result.result(), "result").isEqualTo(Result.UPDATED);
			requireThat(Files.readString(home.resolve("migration-version")), "updatedMarker").isEqualTo("1.1\n");
		}
	}

	/**
	 * Undoing an update whose runtime replacement the stable launcher abandoned restores the replaced plugin and the
	 * migration marker, and consumes the activation record, so that the installation keeps no bundle to return to.
	 *
	 * @throws IOException if fixture I/O fails
	 * @throws TrustException if policy construction fails
	 */
	@Test
	public void undoRestoresInstallationAndMarker() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("engine-undo-"));
			Path home = root.resolve("home");
			Files.createDirectories(home.resolve("live"));
			Files.writeString(home.resolve("live/version"), "1.0");
			Files.writeString(home.resolve("migration-version"), "1.0\n");
			Releases source = new Releases("1.1", "");
			Releases.installation(root.resolve("data"), "1.0");
			UpdateEngine engine = new UpdateEngine(root.resolve("data"), source.policy(home), source,
				new LiveProvider(), "linux-x64", Clock.systemUTC(), interval -> () -> {});
			requireThat(engine.update("v1.1", new RecordingIntegration().
				session()).result(), "update").isEqualTo(Result.UPDATED);
			LauncherSwap.abandon(root.resolve("data"));
			Releases installed = new Releases("1.0", "");
			UpdateEngine undoing = new UpdateEngine(root.resolve("data"), installed.policy(home), installed,
				new LiveProvider(), "linux-x64", Clock.systemUTC(), interval -> () -> {});

			requireThat(undoing.update("v1.0", new RecordingIntegration().session()).result(), "undo").
				isEqualTo(Result.UP_TO_DATE);

			requireThat(Files.readString(home.resolve("live/version")), "version").isEqualTo("1.0");
			requireThat(Files.readString(home.resolve("migration-version")), "marker").isEqualTo("1.0\n");
			requireThat(undoing.status().previousVersion(), "previous").isEqualTo(Optional.empty());
		}
	}

	/**
	 * A marker redirected through a symbolic-link ancestor is refused before live files change.
	 *
	 * @throws IOException if fixture I/O fails
	 * @throws TrustException if policy construction fails
	 */
	@Test
	public void refusesLinkedMigrationAncestor() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("engine-marker-link-"));
			Path home = root.resolve("home");
			Files.createDirectories(home.resolve("live"));
			Files.writeString(home.resolve("live/version"), "1.0");
			Path outside = Files.createDirectory(root.resolve("outside"));
			Files.createSymbolicLink(home.resolve("marker-parent"), outside);
			Releases source = new Releases("1.1", "");
			PluginActivation provider = new LiveProvider()
			{
				@Override
				public Path migrationVersionFile(Path providerHome)
				{
					return providerHome.resolve("marker-parent/migration-version");
				}
			};
			Releases.runtime(root.resolve("data"));
			UpdateEngine engine = new UpdateEngine(root.resolve("data"), source.policy(home), source,
				provider, "linux-x64", Clock.systemUTC(), interval -> () -> {});
			requireThat(engine.update("v1.1", new RecordingIntegration().
				session()).result(), "result").isEqualTo(Result.REFUSED);
			requireThat(Files.readString(home.resolve("live/version")), "version").isEqualTo("1.0");
			requireThat(Files.exists(outside.resolve("migration-version")), "outsideMarker").isFalse();
		}
	}

	/**
	 * Reading status does not create installer state.
	 *
	 * @throws IOException if fixture I/O fails
	 * @throws TrustException if policy construction fails
	 */
	@Test
	public void statusIsReadOnly() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("engine-status-"));
			Path home = root.resolve("home");
			Files.createDirectories(home.resolve("live"));
			Files.writeString(home.resolve("live/version"), "1.0");
			Releases source = new Releases("1.1", "");
			UpdateEngine engine = new UpdateEngine(root.resolve("data"), source.policy(home), source,
				new LiveProvider(), "linux-x64", Clock.systemUTC(), interval -> () -> {});
			requireThat(engine.status().version(), "version").isEqualTo(Optional.of("1.0"));
			requireThat(Files.exists(root.resolve("data")), "stateCreated").isFalse();
		}
	}

	/**
	 * A concurrent engine cannot enter transport or activation while the first engine holds the harness lock.
	 *
	 * @throws Exception if fixture I/O or bounded worker completion fails
	 */
	@Test
	public void serializesConcurrentOperations() throws Exception
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ExecutorService workers = Executors.newSingleThreadExecutor())
		{
			Path root = cleanup.add(testDirectory("engine-lock-"));
			Path home = root.resolve("home");
			Files.createDirectories(home.resolve("live"));
			Files.writeString(home.resolve("live/version"), "1.0");
			Releases release = new Releases("1.1", "");
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
				public byte[] signature(String tag)
				{
					return release.signature(tag);
				}

				@Override
				public void download(String tag, String name, long size, Path destination) throws IOException
				{
					release.download(tag, name, size, destination);
				}
			};
			Releases.installation(root.resolve("data"), "1.0");
			UpdateEngine first = new UpdateEngine(root.resolve("data"), release.policy(home), blocking,
				new LiveProvider(), "linux-x64", Clock.systemUTC(), interval -> () -> {}, Duration.ZERO);
			UpdateEngine second = new UpdateEngine(root.resolve("data"), release.policy(home), release,
				new LiveProvider(), "linux-x64", Clock.systemUTC(), interval -> () ->
				{
					throw new AssertionError("A zero wait must not pause");
				}, Duration.ZERO);
			Future<UpdateResult> running = workers.submit(() -> first.update("v1.1", new RecordingIntegration().
				session()));
			try
			{
				requireThat(entered.await(2, TimeUnit.SECONDS), "enteredTransport").isTrue();
				requireThat(second.update("v1.1", new RecordingIntegration().
					session()).result(), "contendingResult").isEqualTo(Result.LOCKED);
				AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);
				Clock clock = new LockClock(now, ZoneOffset.UTC);
				List<Duration> pauses = new ArrayList<>();
				Duration deadline = Duration.ofMinutes(5);
				UpdateEngine timed = new UpdateEngine(root.resolve("data"), release.policy(home), release,
					new LiveProvider(), "linux-x64", clock, interval -> () ->
					{
						pauses.add(interval);
						if (pauses.size() == 2)
							now.set(Instant.EPOCH.plus(deadline).minusMillis(20));
						else
							now.updateAndGet(time -> time.plus(interval));
					}, deadline);
				requireThat(timed.update("v1.1", new RecordingIntegration().
					session()).result(), "deadlineResult").isEqualTo(Result.LOCKED);
				requireThat(pauses, "lockPauses").isEqualTo(List.of(Duration.ofMillis(50), Duration.ofMillis(50),
					Duration.ofMillis(20)));
				UpdateEngine interrupted = new UpdateEngine(root.resolve("data"), release.policy(home), release,
					new LiveProvider(), "linux-x64", clock, interval -> Pause.interruptible(() ->
					{
						throw new InterruptedException("Interrupted lock wait");
					}));
				try
				{
					requireThat(interrupted.update("v1.1", new RecordingIntegration().
						session()).result(), "interruptedResult").isEqualTo(Result.LOCKED);
					requireThat(Thread.currentThread().isInterrupted(), "interruptPreserved").isTrue();
				}
				finally
				{
					Thread.interrupted();
				}
				requireThat(Files.readString(home.resolve("live/version")), "versionBeforeRelease").isEqualTo("1.0");
			}
			finally
			{
				releaseFirst.countDown();
			}
			requireThat(running.get(2, TimeUnit.SECONDS).result(), "firstResult").isEqualTo(Result.UPDATED);
		}
	}

	/**
	 * A second-step filesystem failure restores the first replacement and the earlier kept copy.
	 *
	 * @throws IOException if fixture I/O fails
	 * @throws TrustException if policy construction fails
	 */
	@Test
	public void restoresAfterSecondStepFailure() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("engine-restore-"));
			Path home = root.resolve("home");
			Files.createDirectories(home.resolve("live"));
			Files.writeString(home.resolve("live/version"), "1.0");
			Files.createDirectories(home.resolve(".cat-previous-live"));
			Files.writeString(home.resolve(".cat-previous-live/version"), "0.9");
			Files.writeString(home.resolve("blocking"), "a file cannot be a destination parent");
			Files.writeString(home.resolve("migration-version"), "0.9\n");
			Releases source = new Releases("1.1", "");
			PluginActivation provider = new LiveProvider()
			{
				@Override
				public List<ActivationStep> plan(Path providerHome, String version)
				{
					return List.of(new ReplaceDirectory(providerHome.resolve("live"), providerHome),
						new AddDirectory(providerHome.resolve("blocking/new"), providerHome));
				}
			};
			Releases.installation(root.resolve("data"), "1.0");
			UpdateEngine engine = new UpdateEngine(root.resolve("data"), source.policy(home), source,
				provider, "linux-x64", Clock.systemUTC(), interval -> () -> {});
			UpdateResult result = engine.update("v1.1", new RecordingIntegration().session());
			requireThat(result.result(), "result").isEqualTo(Result.RESTORED);
			requireThat(result.result().exitStatus(), "exitStatus").isEqualTo(5);
			requireThat(Files.readString(home.resolve("live/version")), "version").isEqualTo("1.0");
			requireThat(Files.readString(home.resolve(".cat-previous-live/version")), "previousVersion").isEqualTo("0.9");
			requireThat(Files.readString(home.resolve("migration-version")), "marker").isEqualTo("0.9\n");
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
		 * @param now the shared instant
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

	/**
	 * Supplies signed bytes without performing network or filesystem activation itself.
	 *
	 * @param manifest manifest bytes
	 * @param signature signature bytes
	 */
	private record MemorySource(byte[] manifest, byte[] signature) implements ReleaseSource
	{
		@Override
		public String latestTag()
		{
			return "v1.1";
		}

		@Override
		public byte[] manifest(String tag)
		{
			return manifest.clone();
		}

		@Override
		public byte[] signature(String tag)
		{
			return signature.clone();
		}

		@Override
		public void download(String tag, String assetName, long expectedSize, Path destination)
		{
			throw new AssertionError("A mismatched manifest must be refused before downloading an archive");
		}
	}
}
