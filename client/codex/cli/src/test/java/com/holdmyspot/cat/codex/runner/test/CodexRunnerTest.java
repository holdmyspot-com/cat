package com.holdmyspot.cat.codex.runner.test;

import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.runner.CodexRunner;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.SessionRequestInstructions;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Tests {@link CodexRunner}.
 */
public final class CodexRunnerTest
{
	/**
	 * Verifies an isolated home uses a credential file only when no usable environment authentication is available.
	 */
	@Test
	public void copiesCredentialFileWhenNeeded()
	{
		requireThat(CodexRunner.requiresCredentialFiles(Map.of()), "withoutAuthentication").isTrue();
		requireThat(CodexRunner.requiresCredentialFiles(Map.of("CODEX_API_KEY", "token")),
			"withAuthentication").isFalse();
	}

	/**
	 * Verifies CAT is installed from this run's marketplace into the fresh isolated home.
	 */
	@Test
	public void reinstallsCatFromTheRunMarketplace()
	{
		requireThat(CodexRunner.pluginSetupCommands("codex", Path.of("marketplace")), "commands").isEqualTo(List.of(
			List.of("codex", "plugin", "marketplace", "add", "marketplace"),
			List.of("codex", "plugin", "add", "cat@cat")));
	}

	/**
	 * Verifies a trial plugin is installed from its own run-local marketplace, which bears its name.
	 */
	@Test
	public void installsTrialPluginByName()
	{
		// Requirement: R-W12
		requireThat(CodexRunner.pluginSetupCommands("codex", Path.of("marketplace"), "release-docs"), "commands").
			isEqualTo(List.of(
				List.of("codex", "plugin", "marketplace", "add", "marketplace"),
				List.of("codex", "plugin", "add", "release-docs@release-docs")));
	}

