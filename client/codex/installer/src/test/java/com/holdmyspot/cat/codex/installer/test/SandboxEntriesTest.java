package com.holdmyspot.cat.codex.installer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.engine.PluginInstall;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the one sandbox entry, for CAT's capacity locks, that the Codex integration adds to and removes from Codex's
 * {@code config.toml}: a writable root, or a write entry in the permission profile that the file selects and defines.
 */
public final class SandboxEntriesTest
{
	/**
	 * The key under which the integration records the writable root that it added.
	 */
	private static final String KEY = "config.toml#sandbox_workspace_write.writable_roots";
	/**
	 * Quotes the lock folder as a TOML basic string, which JSON's string form also is.
	 */
	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	/**
	 * The maintained entry is the lock folder, for any Codex home.
	 *
	 * @throws IOException if the test directory cannot be created
	 */
	@Test
	public void maintainsLockRoot() throws IOException
	{
		Path home = testRoot().resolve("custom codex home");
		Path locks = testRoot().resolve("user/.cat/locks");

		requireThat(provider().sandboxEntries(home, other(), locks), "entries").
			isEqualTo(Map.of(KEY, List.of(locks.toString())));
	}

	/**
	 * Install adds the lock folder in each form it can edit, changing nothing else, and reports the root as added only
	 * when it wrote it. Uninstall then removes only that value.
	 *
	 * @param original the existing {@code config.toml}
	 * @param edited   the expected {@code config.toml} after install, with {@code %1$s} for the quoted lock folder
	 * @param removed  the expected {@code config.toml} after uninstall
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(dataProvider = "editableForms")
	public void addsAndRemovesLockRoot(String original, String edited, String removed) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path locks = home.resolveSibling("user/.cat/locks");
			Path configuration = home.resolve("config.toml");
			Files.writeString(configuration, original);

			provider().validateConfiguration(home);
			Map<String, List<String>> added = provider().installSandboxEntries(home, other(), locks, replacer());
			requireThat(added, "added").isEqualTo(Map.of(KEY, List.of(locks.toString())));
			requireThat(Files.readString(configuration), "edited").isEqualTo(edited.formatted(quote(locks)));
			requireThat(provider().installSandboxEntries(home, other(), locks, replacer()), "repeated").isEmpty();

			provider().removePermissions(home, added, replacer());
			requireThat(Files.readString(configuration), "removed").isEqualTo(removed);
		}
	}

	/**
	 * Supplies the {@code config.toml} forms that the integration edits.
	 *
	 * @return the original file and the expected files after install and after uninstall
	 */
	@DataProvider
	public Object[][] editableForms()
	{
		return new Object[][]
			{
				// No sandbox table
				{"model = \"gpt\" # mine\n\n[marketplaces.cat]\nsource = \"/m\"\n",
					"model = \"gpt\" # mine\n\n[marketplaces.cat]\nsource = \"/m\"\n\n" +
						"[sandbox_workspace_write]\nwritable_roots = [%1$s]\n",
					"model = \"gpt\" # mine\n\n[marketplaces.cat]\nsource = \"/m\"\n\n" +
						"[sandbox_workspace_write]\nwritable_roots = []\n"},
				// No sandbox table, and no final line feed
				{"model = \"gpt\"", "model = \"gpt\"\n\n[sandbox_workspace_write]\nwritable_roots = [%1$s]\n",
					"model = \"gpt\"\n\n[sandbox_workspace_write]\nwritable_roots = []\n"},
				// A sandbox table without writable roots
				{"[sandbox_workspace_write] # mine\nnetwork_access = true\n\n[other]\nx = 1\n",
					"[sandbox_workspace_write] # mine\nwritable_roots = [%1$s]\nnetwork_access = true\n\n[other]\nx = 1\n",
					"[sandbox_workspace_write] # mine\nwritable_roots = []\nnetwork_access = true\n\n[other]\nx = 1\n"},
				// Writable roots that the user chose
				{"[sandbox_workspace_write]\nwritable_roots = [\"/data\", '/more'] # mine\n",
					"[sandbox_workspace_write]\nwritable_roots = [%1$s, \"/data\", '/more'] # mine\n",
					"[sandbox_workspace_write]\nwritable_roots = [\"/data\", '/more'] # mine\n"},
				// No writable roots yet
				{"[sandbox_workspace_write]\nwritable_roots = []\n",
					"[sandbox_workspace_write]\nwritable_roots = [%1$s]\n",
					"[sandbox_workspace_write]\nwritable_roots = []\n"},
				// Windows line ends
				{"[sandbox_workspace_write]\r\nnetwork_access = true\r\n",
					"[sandbox_workspace_write]\r\nwritable_roots = [%1$s]\r\nnetwork_access = true\r\n",
					"[sandbox_workspace_write]\r\nwritable_roots = []\r\nnetwork_access = true\r\n"}
			};
	}

