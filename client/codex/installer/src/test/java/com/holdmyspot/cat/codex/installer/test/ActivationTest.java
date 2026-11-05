package com.holdmyspot.cat.codex.installer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.activation.InstalledState;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
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
 * Tests the Codex activation service through its installer contract.
 */
public final class ActivationTest
{
	/**
	 * The record of CAT's custom agents, relative to the home.
	 */
	private static final String OWNERSHIP = "plugins/data/cat-cat/codex-agent-files";

	/**
	 * Service discovery selects the Codex provider and its plugin manifest.
	 */
	@Test
	public void discoversProvider()
	{
		requireThat(provider().pluginManifest(), "pluginManifest").isEqualTo(".codex-plugin/plugin.json");
	}

	/**
	 * Reading the installation keeps protected files' bytes and modification times unchanged.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void readsWithoutChangingConfiguration() throws IOException
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
	 * Folders named by semantic versions are ordered as Codex orders them, the release after its pre-releases, and
	 * Codex skips a name with a character that it does not allow and an entry that is not a directory.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void ordersSemanticVersionsAsCodex() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			install(home, "1.10.0", "1.10.0", "1.10.0");
			cache(home, "1.9.0", "1.9.0");
			cache(home, "1.10.0-rc.1", "1.10.0-rc.1");
			cache(home, "9.0 copy", "9.0");
			Files.writeString(home.resolve("plugins/cache/cat/cat/9.0"), "not a directory");

			requireThat(provider().installed(home), "installed").
				isEqualTo(new InstalledState(Optional.of("1.10.0"), true));
		}
	}

	/**
	 * Codex orders folder names that are not semantic versions, such as CAT's plugin versions, as text, so the
	 * installed version is the folder that Codex loads even where release order differs.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void ordersPluginVersionsAsCodex() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			install(home, "1.9+bffffffffffff", "1.9+bffffffffffff", "1.9+bffffffffffff");
			cache(home, "1.10+b0123456789ab", "1.10+b0123456789ab");

			requireThat(provider().installed(home), "installed").
				isEqualTo(new InstalledState(Optional.of("1.9+bffffffffffff"), true));
		}
	}

	/**
	 * Supplies two builds of one release in both orders.
	 *
	 * @return the builds in each order
	 */
	@DataProvider
	public Object[][] buildPairs()
	{
		return new Object[][] {{"1.0+bfedcba987654", "1.0+b0123456789ab"}, {"1.0+b0123456789ab", "1.0+bfedcba987654"}};
	}