	/**
	 * Verifies that a trial installs exactly its listed plugin, from a marketplace of its own, and neither CAT nor any
	 * other plugin.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void installsExactlyListedPlugin() throws IOException
	{
		// Requirement: R-W12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-plugins-"));
			Path home = root.resolve("isolated");
			List<String> steps = new ArrayList<>();
			List<List<String>> commands = new ArrayList<>();

			CodexRunner.installPlugins("codex", artifact(root), false, List.of(plugin(root, "release-docs")),
				root.resolve("host"), home, Map.of(), Yielding.executableFiles(), (command, step) ->
				{
					commands.add(command);
					steps.add(step);
				});

			Path marketplace = home.resolve("plugins/release-docs-marketplace");
			requireThat(steps, "steps").isEqualTo(List.of("trial-plugin-release-docs-marketplace-add",
				"trial-plugin-release-docs-add"));
			requireThat(commands, "commands").isEqualTo(CodexRunner.pluginSetupCommands("codex", marketplace,
				"release-docs"));
			requireThat(Files.readString(marketplace.resolve("plugins/release-docs/README.md")), "copy").
				isEqualTo("release-docs");
			requireThat(Files.notExists(home.resolve("agents")), "catAgents").isTrue();
		}
	}

	/**
	 * Verifies that a trial with two plugins installs both, in their listed order, each from its own marketplace.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void installsTwoPlugins() throws IOException
	{
		// Requirement: R-W12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-plugins-"));
			Path home = root.resolve("isolated");
			List<String> steps = new ArrayList<>();

			CodexRunner.installPlugins("codex", artifact(root), false, List.of(plugin(root, "release-docs"),
				plugin(root, "tools")), root.resolve("host"), home, Map.of(), Yielding.executableFiles(), (_,
				step) -> steps.add(step));

			requireThat(steps, "steps").isEqualTo(List.of("trial-plugin-release-docs-marketplace-add",
				"trial-plugin-release-docs-add", "trial-plugin-tools-marketplace-add", "trial-plugin-tools-add"));
			requireThat(Files.readString(home.resolve("plugins/tools-marketplace/plugins/tools/README.md")), "copy").
				isEqualTo("tools");
		}
	}

	/**
	 * Verifies that CAT is installed only when the trial lists it: a trial that lists no plugin installs nothing, and
	 * one that lists CAT installs CAT and its agents from the artifact.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void installsCatOnlyWhenListed() throws IOException
	{
		// Requirement: R-W12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-plugins-"));
			Path artifact = artifact(root);
			List<String> steps = new ArrayList<>();

			Path withoutCat = root.resolve("without");
			CodexRunner.installPlugins("codex", artifact, false, List.of(), root.resolve("host"), withoutCat, Map.of(),
				Yielding.executableFiles(), (_, step) -> steps.add(step));
			requireThat(steps, "withoutCatSteps").isEmpty();
			requireThat(Files.notExists(withoutCat), "withoutCatHome").isTrue();

			Path withCat = root.resolve("with");
			CodexRunner.installPlugins("codex", artifact, true, List.of(), root.resolve("host"), withCat, Map.of(),
				Yielding.executableFiles(), (_, step) -> steps.add(step));
			requireThat(steps, "withCatSteps").isEqualTo(List.of("marketplace-add", "plugin-add"));
			requireThat(withCat.resolve("agents/cat-worker.toml"), "agent").isRegularFile();
		}
	}

	/**
	 * Verifies that the user's approvals of each installed plugin's hooks are copied into the isolated configuration,
	 * under the run-local marketplace of each trial plugin, and that no approval of an unlisted plugin is.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void reusesHookApprovals() throws IOException
	{
		// Requirement: R-W12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-plugins-"));
			Path host = Files.createDirectories(root.resolve("host"));
			Files.writeString(host.resolve("config.toml"), """
				[hooks.state."release-docs@acme:hooks/hooks.json:session_start:0:0"]
				trusted_hash = "sha256:%1$s"

				[hooks.state."tools@acme:hooks/hooks.json:session_start:0:0"]
				trusted_hash = "sha256:%2$s"

				[hooks.state."other@acme:hooks/hooks.json:session_start:0:0"]
				trusted_hash = "sha256:%3$s"

				[hooks.state."cat@cat:hooks/hooks.json:session_start:0:0"]
				trusted_hash = "sha256:%4$s"
				""".formatted("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64)));
			Path home = root.resolve("isolated");

			CodexRunner.installPlugins("codex", artifact(root), true, List.of(plugin(root, "release-docs"),
				plugin(root, "tools")), host, home, Map.of(), Yielding.executableFiles(), (_, _) ->
				{
				});

			String configuration = Files.readString(home.resolve("config.toml"));
			requireThat(configuration, "configuration").
				contains(approval("release-docs@release-docs", "a")).
				contains(approval("tools@tools", "b")).
				contains(approval("cat@cat", "d")).
				doesNotContain("other@").doesNotContain("c".repeat(64));
		}
	}

	/**
	 * Returns an approval of a plugin's session-start hook as the isolated configuration holds it.
	 *
	 * @param plugin the plugin and marketplace, {@code <plugin>@<marketplace>}
	 * @param digit the hexadecimal digit that the approved hash repeats
	 * @return the approval's table
	 */
	private static String approval(String plugin, String digit)
	{
		return "[hooks.state.\"" + plugin + ":hooks/hooks.json:session_start:0:0\"]" + System.lineSeparator() +
			"trusted_hash = \"sha256:" + digit.repeat(64) + "\"";
	}

	/**
	 * Creates a CAT artifact that holds the Codex manifest and one custom agent.
	 *
	 * @param root the directory to create the artifact in
	 * @return the artifact directory
	 * @throws IOException if the artifact cannot be written
	 */
	private static Path artifact(Path root) throws IOException
	{
		Path artifact = plugin(root, "cat");
		Files.createDirectories(artifact.resolve("agents/codex"));
		Files.writeString(artifact.resolve("agents/codex/worker.toml"), "name = \"worker\"\n");
		return artifact;
	}