	/**
	 * Install creates a missing {@code config.toml} with the lock folder, and uninstall removes only that value.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void createsMissingConfiguration() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path locks = home.resolveSibling("user/.cat/locks");
			Path configuration = home.resolve("config.toml");

			Map<String, List<String>> added = provider().installSandboxEntries(home, other(), locks, replacer());
			requireThat(added, "added").isEqualTo(Map.of(KEY, List.of(locks.toString())));
			requireThat(Files.readString(configuration), "created").
				isEqualTo("[sandbox_workspace_write]\nwritable_roots = [" + quote(locks) + "]\n");

			provider().removePermissions(home, added, replacer());
			requireThat(Files.readString(configuration), "removed").
				isEqualTo("[sandbox_workspace_write]\nwritable_roots = []\n");
		}
	}

	/**
	 * Uninstall removes the recorded lock folder and keeps the writable roots that the user added after install.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void removesOnlyRecordedRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path locks = home.resolveSibling("user/.cat/locks");
			Path configuration = home.resolve("config.toml");
			Files.writeString(configuration, "[sandbox_workspace_write]\nwritable_roots = [\"/data\"]\n");
			Map<String, List<String>> added = provider().installSandboxEntries(home, other(), locks, replacer());
			Files.writeString(configuration, "# mine\n[sandbox_workspace_write]\nwritable_roots = [\"/before\", " +
				quote(locks) + ", \"/data\"]\n");

			provider().removePermissions(home, added, replacer());
			requireThat(Files.readString(configuration), "removed").
				isEqualTo("# mine\n[sandbox_workspace_write]\nwritable_roots = [\"/before\", \"/data\"]\n");
		}
	}

	/**
	 * A lock folder that the user already listed is neither written nor recorded, so uninstall keeps it.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void keepsUserLockRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path locks = home.resolveSibling("user/.cat/locks");
			Path configuration = home.resolve("config.toml");
			String original = "[sandbox_workspace_write]\nwritable_roots = [" + quote(locks) + "]\n";
			Files.writeString(configuration, original);

			requireThat(provider().installSandboxEntries(home, other(), locks, replacer()), "added").isEmpty();
			requireThat(Files.readString(configuration), "content").isEqualTo(original);
		}
	}

	/**
	 * Install refuses, changing nothing, each {@code config.toml} form that it cannot edit safely, with a message that
	 * says what to change.
	 *
	 * @param original the existing {@code config.toml}
	 * @param message  a part of the expected refusal
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(dataProvider = "uneditableForms")
	public void refusesUneditableForm(String original, String message) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path locks = home.resolveSibling("user/.cat/locks");
			Path configuration = home.resolve("config.toml");
			Files.writeString(configuration, original);

			for (int attempt = 0; attempt < 2; ++attempt)
			{
				try
				{
					if (attempt == 0)
						provider().validateConfiguration(home);
					else
						provider().installSandboxEntries(home, other(), locks, replacer());
					throw new AssertionError("Accepted a config.toml that CAT cannot edit safely");
				}
				catch (IOException e)
				{
					requireThat(e.getMessage(), "message").contains(configuration.toString()).contains(message);
				}
			}
			requireThat(Files.readString(configuration), "content").isEqualTo(original);
		}
	}

	/**
	 * Supplies the {@code config.toml} forms that the integration refuses to edit.
	 *
	 * @return the file and a part of the refusal
	 */
	@DataProvider
	public Object[][] uneditableForms()
	{
		String oneLine = "write writable_roots on one line";
		return new Object[][]
			{
				{"sandbox_workspace_write = { writable_roots = [\"/data\"] }\n", oneLine},
				{"sandbox_workspace_write.network_access = true\n", oneLine},
				{"[sandbox_workspace_write]\nwritable_roots = [\n  \"/data\",\n]\n", oneLine},
				{"[sandbox_workspace_write]\nwritable_roots = \"/data\"\n", "an array of strings"},
				{"sandbox_workspace_write = true\n", "a table"},
				{"[sandbox_workspace_write\n", "not valid TOML"}
			};
	}

