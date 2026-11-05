package com.holdmyspot.cat.claude.installer.test;

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
import java.util.stream.Stream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests Claude Code's {@link PluginActivation} service.
 */
public final class ActivationTest
{
	/**
	 * The plugin version that the fixtures install first.
	 */
	private static final String VERSION = "1.0+b0123456789ab";
	/**
	 * A later build of the same release.
	 */
	private static final String NEW_VERSION = "1.0+bfedcba987654";
	/**
	 * The folder whose children are CAT's version folders, relative to the configuration directory.
	 */
	private static final String VERSION_FOLDERS = "plugins/cat-marketplace/plugins/cat";

	/**
	 * Creates a test instance.
	 */
	public ActivationTest()
	{
	}

	/**
	 * Service selection exposes the Claude Code manifest path.
	 */
	@Test
	public void selectsClaudeProvider()
	{
		requireThat(provider().pluginManifest(), "manifest").isEqualTo(".claude-plugin/plugin.json");
	}

	/**
	 * Reading the installation changes no protected configuration.
	 *
	 * @throws IOException if the fake configuration cannot be created or read
	 */
	@Test
	public void readingPreservesConfiguration() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			install(home, VERSION);
			List<Path> configuration = List.of(home.resolve(".claude.json"), home.resolve("settings.json"),
				home.resolve("plugins/installed_plugins.json"), home.resolve("plugins/known_marketplaces.json"));
			Map<Path, String> contents = new HashMap<>();
			Map<Path, FileTime> modificationTimes = new HashMap<>();
			for (Path file : configuration)
			{
				if (Files.notExists(file))
					Files.writeString(file, "original " + file.getFileName());
				contents.put(file, Files.readString(file));
				modificationTimes.put(file, Files.getLastModifiedTime(file));
			}

			requireThat(provider().installed(home), "installed").
				isEqualTo(new InstalledState(Optional.of(VERSION), true));

