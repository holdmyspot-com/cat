package com.holdmyspot.cat.codex.installer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests how the Codex integration handles a {@code config.toml} that selects a permission profile built into Codex,
 * to which CAT cannot add the write entry of its capacity locks: install asks the user before it changes anything, and
 * with the user's permission selects a profile of CAT's that extends the built-in one; uninstall undoes only what the
 * user did not change.
 */
public final class BuiltInProfileTest
{
	/**
	 * Quotes the lock folder as a TOML basic string, which JSON's string form also is.
	 */
	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	/**
	 * Install asks the user before it replaces a built-in profile that limits which files commands may write. The
	 * question says that the profile is built into Codex, what fails without the lock folder, and which profile CAT
	 * creates in its place.
	 *
	 * @param builtIn    the selected built-in profile
	 * @param catProfile the name of the profile that CAT offers to create
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(dataProvider = "builtInProfiles")
	public void asksBeforeReplacingBuiltInProfile(String builtIn, String catProfile) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path locks = home.resolveSibling("user/.cat/locks");
			Path configuration = home.resolve("config.toml");
			String original = "default_permissions = \"" + builtIn + "\"\n";
			Files.writeString(configuration, original);

			provider().validateConfiguration(home);
			Optional<String> question = provider().consentRequest(home, locks);

			requireThat(question.isPresent(), "asked").isTrue();
			requireThat(question.get(), "question").contains(configuration.toString()).
				contains("\"" + builtIn + "\", which is built into Codex").contains(locks.toString()).
				contains("cannot start Codex or Claude Code sessions").
				contains("creates the permission profile \"" + catProfile + "\", which extends \"" + builtIn + "\"").
				contains("sets default_permissions to it");
			requireThat(Files.readString(configuration), "configuration").isEqualTo(original);
		}
	}

	/**
	 * Supplies the built-in profiles that limit which files commands may write.
	 *
	 * @return each built-in profile and the name of CAT's profile of it
	 */
	@DataProvider
	public Object[][] builtInProfiles()
	{
		return new Object[][]
			{
				{":workspace", "cat-workspace"},
				{":read-only", "cat-read-only"}
			};
	}

	/**
	 * Install asks nothing when CAT can add the lock folder without replacing a built-in profile: when the file selects
	 * no profile, a profile that it defines, or the built-in {@code :danger-full-access}, which limits no files.
	 *
	 * @param original the existing {@code config.toml}, or null when the file is absent
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(dataProvider = "noQuestion")
	public void asksNothingWithoutBuiltInLimit(String original) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path locks = home.resolveSibling("user/.cat/locks");
			if (original != null)
				Files.writeString(home.resolve("config.toml"), original);

			requireThat(provider().consentRequest(home, locks), "question").isEmpty();
			requireThat(provider().installConsentedEntries(home, replacer()), "consented").isEmpty();
		}
	}

	/**
	 * Supplies the files for which install asks nothing.
	 *
	 * @return each {@code config.toml}
	 */
	@DataProvider
	public Object[][] noQuestion()
	{
		return new Object[][]
			{
				{null},
				{"model = \"gpt\"\n"},
				{"default_permissions = \":danger-full-access\"\n"},
				{"default_permissions = \"mine\"\n\n[permissions.mine]\nextends = \":workspace\"\n"},
				{"default_permissions = \":workspace\"\n\n[permissions.\":workspace\"]\nextends = \":read-only\"\n"}
			};
	}