	/**
	 * When {@code config.toml} selects a permission profile that it defines, the maintained entry is the lock folder in
	 * that profile, not a writable root, so install retires a writable root that it recorded before the user selected
	 * the profile.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void maintainsProfileEntry() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path locks = home.resolveSibling("user/.cat/locks");
			Files.writeString(home.resolve("config.toml"), "default_permissions = \"cat\"\n\n[permissions.cat]\n" +
				"extends = \":workspace\"\n");

			requireThat(provider().sandboxEntries(home, other(), locks), "entries").
				isEqualTo(Map.of("config.toml#permissions.cat.filesystem", List.of(locks.toString())));
		}
	}

	/**
	 * When {@code config.toml} selects a permission profile that it defines, install adds the lock folder to that
	 * profile as a write entry in each form it can edit, instead of a writable root, changing nothing else, and reports
	 * the entry as added only when it wrote it. Uninstall then removes only that entry.
	 *
	 * @param original the existing {@code config.toml}
	 * @param key      the key under which install records the entry
	 * @param edited   the expected {@code config.toml} after install, with {@code %1$s} for the quoted lock folder
	 * @param removed  the expected {@code config.toml} after uninstall
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(dataProvider = "editableProfiles")
	public void addsAndRemovesProfileEntry(String original, String key, String edited, String removed)
		throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path locks = home.resolveSibling("user/.cat/locks");
			Path configuration = home.resolve("config.toml");
			Files.writeString(configuration, original);

			provider().validateConfiguration(home);
			Map<String, List<String>> added = provider().installSandboxEntries(home, other(), locks, replacer());
			requireThat(added, "added").isEqualTo(Map.of(key, List.of(locks.toString())));
			requireThat(Files.readString(configuration), "edited").isEqualTo(edited.formatted(quote(locks)));
			requireThat(provider().installSandboxEntries(home, other(), locks, replacer()), "repeated").isEmpty();
			requireThat(Files.readString(configuration), "repeated").isEqualTo(edited.formatted(quote(locks)));

			provider().removePermissions(home, added, replacer());
			requireThat(Files.readString(configuration), "removed").isEqualTo(removed);
		}
	}

	/**
	 * Supplies the {@code config.toml} forms with a selected permission profile that the integration edits.
	 *
	 * @return the original file, the record key, and the expected files after install and after uninstall
	 */
	@DataProvider
	public Object[][] editableProfiles()
	{
		String key = "config.toml#permissions.cat.filesystem";
		return new Object[][]
			{
				// A filesystem table that the user filled
				{"default_permissions = \"cat\"\n\n[permissions.cat]\nextends = \":workspace\"\n\n" +
					"[permissions.cat.filesystem] # mine\n\"/data\" = \"write\"\n\n[other]\nx = 1\n",
					key,
					"default_permissions = \"cat\"\n\n[permissions.cat]\nextends = \":workspace\"\n\n" +
						"[permissions.cat.filesystem] # mine\n%1$s = \"write\"\n\"/data\" = \"write\"\n\n[other]\nx = 1\n",
					"default_permissions = \"cat\"\n\n[permissions.cat]\nextends = \":workspace\"\n\n" +
						"[permissions.cat.filesystem] # mine\n\"/data\" = \"write\"\n\n[other]\nx = 1\n"},
				// A profile without a filesystem table, and a sandbox table that stays as it is
				{"default_permissions = \"cat\"\n\n[sandbox_workspace_write]\nnetwork_access = true\n\n" +
					"[permissions.cat]\nextends = \":workspace\"\n",
					key,
					"default_permissions = \"cat\"\n\n[sandbox_workspace_write]\nnetwork_access = true\n\n" +
						"[permissions.cat]\nextends = \":workspace\"\n\n[permissions.cat.filesystem]\n%1$s = \"write\"\n",
					"default_permissions = \"cat\"\n\n[sandbox_workspace_write]\nnetwork_access = true\n\n" +
						"[permissions.cat]\nextends = \":workspace\"\n\n[permissions.cat.filesystem]\n"},
				// A profile that only its subtable defines, without a final line feed
				{"default_permissions = \"cat\"\n[permissions.cat.workspace_roots]\n\"/w\" = true",
					key,
					"default_permissions = \"cat\"\n[permissions.cat.workspace_roots]\n\"/w\" = true\n\n" +
						"[permissions.cat.filesystem]\n%1$s = \"write\"\n",
					"default_permissions = \"cat\"\n[permissions.cat.workspace_roots]\n\"/w\" = true\n\n" +
						"[permissions.cat.filesystem]\n"},
				// A profile whose name needs quotes
				{"default_permissions = \"my profile\"\n\n[permissions.\"my profile\".filesystem]\n\"/d\" = \"read\"\n",
					"config.toml#permissions.\"my profile\".filesystem",
					"default_permissions = \"my profile\"\n\n[permissions.\"my profile\".filesystem]\n%1$s = \"write\"\n" +
						"\"/d\" = \"read\"\n",
					"default_permissions = \"my profile\"\n\n[permissions.\"my profile\".filesystem]\n\"/d\" = \"read\"\n"},
				// Windows line ends
				{"default_permissions = \"cat\"\r\n[permissions.cat.filesystem]\r\n\"/d\" = \"write\"\r\n",
					key,
					"default_permissions = \"cat\"\r\n[permissions.cat.filesystem]\r\n%1$s = \"write\"\r\n" +
						"\"/d\" = \"write\"\r\n",
					"default_permissions = \"cat\"\r\n[permissions.cat.filesystem]\r\n\"/d\" = \"write\"\r\n"}
			};
	}