	/**
	 * With the folders of two builds of one release present, the installed version is the build that Codex loads,
	 * the one whose name sorts last as text, whichever folder was written first.
	 *
	 * @param first  the build whose folder is written first
	 * @param second the build whose folder is written second
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(dataProvider = "buildPairs")
	public void reportsBuildCodexLoads(String first, String second) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			String loaded = "1.0+bfedcba987654";
			cache(home, first, first);
			cache(home, second, second);
			manifest(home.resolve("plugins/cat-marketplace/plugins/cat"), loaded);

			requireThat(provider().installed(home), "installed").
				isEqualTo(new InstalledState(Optional.of(loaded), true));
		}
	}

	/**
	 * Of a bare release's folder and a build's folder of that release, Codex loads the build's folder, whose name
	 * extends the release's, whichever folder sorts first in the directory.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void prefersCopyThatNamesBuild() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			install(home, "1.1+b0123456789ab", "1.1+b0123456789ab", "1.1+b0123456789ab");
			cache(home, "1.1", "1.1");

			requireThat(provider().installed(home), "installed").
				isEqualTo(new InstalledState(Optional.of("1.1+b0123456789ab"), true));
		}
	}

	/**
	 * A plugin copy that names another build of the same release does not count as the installed version.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void detectsOtherBuild() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			install(home, "1.0+b0123456789ab", "1.0+b0123456789ab", "1.0+bffffffffffff");

			requireThat(provider().installed(home), "installed").
				isEqualTo(new InstalledState(Optional.of("1.0+b0123456789ab"), false));
		}
	}

	/**
	 * A developer's local directory wins over numeric releases and makes the installation inconsistent.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void reportsLocalInstallation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			install(home, "1.0", "1.0", "1.0");
			cache(home, "local", "1.0");

			requireThat(provider().installed(home), "installed").
				isEqualTo(new InstalledState(Optional.of("local"), false));
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
	 * A marketplace manifest that names another version makes the installation inconsistent.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void detectsMismatchedMarketplace() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			install(home, "1.0", "1.0", "0.9");

			requireThat(provider().installed(home), "installed").
				isEqualTo(new InstalledState(Optional.of("1.0"), false));
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
	 * A home without any CAT plugin location reports a consistent absence.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void reportsMissingInstallation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			requireThat(provider().installed(home), "installed").
				isEqualTo(new InstalledState(Optional.empty(), true));
		}
	}

	/**
	 * Custom agents and their ownership manifest left behind by another removal do not make the absence partial,
	 * because installation replaces the agents that the manifest lists.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void ignoresLeftoverAgentsInAbsence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			write(home.resolve("agents/cat-a.toml"), "agent");
			write(home.resolve("plugins/data/cat-cat/codex-agent-files"), "cat-a.toml\n");
			Files.createDirectories(home.resolve("plugins/cache/cat/cat"));

			requireThat(provider().installed(home), "installed").
				isEqualTo(new InstalledState(Optional.empty(), true));
		}
	}

	/**
	 * A marketplace root or a cache entry without an active cache version is a partial installation.
	 *
	 * @param leftover the path that remains
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(dataProvider = "partialLeftovers")
	public void reportsPartialInstallation(String leftover) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			write(home.resolve(leftover), "leftover");

			requireThat(provider().installed(home), "installed").
				isEqualTo(new InstalledState(Optional.empty(), false));
		}
	}

	/**
	 * Supplies paths that a stopped installation or removal can leave behind, none of which Codex loads: a cache
	 * folder whose name Codex refuses as a version and a cache entry that is not a folder.
	 *
	 * @return leftover paths relative to the home
	 */
	@DataProvider
	public Object[][] partialLeftovers()
	{
		return new Object[][]{{"plugins/cat-marketplace/.agents/plugins/marketplace.json"},
			{"plugins/cache/cat/cat/not a version/file"}, {"plugins/cache/cat/cat/1.0"}};
	}

