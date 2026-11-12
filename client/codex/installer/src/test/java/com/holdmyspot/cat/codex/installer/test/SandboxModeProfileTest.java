package com.holdmyspot.cat.codex.installer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installer.activation.ConsentRequest;
import com.holdmyspot.cat.common.installer.activation.EntryEffect;
import com.holdmyspot.cat.common.installer.activation.EntryGroup;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.engine.PluginInstall;
import com.holdmyspot.cat.common.installer.engine.PluginInstall.InstalledEntries;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Install's protection of the shell startup files when Codex's configuration selects no permission profile: it follows
 * {@code sandbox_mode}, asking first, and uninstall restores the earlier selection.
 */
public final class SandboxModeProfileTest
{
	/**
	 * Waits for nothing, so that a deletion that fails is not retried at length.
	 */
	private static final Waiting NO_WAIT = interval -> () ->
	{
	};
	/**
	 * Quotes a path as a TOML basic string, which JSON's string form also is.
	 */
	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	/**
	 * The words of the notice that says that the startup files stay writable.
	 */
	private static final String WRITABLE = "can still write your shell startup files";

	/**
	 * For {@code sandbox_mode = "workspace-write"} or {@code "read-only"} without a selected profile, install asks
	 * before it selects a profile of CAT's, and goes on when the user answers no. The question names the file, the
	 * profile and what it extends, says that the profile replaces {@code sandbox_mode} in every project, and that the
	 * startup files stay writable without it.
	 *
	 * @param mode       the sandbox mode
	 * @param catProfile the name of the profile that CAT offers to create
	 * @param builtIn    the built-in profile that it extends
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(dataProvider = "modes")
	public void asksBeforeSelectingProfile(String mode, String catProfile, String builtIn) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path configuration = home.resolve("config.toml");
			String original = "sandbox_mode = \"" + mode + "\"\n";
			Files.writeString(configuration, original);

			provider().validateConfiguration(home);
			Optional<ConsentRequest> request = provider().consentRequest(home, locks(home));

			requireThat(request.isPresent(), "asked").isTrue();
			requireThat(request.get().required(), "required").isFalse();
			requireThat(request.get().question(), "question").contains(configuration.toString()).
				contains("selects no permission profile").contains("shell startup files").
				contains("creates the permission profile \"" + catProfile + "\", which extends \"" + builtIn + "\"").
				contains("in place of sandbox_mode = \"" + mode + "\" in every project").
				contains("removes the profile and the default_permissions line").
				contains("the startup files stay writable");
			requireThat(Files.readString(configuration), "configuration").isEqualTo(original);
		}
	}

	/**
	 * Supplies the sandbox modes that CAT follows.
	 *
	 * @return each mode, the name of CAT's profile of it and the built-in profile that the profile extends
	 */
	@DataProvider
	public Object[][] modes()
	{
		return new Object[][]
			{
				{"workspace-write", "cat-sandbox-workspace-write", ":workspace"},
				{"read-only", "cat-sandbox-read-only", ":read-only"}
			};
	}

