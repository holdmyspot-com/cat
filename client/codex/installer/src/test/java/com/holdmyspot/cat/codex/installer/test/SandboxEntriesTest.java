package com.holdmyspot.cat.codex.installer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installer.activation.EntryEffect;
import com.holdmyspot.cat.common.installer.activation.EntryGroup;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.engine.InstallerState;
import com.holdmyspot.cat.common.installer.engine.PermissionRecord;
import com.holdmyspot.cat.common.installer.engine.PluginInstall;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the sandbox entries that the Codex integration adds to and removes from Codex's {@code config.toml}: for CAT's
 * capacity locks, a writable root or a write entry in the permission profile that the file selects and defines.
 */
public final class SandboxEntriesTest
{
	/**
	 * Waits for nothing, so that a deletion that fails is not retried at length.
	 */
	private static final Waiting NO_WAIT = interval -> () ->
	{
	};
	/**
	 * The key under which the integration records the writable root that it added.
	 */
	private static final String KEY = "config.toml#sandbox_workspace_write.writable_roots";
	/**
	 * The suffix of the key under which the integration records the read entries that it added to a profile.
	 */
	private static final String READ_SUFFIX = ".read";
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

		requireThat(sandboxEntries(home, locks), "entries").
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
			Map<String, List<String>> added = installSandboxEntries(home, locks);
			requireThat(added, "added").isEqualTo(Map.of(KEY, List.of(locks.toString())));
			requireThat(Files.readString(configuration), "edited").isEqualTo(edited.formatted(quote(locks)));
			requireThat(installSandboxEntries(home, locks), "repeated").isEmpty();

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

			Map<String, List<String>> added = installSandboxEntries(home, locks);
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
			Map<String, List<String>> added = installSandboxEntries(home, locks);
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

			requireThat(installSandboxEntries(home, locks), "added").isEmpty();
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
						installSandboxEntries(home, locks);
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
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path locks = home.resolveSibling("user/.cat/locks");
			Files.writeString(home.resolve("config.toml"), "default_permissions = \"cat\"\n\n[permissions.cat]\n" +
				"extends = \":workspace\"\n");