	/**
	 * Installing a version's companions copies each of its custom agents into Codex's agents directory under CAT's
	 * prefix and records them as CAT's, leaving the user's agents as they are.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void installsAgents() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path folder = home.resolve("plugins/cache/cat/cat/1.1");
			write(folder.resolve("agents/codex/a.toml"), "agent a");
			write(folder.resolve("agents/codex/b.toml"), "agent b");
			write(home.resolve("agents/user.toml"), "user agent");

			provider().installCompanions(home, folder);

			requireThat(Files.readString(home.resolve("agents/cat-a.toml")), "agentA").isEqualTo("agent a");
			requireThat(Files.readString(home.resolve("agents/cat-b.toml")), "agentB").isEqualTo("agent b");
			requireThat(Files.readString(home.resolve("agents/user.toml")), "userAgent").isEqualTo("user agent");
			requireThat(Files.readAllLines(home.resolve(OWNERSHIP)), "owned").
				isEqualTo(List.of("cat-a.toml", "cat-b.toml"));
		}
	}

	/**
	 * A later version's companions replace CAT's agents and remove the ones that the version no longer ships, and the
	 * record lists only the version's agents.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void replacesAndRetiresAgents() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			install(home, "1.0", "1.0", "1.0");
			write(home.resolve("agents/cat-retired.toml"), "retired agent");
			write(home.resolve(OWNERSHIP), "cat-a.toml\ncat-retired.toml\n");
			Path folder = home.resolve("plugins/cache/cat/cat/1.1");
			write(folder.resolve("agents/codex/a.toml"), "new agent a");
			write(folder.resolve("agents/codex/b.toml"), "new agent b");
			String user = Files.readString(home.resolve("agents/user.toml"));

			provider().installCompanions(home, folder);

			requireThat(Files.readString(home.resolve("agents/cat-a.toml")), "agentA").isEqualTo("new agent a");
			requireThat(Files.readString(home.resolve("agents/cat-b.toml")), "agentB").isEqualTo("new agent b");
			requireThat(Files.exists(home.resolve("agents/cat-retired.toml")), "retiredAgent").isFalse();
			requireThat(Files.readString(home.resolve("agents/user.toml")), "userAgent").isEqualTo(user);
			requireThat(Files.readAllLines(home.resolve(OWNERSHIP)), "owned").
				isEqualTo(List.of("cat-a.toml", "cat-b.toml"));
		}
	}

	/**
	 * A version without custom agents removes the agents that CAT placed earlier.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void removesAgentsThatVersionDoesNotShip() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			install(home, "1.0", "1.0", "1.0");
			Path folder = home.resolve("plugins/cache/cat/cat/1.1");
			manifest(folder, "1.1");

			provider().installCompanions(home, folder);

			requireThat(Files.exists(home.resolve("agents/cat-a.toml")), "agentA").isFalse();
			requireThat(Files.readAllLines(home.resolve(OWNERSHIP)), "owned").isEmpty();
		}
	}

	/**
	 * An agent with CAT's name that CAT did not place is the user's: installing refuses to replace it and changes
	 * nothing.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void refusesUnownedAgent() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			write(home.resolve("agents/cat-a.toml"), "the user's agent");
			Path folder = home.resolve("plugins/cache/cat/cat/1.1");
			write(folder.resolve("agents/codex/a.toml"), "agent a");
			write(folder.resolve("agents/codex/b.toml"), "agent b");

			try
			{
				provider().installCompanions(home, folder);
				throw new AssertionError("The user's agent was replaced");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains(home.resolve("agents/cat-a.toml").toString());
			}
			requireThat(Files.readString(home.resolve("agents/cat-a.toml")), "userAgent").isEqualTo("the user's agent");
			requireThat(Files.exists(home.resolve("agents/cat-b.toml")), "agentB").isFalse();
			requireThat(Files.exists(home.resolve(OWNERSHIP)), "ownership").isFalse();
		}
	}

	/**
	 * Installing refuses a symbolic link on the way to an agent or to the record of CAT's agents, and writes nothing
	 * where the link points.
	 *
	 * @param component the path, relative to the home, that is a symbolic link
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(dataProvider = "linkedAgentPaths")
	public void refusesLinkedAgentPath(String component) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			install(home, "1.0", "1.0", "1.0");
			Path folder = home.resolve("plugins/cache/cat/cat/1.1");
			write(folder.resolve("agents/codex/a.toml"), "new agent a");
			Path redirected = home.resolve(component);
			Path outside = Files.createDirectories(home.resolve("outside")).resolve(redirected.getFileName());
			Files.move(redirected, outside);
			Files.createSymbolicLink(redirected, outside);
			String before = Files.readString(redirected);

			try
			{
				provider().installCompanions(home, folder);
				throw new AssertionError("The link was followed");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("symbolic link");
			}
			requireThat(Files.readString(outside), "outside").isEqualTo(before);
		}
	}

	/**
	 * Supplies the paths that installing an agent writes through.
	 *
	 * @return paths relative to the home
	 */
	@DataProvider
	public Object[][] linkedAgentPaths()
	{
		return new Object[][]{{"agents/cat-a.toml"}, {OWNERSHIP}};
	}