	/**
	 * When the user does not allow it, install selects no profile, grants the lock folder through the writable roots
	 * as before, and tells the user that the startup files stay writable until they allow it. A later install that the
	 * user allows selects CAT's profile, moves the lock folder's grant into it and leaves the user's writable roots as
	 * they were.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void refusalLeavesStartupFilesWritable() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path configuration = home.resolve("config.toml");
			Path data = home.resolveSibling("data");
			String original = "sandbox_mode = \"workspace-write\"\n\n[sandbox_workspace_write]\nwritable_roots = [" +
				quote(data) + "]\n";
			Files.writeString(configuration, original);

			InstalledEntries refused = install(home, false);

			String afterRefusal = Files.readString(configuration);
			requireThat(afterRefusal, "afterRefusal").doesNotContain("default_permissions").
				contains(quote(locks(home)));
			requireThat(refused.notices().stream().anyMatch(notice ->
				notice.contains(WRITABLE) && notice.contains("did not get your permission") &&
					notice.contains("run install again and allow it")), "refusedNotices").
				withContext(refused.notices(), "refusedNoticesList").isTrue();

			requireThat(provider().consentRequest(home, locks(home)).isPresent(), "askedAgain").isTrue();
			InstalledEntries allowed = install(home, true);

			String afterConsent = Files.readString(configuration);
			requireThat(afterConsent, "afterConsent").
				contains("sandbox_mode = \"workspace-write\"\ndefault_permissions = \"cat-sandbox-workspace-write\"\n").
				contains("[sandbox_workspace_write]\nwritable_roots = [" + quote(data) + "]\n").
				contains(quote(data) + " = \"write\"").contains(quote(locks(home)) + " = \"write\"");
			requireThat(allowed.recorded().keySet(), "recorded").
				doesNotContain("config.toml#sandbox_workspace_write.writable_roots");
			requireThat(allowed.notices().stream().noneMatch(notice -> notice.contains(WRITABLE)), "allowedNotices").
				withContext(allowed.notices(), "allowedNoticesList").isTrue();
		}
	}

	/**
	 * With the user's permission, install selects CAT's profile of the sandbox mode, which extends the mode's built-in
	 * profile, holds a copy of the {@code [sandbox_workspace_write]} settings for {@code workspace-write}, and holds
	 * the entries that CAT's runners need and the startup files' read entries. It tells the user so. A repeated install
	 * asks nothing, changes nothing and tells nothing, and uninstall restores the original file.
	 *
	 * @param original the existing {@code config.toml}, with {@code %1$s} for the quoted folder {@code data} beside the
	 *                 Codex home
	 * @param edited   the expected {@code config.toml} after install, with {@code %1$s} as above, {@code %2$s} for the
	 *                 quoted lock folder, {@code %3$s} and {@code %4$s} for the quoted startup files, and {@code %5$s}
	 *                 for the quoted folder {@code projects} in the Codex home
	 * @param recorded the expected recorded entries of {@code config.toml}, with the same placeholders unquoted
	 * @param notice   a part of the expected notice
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(dataProvider = "followedModes")
	public void followsAndRestoresSandboxMode(String original, String edited, Map<String, List<String>> recorded,
		String notice) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path configuration = home.resolve("config.toml");
			Object[] quoted = {quote(home.resolveSibling("data")), quote(locks(home)), quote(startupFiles(home).get(0)),
				quote(startupFiles(home).get(1)), quote(home.resolve("projects"))};
			Object[] paths = {home.resolveSibling("data"), locks(home), startupFiles(home).get(0),
				startupFiles(home).get(1), home.resolve("projects")};
			String before = original.formatted(quoted);
			Files.writeString(configuration, before);

			requireThat(provider().consentRequest(home, locks(home)).isPresent(), "asked").isTrue();
			InstalledEntries entries = install(home, true);

			String expected = edited.formatted(quoted);
			requireThat(Files.readString(configuration), "edited").isEqualTo(expected);
			Map<String, List<String>> expectedRecord = new TreeMap<>();
			for (Map.Entry<String, List<String>> entry : recorded.entrySet())
			{
				expectedRecord.put(entry.getKey(), entry.getValue().stream().
					map(value -> value.formatted(paths)).toList());
			}
			requireThat(configurationEntries(entries.recorded()), "recorded").isEqualTo(expectedRecord);
			requireThat(entries.notices().stream().anyMatch(sentence -> sentence.contains(notice)), "notices").
				withContext(entries.notices(), "noticesList").isTrue();
			requireThat(entries.notices().stream().noneMatch(sentence -> sentence.contains(WRITABLE)), "notices").
				withContext(entries.notices(), "noticesList").isTrue();

			requireThat(provider().consentRequest(home, locks(home)), "repeatedQuestion").isEmpty();
			InstalledEntries repeated = install(home, false);
			requireThat(repeated.notices(), "repeatedNotices").isEmpty();
			requireThat(Files.readString(configuration), "repeated").isEqualTo(expected);
			requireThat(configurationEntries(repeated.recorded()), "repeatedRecord").isEqualTo(expectedRecord);

			provider().removePermissions(home, entries.recorded(), replacer());
			requireThat(Files.readString(configuration), "removed").isEqualTo(before);
			requireThat(provider().describeRetained(home, entries.recorded()), "retained").isEmpty();
		}
	}

	/**
	 * Supplies the sandbox modes in forms that the integration edits.
	 *
	 * @return the original file, the expected file after install, the expected record and a part of the notice
	 */
	@DataProvider
	public Object[][] followedModes()
	{
		String workspace = "permissions.cat-sandbox-workspace-write";
		String readOnly = "permissions.cat-sandbox-read-only";
		return new Object[][]
			{
				// Every setting of [sandbox_workspace_write] is copied, and the other settings stay as they are
				{"model = \"gpt\"\nsandbox_mode = \"workspace-write\" # mine\n\n[sandbox_workspace_write]\n" +
					"writable_roots = [%1$s, \"projects\", \"~/code\"]\nnetwork_access = true\n" +
					"exclude_tmpdir_env_var = true\nexclude_slash_tmp = true\n",
					"model = \"gpt\"\nsandbox_mode = \"workspace-write\" # mine\n" +
						"default_permissions = \"cat-sandbox-workspace-write\"\n\n[sandbox_workspace_write]\n" +
						"writable_roots = [%1$s, \"projects\", \"~/code\"]\nnetwork_access = true\n" +
						"exclude_tmpdir_env_var = true\nexclude_slash_tmp = true\n\n" +
						"[" + workspace + "]\nextends = \":workspace\"\n\n" +
						"[" + workspace + ".filesystem]\n%4$s = \"read\"\n%3$s = \"read\"\n\":slash_tmp\" = \"read\"\n" +
						"\":tmpdir\" = \"read\"\n\"~/code\" = \"write\"\n%5$s = \"write\"\n%1$s = \"write\"\n" +
						"%2$s = \"write\"\n\n" +
						"[" + workspace + ".network]\nenabled = true\n",
					Map.of("config.toml#sandbox_mode", List.of("workspace-write"),
						"config.toml#" + workspace, List.of("workspace-write"),
						"config.toml#" + workspace + ".filesystem", List.of("%2$s", "%1$s", "%5$s", "~/code"),
						"config.toml#" + workspace + ".filesystem.read", List.of(":tmpdir", ":slash_tmp", "%3$s", "%4$s"),
						"config.toml#" + workspace + ".network.enabled", List.of("true")),
					"its permission profile \"cat-sandbox-workspace-write\", which extends \":workspace\" and holds a " +
						"copy of your [sandbox_workspace_write] settings"},
				// read-only copies nothing
				{"sandbox_mode = \"read-only\"\n",
					"sandbox_mode = \"read-only\"\ndefault_permissions = \"cat-sandbox-read-only\"\n\n" +
						"[" + readOnly + "]\nextends = \":read-only\"\n\n" +
						"[" + readOnly + ".filesystem]\n%4$s = \"read\"\n%3$s = \"read\"\n%2$s = \"write\"\n",
					Map.of("config.toml#sandbox_mode", List.of("read-only"),
						"config.toml#" + readOnly, List.of("read-only"),
						"config.toml#" + readOnly + ".filesystem", List.of("%2$s"),
						"config.toml#" + readOnly + ".filesystem.read", List.of("%3$s", "%4$s")),
					"its permission profile \"cat-sandbox-read-only\", which extends \":read-only\", so that " +
						"sandboxed commands cannot write"}
			};
	}