	/**
	 * With the user's permission, install selects CAT's profile, which extends the built-in one and holds only the lock
	 * folder, changing nothing else. It tells the user so, and a repeated install changes nothing and tells nothing.
	 * Uninstall then restores the original file.
	 *
	 * @param original the existing {@code config.toml}
	 * @param edited   the expected {@code config.toml} after install, with {@code %1$s} for the quoted lock folder
	 * @param removed  the expected {@code config.toml} after uninstall
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(dataProvider = "replacedProfiles")
	public void replacesAndRestoresBuiltInProfile(String original, String edited, String removed) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path locks = home.resolveSibling("user/.cat/locks");
			Path configuration = home.resolve("config.toml");
			Files.writeString(configuration, original);

			Map<String, List<String>> recorded = install(home, locks);

			requireThat(Files.readString(configuration), "edited").isEqualTo(edited.formatted(quote(locks)));
			requireThat(recorded, "recorded").isEqualTo(Map.of(
				"config.toml#default_permissions", List.of(":workspace"),
				"config.toml#permissions.cat-workspace", List.of(":workspace"),
				"config.toml#permissions.cat-workspace.filesystem", List.of(locks.toString())));
			requireThat(provider().describeAdded(home, recorded), "notices").isEqualTo(List.of(
				"CAT set default_permissions in " + configuration + " to its permission profile \"cat-workspace\", " +
					"which extends \":workspace\" and lets sandboxed commands take CAT's capacity locks; " +
					"uninstalling CAT sets it back to \":workspace\"."));
			requireThat(sandboxEntries(home, locks), "maintained").isEqualTo(recorded);

			requireThat(provider().consentRequest(home, locks), "repeatedQuestion").isEmpty();
			Map<String, List<String>> repeated = installSandboxEntries(home, locks);
			requireThat(repeated, "repeated").isEmpty();
			requireThat(provider().describeAdded(home, repeated), "repeatedNotices").isEmpty();
			requireThat(Files.readString(configuration), "repeated").isEqualTo(edited.formatted(quote(locks)));

			provider().removePermissions(home, recorded, replacer());
			requireThat(Files.readString(configuration), "removed").isEqualTo(removed);
			requireThat(provider().describeRetained(home, recorded), "retained").isEmpty();
		}
	}

	/**
	 * Supplies the files that select {@code :workspace} in a form that the integration edits.
	 *
	 * @return the original file, and the expected files after install and after uninstall
	 */
	@DataProvider
	public Object[][] replacedProfiles()
	{
		return new Object[][]
			{
				// Other settings and tables stay as they are
				{"model = \"gpt\"\ndefault_permissions = \":workspace\"\n\n[sandbox_workspace_write]\n" +
					"network_access = true\n",
					"model = \"gpt\"\ndefault_permissions = \"cat-workspace\"\n\n[sandbox_workspace_write]\n" +
						"network_access = true\n\n[permissions.cat-workspace]\nextends = \":workspace\"\n\n" +
						"[permissions.cat-workspace.filesystem]\n%1$s = \"write\"\n",
					"model = \"gpt\"\ndefault_permissions = \":workspace\"\n\n[sandbox_workspace_write]\n" +
						"network_access = true\n"},
				// A literal string with a comment, and another profile of the user's
				{"default_permissions = ':workspace' # mine\n\n[permissions.mine]\nextends = \":read-only\"\n",
					"default_permissions = \"cat-workspace\" # mine\n\n[permissions.mine]\nextends = \":read-only\"\n\n" +
						"[permissions.cat-workspace]\nextends = \":workspace\"\n\n" +
						"[permissions.cat-workspace.filesystem]\n%1$s = \"write\"\n",
					"default_permissions = \":workspace\" # mine\n\n[permissions.mine]\nextends = \":read-only\"\n"}
			};
	}

	/**
	 * When the user selects another profile after install, uninstall leaves {@code default_permissions} as the user
	 * set it, removes CAT's profile, which holds only what CAT wrote, and says that it left the setting.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void leavesSelectionThatUserChanged() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path locks = home.resolveSibling("user/.cat/locks");
			Path configuration = home.resolve("config.toml");
			Files.writeString(configuration, "default_permissions = \":workspace\"\n");
			Map<String, List<String>> recorded = install(home, locks);
			Files.writeString(configuration, Files.readString(configuration).
				replace("default_permissions = \"cat-workspace\"", "default_permissions = \":read-only\""));

			provider().removePermissions(home, recorded, replacer());

			requireThat(Files.readString(configuration), "removed").isEqualTo("default_permissions = \":read-only\"\n");
			requireThat(provider().describeRetained(home, recorded), "retained").isEqualTo(List.of(
				"CAT left default_permissions in " + configuration + " as it is, because it no longer names CAT's " +
					"permission profile \"cat-workspace\"."));
		}
	}

	/**
	 * When the user adds a setting or a comment to CAT's profile after install, uninstall restores
	 * {@code default_permissions}, removes the lock folder, leaves the profile and says so.
	 *
	 * @param addition the line that the user adds below the profile's {@code extends} line
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(dataProvider = "userAdditions")
	public void leavesProfileThatUserChanged(String addition) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path locks = home.resolveSibling("user/.cat/locks");
			Path configuration = home.resolve("config.toml");
			Files.writeString(configuration, "default_permissions = \":workspace\"\n");
			Map<String, List<String>> recorded = install(home, locks);
			Files.writeString(configuration, Files.readString(configuration).
				replace("extends = \":workspace\"\n", "extends = \":workspace\"\n" + addition + "\n"));

			provider().removePermissions(home, recorded, replacer());

			requireThat(Files.readString(configuration), "removed").isEqualTo("default_permissions = \":workspace\"\n\n" +
				"[permissions.cat-workspace]\nextends = \":workspace\"\n" + addition + "\n\n" +
				"[permissions.cat-workspace.filesystem]\n");
			requireThat(provider().describeRetained(home, recorded), "retained").isEqualTo(List.of(
				"CAT left its permission profile \"cat-workspace\" in " + configuration + ", because it holds " +
					"settings that CAT did not write."));
		}
	}

	/**
	 * Supplies the lines that make CAT's profile the user's.
	 *
	 * @return each line
	 */
	@DataProvider
	public Object[][] userAdditions()
	{
		return new Object[][]
			{
				{"# my notes"},
				{"[permissions.cat-workspace.network]\nenabled = true"}
			};
	}

