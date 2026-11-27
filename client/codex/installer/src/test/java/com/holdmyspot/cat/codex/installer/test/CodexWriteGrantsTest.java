package com.holdmyspot.cat.codex.installer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.installer.CodexWriteGrants;
import com.holdmyspot.cat.codex.installer.CodexWriteGrants.Launch;
import com.holdmyspot.cat.codex.installer.CodexWriteGrants.Recorded;
import com.holdmyspot.cat.codex.installer.CodexWriteGrants.Session;
import com.holdmyspot.cat.codex.layout.CodexSystemFolder;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.SandboxWrites;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.WriteGrant;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Verifies that {@link CodexWriteGrants} overestimates where Codex's sandbox lets commands write: it reports every
 * grant that it cannot rule out, marks the grants that it cannot be sure of as possible, applies only the denials that
 * it is sure of, and lists what it cannot read.
 */
public final class CodexWriteGrantsTest
{
	/**
	 * Creates the test.
	 */
	public CodexWriteGrantsTest()
	{
	}

	/**
	 * A {@code writable_roots} entry of the user's configuration in workspace-write mode is a certain grant that names
	 * the setting and the file, and the working directory and the temporary folders are certain grants too.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void readsConfiguredRootsAsCertainGrants() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("tools");
			fixture.write(fixture.codexHome.resolve("config.toml"), "sandbox_mode = \"workspace-write\"\n" +
				"[sandbox_workspace_write]\nwritable_roots = [" + Fixture.toml(tools) + "]\n");

			SandboxWrites writes = CodexWriteGrants.read(fixture.known());

			WriteGrant grant = fixture.grantAt(writes, tools);
			requireThat(grant.possible(), "possible").isFalse();
			requireThat(grant.setting(), "setting").contains("sandbox_workspace_write.writable_roots").
				contains(fixture.codexHome.resolve("config.toml").toString());
			requireThat(fixture.grantAt(writes, fixture.project).possible(), "project").isFalse();
			requireThat(fixture.grantAt(writes, fixture.slashTmp).possible(), "slashTmp").isFalse();
			requireThat(fixture.grantAt(writes, fixture.tmpdir).possible(), "tmpdir").isFalse();
			requireThat(writes.problems(), "problems").isEmpty();
		}
	}

	/**
	 * Read-only mode, the sandbox-free mode and the bypass flag give a session no grant.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void reportsNothingWhenNoSandboxWrites() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("tools");
			for (String mode : List.of("read-only", "danger-full-access"))
			{
				fixture.write(fixture.codexHome.resolve("config.toml"), "sandbox_mode = \"" + mode + "\"\n" +
					"[sandbox_workspace_write]\nwritable_roots = [" + Fixture.toml(tools) + "]\n");
				requireThat(CodexWriteGrants.read(fixture.known()).grants(), "grants").withContext(mode, "mode").isEmpty();
			}
			fixture.write(fixture.codexHome.resolve("config.toml"), "sandbox_mode = \"workspace-write\"\n" +
				"[sandbox_workspace_write]\nwritable_roots = [" + Fixture.toml(tools) + "]\n");
			Launch bypass = new Launch(List.of(), Optional.empty(), Optional.empty(), true, List.of(), false);
			requireThat(CodexWriteGrants.read(fixture.known(bypass)).grants(), "bypass").isEmpty();
		}
	}

	/**
	 * A session whose command line CAT cannot read might run in any mode, so every configured grant is possible, and
	 * the working directory is possible too.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void marksGrantsPossibleWhenLaunchUnknown() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("tools");
			fixture.write(fixture.codexHome.resolve("config.toml"), "sandbox_mode = \"read-only\"\n" +
				"[sandbox_workspace_write]\nwritable_roots = [" + Fixture.toml(tools) + "]\n");

			SandboxWrites writes = CodexWriteGrants.read(fixture.session(Optional.empty(), List.of()));

			requireThat(fixture.grantAt(writes, tools).possible(), "tools").isTrue();
			requireThat(fixture.grantAt(writes, fixture.project).possible(), "project").isTrue();
		}
	}

	/**
	 * A session that no layer and no flag gives a sandbox mode has no sandbox, so none of its configured roots, and
	 * neither the working directory nor the temporary folders, are reported, whether or not CAT can read its command
	 * line, while what CAT cannot read still is.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void reportsNothingWhenNoModeIsSet() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("tools");
			fixture.write(fixture.codexHome.resolve("config.toml"), "[sandbox_workspace_write]\n" +
				"writable_roots = [" + Fixture.toml(tools) + "]\n");
			Path broken = fixture.write(fixture.project.resolve(".codex/config.toml"), "writable_roots = [");

			SandboxWrites known = CodexWriteGrants.read(fixture.known());
			SandboxWrites unknown = CodexWriteGrants.read(fixture.session(Optional.empty(), List.of()));

			requireThat(known.grants(), "knownGrants").isEmpty();
			requireThat(unknown.grants(), "unknownGrants").isEmpty();
			requireThat(String.join("\n", known.problems()), "knownProblems").contains(broken.toString());
			requireThat(String.join("\n", unknown.problems()), "unknownProblems").contains(broken.toString());
		}
	}

	/**
	 * The sandbox-free mode gives no grant even when CAT cannot read the session's command line, because the setting
	 * says that the session has no sandbox.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void reportsNothingForUnrestrictedMode() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("tools");
			fixture.write(fixture.codexHome.resolve("config.toml"), "sandbox_mode = \"danger-full-access\"\n" +
				"[sandbox_workspace_write]\nwritable_roots = [" + Fixture.toml(tools) + "]\n");

			SandboxWrites writes = CodexWriteGrants.read(fixture.session(Optional.empty(), List.of()));

			requireThat(writes.grants(), "grants").isEmpty();
		}
	}

	/**
	 * A mode that a command line option gives is a sandbox, so a session with no mode in its configuration but a
	 * {@code --sandbox workspace-write} option reports its grants.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void readsModeOfCommandLine() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("tools");
			fixture.write(fixture.codexHome.resolve("config.toml"), "[sandbox_workspace_write]\n" +
				"writable_roots = [" + Fixture.toml(tools) + "]\n");
			Launch launch = new Launch(List.of(), Optional.empty(), Optional.of("workspace-write"), false, List.of(),
				false);

			SandboxWrites writes = CodexWriteGrants.read(fixture.known(launch));

			requireThat(fixture.grantAt(writes, tools).possible(), "tools").isFalse();
			requireThat(fixture.grantAt(writes, fixture.project).possible(), "project").isFalse();
		}
	}

	/**
	 * Layers that disagree on whether the sandbox is on leave the session possibly sandboxed, so the grants stay
	 * possible: one layer gives the sandbox-free mode and another gives workspace-write.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void marksGrantsPossibleOnSandboxSplit() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("tools");
			fixture.write(fixture.codexHome.resolve("config.toml"), "sandbox_mode = \"danger-full-access\"\n" +
				"[sandbox_workspace_write]\nwritable_roots = [" + Fixture.toml(tools) + "]\n");
			fixture.write(fixture.project.resolve(".codex/config.toml"), "sandbox_mode = \"workspace-write\"\n");

			SandboxWrites writes = CodexWriteGrants.read(fixture.known());

			requireThat(fixture.grantAt(writes, tools).possible(), "tools").isTrue();
			requireThat(fixture.grantAt(writes, fixture.project).possible(), "project").isTrue();
		}
	}

	/**
	 * A profile that sets another mode than the top level makes the effective mode uncertain, so the grants are
	 * possible.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void marksGrantsPossibleOnModeDisagreement() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("tools");
			fixture.write(fixture.codexHome.resolve("config.toml"), "sandbox_mode = \"read-only\"\n" +
				"[profiles.dev]\nsandbox_mode = \"workspace-write\"\n[sandbox_workspace_write]\n" +
				"writable_roots = [" + Fixture.toml(tools) + "]\n");

			SandboxWrites writes = CodexWriteGrants.read(fixture.known());

			requireThat(fixture.grantAt(writes, tools).possible(), "tools").isTrue();
		}
	}

	/**
	 * The machine-wide configuration, the managed configuration and the file of the named profile are read like the
	 * user's, and each grant names its own file.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void readsSystemManagedAndProfileFiles() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path system = fixture.root.resolve("system-tools");
			Path managed = fixture.root.resolve("managed-tools");
			Path profile = fixture.root.resolve("profile-tools");
			fixture.write(fixture.codexHome.resolve("config.toml"), "sandbox_mode = \"workspace-write\"\n");
			fixture.write(fixture.systemFolder.resolve("config.toml"), "[sandbox_workspace_write]\n" +
				"writable_roots = [" + Fixture.toml(system) + "]\n");
			fixture.write(fixture.systemFolder.resolve("managed_config.toml"), "[sandbox_workspace_write]\n" +
				"writable_roots = [" + Fixture.toml(managed) + "]\n");
			fixture.write(fixture.codexHome.resolve("dev.config.toml"), "[sandbox_workspace_write]\n" +
				"writable_roots = [" + Fixture.toml(profile) + "]\n");
			Launch launch = new Launch(List.of(), Optional.of("dev"), Optional.empty(), false, List.of(), false);

			SandboxWrites writes = CodexWriteGrants.read(fixture.known(launch));

			requireThat(fixture.grantAt(writes, system).setting(), "system").
				contains(fixture.systemFolder.resolve("config.toml").toString());
			requireThat(fixture.grantAt(writes, managed).setting(), "managed").
				contains(fixture.systemFolder.resolve("managed_config.toml").toString());
			requireThat(fixture.grantAt(writes, profile).setting(), "profile").
				contains(fixture.codexHome.resolve("dev.config.toml").toString());
		}
	}

	/**
	 * A project's {@code .codex/config.toml} may or may not be applied, depending on whether Codex trusts the project,
	 * so every one of its grants is possible, whichever ancestor of the working directory holds it.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void marksProjectConfigurationGrantsPossible() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("project-tools");
			Path parentTools = fixture.root.resolve("parent-tools");
			fixture.write(fixture.codexHome.resolve("config.toml"), "sandbox_mode = \"workspace-write\"\n");
			fixture.write(fixture.project.resolve(".codex/config.toml"), "[sandbox_workspace_write]\n" +
				"writable_roots = [" + Fixture.toml(tools) + "]\n");
			fixture.write(fixture.root.resolve(".codex/config.toml"), "[sandbox_workspace_write]\n" +
				"writable_roots = [" + Fixture.toml(parentTools) + "]\n");

			SandboxWrites writes = CodexWriteGrants.read(fixture.known());

			WriteGrant grant = fixture.grantAt(writes, tools);
			requireThat(grant.possible(), "possible").isTrue();
			requireThat(grant.setting(), "setting").contains(fixture.project.resolve(".codex/config.toml").toString());
			requireThat(fixture.grantAt(writes, parentTools).possible(), "parent").isTrue();
		}
	}

	/**
	 * A {@code -c} override of the roots and an {@code --add-dir} folder are grants, and a relative one is resolved
	 * against the working directory.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void readsOverridesAndAddedFolders() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path flagged = fixture.root.resolve("flagged");
			fixture.write(fixture.codexHome.resolve("config.toml"), "sandbox_mode = \"workspace-write\"\n");
			Launch launch = new Launch(List.of("sandbox_workspace_write.writable_roots=[" + Fixture.toml(flagged) + "]"),
				Optional.empty(), Optional.empty(), false, List.of("extra", fixture.root.resolve("added").toString()),
				false);

			SandboxWrites writes = CodexWriteGrants.read(fixture.known(launch));

			requireThat(fixture.grantAt(writes, flagged).setting(), "override").contains("-c");
			requireThat(fixture.grantAt(writes, fixture.project.resolve("extra")).setting(), "relative").
				contains("--add-dir");
			requireThat(fixture.grantAt(writes, fixture.root.resolve("added")).setting(), "absolute").
				contains("--add-dir");
		}
	}

	/**
	 * A relative root might be relative to the working directory or to the file that holds it, and {@code ~} is the
	 * user's home, so CAT reports both candidates of a relative root as possible grants and expands the {@code ~}.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void marksRelativeRootsPossible() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			fixture.write(fixture.codexHome.resolve("config.toml"), "sandbox_mode = \"workspace-write\"\n" +
				"[sandbox_workspace_write]\nwritable_roots = [\"rel\", \"~/bin\"]\n");

			SandboxWrites writes = CodexWriteGrants.read(fixture.known());

			requireThat(fixture.grantAt(writes, fixture.project.resolve("rel")).possible(), "cwd").isTrue();
			requireThat(fixture.grantAt(writes, fixture.codexHome.resolve("rel")).possible(), "file").isTrue();
			requireThat(fixture.grantAt(writes, fixture.userHome.resolve("bin")).possible(), "home").isFalse();
		}
	}

	/**
	 * A permission profile's write entries are grants, whether or not the session selects the profile, and CAT reports
	 * them as possible: the special {@code :root} entry grants the whole filesystem.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void marksPermissionProfileGrantsPossible() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("profile-tools");
			fixture.write(fixture.codexHome.resolve("config.toml"), "default_permissions = \"dev\"\n" +
				"[permissions.dev.filesystem]\n" + Fixture.toml(tools) + " = \"write\"\n" +
				"\":project_roots\" = { \"sub\" = \"write\", \".\" = \"read\" }\n" +
				"[permissions.other.filesystem]\n\":root\" = \"write\"\n");

			SandboxWrites writes = CodexWriteGrants.read(fixture.known());

			WriteGrant grant = fixture.grantAt(writes, tools);
			requireThat(grant.possible(), "possible").isTrue();
			requireThat(grant.setting(), "setting").contains("permissions.dev.filesystem");
			requireThat(fixture.grantAt(writes, fixture.project.resolve("sub")).possible(), "sub").isTrue();
			requireThat(fixture.grantAt(writes, fixture.root.getRoot()).possible(), "root").isTrue();
		}
	}

	/**
	 * A policy that a rollout records is a certain grant, and a configured grant that the recorded policy does not
	 * cover might be one that the intersection of the two hides, so it is reported as possible.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void marksUncoveredGrantsPossible() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("tools");
			Path recorded = fixture.root.resolve("recorded");
			fixture.write(fixture.codexHome.resolve("config.toml"), "sandbox_mode = \"workspace-write\"\n" +
				"[sandbox_workspace_write]\nwritable_roots = [" + Fixture.toml(tools) + "]\n");
			Session session = fixture.session(Optional.of(Fixture.NO_OPTIONS),
				List.of(new Recorded(true, List.of(fixture.project, recorded))));

			SandboxWrites writes = CodexWriteGrants.read(session);

			requireThat(fixture.grantAt(writes, tools).possible(), "tools").isTrue();
			requireThat(fixture.grantAt(writes, recorded).possible(), "recorded").isFalse();
			requireThat(fixture.grantAt(writes, fixture.project).possible(), "project").isFalse();
		}
	}

	/**
	 * A recorded policy without a sandbox does not stop the configured grants from being reported, because the
	 * recorded turn may not be the one that applies to the hook's commands.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void keepsGrantsOnUnsandboxedRecord() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("tools");
			fixture.write(fixture.codexHome.resolve("config.toml"), "sandbox_mode = \"workspace-write\"\n" +
				"[sandbox_workspace_write]\nwritable_roots = [" + Fixture.toml(tools) + "]\n");
			Session session = fixture.session(Optional.of(Fixture.NO_OPTIONS),
				List.of(new Recorded(false, List.of())));

			SandboxWrites writes = CodexWriteGrants.read(session);

			requireThat(fixture.grantAt(writes, tools).possible(), "tools").isTrue();
		}
	}

	/**
	 * A grant that excludes {@code /tmp} or the temporary folder cannot be trusted to remove them, so they are
	 * reported as possible when any layer mentions the exclusion.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void marksExcludedTmpFoldersPossible() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			fixture.write(fixture.codexHome.resolve("config.toml"), "sandbox_mode = \"workspace-write\"\n" +
				"[sandbox_workspace_write]\nexclude_slash_tmp = true\nexclude_tmpdir_env_var = true\n");

			SandboxWrites writes = CodexWriteGrants.read(fixture.known());

			requireThat(fixture.grantAt(writes, fixture.slashTmp).possible(), "slashTmp").isTrue();
			requireThat(fixture.grantAt(writes, fixture.tmpdir).possible(), "tmpdir").isTrue();
			requireThat(fixture.grantAt(writes, fixture.project).possible(), "project").isFalse();
		}
	}

	/**
	 * The names that Codex protects inside a write root are denials, but only under a root that CAT is sure of.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void deniesProtectedNamesUnderCertainRoots() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("tools");
			fixture.write(fixture.codexHome.resolve("config.toml"), "sandbox_mode = \"workspace-write\"\n" +
				"[sandbox_workspace_write]\nwritable_roots = [" + Fixture.toml(tools) + "]\n");
			fixture.write(fixture.project.resolve(".codex/config.toml"), "[sandbox_workspace_write]\n" +
				"writable_roots = [" + Fixture.toml(fixture.root.resolve("maybe")) + "]\n");

			SandboxWrites writes = CodexWriteGrants.read(fixture.known());

			requireThat(writes.denials().contains(tools.resolve(".git")), "git").isTrue();
			requireThat(writes.denials().contains(fixture.project.resolve(".codex")), "codex").isTrue();
			requireThat(writes.denials().contains(fixture.root.resolve("maybe/.git")), "maybe").isFalse();
		}
	}

	/**
	 * A file that is not valid TOML, a value of the wrong type and managed preferences that CAT cannot parse are
	 * listed as problems that name the file or setting, and the grants that CAT can read are still reported.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void listsUnreadableSettingsAsProblems() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("tools");
			Path preferences = fixture.write(fixture.root.resolve("managed.plist"), "x");
			fixture.write(fixture.codexHome.resolve("config.toml"), "sandbox_mode = \"workspace-write\"\n" +
				"[sandbox_workspace_write]\nwritable_roots = [" + Fixture.toml(tools) + ", 7]\n");
			fixture.write(fixture.systemFolder.resolve("config.toml"), "this is = = not toml");
			Session session = new Session(CodexSystemFolder.at(fixture.systemFolder), List.of(preferences), fixture.codexHome,
				fixture.userHome, fixture.project, fixture.environment(), Optional.of(fixture.slashTmp),
				Optional.of(Fixture.NO_OPTIONS), List.of());

			SandboxWrites writes = CodexWriteGrants.read(session);

			requireThat(fixture.grantAt(writes, tools).setting(), "tools").contains("writable_roots");
			String problems = String.join("\n", writes.problems());
			requireThat(problems, "problems").contains(fixture.systemFolder.resolve("config.toml").toString()).
				contains("writable_roots").contains(preferences.toString());
		}
	}

	/**
	 * On Windows, Codex reads {@code config.toml} from the folder that Windows names, and reads no machine-wide
	 * {@code managed_config.toml}, though the folder holds a file of that name: the grants of the first are reported and
	 * those of the second are not.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void readsWindowsConfigNotManagedFile() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path programData = fixture.root.resolve("ProgramData");
			CodexSystemFolder windows = CodexSystemFolder.of(Platform.WINDOWS_X64, () -> programData,
				(key, name) -> Optional.empty());
			Path system = fixture.root.resolve("system-tools");
			Path managed = fixture.root.resolve("managed-tools");
			fixture.write(windows.config(), "sandbox_mode = \"workspace-write\"\n[sandbox_workspace_write]\n" +
				"writable_roots = [" + Fixture.toml(system) + "]\n");
			fixture.write(windows.folder().resolve("managed_config.toml"), "[sandbox_workspace_write]\n" +
				"writable_roots = [" + Fixture.toml(managed) + "]\n");

			SandboxWrites writes = CodexWriteGrants.read(fixture.session(windows));

			requireThat(fixture.grantAt(writes, system).setting(), "system").contains(windows.config().toString());
			requireThat(writes.grants().stream().anyMatch(grant -> grant.path().equals(managed)), "managedGranted").
				isFalse();
			requireThat(writes.problems(), "problems").isEmpty();
		}
	}

	/**
	 * When Windows names no program data folder, the reading uses the folder that Codex falls back to, and lists the
	 * cause among its problems, so that the user is told that CAT may have read the wrong folder.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void listsWhyWindowsFolderIsAFallback() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			CodexSystemFolder windows = CodexSystemFolder.of(Platform.WINDOWS_X64, () ->
			{
				throw new IOException("SHGetKnownFolderPath failed with HRESULT 0x80070005");
			}, (key, name) -> Optional.empty());

			SandboxWrites writes = CodexWriteGrants.read(fixture.session(windows));

			requireThat(String.join("\n", writes.problems()), "problems").contains("0x80070005").
				contains(windows.folder().toString());
		}
	}

	/**
	 * Verifies that the files of the managed preferences, which only macOS delivers, are absolute paths that a session
	 * can hold, so that the check runs on every platform: Windows has none.
	 */
	@Test
	public void managedPreferencesSuitThisPlatform()
	{
		List<Path> files = CodexWriteGrants.managedPreferences("someone");

		for (Path file : files)
			requireThat(file, "file").isAbsolute();
		if (Platform.current().isWindows())
			requireThat(files, "files").isEmpty();
		else
			requireThat(files, "files").isNotEmpty();
	}