	/**
	 * Without {@code sandbox_mode}, and with {@code danger-full-access}, install asks nothing, creates no profile and
	 * tells the user that the startup files stay writable, and why.
	 *
	 * @param original the existing {@code config.toml}, or null when the file is absent
	 * @param reason   a part of the expected notice
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(dataProvider = "unfollowedModes")
	public void reportsUnprotectedStartupFiles(String original, String reason) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path configuration = home.resolve("config.toml");
			if (original != null)
				Files.writeString(configuration, original);

			requireThat(provider().consentRequest(home, locks(home)), "question").isEmpty();
			InstalledEntries entries = install(home, true);

			requireThat(Files.readString(configuration), "configuration").doesNotContain("default_permissions").
				doesNotContain("[permissions");
			requireThat(entries.notices().stream().anyMatch(notice ->
				notice.contains(WRITABLE) && notice.contains(reason)), "notices").
				withContext(entries.notices(), "noticesList").isTrue();
		}
	}

	/**
	 * Supplies the files whose sandbox mode CAT does not follow.
	 *
	 * @return each {@code config.toml} and a part of the reason that the notice gives
	 */
	@DataProvider
	public Object[][] unfollowedModes()
	{
		String neither = "sets neither default_permissions nor sandbox_mode";
		return new Object[][]
			{
				{null, neither},
				{"model = \"gpt\"\n", neither},
				{"sandbox_mode = \"danger-full-access\"\n", "runs commands without a sandbox"}
			};
	}