	/**
	 * Creates a Codex plugin directory, named after the plugin, that holds a manifest and a readme.
	 *
	 * @param root the directory to create the plugin in
	 * @param name the manifest's plugin name, which the readme also holds
	 * @return the plugin directory
	 * @throws IOException if the plugin cannot be written
	 */
	private static Path plugin(Path root, String name) throws IOException
	{
		Path plugin = root.resolve("sources").resolve(name);
		Files.createDirectories(plugin.resolve(".codex-plugin"));
		Files.writeString(plugin.resolve(".codex-plugin/plugin.json"), "{\"name\": \"" + name + "\"}\n");
		Files.writeString(plugin.resolve("README.md"), name);
		return plugin;
	}

	/**
	 * Verifies the app-server runs CAT's hooks when CAT is installed, and loads no plugin when it is not.
	 */
	@Test
	public void startsAppServerForInstallation()
	{
		requireThat(CodexRunner.appServerCommand("codex", true), "withCat").isEqualTo(
			List.of("codex", "app-server", "--stdio", "--enable", "hooks"));
		requireThat(CodexRunner.appServerCommand("codex", false), "withoutCat").isEqualTo(
			List.of("codex", "app-server", "--stdio", "--disable", "plugins"));
	}

	/**
	 * Verifies the app-server runs under bubblewrap with a private, empty {@code /tmp}, so that a Codex sandbox around
	 * the runner cannot hide the directory that the app-server's own sandbox needs there.
	 */
	@Test
	public void startsAppServerWithPrivateTmp()
	{
		List<String> command = CodexRunner.appServerCommand("codex", false);
		Path bwrap = Path.of("/usr/bin/bwrap").toAbsolutePath();

		requireThat(CodexRunner.withPrivateTmp(command, bwrap, Path.of("/work/run").toAbsolutePath()), "command").
			isEqualTo(List.of(bwrap.toString(), "--dev-bind", "/", "/", "--tmpfs", Path.of("/tmp").toString(),
				"--die-with-parent", "--", "codex", "app-server", "--stdio", "--disable", "plugins"));
	}

	/**
	 * Verifies a run directory below {@code /tmp} stays visible inside the private {@code /tmp}.
	 */
	@Test
	public void keepsRunDirectoryInPrivateTmp()
	{
		List<String> command = CodexRunner.appServerCommand("codex", true);
		Path bwrap = Path.of("/usr/bin/bwrap").toAbsolutePath();
		Path run = Path.of("/tmp/runs/run").toAbsolutePath();

		requireThat(CodexRunner.withPrivateTmp(command, bwrap, run), "command").isEqualTo(List.of(bwrap.toString(),
			"--dev-bind", "/", "/", "--tmpfs", Path.of("/tmp").toString(), "--bind", run.toString(), run.toString(),
			"--die-with-parent", "--", "codex", "app-server", "--stdio", "--enable", "hooks"));
	}