	/**
	 * The folders and files that the test builds.
	 */
	private static final class Fixture
	{
		/**
		 * The command line options of a session that gives none.
		 */
		private static final Launch NO_OPTIONS = new Launch(List.of(), Optional.empty(), Optional.empty(), false,
			List.of(), false);
		private final Path root;
		private final Path userHome;
		private final Path codexHome;
		private final Path systemFolder;
		private final Path project;
		private final Path slashTmp;
		private final Path tmpdir;

		/**
		 * Creates the folders.
		 *
		 * @param cleanup removes the temporary folder
		 * @throws IOException if the folders cannot be created
		 */
		private Fixture(TemporaryDirectories cleanup) throws IOException
		{
			this.root = cleanup.add(Files.createTempDirectory("codex-write-grants-")).toRealPath();
			this.userHome = Files.createDirectories(root.resolve("home/user"));
			this.codexHome = Files.createDirectories(userHome.resolve(".codex"));
			this.systemFolder = Files.createDirectories(root.resolve("etc-codex"));
			this.project = Files.createDirectories(root.resolve("project"));
			this.slashTmp = Files.createDirectories(root.resolve("slash-tmp"));
			this.tmpdir = Files.createDirectories(root.resolve("tmpdir"));
		}

		/**
		 * Writes a file, creating its folder.
		 *
		 * @param file    the file
		 * @param content the content
		 * @return the file
		 * @throws IOException if the file cannot be written
		 */
		private Path write(Path file, String content) throws IOException
		{
			Files.createDirectories(file.getParent());
			Files.writeString(file, content);
			return file;
		}