	/**
	 * The installer's report lists the {@code default_permissions} line, CAT's profile and its network setting as
	 * values that install set, and the copied writable folders as paths that the sandbox may write.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void reportsProfileEntries() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path data = home.resolveSibling("data");
			Files.writeString(home.resolve("config.toml"), "sandbox_mode = \"workspace-write\"\n\n" +
				"[sandbox_workspace_write]\nwritable_roots = [" + quote(data) + "]\nnetwork_access = true\n");

			InstalledEntries entries = install(home, true);
			Set<EntryGroup> groups = Set.copyOf(provider().describeEntries(
				configurationEntries(entries.recorded()), installer(home)));

			Path file = Path.of("config.toml");
			String profile = "permissions.cat-sandbox-workspace-write";
			requireThat(groups, "groups").contains(new EntryGroup(EntryEffect.SETS_VALUE, file,
				"default_permissions", List.of("cat-sandbox-workspace-write"),
				List.of("default_permissions = \"cat-sandbox-workspace-write\"")));
			requireThat(groups, "groups").contains(new EntryGroup(EntryEffect.SETS_VALUE, file, profile,
				List.of("extends = \":workspace\""), List.of("extends = \":workspace\"")));
			requireThat(groups, "groups").contains(new EntryGroup(EntryEffect.SETS_VALUE, file, profile + ".network",
				List.of("enabled = true"), List.of("enabled = true")));
			requireThat(groups, "groups").contains(new EntryGroup(EntryEffect.SANDBOX_MAY_WRITE, file,
				profile + ".filesystem", List.of(locks(home).toString(), data.toString()),
				List.of(quote(locks(home)) + " = \"write\"", quote(data) + " = \"write\"")));
		}
	}

	/**
	 * When the user changes CAT's profile after install, uninstall removes the {@code default_permissions} line and
	 * CAT's entries, leaves the profile with the user's setting, and says that it left it.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void leavesProfileThatUserChanged() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path configuration = home.resolve("config.toml");
			Files.writeString(configuration, "sandbox_mode = \"read-only\"\n");
			InstalledEntries entries = install(home, true);
			String profile = "[permissions.cat-sandbox-read-only.filesystem]\n";
			Files.writeString(configuration, Files.readString(configuration).
				replace(profile, profile + "\"/mine\" = \"write\"\n"));

			provider().removePermissions(home, entries.recorded(), replacer());

			requireThat(Files.readString(configuration), "removed").doesNotContain("default_permissions").
				contains("[permissions.cat-sandbox-read-only]\nextends = \":read-only\"\n").
				contains(profile + "\"/mine\" = \"write\"\n").doesNotContain(quote(locks(home)));
			requireThat(provider().describeRetained(home, entries.recorded()), "retained").isEqualTo(List.of(
				"CAT left its permission profile \"cat-sandbox-read-only\" in " + configuration + ", because it holds " +
					"settings that CAT did not write."));
		}
	}

	/**
	 * Install refuses, changing nothing, a sandbox mode that it cannot follow with a {@code default_permissions} line
	 * below it, and a profile name that the user took, with a message that says what to change.
	 *
	 * @param original the existing {@code config.toml}
	 * @param message  a part of the expected refusal
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(dataProvider = "uneditableModes")
	public void refusesUneditableMode(String original, String message) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path configuration = home.resolve("config.toml");
			Files.writeString(configuration, original);

			try
			{
				provider().validateConfiguration(home);
				throw new AssertionError("validateConfiguration accepted " + original);
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains(configuration.toString()).contains(message);
			}
			requireThat(Files.readString(configuration), "configuration").isEqualTo(original);
		}
	}

	/**
	 * Supplies the files whose sandbox mode the integration refuses to follow.
	 *
	 * @return the file and a part of the refusal
	 */
	@DataProvider
	public Object[][] uneditableModes()
	{
		return new Object[][]
			{
				{"sandbox_mode = \"workspace-write\"\n\n[permissions.cat-sandbox-workspace-write]\n" +
					"extends = \":read-only\"\n", "does not extend \":workspace\""},
				{"sandbox_mode = \"\"\"\nworkspace-write\"\"\"\n", "below a line such as sandbox_mode = " +
					"\"workspace-write\""}
			};
	}