			requireThat(sandboxEntries(home, locks), "entries").isEqualTo(Map.of(
				"config.toml#permissions.cat.filesystem", List.of(locks.toString())));
		}
	}

	/**
	 * When {@code config.toml} selects a permission profile that it defines, install adds the lock folder to that
	 * profile as a write entry, in each form it can edit, instead of a writable root, changing nothing else, and
	 * reports the entry as added only when it wrote it. Uninstall then removes only that entry.
	 *
	 * @param original the existing {@code config.toml}
	 * @param key      the key under which install records the write entry
	 * @param edited   the expected {@code config.toml} after install, with {@code %1$s} for the quoted lock folder
	 * @param removed  the expected {@code config.toml} after uninstall
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(dataProvider = "editableProfiles")
	public void addsAndRemovesProfileEntry(String original, String key, String edited, String removed)
		throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path locks = home.resolveSibling("user/.cat/locks");
			Path configuration = home.resolve("config.toml");
			Files.writeString(configuration, original);

			provider().validateConfiguration(home);
			Map<String, List<String>> added = installSandboxEntries(home, locks);
			requireThat(added, "added").isEqualTo(Map.of(key, List.of(locks.toString())));
			String expected = edited.formatted(quote(locks));
			requireThat(Files.readString(configuration), "edited").isEqualTo(expected);
			requireThat(installSandboxEntries(home, locks), "repeated").isEmpty();
			requireThat(Files.readString(configuration), "repeated").isEqualTo(expected);

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
						"[permissions.cat.filesystem] # mine\n%1$s = \"write\"\n\"/data\" = \"write\"\n" +
						"\n[other]\nx = 1\n",
					"default_permissions = \"cat\"\n\n[permissions.cat]\nextends = \":workspace\"\n\n" +
						"[permissions.cat.filesystem] # mine\n\"/data\" = \"write\"\n\n[other]\nx = 1\n"},
				// A profile without a filesystem table, and a sandbox table that stays as it is
				{"default_permissions = \"cat\"\n\n[sandbox_workspace_write]\nnetwork_access = true\n\n" +
					"[permissions.cat]\nextends = \":workspace\"\n",
					key,
					"default_permissions = \"cat\"\n\n[sandbox_workspace_write]\nnetwork_access = true\n\n" +
						"[permissions.cat]\nextends = \":workspace\"\n\n[permissions.cat.filesystem]\n" +
						"%1$s = \"write\"\n",
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
					"default_permissions = \"my profile\"\n\n[permissions.\"my profile\".filesystem]\n" +
						"%1$s = \"write\"\n\"/d\" = \"read\"\n",
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
	 * When {@code config.toml} selects a permission profile that it defines, the read-only entries add a read entry
	 * for each file to that profile, keep the user's own entry for a file, are reported as paths that the sandbox may
	 * not write, and are removed completely; repeating the call adds nothing.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void readOnlyEntriesNarrowProfile() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path userHome = home.resolveSibling("user");
			Path bashrc = userHome.resolve(".bashrc");
			Path zshenv = userHome.resolve(".zshenv");
			Path profile = userHome.resolve(".profile");
			Path configuration = home.resolve("config.toml");
			String original = "default_permissions = \"cat\"\n\n[permissions.cat.filesystem]\n" + quote(profile) +
				" = \"write\"\n";
			Files.writeString(configuration, original);
			List<Path> files = List.of(bashrc, zshenv, profile);

			Map<String, List<String>> added = provider().installReadOnlyEntries(home, files, replacer());

			String key = "config.toml#permissions.cat.filesystem" + READ_SUFFIX;
			requireThat(added, "added").isEqualTo(Map.of(key, List.of(bashrc.toString(), zshenv.toString())));
			requireThat(provider().readOnlyEntries(home, files), "maintained").
				isEqualTo(Map.of(key, List.of(bashrc.toString(), zshenv.toString(), profile.toString())));
			requireThat(provider().installReadOnlyEntries(home, files, replacer()), "repeated").isEmpty();
			requireThat(Files.readString(configuration), "edited").contains(quote(bashrc) + " = \"read\"\n").
				contains(quote(zshenv) + " = \"read\"\n").contains(quote(profile) + " = \"write\"\n");
			List<EntryGroup> groups = provider().describeEntries(added, home.resolve("bin/installer"));
			requireThat(groups, "groups").isEqualTo(List.of(new EntryGroup(EntryEffect.SANDBOX_MAY_NOT_WRITE,
				Path.of("config.toml"), "permissions.cat.filesystem", added.get(key),
				List.of(quote(bashrc) + " = \"read\"", quote(zshenv) + " = \"read\""))));

			provider().removePermissions(home, added, replacer());
			requireThat(Files.readString(configuration), "removed").isEqualTo(original);
		}
	}

	/**
	 * Without a selected permission profile that {@code config.toml} defines, Codex's sandbox settings cannot deny a
	 * path, so the read-only entries add nothing.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void readOnlyEntriesNeedProfile() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path configuration = home.resolve("config.toml");
			String original = "[sandbox_workspace_write]\nwritable_roots = []\n";
			Files.writeString(configuration, original);
			List<Path> files = List.of(home.resolveSibling("user/.bashrc"));

			requireThat(provider().installReadOnlyEntries(home, files, replacer()), "added").isEmpty();
			requireThat(provider().readOnlyEntries(home, files), "maintained").isEmpty();
			requireThat(Files.readString(configuration), "content").isEqualTo(original);
		}
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
			Map<String, List<String>> added = installSandboxEntries(home, locks);
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
	 * @param access the access that the user's entries grant
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

			requireThat(installSandboxEntries(home, locks), "added").isEmpty();
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
	 * After the user selects another permission profile, install retires the entries that it recorded in the earlier
	 * profile, and removing those entries changes only the earlier profile.
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
			Map<String, List<String>> earlier = installSandboxEntries(home, locks);
			String selectsB = "default_permissions = \"b\"\n[permissions.a.filesystem]\n" + quote(locks) +
				" = \"write\"\n[permissions.b.filesystem]\n";
			Files.writeString(configuration, selectsB);

			requireThat(sandboxEntries(home, locks), "entries").isEqualTo(Map.of(
				"config.toml#permissions.b.filesystem", List.of(locks.toString())));
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
			PluginInstall install = new PluginInstall(provider, CodexProgram.cli(home), replacer(), NO_WAIT);
			Path launcher = home.resolve("plugins/cache/cat/cat/1.0+b0123456789ab/bin/installer");
			if (Platform.current().isWindows())
				launcher = launcher.resolveSibling("installer.ps1");
			HarnessInstallation installation = provider.installation(home);
			install.installEntries(home, launcher, installation, List.of(other()), wrongLocks,
				launcherState(wrongLocks), List.of(), false);
			requireThat(Files.readString(configuration), "granted").contains(quote(wrongLocks));

			PluginInstall.InstalledEntries entries = install.installEntries(home, launcher, installation,
				List.of(other()), locks, launcherState(locks), List.of(), false);

			requireThat(Files.readString(configuration), "reinstalled").contains(quote(locks)).
				doesNotContain(quote(wrongLocks)).contains(kept);
			requireThat(entries.recorded().get(key), "recorded").isEqualTo(List.of(locks.toString()));
		}
	}

	/**
	 * A reinstall after an earlier install added a read entry for the session launcher's state folder to the selected
	 * permission profile removes that recorded entry and the folder, and keeps the user's own entries.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void reinstallRemovesLauncherState() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			// The state folder is created and deleted by this test alone. Tests run in parallel, and every other test names
			// the shared user folder's state folder without creating it, so a second thread that inspects or deletes the
			// folder while this install deletes it makes Windows refuse with AccessDeniedException (delete-pending).
			Path user = cleanup.add(Files.createTempDirectory(testRoot(), "user-"));
			Path locks = user.resolve(".cat/locks");
			Path state = launcherState(locks);
			Files.createDirectories(state.resolve("codex/launchers/1234"));
			Files.writeString(state.resolve("codex/launchers/1234/heartbeat"), "1");
			Path configuration = home.resolve("config.toml");
			Files.writeString(configuration, "default_permissions = \"cat\"\n[permissions.cat.filesystem]\n" +
				quote(locks) + " = \"write\"\n" + quote(state) + " = \"read\"\n\"/mine\" = \"read\"\n");
			HarnessIntegration provider = provider();
			HarnessInstallation installation = provider.installation(home);
			PermissionRecord.add(InstallerState.of(installation.dataDirectory(), "codex").permissions(), Map.of(
				"config.toml#permissions.cat.filesystem" + READ_SUFFIX, List.of(state.toString())));
			PluginInstall install = new PluginInstall(provider, CodexProgram.cli(home), replacer(), NO_WAIT);
			Path launcher = home.resolve("plugins/cache/cat/cat/1.0+b0123456789ab/bin/installer");
			if (Platform.current().isWindows())
				launcher = launcher.resolveSibling("installer.ps1");

			PluginInstall.InstalledEntries entries = install.installEntries(home, launcher, installation,
				List.of(other()), locks, state, List.of(), false);

			requireThat(Files.readString(configuration), "reinstalled").doesNotContain(quote(state)).
				contains("\"/mine\" = \"read\"").contains(quote(locks) + " = \"write\"");
			requireThat(entries.recorded().getOrDefault("config.toml#permissions.cat.filesystem" + READ_SUFFIX,
				List.of()), "recorded").doesNotContain(state.toString());
			requireThat(Files.exists(state, LinkOption.NOFOLLOW_LINKS), "stateExists").isFalse();
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
	 * Returns the folder in which an earlier release's session launcher kept its state, beside the lock folder.
	 *
	 * @param locks the capacity lock folder
	 * @return the folder
	 */
	private static Path launcherState(Path locks)
	{
		return locks.resolveSibling("sessions");
	}

	/**
	 * Adds the sandbox entries for the lock folder.
	 *
	 * @param home  the Codex home
	 * @param locks the capacity lock folder
	 * @return the entries that the call added
	 * @throws IOException if the configuration cannot be changed
	 */
	private static Map<String, List<String>> installSandboxEntries(Path home, Path locks) throws IOException
	{
		return provider().installSandboxEntries(home, other(), locks, replacer());
	}

	/**
	 * Returns the sandbox entries for the lock folder.
	 *
	 * @param home  the Codex home
	 * @param locks the capacity lock folder
	 * @return the entries
	 * @throws IOException if the other harness's installation cannot be located
	 */
	private static Map<String, List<String>> sandboxEntries(Path home, Path locks) throws IOException
	{
		return provider().sandboxEntries(home, other(), locks);
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