	/**
	 * Uninstall removes the recorded profile entry and keeps the entries that the user added after install.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void removesOnlyRecordedProfileEntry() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path locks = home.resolveSibling("user/.cat/locks");
			Path configuration = home.resolve("config.toml");
			Files.writeString(configuration, "default_permissions = \"cat\"\n[permissions.cat.filesystem]\n");
			Map<String, List<String>> added = provider().installSandboxEntries(home, other(), locks, replacer());
			Files.writeString(configuration, "default_permissions = \"cat\"\n# mine\n[permissions.cat.filesystem]\n" +
				"\"/before\" = \"write\"\n" + quote(locks) + " = \"write\"\n\"/after\" = \"none\"\n");

			provider().removePermissions(home, added, replacer());
			requireThat(Files.readString(configuration), "removed").isEqualTo("default_permissions = \"cat\"\n" +
				"# mine\n[permissions.cat.filesystem]\n\"/before\" = \"write\"\n\"/after\" = \"none\"\n");
		}
	}

	/**
	 * A profile entry for the lock folder that the user already wrote, whatever its access, is neither changed nor
	 * recorded, so uninstall keeps it.
	 *
	 * @param access the access that the user's entry grants
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(dataProvider = "accesses")
	public void keepsUserProfileEntry(String access) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path locks = home.resolveSibling("user/.cat/locks");
			Path configuration = home.resolve("config.toml");
			String original = "default_permissions = \"cat\"\n[permissions.cat.filesystem]\n" + quote(locks) +
				" = \"" + access + "\"\n";
			Files.writeString(configuration, original);

			requireThat(provider().installSandboxEntries(home, other(), locks, replacer()), "added").isEmpty();
			requireThat(Files.readString(configuration), "content").isEqualTo(original);
		}
	}

	/**
	 * Supplies the accesses that a profile's filesystem entry grants.
	 *
	 * @return the accesses
	 */
	@DataProvider
	public Object[][] accesses()
	{
		return new Object[][]{{"write"}, {"read"}, {"none"}};
	}