	/**
	 * Runs the installer's entry steps, as install and update do.
	 *
	 * @param home      the Codex home
	 * @param consented whether the user allowed the change that the integration asked for
	 * @return the installed entries
	 * @throws IOException if a step fails
	 */
	private static InstalledEntries install(Path home, boolean consented) throws IOException
	{
		HarnessIntegration provider = provider();
		provider.validateConfiguration(home);
		PluginInstall install = new PluginInstall(provider, CodexProgram.cli(home), replacer(), NO_WAIT);
		HarnessInstallation installation = provider.installation(home);
		return install.installEntries(home, installer(home), installation, List.of(other()), locks(home),
			launcherState(home), startupFiles(home), consented);
	}

	/**
	 * Returns the recorded entries of {@code config.toml}.
	 *
	 * @param recorded the recorded entries
	 * @return the entries whose key names {@code config.toml}, sorted by key
	 */
	private static Map<String, List<String>> configurationEntries(Map<String, List<String>> recorded)
	{
		Map<String, List<String>> entries = new TreeMap<>();
		for (Map.Entry<String, List<String>> entry : recorded.entrySet())
		{
			if (entry.getKey().startsWith("config.toml#"))
				entries.put(entry.getKey(), entry.getValue());
		}
		return entries;
	}

	/**
	 * Returns the installer launcher of a CAT version in the Codex home.
	 *
	 * @param home the Codex home
	 * @return the launcher
	 */
	private static Path installer(Path home)
	{
		Path launcher = home.resolve("plugins/cache/cat/cat/1.0+b0123456789ab/bin/installer");
		if (Platform.current().isWindows())
			launcher = launcher.resolveSibling("installer.ps1");
		return launcher;
	}

	/**
	 * Returns the capacity lock folder beside the Codex home.
	 *
	 * @param home the Codex home
	 * @return the folder
	 */
	private static Path locks(Path home)
	{
		return home.resolveSibling("user/.cat/locks");
	}

	/**
	 * Returns the folder in which an earlier release's session launcher kept its state, beside the lock folder.
	 *
	 * @param home the Codex home
	 * @return the folder
	 */
	private static Path launcherState(Path home)
	{
		return locks(home).resolveSibling("sessions");
	}

	/**
	 * Returns the shell startup files of the user beside the Codex home.
	 *
	 * @param home the Codex home
	 * @return the files
	 */
	private static List<Path> startupFiles(Path home)
	{
		Path user = home.resolveSibling("user");
		return List.of(user.resolve(".bashrc"), user.resolve(".profile"));
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
