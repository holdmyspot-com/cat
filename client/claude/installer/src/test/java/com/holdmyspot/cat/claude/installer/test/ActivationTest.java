package com.holdmyspot.cat.claude.installer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.activation.ActivationStep;
import com.holdmyspot.cat.common.installer.activation.AddDirectory;
import com.holdmyspot.cat.common.installer.activation.InstalledState;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import com.holdmyspot.cat.common.installer.activation.ReplaceDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests Claude Code's {@link PluginActivation} service.
 */
public final class ActivationTest
{
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
	 * Planning changes no protected configuration and targets only the live marketplace plugin.
	 *
	 * @throws IOException if the fake configuration cannot be created or read
	 */
	@Test
	public void planningPreservesConfiguration() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path live = home.resolve("plugins/cat-marketplace/plugins/cat");
			Files.createDirectories(live);
			List<Path> configuration = List.of(home.resolve(".claude.json"), home.resolve("settings.json"),
				home.resolve("plugins/installed_plugins.json"), home.resolve("plugins/known_marketplaces.json"));
			List<FileTime> modificationTimes = new ArrayList<>();
			for (Path file : configuration)
			{
				Files.writeString(file, "original " + file.getFileName());
				modificationTimes.add(Files.getLastModifiedTime(file));
			}

			List<ActivationStep> steps = provider().plan(home, "2.0");

