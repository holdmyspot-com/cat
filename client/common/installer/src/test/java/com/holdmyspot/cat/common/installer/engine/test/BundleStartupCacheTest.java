package com.holdmyspot.cat.common.installer.engine.test;

import static com.holdmyspot.cat.common.installer.engine.test.BundleUpdateTest.engine;
import static com.holdmyspot.cat.common.installer.engine.test.BundleUpdateTest.plugin;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.StartupCache;
import com.holdmyspot.cat.common.installer.engine.UpdateResult;
import com.holdmyspot.cat.common.installer.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Exercises the startup cache of the plugin that each operation activates: the cache must suit the Java runtime that
 * the installation runs once the stable launcher has finished the operation's runtime replacement.
 * <p>
 * Each fixture runtime's {@code java} names its runtime to the plugin's training program, which writes that name into
 * the cache, so a test reads which runtime trained the live plugin's cache. Each test lets only the operation under
 * test train a cache, because each training starts Java twice.
 */
public final class BundleStartupCacheTest
{
	/**
	 * An install trains the plugin's cache with the installation's runtime and binds the plugin to that runtime.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void installTrainsForInstalledRuntime() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("bundle-cache-install-"));
			Path data = root.resolve("data");
			Path home = Files.createDirectories(root.resolve("home"));
			Releases.installation(data, "1.0");

			UpdateResult result = engine(data, home, Releases.trainable("1.0")).install(null,
				new RecordingIntegration().session());

			requireThat(result.result(), "result").isEqualTo(Result.INSTALLED);
			requireThat(result.aotCache(), "aotCache").isEqualTo(Optional.of(true));
			requireThat(cache(home), "cache").isEqualTo(FakeJava.cacheOf("runtime 1.0"));
			requireThat(binding(home), "binding").isEqualTo(boundRuntime(data));
		}
	}

	/**
	 * An update trains the activated plugin's cache with the release's staged runtime, which the stable launcher puts
	 * in place after the installer exits, and leaves the plugin bound to the installation's runtime location.
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
			Path home = installed(root, data, true);

			UpdateResult result = engine(data, home, Releases.trainable("1.1")).update("v1.1",
				new RecordingIntegration().session());

			requireThat(result.result(), "result").isEqualTo(Result.UPDATED);
			requireThat(result.aotCache(), "aotCache").isEqualTo(Optional.of(true));
			requireThat(cache(home), "cache").isEqualTo(FakeJava.cacheOf("runtime 1.1"));
			requireThat(binding(home), "binding").isEqualTo(boundRuntime(data));
			LauncherSwap.finish(data);
			requireThat(Files.readString(data.resolve("runtime/release")), "runtime").isEqualTo("runtime 1.1");
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
			Path home = installed(root, data, true);
			requireThat(engine(data, home, new Releases("1.1", "")).update("v1.1",
				new RecordingIntegration().session()).result(), "update").isEqualTo(Result.UPDATED);
			LauncherSwap.abandon(data);

			UpdateResult result = engine(data, home, Releases.trainable("1.0")).install(null,
				new RecordingIntegration().session());

			requireThat(result.result(), "result").isEqualTo(Result.UP_TO_DATE);
			requireThat(result.aotCache(), "aotCache").isEqualTo(Optional.of(true));
			requireThat(Files.readString(home.resolve("live/version")), "plugin").isEqualTo("1.0");
			requireThat(cache(home), "cache").isEqualTo(FakeJava.cacheOf("runtime 1.0"));
			requireThat(binding(home), "binding").isEqualTo(boundRuntime(data));
		}
	}

	/**
	 * Setup that repairs the installed release trains the plugin that it activates with the installation's runtime.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void repairTrainsForInstalledRuntime() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("bundle-cache-repair-"));
			Path data = root.resolve("data");
			Path home = plugin(root, "1.0");
			Releases.installation(data, "1.0");

			UpdateResult result = engine(data, home, Releases.trainable("1.0")).setUp("1.0",
				new RecordingIntegration().session());

			requireThat(result.result(), "result").isEqualTo(Result.UPDATED);
			requireThat(result.aotCache(), "aotCache").isEqualTo(Optional.of(true));
			requireThat(cache(home), "cache").isEqualTo(FakeJava.cacheOf("runtime 1.0"));
			requireThat(binding(home), "binding").isEqualTo(boundRuntime(data));
		}
	}

	/**
	 * Setup that moves an installation to another release trains the plugin that it activates with the release's
	 * runtime, which setup puts in place before it activates the plugin.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void setupTrainsForNewRuntime() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("bundle-cache-setup-"));
			Path data = root.resolve("data");
			Path home = plugin(root, "1.0");
			Releases.installation(data, "1.1");

			UpdateResult result = engine(data, home, Releases.trainable("1.1")).setUp("1.1",
				new RecordingIntegration().session());

			requireThat(result.result(), "result").isEqualTo(Result.UPDATED);
			requireThat(result.aotCache(), "aotCache").isEqualTo(Optional.of(true));
			requireThat(cache(home), "cache").isEqualTo(FakeJava.cacheOf("runtime 1.1"));
			requireThat(binding(home), "binding").isEqualTo(boundRuntime(data));
		}
	}

	/**
	 * Creates an installation of release 1.0 whose harness has that release's trainable plugin without a startup
	 * cache, as an installation looks before any training.
	 *
	 * @param root      the test directory
	 * @param data      the installation's installer data directory
	 * @param described true if the plugin has its training description
	 * @return the harness home
	 * @throws IOException if the fixture cannot be created
	 */
	private static Path installed(Path root, Path data, boolean described) throws IOException
	{
		Path runtime = Releases.installation(data, "1.0");
		Path home = plugin(root, "1.0");
		Path bin = Files.createDirectories(home.resolve("live/bin"));
		String main = FakeJava.class.getName();
		Path wrapper = Files.writeString(bin.resolve("java"), StartupCacheFixtures.posixWrapper(main));
		requireThat(wrapper.toFile().setExecutable(true), "executable").isTrue();
		Files.writeString(bin.resolve("java.cmd"), StartupCacheFixtures.windowsWrapper(main, "java.cmd"));
		Files.createDirectories(bin.resolve("modules"));
		Files.write(home.resolve("live").resolve(StartupCacheFixtures.JAR), StartupCacheFixtures.jar(FakeJava.class));
		Files.writeString(bin.resolve("runtime-binding"), runtime.toAbsolutePath().normalize() + "\n");
		if (described)
			describe(home.resolve("live"));
		return home;
	}

	/**
	 * Gives a fixture plugin its training description.
	 *
	 * @param plugin the plugin directory
	 * @throws IOException if the description cannot be written
	 */
	private static void describe(Path plugin) throws IOException
	{
		Files.writeString(plugin.resolve(StartupCache.TRAINING),
			StartupCacheFixtures.description(List.of("-Dfixture.behavior=train")));
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
		return Files.readString(home.resolve("live/bin/runtime-binding"));
	}

	/**
	 * Returns the binding that names the installation's runtime location.
	 *
	 * @param data the installation's installer data directory
	 * @return the binding's content
	 */
	private static String boundRuntime(Path data)
	{
		return data.resolve("runtime").toAbsolutePath().normalize() + "\n";
	}
}
