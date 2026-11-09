package com.holdmyspot.cat.common.installer.cli.internal.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.cli.internal.InstallerProcess;
import com.holdmyspot.cat.common.installer.fakes.test.ExitedProgram;
import com.holdmyspot.cat.common.installer.secrets.InstallerProcessSecrets;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests the command line, environment and streams with which the installer of another version folder is started, and
 * what its run returns, through the process builder that would start it.
 */
public final class InstallerProcessTest
{
	/**
	 * Creates the test.
	 */
	public InstallerProcessTest()
	{
	}

	/**
	 * On Linux and macOS the launcher itself is started with the arguments, with exactly the installer's environment,
	 * the installer's standard error and its standard output read as the result, and the exit status and output are
	 * returned.
	 *
	 * @throws IOException if the test directory cannot be created or the run fails
	 */
	@Test
	public void startsLauncherOnPosix() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("installer-process-posix-"));
			List<ProcessBuilder> started = new ArrayList<>();
			Map<String, String> environment = Map.of("HOME", root.toString(), "PATH", root.resolve("bin").toString());
			InstallerProcess installers = InstallerProcessSecrets.getInstallerProcessAccess().create(Platform.LINUX_X64,
				environment, builder ->
				{
					started.add(builder);
					return new ExitedProgram(new CommandResult(10, "{\"result\":\"failed\"}\n"));
				});
			Path launcher = root.resolve("cache/cat/1.2+b0123456789ab/bin/installer");

			CommandResult result = installers.run(launcher, List.of("install", "--harness", "claude-code", "v1.2"));

			requireThat(result.exitCode(), "exitCode").isEqualTo(10);
			requireThat(result.output(), "output").isEqualTo("{\"result\":\"failed\"}\n");
			requireThat(started, "started").size().isEqualTo(1);
			ProcessBuilder builder = started.getFirst();
			requireThat(builder.command(), "command").
				isEqualTo(List.of(launcher.toString(), "install", "--harness", "claude-code", "v1.2"));
			requireThat(Map.copyOf(builder.environment()), "environment").isEqualTo(environment);
			requireThat(builder.redirectError(), "redirectError").isEqualTo(ProcessBuilder.Redirect.INHERIT);
			requireThat(builder.redirectOutput(), "redirectOutput").isEqualTo(ProcessBuilder.Redirect.PIPE);
		}
	}

	/**
	 * On Windows the PowerShell launcher is started through the Windows PowerShell of the Windows folder that the
	 * environment names, with the options that the installer's permission entries name it with.
	 *
	 * @throws IOException if the test directory cannot be created or the run fails
	 */
	@Test
	public void startsLauncherThroughPowerShellOnWindows() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("installer-process-windows-"));
			List<List<String>> started = new ArrayList<>();
			Path systemRoot = root.resolve("Windows");
			InstallerProcess installers = InstallerProcessSecrets.getInstallerProcessAccess().create(
				Platform.WINDOWS_X64, Map.of("SystemRoot", systemRoot.toString()), builder ->
				{
					started.add(builder.command());
					return new ExitedProgram(new CommandResult(0, ""));
				});
			Path launcher = root.resolve("cache/cat/1.2+b0123456789ab/bin/installer.ps1");

			installers.run(launcher, List.of("install", "--harness", "codex"));

			Path powershell = systemRoot.resolve("System32/WindowsPowerShell/v1.0/powershell.exe");
			requireThat(started, "started").isEqualTo(List.of(List.of(powershell.toString(), "-NoProfile",
				"-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", launcher.toString(), "install", "--harness",
				"codex")));
		}
	}

	/**
	 * On Windows an environment that does not name the Windows folder fails the run without starting anything.
	 *
	 * @throws IOException if the test directory cannot be created
	 */
	@Test
	public void windowsWithoutSystemRootFails() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("installer-process-unnamed-"));
			List<List<String>> started = new ArrayList<>();
			InstallerProcess installers = InstallerProcessSecrets.getInstallerProcessAccess().create(
				Platform.WINDOWS_X64, Map.of(), builder ->
				{
					started.add(builder.command());
					return new ExitedProgram(new CommandResult(0, ""));
				});
			Path launcher = root.resolve("installer.ps1");
			try
			{
				installers.run(launcher, List.of("install", "--harness", "codex"));
				throw new AssertionError("The run did not fail");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("SystemRoot");
			}
			requireThat(started, "started").isEmpty();
		}
	}
}
