package io.github.cowwoc.cat.codex.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.codex.runner.CodexRunner;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

		requireThat(CodexRunner.withPrivateTmp(command, Path.of("/usr/bin/bwrap"), Path.of("/work/run")),
			"command").isEqualTo(List.of("/usr/bin/bwrap", "--dev-bind", "/", "/", "--tmpfs", "/tmp",
			"--die-with-parent", "--", "codex", "app-server", "--stdio", "--disable", "plugins"));
	}

	/**
	 * Verifies a run directory below {@code /tmp} stays visible inside the private {@code /tmp}.
	 */
	@Test
	public void keepsRunDirectoryInPrivateTmp()
	{
		List<String> command = CodexRunner.appServerCommand("codex", true);

		requireThat(CodexRunner.withPrivateTmp(command, Path.of("/usr/bin/bwrap"), Path.of("/tmp/runs/run")),
			"command").isEqualTo(List.of("/usr/bin/bwrap", "--dev-bind", "/", "/", "--tmpfs", "/tmp",
			"--bind", "/tmp/runs/run", "/tmp/runs/run", "--die-with-parent", "--", "codex", "app-server", "--stdio",
			"--enable", "hooks"));
	}

	/**
	 * Verifies bubblewrap is the first executable {@code bwrap} on the caller's {@code PATH}, skipping a file of that
	 * name that is not executable, and that it is absent when no directory on the {@code PATH} has one.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void findsBubblewrapOnPath() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path empty = cleanup.add(Files.createTempDirectory("cat-path-"));
			Path unusable = cleanup.add(Files.createTempDirectory("cat-path-"));
			Files.createFile(unusable.resolve("bwrap"), PosixFilePermissions.asFileAttribute(
				PosixFilePermissions.fromString("rw-r--r--")));
			Path usable = cleanup.add(Files.createTempDirectory("cat-path-"));
			Path bwrap = Files.createFile(usable.resolve("bwrap"), PosixFilePermissions.asFileAttribute(
				PosixFilePermissions.fromString("rwxr-xr-x")));
			String path = empty + File.pathSeparator + unusable + File.pathSeparator + usable;

			requireThat(CodexRunner.bubblewrap(Map.of("PATH", path)), "found").isEqualTo(Optional.of(bwrap));
			requireThat(CodexRunner.bubblewrap(Map.of("PATH", empty.toString())), "absent").isEmpty();
			requireThat(CodexRunner.bubblewrap(Map.of()), "withoutPath").isEmpty();
		}
	}
}