	/**
	 * Removal lists the marketplace root, the marketplace's cache directory, each owned agent that still equals the
	 * release's agent, and then the ownership manifest; a changed agent and the user's agents stay.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void listsOwnedRemovalPaths() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			install(home, "1.0", "1.0", "1.0");
			write(home.resolve("plugins/cat-marketplace/plugins/cat/agents/codex/a.toml"), "agent a");
			write(home.resolve("plugins/cat-marketplace/plugins/cat/agents/codex/b.toml"), "agent b");
			write(home.resolve("agents/cat-a.toml"), "agent a");
			write(home.resolve("agents/cat-b.toml"), "agent b, edited by the user");
			write(home.resolve("plugins/data/cat-cat/codex-agent-files"), "cat-a.toml\ncat-b.toml\ncat-c.toml\n");

			requireThat(provider().removalPaths(home), "removalPaths").isEqualTo(List.of(
				home.resolve("plugins/cat-marketplace"), home.resolve("plugins/cache/cat"),
				home.resolve("agents/cat-a.toml"), home.resolve("plugins/data/cat-cat/codex-agent-files")));
		}
	}

	/**
	 * Without a marketplace source, removal compares owned agents with a cache copy of the release.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void comparesAgentsWithCacheCopy() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			cache(home, "1.0", "1.0");
			write(home.resolve("plugins/cache/cat/cat/1.0/agents/codex/a.toml"), "agent a");
			write(home.resolve("agents/cat-a.toml"), "agent a");
			write(home.resolve("plugins/data/cat-cat/codex-agent-files"), "cat-a.toml\n");

			requireThat(provider().removalPaths(home), "removalPaths").isEqualTo(List.of(
				home.resolve("plugins/cache/cat"), home.resolve("agents/cat-a.toml"),
				home.resolve("plugins/data/cat-cat/codex-agent-files")));
		}
	}

	/**
	 * A home without CAT has nothing to remove.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void removesNothingFromEmptyHome() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			write(home.resolve("agents/user.toml"), "user agent");

			requireThat(provider().removalPaths(home), "removalPaths").isEmpty();
		}
	}

	/**
	 * An ownership manifest that names a path outside the agents directory is refused.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*invalid file name.*")
	public void refusesHostileOwnershipManifest() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			write(home.resolve("plugins/data/cat-cat/codex-agent-files"), "cat-../../config.toml\n");

			provider().removalPaths(home);
		}
	}

	/**
	 * CAT's data directory is the plugin's Codex data directory.
	 *
	 * @throws IOException if the test directory cannot be created
	 */
	@Test
	public void locatesDataDirectory() throws IOException
	{
		Path home = testRoot().resolve("fake-home");
		requireThat(provider().dataDirectory(home), "dataDirectory").
			isEqualTo(home.resolve("plugins/data/cat-cat"));
	}

	/**
	 * A marketplace source alone supplies no installed version.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void reportsMissingCache() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			manifest(home.resolve("plugins/cat-marketplace/plugins/cat"), "1.0");
			requireThat(provider().installed(home).version(), "version").isEqualTo(Optional.empty());
		}
	}

	/**
	 * A cache without its marketplace source is an inconsistent installation.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void reportsMissingMarketplace() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			cache(home, "1.0", "1.0");
			requireThat(provider().installed(home), "installed").
				isEqualTo(new InstalledState(Optional.of("1.0"), false));
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
	 * Locates build-owned fixture storage passed explicitly by Maven.
	 *
	 * @return a directory beneath this module's target directory
	 * @throws IOException if the directory cannot be created
	 */
	private static Path testRoot() throws IOException
	{
		return Files.createDirectories(Path.of(System.getProperty("cat.installer.test.directory")).toAbsolutePath());
	}

	/**
	 * Creates an installed plugin and the configuration and agents that reading must not modify.
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
	 * Writes a fixture file, creating its parent directories.
	 *
	 * @param file    the file
	 * @param content its content
	 * @throws IOException if the file cannot be written
	 */
	private static void write(Path file, String content) throws IOException
	{
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
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
	 * Finds the Codex service through the same registration that the installer consumes.
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