	/**
	 * Install refuses, before it changes anything, a file that already defines a profile with the name of CAT's
	 * profile that does not extend the selected built-in profile.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void refusesProfileNameThatUserTook() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path configuration = home.resolve("config.toml");
			String original = "default_permissions = \":workspace\"\n\n[permissions.cat-workspace]\n" +
				"extends = \":read-only\"\n";
			Files.writeString(configuration, original);

			try
			{
				provider().validateConfiguration(home);
				throw new AssertionError("validateConfiguration accepted the file");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("\"cat-workspace\"").contains("does not extend").
					contains(configuration.toString());
			}
			requireThat(Files.readString(configuration), "configuration").isEqualTo(original);
		}
	}

	/**
	 * A write entry that install adds to a profile of the user's comes with a notice that names the profile, the file
	 * and the lock folder, and says that every project that uses the profile gets the entry; an entry that the profile
	 * already holds comes with none.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void tellsWhenUserProfileGetsEntry() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path locks = home.resolveSibling("user/.cat/locks");
			Path configuration = home.resolve("config.toml");
			Files.writeString(configuration, "default_permissions = \"mine\"\n\n[permissions.mine]\n" +
				"extends = \":workspace\"\n");

			Map<String, List<String>> added = installSandboxEntries(home, locks);

			requireThat(provider().describeAdded(home, added), "notices").isEqualTo(List.of(
				"CAT added " + quote(locks) + " = \"write\" to the Codex permission profile \"mine\" in " +
					configuration + ", so that sandboxed commands can take CAT's capacity locks; every project that " +
					"uses this profile can now write that folder too."));
			Map<String, List<String>> repeated = installSandboxEntries(home, locks);
			requireThat(provider().describeAdded(home, repeated), "repeated").isEmpty();
		}
	}

	/**
	 * Runs the steps of an install that the user allowed, in the installer's order.
	 *
	 * @param home  the Codex home
	 * @param locks the capacity lock folder
	 * @return the entries that install recorded
	 * @throws IOException if a step fails
	 */
	private static Map<String, List<String>> install(Path home, Path locks) throws IOException
	{
		provider().validateConfiguration(home);
		requireThat(provider().consentRequest(home, locks).isPresent(), "asked").isTrue();
		Map<String, List<String>> recorded = new HashMap<>(provider().installConsentedEntries(home, replacer()));
		recorded.putAll(installSandboxEntries(home, locks));
		return recorded;
	}

	/**
	 * Quotes a path as a TOML basic string.
	 *
	 * @param path the path
	 * @return the quoted path
	 */
	private static String quote(Path path)
	{
		return MAPPER.writeValueAsString(path.toString());
	}

	/**
	 * Returns the folder that holds the session launcher's state, beside the lock folder.
	 *
	 * @param locks the capacity lock folder
	 * @return the folder
	 */
	private static Path launcherState(Path locks)
	{
		return locks.resolveSibling("sessions");
	}

	/**
	 * Adds the sandbox entries for the lock folder and the launcher's state.
	 *
	 * @param home  the Codex home
	 * @param locks the capacity lock folder
	 * @return the entries that the call added
	 * @throws IOException if the configuration cannot be changed
	 */
	private static Map<String, List<String>> installSandboxEntries(Path home, Path locks) throws IOException
	{
		return provider().installSandboxEntries(home, other(), locks, launcherState(locks), replacer());
	}

	/**
	 * Returns the sandbox entries for the lock folder and the launcher's state.
	 *
	 * @param home  the Codex home
	 * @param locks the capacity lock folder
	 * @return the entries
	 * @throws IOException if the other harness's installation cannot be located
	 */
	private static Map<String, List<String>> sandboxEntries(Path home, Path locks) throws IOException
	{
		return provider().sandboxEntries(home, other(), locks, launcherState(locks));
	}

	/**
	 * Returns another harness's CAT installation, for which Codex needs no entries.
	 *
	 * @return the installation
	 * @throws IOException if the test directory cannot be created
	 */
	private static HarnessInstallation other() throws IOException
	{
		return provider().installation(testRoot().resolve("other-harness-home"));
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
	 * Returns a replacer whose test-owned files never need a retry wait.
	 *
	 * @return the replacer
	 */
	private static FileReplacer replacer()
	{
		return new FileReplacer(Platform.current(), () ->
		{
		}, Clock.systemUTC());
	}

	/**
	 * Finds the Codex integration through the same registration that the installer consumes.
	 *
	 * @return the Codex integration
	 */
	private static HarnessIntegration provider()
	{
		return ServiceLoader.load(HarnessIntegration.class).stream().map(ServiceLoader.Provider::get).
			filter(provider -> provider.harness().equals("codex")).findFirst().
			orElseThrow(() -> new AssertionError("The Codex harness integration service is missing"));
	}
}