	/**
	 * Verifies bubblewrap is the first executable {@code bwrap} file on the caller's {@code PATH}, skipping a directory
	 * of that name and, where the file system has permission bits, a file of that name that is not executable, and
	 * that it is absent when no directory on the {@code PATH} has one.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void findsBubblewrapOnPath() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path empty = cleanup.add(Files.createTempDirectory("cat-path-"));
			Path notFile = cleanup.add(Files.createTempDirectory("cat-path-"));
			Files.createDirectory(notFile.resolve("bwrap"));
			Path notExecutable = cleanup.add(Files.createTempDirectory("cat-path-"));
			Path usable = cleanup.add(Files.createTempDirectory("cat-path-"));
			Path bwrap = Files.createFile(usable.resolve("bwrap"));
			if (PortableFiles.hasModes(usable))
			{
				Files.createFile(notExecutable.resolve("bwrap"), PosixFilePermissions.asFileAttribute(
					PosixFilePermissions.fromString("rw-r--r--")));
				Files.setPosixFilePermissions(bwrap, PosixFilePermissions.fromString("rwxr-xr-x"));
			}
			String path = String.join(File.pathSeparator, empty.toString(), notFile.toString(), notExecutable.toString(),
				usable.toString());

			requireThat(CodexRunner.bubblewrap(Map.of("PATH", path)), "found").isEqualTo(Optional.of(bwrap));
			requireThat(CodexRunner.bubblewrap(Map.of("PATH", empty.toString())), "absent").isEmpty();
			requireThat(CodexRunner.bubblewrap(Map.of()), "withoutPath").isEmpty();
		}
	}

	/**
	 * Verifies that on Linux a session that reads no evidence under the run root is denied the whole run root as one
	 * path, besides the Codex session logs and login in its home and the paths that the caller denies. The sandbox
	 * keeps the session's project and home writable inside the denied root, so the session reads them while every
	 * other run, including one that starts later, and the workspaces stay hidden. The session log directory exists so
	 * that the sandbox can deny it.
	 *
	 * @throws IOException if the run cannot be created
	 */
	@Test
	public void deniesRunRootOnLinux() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = jvm.temporaryDirectory();
			Path root = cleanup.add(Files.createTempDirectory(temporary, "cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("codex");
			Path directory = run.directory();
			manager.snapshotProject(run);
			Path login = Files.writeString(directory.resolve("home/auth.json"), "{}");
			manager.create("codex");
			Files.createDirectories(root.resolve("workspaces"));
			Path heldOut = cleanup.add(Files.createTempDirectory(temporary, "cat-held-out-"));

			List<Path> deniedReads = CodexRunner.sessionDeniedReads(manager, run, List.of(), List.of(heldOut),
				Platform.LINUX_X64, Map.of());

			requireThat(Set.copyOf(deniedReads), "deniedReads").isEqualTo(Set.of(heldOut, root,
				directory.resolve("home/sessions"), login));
			requireThat(Files.isDirectory(directory.resolve("home/sessions")), "sessionLogsExist").isTrue();
		}
	}

	/**
	 * Verifies that a session's developer instructions first tell the model that the denied reads under the run root
	 * do not cover its project or home, then give the agent's contract, if any. Codex lists the run root among the
	 * denied reads, and without that note models answered that their own project was denied without trying to read it.
	 */
	@Test
	public void developerNoteExemptsProject()
	{
		Path root = Path.of("/runs");
		Path project = Path.of("/runs/codex/run-1/project");
		Path home = Path.of("/runs/codex/run-1/home");
		String note = "The denied reads protect other sessions' projects in " + root + ". They do not apply to your " +
			"project " + project + ", your working directory, or to anything under it, nor to " + home + ": you have " +
			"full read and write access to both. Your project's files are not in your context; read one with a shell " +
			"command whenever a task needs it.";

		requireThat(CodexRunner.developerInstructions(root, project, home, ""), "withoutAgent").isEqualTo(note);
		requireThat(CodexRunner.developerInstructions(root, project, home, "Review the plan."), "withAgent").
			isEqualTo(note + "\n\nReview the plan.");
	}

	/**
	 * Verifies that the developer instructions of a session that may request sessions tell it how, after the note on
	 * denied reads and before the agent's contract, and that those of a session that may request none do not.
	 */
	@Test
	public void developerNoteExplainsRequests()
	{
		// Requirement: R-I9
		Path root = Path.of("/runs");
		Path project = Path.of("/runs/codex/run-1/project");
		Path home = Path.of("/runs/codex/run-1/home");
		String note = CodexRunner.developerInstructions(root, project, home, "");

		requireThat(CodexRunner.developerInstructions(root, project, home, "Review the plan.", 2), "withRequests").
			isEqualTo(note + "\n\n" + SessionRequestInstructions.text(2) + "\n\nReview the plan.");
		requireThat(CodexRunner.developerInstructions(root, project, home, "", 0), "withoutRequests").isEqualTo(note);
	}

