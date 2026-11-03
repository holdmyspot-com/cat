package com.holdmyspot.cat.claude.updater.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.ShellQuoting;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.updater.cli.SandboxExemption;
import com.holdmyspot.cat.common.updater.activation.ActivationStep;
import com.holdmyspot.cat.common.updater.activation.InstalledState;
import com.holdmyspot.cat.common.updater.activation.PluginActivation;
import com.holdmyspot.cat.common.updater.activation.ReplaceDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
	 * The exemption service emits the exact latest invocation and version wildcard for both required settings.
	 */
	@Test
	public void suppliesSandboxExemptions()
	{
		List<SandboxExemption> providers = ServiceLoader.load(SandboxExemption.class).stream().
			map(ServiceLoader.Provider::get).filter(provider -> provider.harness().equals("claude")).toList();
		requireThat(providers, "providers").size().isEqualTo(1);
		Path launcher = Path.of(System.getProperty("cat.updater.test.directory")).
			resolve("updater/bin/cat-updater");
		Path home = Path.of(System.getProperty("cat.updater.test.directory")).resolve("fake-home");
		String latest = ShellQuoting.posix(launcher.toString()).replace("*", "'\\*'") +
			" update --harness claude";
		String version = latest.replace("\\", "\\\\").replace("*", "\\*") + " *";

		Map<String, List<String>> entries = providers.getFirst().entries(launcher, home);

		requireThat(entries, "entries").isEqualTo(Map.of(
			"sandbox.excludedCommands", List.of(latest, version),
			"permissions.allow", List.of("Bash(" + latest + ")", "Bash(" + version + ")")));
	}

	/**
	 * Supplies shell-sensitive launcher names; a literal star is supplied only on file systems that accept it.
	 *
	 * @return launcher names and the exact latest and version-pattern forms that preserve those paths
	 */
	@DataProvider
	public Object[][] launcherNames()
	{
		Path base = Path.of(System.getProperty("cat.updater.test.directory"));
		String spaced = base.resolve("updater data/bin/cat-updater").toString();
		String metacharacters = base.resolve("updater $payload;echo hi/bin/cat-updater").toString();
		String apostrophe = base.resolve("updater's data/bin/cat-updater").toString();
		List<Object[]> cases = new ArrayList<>();
		for (String launcher : List.of(spaced, metacharacters, apostrophe))
		{
			String command = ShellQuoting.posix(launcher).replace("*", "'\\*'") + " update --harness claude";
			String pattern = command.replace("\\", "\\\\").replace("*", "\\*") + " *";
			cases.add(new Object[] {Path.of(launcher), command, pattern});
		}
		try
		{
			Path launcher = base.resolve("updater*data/bin/cat-updater");
			String command = ShellQuoting.posix(launcher.toString()).replace("*", "'\\*'") +
				" update --harness claude";
			String pattern = command.replace("\\", "\\\\").replace("*", "\\*") + " *";
			cases.add(new Object[] {launcher, command, pattern});
		}
		catch (InvalidPathException _)
		{
			// Windows cannot supply a launcher path containing a literal star.
		}
		return cases.toArray(Object[][]::new);
	}

	/**
	 * Exemption entries quote one executable path and keep its literal characters out of the version wildcard.
	 *
	 * @param launcher the absolute installed launcher path
	 * @param latest   the exact shell command that invokes the latest approved release
	 * @param version  the pattern that adds only the version argument wildcard
	 */
	@Test(dataProvider = "launcherNames")
	public void quotesLauncherInExemptions(Path launcher, String latest, String version)
	{
		SandboxExemption exemption = ServiceLoader.load(SandboxExemption.class).stream().
			map(ServiceLoader.Provider::get).filter(provider -> provider.harness().equals("claude")).
			findFirst().orElseThrow();

		Map<String, List<String>> entries = exemption.entries(launcher, launcher.getParent());

		requireThat(entries, "entries").isEqualTo(Map.of(
			"sandbox.excludedCommands", List.of(latest, version),
			"permissions.allow", List.of("Bash(" + latest + ")", "Bash(" + version + ")")));
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
	 * An absent live directory or manifest reports no installation and no consistent copy.
	 *
	 * @throws IOException if the fake configuration cannot be created or read
	 */
	@Test
	public void missingManifestHasNoVersion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			requireThat(provider().installed(home).version().isEmpty(), "absentLiveVersion").isTrue();
			Files.createDirectories(home.resolve("plugins/cat-marketplace/plugins/cat"));

			InstalledState state = provider().installed(home);

			requireThat(state.version().isEmpty(), "absentManifestVersion").isTrue();
			requireThat(state.consistent(), "consistent").isFalse();
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
		Path base = Path.of(System.getProperty("cat.updater.test.directory"));
		Files.createDirectories(base);
		return Files.createTempDirectory(base, "configuration-");
	}

	/**
	 * Selects the provider exactly as the updater does.
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