			for (Path file : configuration)
			{
				requireThat(Files.readString(file), "configurationBytes").isEqualTo(contents.get(file));
				requireThat(Files.getLastModifiedTime(file), "modificationTime").isEqualTo(modificationTimes.get(file));
			}
			requireThat(provider().migrationVersionFile(home), "migrationMarker").
				isEqualTo(home.resolve("plugins/data/cat-cat/migration-version"));
		}
	}

	/**
	 * Claude Code loads everything of CAT's from the version folder, so installing companions changes no file.
	 *
	 * @throws IOException if the fake configuration cannot be created or read
	 */
	@Test
	public void installsNoCompanions() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			install(home, VERSION);
			Path folder = home.resolve(VERSION_FOLDERS).resolve(VERSION);
			Files.writeString(Files.createDirectories(folder.resolve("agents/codex")).resolve("a.toml"), "agent");
			Map<Path, FileTime> before = modificationTimes(home);

			provider().installCompanions(home, folder);

			requireThat(modificationTimes(home), "files").isEqualTo(before);
		}
	}

	/**
	 * Returns the modification time of every file and directory below a directory.
	 *
	 * @param directory the directory
	 * @return the modification times by path
	 * @throws IOException if the tree cannot be read
	 */
	private static Map<Path, FileTime> modificationTimes(Path directory) throws IOException
	{
		Map<Path, FileTime> times = new HashMap<>();
		try (Stream<Path> paths = Files.walk(directory))
		{
			for (Path path : paths.toList())
				times.put(path, Files.getLastModifiedTime(path));
		}
		return times;
	}

	/**
	 * Returns two builds of one release, in both orders: the build that Claude Code records as installed and the
	 * other build.
	 *
	 * @return the recorded and the other version
	 */
	@DataProvider
	public Object[][] buildPairs()
	{
		return new Object[][] {{VERSION, NEW_VERSION}, {NEW_VERSION, VERSION}};
	}

	/**
	 * With the folders of two builds of one release present, the installed version is the one that Claude Code
	 * records, whichever build sorts or was written first.
	 *
	 * @param recorded the version that Claude Code records
	 * @param other    the other build, whose folder is written first
	 * @throws IOException if the fake configuration cannot be created or read
	 */
	@Test(dataProvider = "buildPairs")
	public void readsRecordedVersion(String recorded, String other) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			writeVersionFolder(home, other, other);
			install(home, recorded);

			requireThat(provider().installed(home), "installed").
				isEqualTo(new InstalledState(Optional.of(recorded), true));
		}
	}

	/**
	 * A recorded version whose folder is absent or states another version is an inconsistent installation that a
	 * repair replaces.
	 *
	 * @param folderVersion the version that the recorded version's folder states, or null for no folder
	 * @throws IOException if the fake configuration cannot be created or read
	 */
	@Test(dataProvider = "brokenFolders")
	public void recordedFolderMismatchInconsistent(String folderVersion) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			FakeClaude.recordInstalled(home, VERSION);
			writeVersionFolder(home, NEW_VERSION, NEW_VERSION);
			if (folderVersion != null)
				writeVersionFolder(home, VERSION, folderVersion);

			requireThat(provider().installed(home), "installed").
				isEqualTo(new InstalledState(Optional.of(VERSION), false));
		}
	}

	/**
	 * Supplies version folders that do not hold the recorded version.
	 *
	 * @return the version that the recorded version's folder states, or null for no folder
	 */
	@DataProvider
	public Object[][] brokenFolders()
	{
		return new Object[][] {{null}, {NEW_VERSION}};
	}

	/**
	 * A home without CAT's marketplace reports no installation, which an install from absence applies to.
	 *
	 * @throws IOException if the fake configuration cannot be created or read
	 */
	@Test
	public void absentMarketplaceIsAbsence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());

			InstalledState state = provider().installed(home);

			requireThat(state.version().isEmpty(), "version.isEmpty()").isTrue();
			requireThat(state.consistent(), "consistent").isTrue();
		}
	}

	/**
	 * A marketplace without a plugin manifest is a partial installation that an install repairs.
	 *
	 * @param present the directory, relative to the fake home, that a partial installation left behind
	 * @throws IOException if the fake configuration cannot be created or read
	 */
	@Test(dataProvider = "partialInstallations")
	public void partialInstallationInconsistent(String present) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Files.createDirectories(home.resolve(present));

			InstalledState state = provider().installed(home);

			requireThat(state.version().isEmpty(), "version.isEmpty()").isTrue();
			requireThat(state.consistent(), "consistent").isFalse();
		}
	}

	/**
	 * Supplies the directories that an interrupted installation or removal can leave without a plugin manifest.
	 *
	 * @return paths relative to the fake home
	 */
	@DataProvider
	public Object[][] partialInstallations()
	{
		return new Object[][] {{"plugins/cat-marketplace"}, {"plugins/cat-marketplace/.claude-plugin"},
			{"plugins/cat-marketplace/plugins/cat"}};
	}

	/**
	 * Uninstall deletes the marketplace and Claude Code's cached copy of CAT, and keeps CAT's data and every other
	 * plugin's files.
	 *
	 * @throws IOException if the fake configuration cannot be created or read
	 */
	@Test
	public void removalPathsListCatOnly() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path marketplace = Files.createDirectories(home.resolve(VERSION_FOLDERS).resolve(VERSION));
			Path cache = Files.createDirectories(home.resolve("plugins/cache/cat/cat/2.0"));
			Files.createDirectories(home.resolve("plugins/cache/other/other/1.0"));
			Files.createDirectories(home.resolve("plugins/data/cat-cat"));

			List<Path> paths = provider().removalPaths(home);

			requireThat(paths, "paths").isEqualTo(List.of(home.resolve("plugins/cat-marketplace"),
				cache.getParent().getParent()));
			requireThat(Files.isDirectory(marketplace), "marketplaceKept").isTrue();
		}
	}

	/**
	 * Uninstall of a home without CAT's files deletes nothing, so a repeated uninstall completes.
	 *
	 * @throws IOException if the fake configuration cannot be created or read
	 */
	@Test
	public void removalPathsOmitAbsent() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());

			requireThat(provider().removalPaths(home), "paths").isEmpty();
		}
	}

	/**
	 * Uninstall keeps CAT's data in the plugin data directory that Claude Code assigns to CAT.
	 *
	 * @throws IOException if the fake configuration cannot be created
	 */
	@Test
	public void dataDirectoryIsPluginData() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());

			requireThat(provider().dataDirectory(home), "dataDirectory").
				isEqualTo(home.resolve("plugins/data/cat-cat"));
		}
	}

	/**
	 * Relative homes cannot redirect the installation beneath the current project.
	 *
	 * @throws IOException if reading the installation fails for another reason
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void relativeHomeRefused() throws IOException
	{
		provider().installed(Path.of("project-home"));
	}

	/**
	 * An invalid manifest in the recorded version's folder produces a read failure rather than a usable installed
	 * version.
	 *
	 * @throws IOException if the fake configuration cannot be created or the read failure is returned
	 */
	@Test(expectedExceptions = IOException.class)
	public void invalidManifestRefused() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			FakeClaude.recordInstalled(home, VERSION);
			Path manifest = home.resolve(VERSION_FOLDERS).resolve(VERSION).resolve(".claude-plugin/plugin.json");
			Files.createDirectories(manifest.getParent());
			Files.writeString(manifest, "invalid JSON");

			provider().installed(home);
		}
	}

	/**
	 * Writes a version's folder and records the version as Claude Code's installed version.
	 *
	 * @param home    the fake configuration directory
	 * @param version the installed version
	 * @throws IOException if a file cannot be written
	 */
	private static void install(Path home, String version) throws IOException
	{
		writeVersionFolder(home, version, version);
		FakeClaude.recordInstalled(home, version);
	}

	/**
	 * Writes a version folder whose plugin manifest states a version.
	 *
	 * @param home          the fake configuration directory
	 * @param name          the folder's name
	 * @param folderVersion the version that the folder's plugin manifest states
	 * @throws IOException if the folder cannot be written
	 */
	private static void writeVersionFolder(Path home, String name, String folderVersion) throws IOException
	{
		Path manifest = home.resolve(VERSION_FOLDERS).resolve(name).resolve(".claude-plugin/plugin.json");
		Files.createDirectories(manifest.getParent());
		Files.writeString(manifest, "{\"name\":\"cat\",\"version\":\"" + folderVersion + "\"}\n");
	}

	/**
	 * Creates a test-owned fake home beneath this module's build directory.
	 *
	 * @return the new fake home, registered by the caller with its cleanup scope
	 * @throws IOException if the directory cannot be created
	 */
	private static Path testDirectory() throws IOException
	{
		Path base = Path.of(System.getProperty("cat.installer.test.directory"));
		Files.createDirectories(base);
		return Files.createTempDirectory(base, "configuration-");
	}

	/**
	 * Selects the provider exactly as the installer does.
	 *
	 * @return the Claude Code provider
	 */
	private static PluginActivation provider()
	{
		List<PluginActivation> providers = ServiceLoader.load(PluginActivation.class).stream().
			map(ServiceLoader.Provider::get).filter(provider -> provider.harness().equals("claude")).toList();
		requireThat(providers, "providers").size().isEqualTo(1);
		return providers.getFirst();
	}
}