	/**
	 * Verifies that on macOS and Windows a session is denied the whole run root as one path, besides the Codex session
	 * logs and login in its home and the paths that the caller denies. Their sandboxes keep the session's project and
	 * home readable and writable inside the denied root.
	 *
	 * @throws IOException if the run cannot be created
	 */
	@Test
	public void deniesRunRootOutsideLinux() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = jvm.temporaryDirectory();
			Path root = cleanup.add(Files.createTempDirectory(temporary, "cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("codex");
			Path directory = run.directory();
			manager.snapshotProject(run);
			Path login = Files.writeString(directory.resolve("home/auth.json"), "{}");
			manager.create("codex");
			Files.createDirectories(root.resolve("workspaces"));
			Path heldOut = cleanup.add(Files.createTempDirectory(temporary, "cat-held-out-"));

			for (Platform platform : List.of(Platform.MACOS_ARM64, Platform.WINDOWS_X64))
			{
				List<Path> deniedReads = CodexRunner.sessionDeniedReads(manager, run, List.of(), List.of(heldOut),
					platform, Map.of());

				requireThat(Set.copyOf(deniedReads), platform + ".deniedReads").isEqualTo(Set.of(heldOut, root,
					directory.resolve("home/sessions"), login));
			}
		}
	}

	/**
	 * Verifies that on macOS and Windows a session that validates evidence under the run root is still denied the whole
	 * run root as one path, because their sandboxes keep a read-only allowance readable inside a denied root.
	 *
	 * @throws IOException if the run cannot be created
	 */
	@Test
	public void deniesRunRootBesideEvidenceOutsideLinux() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = jvm.temporaryDirectory();
			Path root = cleanup.add(Files.createTempDirectory(temporary, "cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("codex");
			Path directory = run.directory();
			manager.snapshotProject(run);
			HarnessRun evidence = manager.create("codex");
			Path evidenceArtifacts = Files.createDirectories(evidence.directory().resolve("artifacts"));
			Path heldOut = cleanup.add(Files.createTempDirectory(temporary, "cat-held-out-"));

			for (Platform platform : List.of(Platform.MACOS_X64, Platform.WINDOWS_ARM64))
			{
				List<Path> deniedReads = CodexRunner.sessionDeniedReads(manager, run, List.of(evidenceArtifacts),
					List.of(heldOut), platform, Map.of());

				requireThat(Set.copyOf(deniedReads), platform + ".deniedReads").isEqualTo(Set.of(heldOut, root,
					directory.resolve("home/sessions")));
			}
		}
	}

	/**
	 * Verifies that on Linux a session that validates evidence under the run root is denied each other run by its own
	 * path rather than the whole root, because the sandbox would hide read-only evidence inside a denied root. The
	 * session can still read its project, the rest of its home and the evidence that it validates.
	 *
	 * @throws IOException if the run cannot be created
	 */
	@Test
	public void deniesEachRunBesideEvidenceInRoot() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = jvm.temporaryDirectory();
			Path root = cleanup.add(Files.createTempDirectory(temporary, "cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("codex");
			Path directory = run.directory();
			manager.snapshotProject(run);
			Path login = Files.writeString(directory.resolve("home/auth.json"), "{}");
			HarnessRun other = manager.create("codex");
			HarnessRun evidence = manager.create("codex");
			Path evidenceArtifacts = Files.createDirectories(evidence.directory().resolve("artifacts"));
			Path workspaces = Files.createDirectories(root.resolve("workspaces"));
			Path heldOut = cleanup.add(Files.createTempDirectory(temporary, "cat-held-out-"));

			List<Path> deniedReads = CodexRunner.sessionDeniedReads(manager, run, List.of(evidenceArtifacts),
				List.of(heldOut), Platform.LINUX_X64, Map.of());

			requireThat(Set.copyOf(deniedReads), "deniedReads").isEqualTo(Set.of(heldOut,
				directory.resolve("artifacts"), directory.resolve("run.json"), directory.resolve(".lock"),
				directory.resolve("initial-project"), directory.resolve("home/sessions"), login, other.directory(),
				evidence.directory().resolve("project"), evidence.directory().resolve("home"),
				evidence.directory().resolve("run.json"), evidence.directory().resolve(".lock"), workspaces));
		}
	}

