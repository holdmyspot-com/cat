package com.holdmyspot.cat.codex.updater.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.updater.activation.ActivationStep;
import com.holdmyspot.cat.common.updater.activation.AddDirectory;
import com.holdmyspot.cat.common.updater.activation.InstalledState;
import com.holdmyspot.cat.common.updater.activation.PluginActivation;
import com.holdmyspot.cat.common.updater.activation.ReplaceDirectory;
import com.holdmyspot.cat.common.updater.activation.ReplaceOwnedFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests the Codex activation service through its updater contract.
 */
public final class ActivationTest
{
	/**
	 * Service discovery selects the Codex provider and its plugin manifest.
	 */
	@Test
	public void discoversProvider()
	{
		requireThat(provider().pluginManifest(), "pluginManifest").isEqualTo(".codex-plugin/plugin.json");
	}

	/**
	 * The provider plans agents, marketplace source, then a new cache version outside the scanned staging tree.
	 * Reading and planning keep protected files' bytes and modification times unchanged.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void plansWithoutChangingConfiguration() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			install(home, "1.0", "1.0", "1.0");
			List<Path> protectedFiles = List.of(home.resolve("config.toml"), home.resolve("auth.json"),
				home.resolve("plugins/cat-marketplace/.agents/plugins/marketplace.json"),
				home.resolve("agents/user.toml"), home.resolve("agents/cat-a.toml"),
				home.resolve("plugins/data/cat-cat/codex-agent-files"));
			Map<Path, String> bytes = new HashMap<>();
			Map<Path, FileTime> times = new HashMap<>();
			for (Path file : protectedFiles)
			{
				bytes.put(file, Files.readString(file));
				times.put(file, Files.getLastModifiedTime(file));
			}

			PluginActivation provider = provider();
			requireThat(provider.installed(home), "installed").
				isEqualTo(new InstalledState(Optional.of("1.0"), true));
			List<ActivationStep> steps = provider.plan(home, "1.1");
			requireThat(steps, "steps").isEqualTo(List.of(
				new ReplaceOwnedFiles(home.resolve("agents"), home.resolve("plugins/data/cat-cat/codex-agent-files"),
					"cat-", "agents/codex", "*.toml"),
				new ReplaceDirectory(home.resolve("plugins/cat-marketplace/plugins/cat"),
					home.resolve("plugins/cat-marketplace/plugins")),
				new AddDirectory(home.resolve("plugins/cache/cat/cat/1.1"), home.resolve("plugins/cache/cat"))));
			requireThat(provider.recoveryPlan(home, "1.0", "1.1"), "recoverySteps").isEqualTo(steps);
			requireThat(provider.migrationVersionFile(home), "migrationVersionFile").
				isEqualTo(home.resolve("plugins/data/cat-cat/migration-version"));
			for (Path file : protectedFiles)
			{
				requireThat(Files.readString(file), "content").isEqualTo(bytes.get(file));
				requireThat(Files.getLastModifiedTime(file), "modified").isEqualTo(times.get(file));
			}
		}
	}

	/**
	 * Numeric ordering selects 1.10 before 1.9, skipping invalid names and hidden directories.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void choosesHighestNumericVersion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			install(home, "1.10", "1.10", "1.10");
			cache(home, "1.9", "1.9");
			cache(home, ".9.0", "9.0");
			cache(home, "invalid", "9.0");
			Files.writeString(home.resolve("plugins/cache/cat/cat/8.0"), "not a directory");

			requireThat(provider().installed(home), "installed").
				isEqualTo(new InstalledState(Optional.of("1.10"), true));
		}
	}

	/**
	 * A developer's local directory wins over numeric releases and prevents routine activation.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*local.*")
	public void refusesLocalInstallation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			install(home, "1.0", "1.0", "1.0");
			cache(home, "local", "1.0");

			requireThat(provider().installed(home), "installed").
				isEqualTo(new InstalledState(Optional.of("local"), false));
			provider().plan(home, "1.1");
		}
	}

	/**
	 * A symlinked version directory does not participate in Codex's version selection.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void ignoresSymlinkedVersion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			install(home, "1.0", "1.0", "1.0");
			Path other = Files.createDirectories(home.resolve("other"));
			Files.createSymbolicLink(home.resolve("plugins/cache/cat/cat/9.0"), other);

			requireThat(provider().installed(home), "installed").
				isEqualTo(new InstalledState(Optional.of("1.0"), true));
		}
	}

	/**
	 * The same version repairs a mismatched marketplace manifest by replacing both source and cache.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void repairsMismatchedMarketplace() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			install(home, "1.0", "1.0", "0.9");

			requireThat(provider().installed(home), "installed").
				isEqualTo(new InstalledState(Optional.of("1.0"), false));
			requireThat(provider().plan(home, "1.0").getLast(), "cacheStep").
				isEqualTo(new ReplaceDirectory(home.resolve("plugins/cache/cat/cat/1.0"),
					home.resolve("plugins/cache/cat")));
		}
	}

	/**
	 * A cache manifest that differs from its selected directory makes the installation inconsistent.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void detectsMismatchedCache() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			install(home, "1.0", "0.9", "1.0");

			requireThat(provider().installed(home), "installed").
				isEqualTo(new InstalledState(Optional.of("1.0"), false));
		}
	}

	/**
	 * A home without either copy has no installed version and cannot be activated by a routine update.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*not installed.*")
	public void refusesMissingInstallation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			requireThat(provider().installed(home), "installed").
				isEqualTo(new InstalledState(Optional.empty(), false));
			provider().plan(home, "1.1");
		}
	}

	/**
	 * A marketplace source alone cannot supply an active cache version or a routine activation target.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*not installed.*")
	public void refusesMissingCache() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			manifest(home.resolve("plugins/cat-marketplace/plugins/cat"), "1.0");
			requireThat(provider().installed(home).version(), "version").isEqualTo(Optional.empty());
			provider().plan(home, "1.1");
		}
	}

	/**
	 * A cache without its marketplace source cannot be activated without an explicit installation.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*not installed.*")
	public void refusesMissingMarketplace() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			cache(home, "1.0", "1.0");
			requireThat(provider().installed(home), "installed").
				isEqualTo(new InstalledState(Optional.of("1.0"), false));
			provider().plan(home, "1.1");
		}
	}

	/**
	 * Relative harness homes are refused before any filesystem access.
	 *
	 * @throws IOException if the provider unexpectedly attempts a read
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void refusesRelativeHome() throws IOException
	{
		provider().installed(Path.of("fake-home"));
	}

	/**
	 * A requested version cannot escape the cache through path syntax.
	 *
	 * @throws IOException if the provider unexpectedly attempts a read
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void refusesVersionPathTraversal() throws IOException
	{
		provider().plan(Path.of(System.getProperty("cat.updater.test.directory")).resolve("fake-home"), "../outside");
	}

	/**
	 * Missing manifest versions fail with IOException.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*string version.*")
	public void refusesMissingManifestVersion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			install(home, "1.0", "1.0", "1.0");
			Files.writeString(home.resolve("plugins/cat-marketplace/plugins/cat/.codex-plugin/plugin.json"), "{}");

			provider().installed(home);
		}
	}

	/**
	 * A symlink in a mutable installation path is refused instead of redirecting the updater.
	 *
	 * @param component  the component replaced with a symlink
	 * @param newVersion the version whose activation uses that component
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(dataProvider = "symlinkComponents", expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*symbolic link.*")
	public void refusesSymlinkComponents(String component, String newVersion) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			install(home, "1.0", "1.0", "1.0");
			Path redirected = home.resolve(component);
			Path original = redirected.resolveSibling("original");
			Files.move(redirected, original);
			Files.createSymbolicLink(redirected, original);

			provider().plan(home, newVersion);
		}
	}

	/**
	 * Supplies components used by directory and owned-agent activation steps.
	 *
	 * @return mutable path components whose symlinks must be refused
	 */
	@DataProvider
	public Object[][] symlinkComponents()
	{
		return new Object[][]{{"plugins/cache", "1.1"}, {"plugins/cat-marketplace", "1.1"}, {"agents", "1.1"},
			{"plugins/data/cat-cat/codex-agent-files", "1.1"}, {"plugins/cache/cat/cat/1.0", "1.0"},
			{"plugins/cat-marketplace/plugins/cat/.codex-plugin/plugin.json", "1.1"}};
	}

