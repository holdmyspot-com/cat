package com.holdmyspot.cat.common.updater.engine.test;

import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.updater.engine.ReleaseSource;
import com.holdmyspot.cat.common.updater.engine.UpdateEngine;
import com.holdmyspot.cat.common.updater.engine.UpdateResult;
import com.holdmyspot.cat.common.updater.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests installing the plugin where a harness has none, the runtime requirement check, the runtime binding, and
 * registration with the harness.
 */
public final class InstallTest
{
	/**
	 * Creates the test.
	 */
	public InstallTest()
	{
	}

	/**
	 * Install adds the release where the harness has no plugin, binds it to the installation's runtime, registers it
	 * with the harness and records the highest version.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void installsFromAbsence() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("install-absent-"));
			Path home = Files.createDirectories(root.resolve("home"));
			Path data = root.resolve("data");
			Path runtime = Releases.installation(data, "1.1");
			RecordingIntegration integration = new RecordingIntegration();

			UpdateResult result = engine(root, home, new Releases("1.1", "")).install("v1.1",
				integration.registration());

			requireThat(result.result(), "result").isEqualTo(Result.INSTALLED);
			requireThat(result.result().exitStatus(), "exitStatus").isEqualTo(0);
			requireThat(result.version().orElseThrow(), "version").isEqualTo("1.1");
			requireThat(Files.readString(home.resolve("live/version")), "liveVersion").isEqualTo("1.1");
			requireThat(Files.readString(home.resolve("live/client/runtime-binding")), "binding").
				isEqualTo(runtime.toAbsolutePath().normalize() + "\n");
			requireThat(integration.calls(), "calls").isEqualTo(List.of("register"));
			requireThat(Files.readString(data.resolve("state/harness-a/highest-version")), "highest").
				isEqualTo("1.1\n");
		}
	}

	/**
	 * An install whose plugin cannot get a startup cache still installs the plugin, and reports that no startup cache
	 * was written.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void installsWithoutStartupCache() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("install-uncached-"));
			Path home = Files.createDirectories(root.resolve("home"));
			Releases.installation(root.resolve("data"), "1.1");

			UpdateResult result = engine(root, home, new Releases("1.1", "")).install("v1.1",
				new RecordingIntegration().registration());

			requireThat(result.result(), "result").isEqualTo(Result.INSTALLED);
			requireThat(result.aotCache(), "aotCache").isEqualTo(Optional.of(false));
			requireThat(Files.readString(home.resolve("live/version")), "liveVersion").isEqualTo("1.1");
		}
	}

	/**
	 * A partial installation that the harness does not report as installed is deleted before the release is added.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void repairsPartialInstallation() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("install-partial-"));
			Path home = Files.createDirectories(root.resolve("home"));
			Files.createDirectories(home.resolve("live/leftover"));
			Files.writeString(home.resolve("live/leftover/file"), "partial");
			Releases.installation(root.resolve("data"), "1.1");

			UpdateResult result = engine(root, home, new Releases("1.1", "")).install("v1.1",
				new RecordingIntegration().registration());

			requireThat(result.result(), "result").isEqualTo(Result.INSTALLED);
			requireThat(Files.exists(home.resolve("live/leftover"), NOFOLLOW_LINKS), "leftover").isFalse();
			requireThat(Files.readString(home.resolve("live/version")), "liveVersion").isEqualTo("1.1");
		}
	}

	/**
	 * A harness home that is reached through a symbolic link is installed into, and a link inside the partial
	 * installation is deleted as an entry, leaving what it points to.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void installsIntoLinkedHome() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("install-linked-")).toRealPath();
			Path realHome = Files.createDirectories(root.resolve("real-home"));
			Files.createDirectories(realHome.resolve("live/leftover"));
			Path outside = Files.writeString(root.resolve("outside"), "kept");
			Files.createSymbolicLink(realHome.resolve("live/leftover/escape"), outside);
			Path home = Files.createSymbolicLink(root.resolve("home"), realHome);
			Releases.installation(root.resolve("data"), "1.1");

			UpdateResult result = engine(root, home, new Releases("1.1", "")).install("v1.1",
				new RecordingIntegration().registration());

			requireThat(result.result(), "result").isEqualTo(Result.INSTALLED);
			requireThat(Files.exists(realHome.resolve("live/leftover"), NOFOLLOW_LINKS), "leftover").isFalse();
			requireThat(Files.readString(realHome.resolve("live/version")), "liveVersion").isEqualTo("1.1");
			requireThat(Files.readString(outside), "outside").isEqualTo("kept");
		}
	}

	/**
	 * When the harness refuses registration, the added plugin is deleted again and the harness has no plugin.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void registrationFailureRestoresAbsence() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("install-register-"));
			Path home = Files.createDirectories(root.resolve("home"));
			Path data = root.resolve("data");
			Releases.installation(data, "1.1");

			UpdateResult result = engine(root, home, new Releases("1.1", "")).install("v1.1",
				new RecordingIntegration().fail("register").registration());

			requireThat(result.result(), "result").isEqualTo(Result.RESTORED);
			requireThat(result.message(), "message").contains("register");
			requireThat(Files.exists(home.resolve("live"), NOFOLLOW_LINKS), "live").isFalse();
			requireThat(Files.exists(data.resolve("state/harness-a/activation.json"), NOFOLLOW_LINKS), "journal").
				isFalse();
			requireThat(Files.exists(data.resolve("state/harness-a/highest-version"), NOFOLLOW_LINKS), "highest").
				isFalse();
		}
	}

	/**
	 * An install that stopped before completing is recorded as an install, and the next operation returns the harness
	 * to having no plugin.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void interruptedInstallRecoversToAbsence() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("install-interrupted-"));
			Path home = Files.createDirectories(root.resolve("home"));
			Path data = root.resolve("data");
			Releases.installation(data, "1.1");
			Releases source = new Releases("1.1", "");
			try
			{
				engine(root, home, source).install("v1.1", new RecordingIntegration().crash("register").registration());
				throw new AssertionError("The stopped install completed");
			}
			catch (IllegalStateException _)
			{
				// The process stopped during registration.
			}
			Path journal = data.resolve("state/harness-a/activation.json");
			requireThat(Files.readString(journal), "journal").contains("\"operation\"").contains("\"install\"");
			requireThat(Files.exists(home.resolve("live"), NOFOLLOW_LINKS), "liveBeforeRecovery").isTrue();

			UpdateResult result = engine(root, home, source).rollback();

			requireThat(result.result(), "result").isEqualTo(Result.REFUSED);
			requireThat(Files.exists(home.resolve("live"), NOFOLLOW_LINKS), "live").isFalse();
			requireThat(Files.exists(journal, NOFOLLOW_LINKS), "journalAfterRecovery").isFalse();
		}
	}

	/**
	 * Install where the harness has no plugin adds the release that the installation runs, also when the harness has
	 * had a newer release, as after a rollback.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void installAfterRollbackAddsSelectedRelease() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("install-highest-"));
			Path home = Files.createDirectories(root.resolve("home"));
			Path data = root.resolve("data");
			Releases.installation(data, "1.1");
			Files.createDirectories(data.resolve("state/harness-a"));
			Files.writeString(data.resolve("state/harness-a/highest-version"), "1.2\n");

			UpdateResult result = engine(root, home, new Releases("1.1", "")).install(null,
				new RecordingIntegration().registration());

			requireThat(result.result(), "result").isEqualTo(Result.INSTALLED);
			requireThat(Files.readString(home.resolve("live/version")), "liveVersion").isEqualTo("1.1");
		}
	}

	/**
	 * Install without a release adds the release that the installation runs, not the latest release.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void installAddsSelectedRelease() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("install-selected-"));
			Path home = Files.createDirectories(root.resolve("home"));
			Releases.installation(root.resolve("data"), "1.1");
			Releases release = new Releases("1.1", "");
			ReleaseSource newer = new ReleaseSource()
			{
				@Override
				public String latestTag()
				{
					return "v1.2";
				}

				@Override
				public byte[] manifest(String tag)
				{
					return release.manifest(tag);
				}

				@Override
				public byte[] signature(String tag)
				{
					return release.signature(tag);
				}

				@Override
				public void download(String tag, String assetName, long expectedSize, Path destination)
					throws IOException
				{
					release.download(tag, assetName, expectedSize, destination);
				}
			};
			UpdateEngine engine = new UpdateEngine(root.resolve("data"), release.policy(home), newer,
				new LiveProvider(), "linux-x64", Clock.systemUTC(), interval -> () -> {});

			UpdateResult result = engine.install(null, new RecordingIntegration().registration());

			requireThat(result.result(), "result").isEqualTo(Result.INSTALLED);
			requireThat(Files.readString(home.resolve("live/version")), "liveVersion").isEqualTo("1.1");
		}
	}

	/**
	 * Install of a release other than the one that the installation runs, named by its tag or as the latest release,
	 * is refused before any change, where the harness has no plugin and where it has one.
	 *
	 * @param plugin the harness's plugin version, or null when it has none
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test(dataProvider = "plugins")
	public void installRefusesOtherRelease(String plugin) throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("install-other-"));
			Path home = Files.createDirectories(root.resolve("home"));
			if (plugin != null)
			{
				Files.createDirectories(home.resolve("live"));
				Files.writeString(home.resolve("live/version"), plugin);
			}
			Releases.installation(root.resolve("data"), "1.0");
			Map<String, String> before = Releases.snapshot(root);
			RecordingIntegration integration = new RecordingIntegration();
			UpdateEngine engine = engine(root, home, new Releases("1.1", ""));

			for (String tag : List.of("v1.1", UpdateEngine.LATEST))
			{
				UpdateResult result = engine.install(tag, integration.registration());

				requireThat(result.result(), "result").isEqualTo(Result.REFUSED);
				requireThat(result.result().exitStatus(), "exitStatus").isEqualTo(3);
				requireThat(result.message(), "message").contains("1.0").contains("update");
				requireThat(Releases.snapshot(root), "installation").isEqualTo(before);
				requireThat(integration.calls(), "calls").isEmpty();
			}
		}
	}

	/**
	 * Returns the plugin versions that the harness has before an install.
	 *
	 * @return no plugin, and the plugin of the release that the installation runs
	 */
	@DataProvider(name = "plugins")
	public static Object[][] plugins()
	{
		return new Object[][]{{null}, {"1.0"}};
	}