	/**
	 * After the user selects another permission profile, install retires the entry that it recorded in the earlier
	 * profile, and removing that entry changes only the earlier profile.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void retiresEntryOfEarlierProfile() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path locks = home.resolveSibling("user/.cat/locks");
			Path configuration = home.resolve("config.toml");
			Files.writeString(configuration, "default_permissions = \"a\"\n[permissions.a.filesystem]\n" +
				"[permissions.b.filesystem]\n");
			Map<String, List<String>> earlier = provider().installSandboxEntries(home, other(), locks, replacer());
			String selectsB = "default_permissions = \"b\"\n[permissions.a.filesystem]\n" + quote(locks) +
				" = \"write\"\n[permissions.b.filesystem]\n";
			Files.writeString(configuration, selectsB);

			requireThat(provider().sandboxEntries(home, other(), locks), "entries").
				isEqualTo(Map.of("config.toml#permissions.b.filesystem", List.of(locks.toString())));
			provider().removePermissions(home, earlier, replacer());
			requireThat(Files.readString(configuration), "removed").
				isEqualTo("default_permissions = \"b\"\n[permissions.a.filesystem]\n[permissions.b.filesystem]\n");
		}
	}

	/**
	 * Returns the {@code config.toml} forms that can hold the lock folder.
	 *
	 * @return the existing file, the key under which the folder is recorded, and text of the file that the user wrote
	 */
	@DataProvider
	public static Object[][] lockForms()
	{
		return new Object[][]{
			{"[sandbox_workspace_write]\nwritable_roots = [\"/data\"]\n", KEY, "\"/data\""},
			{"default_permissions = \"cat\"\n\n[permissions.cat]\nextends = \":workspace\"\n" +
				"[permissions.cat.filesystem]\n", "config.toml#permissions.cat.filesystem", "extends = \":workspace\""}};
	}

	/**
	 * A reinstall after CAT granted writes to another capacity locks folder, as an earlier installer did from a wrong
	 * user home, leaves only the new folder granted in each form, keeps the user's own entries, and records only the
	 * new grant.
	 *
	 * @param original the existing {@code config.toml}
	 * @param key      the key under which the grant is recorded
	 * @param kept     text that the user wrote and the reinstall keeps
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(dataProvider = "lockForms")
	public void reinstallReplacesEarlierLocksGrant(String original, String key, String kept) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path wrongLocks = home.resolveSibling("account/.cat/locks");
			Path locks = home.resolveSibling("user/.cat/locks");
			Path configuration = home.resolve("config.toml");
			Files.writeString(configuration, original);
			HarnessIntegration provider = provider();
			PluginInstall install = new PluginInstall(provider, _ -> new CommandResult(0, ""), replacer());
			Path launcher = home.resolve("plugins/cache/cat/cat/1.0+b0123456789ab/bin/installer");
			if (Platform.current().isWindows())
				launcher = launcher.resolveSibling("installer.ps1");
			HarnessInstallation installation = provider.installation(home);
			install.installEntries(home, launcher, installation, List.of(other()), wrongLocks, false);
			requireThat(Files.readString(configuration), "granted").contains(quote(wrongLocks));

			PluginInstall.InstalledEntries entries = install.installEntries(home, launcher, installation,
				List.of(other()), locks, false);

			requireThat(Files.readString(configuration), "reinstalled").contains(quote(locks)).
				doesNotContain(quote(wrongLocks)).contains(kept);
			requireThat(entries.recorded().get(key), "recorded").isEqualTo(List.of(locks.toString()));
		}
	}

	/**
	 * Install refuses, changing nothing, each form of a selected permission profile that it cannot edit safely, with a
	 * message that says what to change.
	 *
	 * @param original the existing {@code config.toml}
	 * @param message  a part of the expected refusal
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(dataProvider = "uneditableProfiles")
	public void refusesUneditableProfile(String original, String message) throws IOException
	{
		refusesUneditableForm(original, message);
	}

	/**
	 * Supplies the forms of a selected permission profile that the integration refuses to edit.
	 *
	 * @return the file and a part of the refusal
	 */
	@DataProvider
	public Object[][] uneditableProfiles()
	{
		String oneLine = "one entry per line";
		return new Object[][]
			{
				{"default_permissions = \"cat\"\n[permissions.cat]\nfilesystem = { \"/d\" = \"write\" }\n", oneLine},
				{"default_permissions = \"cat\"\n[permissions.cat]\nfilesystem.\"/d\" = \"write\"\n", oneLine},
				{"default_permissions = \"cat\"\n[permissions]\ncat = { extends = \":workspace\" }\n", oneLine},
				{"default_permissions = \"cat\"\n[permissions.cat]\nfilesystem = \"write\"\n", "a table"}
			};
	}

	/**
	 * Install refuses a {@code config.toml} that is a symbolic link.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*symbolic link.*")
	public void refusesLinkedConfiguration() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path target = Files.writeString(home.resolve("elsewhere.toml"), "");
			Files.createSymbolicLink(home.resolve("config.toml"), target);

			provider().validateConfiguration(home);
		}
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