	/**
	 * Recovery reconstructs the original new-version plan while the marketplace source is in its previous slot.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void recoversWithoutMarketplaceLive() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			install(home, "1.0", "1.0", "1.0");
			PluginActivation provider = provider();
			List<ActivationStep> original = provider.plan(home, "1.1");
			Path source = home.resolve("plugins/cat-marketplace/plugins/cat");
			// Models interruption after the executor moves the live source to its previous-installation slot.
			Files.move(source, source.resolveSibling(".cat-previous-cat"));

			requireThat(provider.recoveryPlan(home, "1.0", "1.1"), "recoveryPlan").isEqualTo(original);
		}
	}

	/**
	 * Recovery retains the original AddDirectory step after the new version becomes active, even though a fresh
	 * same-version update would plan ReplaceDirectory.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void recoversOriginalAddAfterActivation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			install(home, "1.0", "1.0", "1.0");
			PluginActivation provider = provider();
			List<ActivationStep> original = provider.plan(home, "1.1");
			// Models the executor's completed marketplace replacement and cache addition.
			manifest(home.resolve("plugins/cat-marketplace/plugins/cat"), "1.1");
			cache(home, "1.1", "1.1");

			requireThat(provider.installed(home), "installed").
				isEqualTo(new InstalledState(Optional.of("1.1"), true));
			requireThat(provider.plan(home, "1.1").getLast(), "freshCacheStep").
				isEqualTo(new ReplaceDirectory(home.resolve("plugins/cache/cat/cat/1.1"),
					home.resolve("plugins/cache/cat")));
			requireThat(provider.recoveryPlan(home, "1.0", "1.1"), "recoveryPlan").isEqualTo(original);
		}
	}

	/**
	 * Same-version recovery reconstructs ReplaceDirectory while the live cache is in its previous slot.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void recoversWithoutCacheLive() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			install(home, "1.0", "1.0", "1.0");
			PluginActivation provider = provider();
			List<ActivationStep> original = provider.plan(home, "1.0");
			Path cache = home.resolve("plugins/cache/cat/cat/1.0");
			// Models interruption between the executor's cache-directory renames.
			Files.move(cache, home.resolve("plugins/cache/cat/.cat-previous-1.0"));

			requireThat(provider.recoveryPlan(home, "1.0", "1.0"), "recoveryPlan").isEqualTo(original);
		}
	}

	/**
	 * Recovery refuses a journal version that could redirect a canonical cache path.
	 *
	 * @param previousVersion the recorded prior version
	 * @param newVersion      the recorded activated version
	 * @throws IOException if the provider unexpectedly attempts to read the installation
	 */
	@Test(dataProvider = "invalidRecoveryVersions", expectedExceptions = IllegalArgumentException.class)
	public void refusesInvalidRecoveryVersions(String previousVersion, String newVersion) throws IOException
	{
		provider().recoveryPlan(Path.of(System.getProperty("cat.updater.test.directory")).resolve("fake-home"),
			previousVersion, newVersion);
	}

