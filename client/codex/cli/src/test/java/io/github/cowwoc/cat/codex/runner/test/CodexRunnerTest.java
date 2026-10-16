package io.github.cowwoc.cat.codex.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.codex.runner.CodexRunner;
import io.github.cowwoc.cat.common.PortableFiles;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.runner.HarnessRun;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
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
	 * Verifies a plugin under test is installed from its own run-local marketplace, which bears its name.
	 */
	@Test
	public void installsPluginUnderTestByName()
	{
		// Requirement: R-W12
		requireThat(CodexRunner.pluginSetupCommands("codex", Path.of("marketplace"), "release-docs"), "commands").
			isEqualTo(List.of(
				List.of("codex", "plugin", "marketplace", "add", "marketplace"),
				List.of("codex", "plugin", "add", "release-docs@release-docs")));
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
	 * Verifies that a session is denied reading the runner's records of its own run, namely the run's artifacts,
	 * manifest, lock, initial project and Codex session logs, the login that the run copied from the host, and the
	 * other runs and fixtures under the run root, besides the paths that the caller denies. The session can still read
	 * its project, the rest of its home and the evidence that it validates. The session log directory exists so that
	 * the sandbox can deny it.
	 *
	 * @throws IOException if the run cannot be created
	 */
	@Test
	public void deniesReadingRunRecordsOtherRunsAndLogin() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = Files.createDirectories(Path.of(System.getProperty("cat.work.temp")).normalize());
			Path root = cleanup.add(Files.createTempDirectory(temporary, "cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC());
			HarnessRun run = manager.create("codex");
			Path directory = run.directory();
			manager.snapshotProject(run);
			Path login = Files.writeString(directory.resolve("home/auth.json"), "{}");
			HarnessRun other = manager.create("codex");
			HarnessRun evidence = manager.create("codex");
			Path evidenceArtifacts = Files.createDirectories(evidence.directory().resolve("artifacts"));
			Path fixtures = Files.createDirectories(root.resolve("fixtures"));
			Path heldOut = cleanup.add(Files.createTempDirectory(temporary, "cat-held-out-"));

			List<Path> deniedReads = CodexRunner.sessionDeniedReads(manager, run, List.of(evidenceArtifacts),
				List.of(heldOut));

			requireThat(Set.copyOf(deniedReads), "deniedReads").isEqualTo(Set.of(heldOut,
				directory.resolve("artifacts"), directory.resolve("run.json"), directory.resolve(".lock"),
				directory.resolve("initial-project"), directory.resolve("home/sessions"), login, other.directory(),
				evidence.directory().resolve("project"), evidence.directory().resolve("home"),
				evidence.directory().resolve("run.json"), evidence.directory().resolve(".lock"), fixtures));
			requireThat(Files.isDirectory(directory.resolve("home/sessions")), "sessionLogsExist").isTrue();
		}
	}
}