	/**
	 * Verifies that on Linux a session that validates evidence inside a project that the caller denies is denied each
	 * other file and directory of that project by its own path, each path once, because the sandbox would hide
	 * read-only evidence inside a denied directory. On macOS and Windows the session is denied the whole project, which
	 * holds the run root, because their sandboxes keep the session's project, home and evidence readable inside it.
	 *
	 * @throws IOException if the run cannot be created
	 */
	@Test
	public void deniesProjectBesideEvidence() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = jvm.temporaryDirectory();
			Path project = cleanup.add(Files.createTempDirectory(temporary, "cat-user-project-"));
			Path instructions = Files.writeString(project.resolve("AGENTS.md"), "Follow the handbook.");
			Path documents = Files.createDirectories(project.resolve("docs"));
			Path session = Files.createDirectories(project.resolve(".cat/sessions/session-1"));
			Path otherSession = Files.createDirectories(project.resolve(".cat/sessions/session-2"));
			Path campaigns = Files.createDirectories(session.resolve("campaigns"));
			Path root = Files.createDirectories(session.resolve("optimizer-runs/codex"));
			Path heldOutRoot = Files.createDirectories(session.resolve("optimizer-runs/codex-held_out"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("codex");
			Path directory = run.directory();
			manager.snapshotProject(run);
			HarnessRun evidence = manager.create("codex");
			Path evidenceArtifacts = Files.createDirectories(evidence.directory().resolve("artifacts"));

			List<Path> deniedReads = CodexRunner.sessionDeniedReads(manager, run, List.of(evidenceArtifacts),
				List.of(project), Platform.LINUX_X64, Map.of());

			requireThat(deniedReads.size(), "deniedReads.size()").isEqualTo(Set.copyOf(deniedReads).size());
			requireThat(Set.copyOf(deniedReads), "deniedReads").isEqualTo(Set.of(instructions, documents,
				otherSession, campaigns, heldOutRoot, directory.resolve("artifacts"), directory.resolve("run.json"),
				directory.resolve(".lock"), directory.resolve("initial-project"), directory.resolve("home/sessions"),
				evidence.directory().resolve("project"), evidence.directory().resolve("home"),
				evidence.directory().resolve("run.json"), evidence.directory().resolve(".lock")));
			for (Platform platform : List.of(Platform.MACOS_ARM64, Platform.WINDOWS_X64))
			{
				deniedReads = CodexRunner.sessionDeniedReads(manager, run, List.of(evidenceArtifacts),
					List.of(project), platform, Map.of());

				requireThat(Set.copyOf(deniedReads), platform + ".deniedReads").isEqualTo(Set.of(project,
					directory.resolve("home/sessions")));
			}
		}
	}

	/**
	 * Verifies that a session is not denied a path inside another denied directory, such as the held-out run root
	 * inside the project, because the Linux sandbox fails to start when it must hide a directory inside one that it
	 * already hides, while a denied path inside the session's home stays denied.
	 *
	 * @throws IOException if the run cannot be created
	 */
	@Test
	public void skipsDeniedReadsInsideDeniedDirectory() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = jvm.temporaryDirectory();
			Path project = cleanup.add(Files.createTempDirectory(temporary, "cat-user-project-"));
			Path session = Files.createDirectories(project.resolve(".cat/sessions/session-1"));
			Path root = Files.createDirectories(session.resolve("optimizer-runs/codex"));
			Path heldOutRoot = Files.createDirectories(session.resolve("optimizer-runs/codex-held_out"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("codex");

			for (Platform platform : List.of(Platform.LINUX_X64, Platform.MACOS_ARM64, Platform.WINDOWS_X64))
			{
				List<Path> deniedReads = CodexRunner.sessionDeniedReads(manager, run, List.of(),
					List.of(heldOutRoot, project), platform, Map.of());

				requireThat(deniedReads, platform + ".deniedReads").containsExactly(List.of(project,
					run.directory().resolve("home/sessions")));
			}
		}
	}
}