	/**
	 * Supplies invalid version inputs for each journal version's validation boundary.
	 *
	 * @return prior and activated version pairs that cannot authorize recovery
	 */
	@DataProvider
	public Object[][] invalidRecoveryVersions()
	{
		return new Object[][]{{"../outside", "1.1"}, {"1.0", "../outside"}, {"local", "1.1"}};
	}

	/**
	 * Recovery retains symbolic-link refusals for every canonical activation target.
	 *
	 * @param component  the component replaced with a symlink
	 * @param newVersion the version whose activation uses that component
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(dataProvider = "symlinkComponents", expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*symbolic link.*")
	public void refusesRecoverySymlinkComponents(String component, String newVersion) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			install(home, "1.0", "1.0", "1.0");
			Path redirected = home.resolve(component);
			Path original = redirected.resolveSibling("original");
			Files.move(redirected, original);
			Files.createSymbolicLink(redirected, original);

			provider().recoveryPlan(home, "1.0", newVersion);
		}
	}

	/**
	 * Recovery requires the protected policy's normalized absolute home.
	 *
	 * @throws IOException if the provider unexpectedly attempts to read the installation
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void refusesUnnormalizedRecoveryHome() throws IOException
	{
		provider().recoveryPlan(Path.of(System.getProperty("cat.updater.test.directory")).
			resolve("fake-home/../fake-home"), "1.0", "1.1");
	}

	/**
	 * Locates build-owned fixture storage passed explicitly by Maven.
	 *
	 * @return a directory beneath this module's target directory
	 * @throws IOException if the directory cannot be created
	 */
	private static Path testRoot() throws IOException
	{
		return Files.createDirectories(Path.of(System.getProperty("cat.updater.test.directory")).toAbsolutePath());
	}