	/**
	 * Update refuses a release whose plugin needs a newer Java runtime than the installation's, before any change.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void updateRefusesUnsatisfiedRequirement() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("update-requirement-"));
			Path home = Files.createDirectories(root.resolve("home/live")).getParent();
			Files.writeString(home.resolve("live/version"), "1.0");
			Releases.runtime(root.resolve("data"));
			RecordingIntegration integration = new RecordingIntegration();

			UpdateResult result = engine(root, home, new Releases("1.1", "requirement")).update("v1.1",
				integration.registration());

			requireThat(result.result(), "result").isEqualTo(Result.REFUSED);
			requireThat(result.message(), "message").contains("entry command");
			requireThat(Files.readString(home.resolve("live/version")), "liveVersion").isEqualTo("1.0");
			requireThat(Files.exists(home.resolve(".cat-previous-live"), NOFOLLOW_LINKS), "previous").isFalse();
			requireThat(integration.calls(), "calls").isEmpty();
		}
	}

	/**
	 * Update binds the activated plugin to the installation's runtime and registers it with the harness.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void updateBindsAndRegisters() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("update-binding-"));
			Path home = Files.createDirectories(root.resolve("home/live")).getParent();
			Files.writeString(home.resolve("live/version"), "1.0");
			Path runtime = Releases.installation(root.resolve("data"), "1.0");
			RecordingIntegration integration = new RecordingIntegration();

			UpdateResult result = engine(root, home, new Releases("1.1", "")).update("v1.1",
				integration.registration());

			requireThat(result.result(), "result").isEqualTo(Result.UPDATED);
			requireThat(Files.readString(home.resolve("live/client/runtime-binding")), "binding").
				isEqualTo(runtime.toAbsolutePath().normalize() + "\n");
			requireThat(integration.calls(), "calls").isEqualTo(List.of("register"));
			requireThat(Files.readString(root.resolve("data/state/harness-a/highest-version")), "highest").
				isEqualTo("1.1\n");
		}
	}

	/**
	 * Update registers the plugin again when its files are already current, so a plugin that the harness's own
	 * command removed is registered again.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void updateRegistersCurrentPlugin() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("update-current-"));
			Path home = Files.createDirectories(root.resolve("home/live")).getParent();
			Files.writeString(home.resolve("live/version"), "1.1");
			Releases.installation(root.resolve("data"), "1.1");
			RecordingIntegration integration = new RecordingIntegration();

			UpdateResult result = engine(root, home, new Releases("1.1", "")).update("v1.1",
				integration.registration());

			requireThat(result.result(), "result").isEqualTo(Result.UP_TO_DATE);
			requireThat(integration.calls(), "calls").isEqualTo(List.of("register"));
		}
	}

	/**
	 * Update where the harness has no plugin refuses and names install.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void updateRefusesAbsentPlugin() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("update-absent-"));
			Path home = Files.createDirectories(root.resolve("home"));
			Releases.runtime(root.resolve("data"));

			UpdateResult result = engine(root, home, new Releases("1.1", "")).update("v1.1",
				new RecordingIntegration().registration());

			requireThat(result.result(), "result").isEqualTo(Result.REFUSED);
			requireThat(result.message(), "message").contains("install");
			requireThat(Files.exists(home.resolve("live"), NOFOLLOW_LINKS), "live").isFalse();
		}
	}

	/**
	 * Status reports a harness without a plugin as having no version.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void statusReportsAbsentPlugin() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("status-absent-"));
			Path home = Files.createDirectories(root.resolve("home"));

			UpdateResult result = engine(root, home, new Releases("1.1", "")).status();

			requireThat(result.result(), "result").isEqualTo(Result.STATUS);
			requireThat(result.version(), "version").isEmpty();
		}
	}

	/**
	 * Creates an engine over the test's data directory.
	 *
	 * @param root   the test directory, whose {@code data} directory is the installation's data directory
	 * @param home   the harness home
	 * @param source the releases
	 * @return the engine
	 * @throws TrustException if the test policy is invalid
	 */
	private static UpdateEngine engine(Path root, Path home, Releases source) throws TrustException
	{
		return new UpdateEngine(root.resolve("data"), source.policy(home), source, new LiveProvider(), "linux-x64",
			Clock.systemUTC(), interval -> () -> {});
	}
}