		/**
		 * Returns the environment of the session.
		 *
		 * @return the environment
		 */
		private Map<String, String> environment()
		{
			return Map.of("TMPDIR", tmpdir.toString());
		}

		/**
		 * Returns a session whose command line is known.
		 *
		 * @return the session
		 */
		private Session known()
		{
			return session(Optional.of(NO_OPTIONS), List.of());
		}

		/**
		 * Returns a session that was started with options.
		 *
		 * @param launch the options
		 * @return the session
		 */
		private Session known(Launch launch)
		{
			return session(Optional.of(launch), List.of());
		}

		/**
		 * Returns a session.
		 *
		 * @param launch   the command line options, or empty if CAT cannot read them
		 * @param recorded the policies that the session's rollouts record
		 * @return the session
		 */
		private Session session(Optional<Launch> launch, List<Recorded> recorded)
		{
			return session(CodexSystemFolder.at(systemFolder), launch, recorded);
		}

		/**
		 * Returns a session whose command line is known, on a machine whose Codex reads another machine-wide folder.
		 *
		 * @param system the machine-wide folder
		 * @return the session
		 */
		private Session session(CodexSystemFolder system)
		{
			return session(system, Optional.of(NO_OPTIONS), List.of());
		}

		/**
		 * Returns a session.
		 *
		 * @param system   the machine-wide folder
		 * @param launch   the command line options, or empty if CAT cannot read them
		 * @param recorded the policies that the session's rollouts record
		 * @return the session
		 */
		private Session session(CodexSystemFolder system, Optional<Launch> launch, List<Recorded> recorded)
		{
			return new Session(system, List.of(), codexHome, userHome, project, environment(),
				Optional.of(slashTmp), launch, recorded);
		}

		/**
		 * Returns the grant that covers a folder, requiring it to exist.
		 *
		 * @param writes the writes
		 * @param folder the folder
		 * @return the grant whose path is the folder
		 */
		private WriteGrant grantAt(SandboxWrites writes, Path folder)
		{
			return writes.grants().stream().filter(grant -> grant.path().equals(folder)).findFirst().
				orElseThrow(() -> new AssertionError("No grant at " + folder + " among " + writes.grants()));
		}

		/**
		 * Quotes a path as a TOML string.
		 *
		 * @param path the path
		 * @return the quoted path
		 */
		private static String toml(Path path)
		{
			return "\"" + path.toString().replace("\\", "\\\\") + "\"";
		}
	}
}