	/**
	 * Creates an installed plugin and the configuration and agents that planning must not modify.
	 *
	 * @param home               fake harness home
	 * @param cacheVersion       active cache directory name
	 * @param cacheManifest      manifest version in that directory
	 * @param marketplaceVersion manifest version in the marketplace source
	 * @throws IOException if a fixture cannot be written
	 */
	private static void install(Path home, String cacheVersion, String cacheManifest, String marketplaceVersion)
		throws IOException
	{
		cache(home, cacheVersion, cacheManifest);
		manifest(home.resolve("plugins/cat-marketplace/plugins/cat"), marketplaceVersion);
		for (String file : List.of("config.toml", "auth.json",
			"plugins/cat-marketplace/.agents/plugins/marketplace.json", "agents/cat-a.toml", "agents/user.toml"))
		{
			Path path = home.resolve(file);
			Files.createDirectories(path.getParent());
			Files.writeString(path, "dummy content for " + file);
		}
		Path ownership = home.resolve("plugins/data/cat-cat/codex-agent-files");
		Files.createDirectories(ownership.getParent());
		Files.writeString(ownership, "cat-a.toml\n");
	}

	/**
	 * Creates one real cache version directory.
	 *
	 * @param home    fake harness home
	 * @param version cache directory name
	 * @param content the manifest's version
	 * @throws IOException if a fixture cannot be written
	 */
	private static void cache(Path home, String version, String content) throws IOException
	{
		manifest(home.resolve("plugins/cache/cat/cat").resolve(version), content);
	}

	/**
	 * Creates a plugin manifest in a fake installed copy.
	 *
	 * @param plugin  plugin directory
	 * @param version the manifest's version
	 * @throws IOException if a fixture cannot be written
	 */
	private static void manifest(Path plugin, String version) throws IOException
	{
		Path manifest = plugin.resolve(".codex-plugin/plugin.json");
		Files.createDirectories(manifest.getParent());
		Files.writeString(manifest, """
			{"name":"cat","version":"%s"}
			""".formatted(version));
	}

	/**
	 * Finds the Codex service through the same registration that the updater consumes.
	 *
	 * @return the Codex provider
	 */
	private static PluginActivation provider()
	{
		return ServiceLoader.load(PluginActivation.class).stream().map(ServiceLoader.Provider::get).
			filter(provider -> provider.harness().equals("codex")).findFirst().
			orElseThrow(() -> new AssertionError("The Codex activation service is missing"));
	}
}
