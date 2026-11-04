package com.holdmyspot.cat.common.updater.engine.test;

import static com.holdmyspot.cat.common.updater.engine.test.BundleUpdateTest.engine;
import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.StartupCache;
import com.holdmyspot.cat.common.updater.engine.UpdateResult;
import com.holdmyspot.cat.common.updater.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Exercises the startup cache of the plugin that each operation activates: the cache must suit the Java runtime that
 * the installation runs once the stable launcher has finished the operation's runtime replacement.
 * <p>
 * Each fixture runtime's {@code java} names its runtime to the plugin's training program, which writes that name into
 * the cache, so a test reads which runtime trained the live plugin's cache.
 */
public final class BundleStartupCacheTest
{
	/**
	 * An update trains the activated plugin's cache with the release's staged runtime, which the stable launcher puts
	 * in place after the updater exits, and leaves the plugin bound to the installation's runtime location.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void updateTrainsForStagedRuntime() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("bundle-cache-update-"));
			Path data = root.resolve("data");
			Path home = installed(root, data);

			UpdateResult result = engine(data, home, Releases.trainable("1.1")).update("v1.1",
				new RecordingIntegration().registration());

			requireThat(result.result(), "result").isEqualTo(Result.UPDATED);
			requireThat(result.aotCache(), "aotCache").isEqualTo(Optional.of(true));
			requireThat(cache(home), "cache").isEqualTo(FakeJava.cacheOf("runtime 1.1"));
			requireThat(binding(home), "binding").isEqualTo(boundRuntime(data));
			LauncherSwap.finish(data);
			requireThat(Files.readString(data.resolve("runtime/release")), "runtime").isEqualTo("runtime 1.1");
		}
	}

	/**
	 * A rollback trains the restored plugin's cache with the previous runtime, which the stable launcher restores
	 * after the updater exits.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void rollbackTrainsForRestoredRuntime() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("bundle-cache-rollback-"));
			Path data = root.resolve("data");
			Path home = installed(root, data);
			Releases release = Releases.trainable("1.1");
			requireThat(engine(data, home, release).update("v1.1", new RecordingIntegration().registration()).
				result(), "update").isEqualTo(Result.UPDATED);
			LauncherSwap.finish(data);

			UpdateResult result = engine(data, home, release).rollback();

			requireThat(result.result(), "result").isEqualTo(Result.ROLLED_BACK);
			requireThat(result.aotCache(), "aotCache").isEqualTo(Optional.of(true));
			requireThat(cache(home), "cache").isEqualTo(FakeJava.cacheOf("runtime 1.0"));
			requireThat(binding(home), "binding").isEqualTo(boundRuntime(data));
		}
	}

	/**
	 * A rollback that the next operation resumes trains the restored plugin's cache with the previous runtime.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void resumedRollbackTrainsForRestoredRuntime() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("bundle-cache-resumed-"));
			Path data = root.resolve("data");
			Path home = installed(root, data);
			Releases release = Releases.trainable("1.1");
			requireThat(engine(data, home, release).update("v1.1", new RecordingIntegration().registration()).
				result(), "update").isEqualTo(Result.UPDATED);
			LauncherSwap.finish(data);
			requireThat(engine(data, home, release).rollback().result(), "rollback").isEqualTo(Result.ROLLED_BACK);
			LauncherSwap.abandon(data);
			// A rollback that stopped after it restored the plugin may have stopped before it trained the cache
			TemporaryDirectories.deleteRecursively(home.resolve("live").resolve(StartupCache.DIRECTORY));

			UpdateResult result = engine(data, home, release).update("v1.1",
				new RecordingIntegration().registration());

			requireThat(result.result(), "result").isEqualTo(Result.ROLLED_BACK);
			requireThat(result.aotCache(), "aotCache").isEqualTo(Optional.of(true));
			requireThat(cache(home), "cache").isEqualTo(FakeJava.cacheOf("runtime 1.0"));
			requireThat(binding(home), "binding").isEqualTo(boundRuntime(data));
		}
	}

	/**
	 * An operation that undoes an update whose runtime replacement the launcher abandoned trains the restored plugin's
	 * cache and reports it, also when the operation then finds the installation up to date.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void undoneUpdateTrainsRestoredPlugin() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("bundle-cache-undo-"));
			Path data = root.resolve("data");
			Path home = installed(root, data);
			requireThat(engine(data, home, Releases.trainable("1.1")).update("v1.1",
				new RecordingIntegration().registration()).result(), "update").isEqualTo(Result.UPDATED);
			LauncherSwap.abandon(data);

			UpdateResult result = engine(data, home, Releases.trainable("1.0")).install(null,
				new RecordingIntegration().registration());

			requireThat(result.result(), "result").isEqualTo(Result.UP_TO_DATE);
			requireThat(result.aotCache(), "aotCache").isEqualTo(Optional.of(true));
			requireThat(Files.readString(home.resolve("live/version")), "plugin").isEqualTo("1.0");
			requireThat(cache(home), "cache").isEqualTo(FakeJava.cacheOf("runtime 1.0"));
			requireThat(binding(home), "binding").isEqualTo(boundRuntime(data));
		}
	}

	/**
	 * Setup trains the plugin that it activates with the runtime that it put in place, both when it repairs the
	 * installed release and when it moves the installation to another release.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void setupTrainsForInstalledRuntime() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("bundle-cache-setup-"));
			Path data = root.resolve("data");
			Path home = installed(root, data);
			TemporaryDirectories.deleteRecursively(home.resolve("live").resolve(StartupCache.DIRECTORY));

			UpdateResult repaired = engine(data, home, Releases.trainable("1.0")).setUp("1.0",
				new RecordingIntegration().registration());

			requireThat(repaired.result(), "repaired").isEqualTo(Result.UPDATED);
			requireThat(repaired.aotCache(), "repairedAotCache").isEqualTo(Optional.of(true));
			requireThat(cache(home), "repairedCache").isEqualTo(FakeJava.cacheOf("runtime 1.0"));

			// Setup puts the release's runtime in place before it activates the plugin
			Releases.installation(data, "1.1");
			UpdateResult moved = engine(data, home, Releases.trainable("1.1")).setUp("1.1",
				new RecordingIntegration().registration());

			requireThat(moved.result(), "moved").isEqualTo(Result.UPDATED);
			requireThat(moved.aotCache(), "movedAotCache").isEqualTo(Optional.of(true));
			requireThat(cache(home), "movedCache").isEqualTo(FakeJava.cacheOf("runtime 1.1"));
			requireThat(binding(home), "binding").isEqualTo(boundRuntime(data));
		}
	}

	/**
	 * Creates an installation of release 1.0 and installs its trainable plugin into a harness that had none, which
	 * trains the plugin's cache with the installation's runtime.
	 *
	 * @param root the test directory
	 * @param data the installation's updater data directory
	 * @return the harness home
	 * @throws IOException    if the fixture cannot be created
	 * @throws TrustException if the test policy is invalid
	 */
	private static Path installed(Path root, Path data) throws IOException, TrustException
	{
		Path home = Files.createDirectories(root.resolve("home"));
		Releases.installation(data, "1.0");
		UpdateResult result = engine(data, home, Releases.trainable("1.0")).install(null,
			new RecordingIntegration().registration());
		requireThat(result.result(), "install").isEqualTo(Result.INSTALLED);
		requireThat(result.aotCache(), "installAotCache").isEqualTo(Optional.of(true));
		requireThat(cache(home), "installCache").isEqualTo(FakeJava.cacheOf("runtime 1.0"));
		return home;
	}

	/**
	 * Returns the live plugin's startup cache.
	 *
	 * @param home the harness home
	 * @return the cache's content
	 * @throws IOException if the cache cannot be read
	 */
	private static String cache(Path home) throws IOException
	{
		return Files.readString(home.resolve("live").resolve(StartupCache.FILE));
	}

	/**
	 * Returns the live plugin's runtime binding.
	 *
	 * @param home the harness home
	 * @return the binding's content
	 * @throws IOException if the binding cannot be read
	 */
	private static String binding(Path home) throws IOException
	{
		return Files.readString(home.resolve("live/client/runtime-binding"));
	}

	/**
	 * Returns the binding that names the installation's runtime location.
	 *
	 * @param data the installation's updater data directory
	 * @return the binding's content
	 */
	private static String boundRuntime(Path data)
	{
		return data.resolve("runtime").toAbsolutePath().normalize() + "\n";
	}
}