			requireThat(steps, "steps").isEqualTo(List.of(new ReplaceDirectory(live, live.getParent())));
			for (int index = 0; index < configuration.size(); ++index)
			{
				Path file = configuration.get(index);
				requireThat(Files.readString(file), "configurationBytes").isEqualTo("original " + file.getFileName());
				requireThat(Files.getLastModifiedTime(file), "modificationTime").isEqualTo(modificationTimes.get(index));
			}
			requireThat(provider().migrationVersionFile(home), "migrationMarker").
				isEqualTo(home.resolve("plugins/data/cat-cat/migration-version"));
		}
	}

	/**
	 * Installed state follows the live manifest even when the install records state another version.
	 *
	 * @throws IOException if the fake configuration cannot be created or read
	 */
	@Test
	public void readsLiveManifest() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path manifest = home.resolve("plugins/cat-marketplace/plugins/cat/.claude-plugin/plugin.json");
			Files.createDirectories(manifest.getParent());
			Files.writeString(manifest, """
				{"name":"cat","version":"2.0"}
				""");
			Files.writeString(home.resolve("plugins/installed_plugins.json"), """
				{"version":2,"plugins":{"cat@cat":[{"version":"1.0"}]}}
				""");

			InstalledState state = provider().installed(home);

			requireThat(state.version().orElseThrow(), "activeVersion").isEqualTo("2.0");
			requireThat(state.consistent(), "consistent").isTrue();
		}
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
	 * Installing from absence adds the plugin directory inside the marketplace, staged beside it where Claude Code
	 * does not load plugins, and changes nothing while planning.
	 *
	 * @throws IOException if the fake configuration cannot be created or read
	 */
	@Test
	public void installPlanAddsPlugin() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path live = home.resolve("plugins/cat-marketplace/plugins/cat");

			List<ActivationStep> steps = provider().installPlan(home, "2.0");

			requireThat(steps, "steps").isEqualTo(List.of(new AddDirectory(live, live.getParent())));
			requireThat(Files.exists(home.resolve("plugins")), "pluginsCreated").isFalse();
		}
	}

	/**
	 * Install planning refuses a link at each directory boundary, so that an install cannot be redirected.
	 *
	 * @param component the linked directory relative to the fake home
	 * @throws IOException if the fake configuration cannot be created or the refusal is returned
	 */
	@Test(dataProvider = "linkedComponents", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*symbolic link.*")
	public void installPlanRefusesSymlink(String component) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory());
			Path home = Files.createDirectory(root.resolve("home"));
			Path link = home.resolve(component);
			Files.createDirectories(link.getParent());
			Files.createSymbolicLink(link, root.resolve("outside"));

			provider().installPlan(home, "2.0");
		}
	}

	/**
	 * Activating the installed version again replaces the plugin directory, in routine planning and in recovery.
	 *
	 * @throws IOException if the fake configuration cannot be created or read
	 */
	@Test
	public void sameVersionPlanReplaces() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path live = home.resolve("plugins/cat-marketplace/plugins/cat");
			Path manifest = live.resolve(".claude-plugin/plugin.json");
			Files.createDirectories(manifest.getParent());
			Files.writeString(manifest, """
				{"name":"cat","version":"2.0"}
				""");

			List<ActivationStep> steps = provider().plan(home, "2.0");
			List<ActivationStep> recovery = provider().recoveryPlan(home, "2.0", "2.0");

			List<ActivationStep> expected = List.of(new ReplaceDirectory(live, live.getParent()));
			requireThat(steps, "steps").isEqualTo(expected);
			requireThat(recovery, "recovery").isEqualTo(expected);
		}
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
			Path marketplace = Files.createDirectories(home.resolve("plugins/cat-marketplace/plugins/cat"));
			Path cache = Files.createDirectories(home.resolve("plugins/cache/cat/cat/2.0"));
			Files.createDirectories(home.resolve("plugins/cache/other/other/1.0"));
			Files.createDirectories(home.resolve("plugins/data/cat-cat"));

			List<Path> paths = provider().removalPaths(home);

			requireThat(paths, "paths").isEqualTo(List.of(marketplace.getParent().getParent(),
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
	 * Planning refuses an absent installation and directs the user to initial setup.
	 *
	 * @throws IOException if the fake configuration cannot be created or the refusal is returned
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*initial setup.*")
	public void missingInstallationRefused() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			provider().plan(cleanup.add(testDirectory()), "2.0");
		}
	}

	/**
	 * Recovery reconstructs the canonical replacement when a crash has moved the live directory to its previous slot.
	 *
	 * @throws IOException if the fake configuration cannot be created or read
	 */
	@Test
	public void recoveryAllowsMissingLive() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path live = home.resolve("plugins/cat-marketplace/plugins/cat");
			Path previous = Files.createDirectories(live.getParent().resolve(".cat-previous-cat"));
			Path marker = Files.writeString(previous.resolve("previous-marker"), "previous release");
			FileTime modificationTime = Files.getLastModifiedTime(marker);

			List<ActivationStep> steps = provider().recoveryPlan(home, "1.0", "2.0");

			requireThat(steps, "steps").isEqualTo(List.of(new ReplaceDirectory(live, live.getParent())));
			requireThat(Files.notExists(live), "liveStillAbsent").isTrue();
			requireThat(Files.readString(marker), "previousContent").isEqualTo("previous release");
			requireThat(Files.getLastModifiedTime(marker), "modificationTime").isEqualTo(modificationTime);
		}
	}

	/**
	 * Recovery refuses the same redirected directory components as routine planning, even with no live directory.
	 *
	 * @param component the linked directory relative to the fake home
	 * @throws IOException if the fake configuration cannot be created or the refusal is returned
	 */
	@Test(dataProvider = "linkedComponents", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*symbolic link.*")
	public void recoveryRefusesSymlink(String component) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory());
			Path home = Files.createDirectory(root.resolve("home"));
			Path link = home.resolve(component);
			Files.createDirectories(link.getParent());
			Files.createSymbolicLink(link, root.resolve("outside"));

			provider().recoveryPlan(home, "1.0", "2.0");
		}
	}

	/**
	 * Relative homes cannot redirect activation beneath the current project.
	 *
	 * @throws IOException if planning reads an activation path
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void relativeHomeRefused() throws IOException
	{
		provider().plan(Path.of("project-home"), "2.0");
	}

	/**
	 * Supplies links at each directory boundary that a project could redirect.
	 *
	 * @return the paths relative to the policy home
	 */
	@DataProvider
	public Object[][] linkedComponents()
	{
		return new Object[][] {{"plugins"}, {"plugins/cat-marketplace"},
			{"plugins/cat-marketplace/plugins"}, {"plugins/cat-marketplace/plugins/cat"}};
	}

	/**
	 * Planning refuses an existing or dangling link before it can describe a replacement outside the policy home.
	 *
	 * @param component the linked directory relative to the fake home
	 * @throws IOException if the fake configuration cannot be created or the refusal is returned
	 */
	@Test(dataProvider = "linkedComponents", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*symbolic link.*")
	public void symlinkedComponentRefused(String component) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory());
			Path home = Files.createDirectory(root.resolve("home"));
			Path link = home.resolve(component);
			Files.createDirectories(link.getParent());
			Files.createSymbolicLink(link, root.resolve("outside"));

			provider().plan(home, "2.0");
		}
	}

	/**
	 * An invalid live manifest produces a read failure rather than a usable installed version.
	 *
	 * @throws IOException if the fake configuration cannot be created or the read failure is returned
	 */
	@Test(expectedExceptions = IOException.class)
	public void invalidManifestRefused() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			Path manifest = home.resolve("plugins/cat-marketplace/plugins/cat/.claude-plugin/plugin.json");
			Files.createDirectories(manifest.getParent());
			Files.writeString(manifest, "invalid JSON");

			provider().installed(home);
		}
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
