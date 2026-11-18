package com.holdmyspot.cat.common.installer.cli.internal.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.cli.internal.InstallerProcess;
import com.holdmyspot.cat.common.installer.engine.test.RecordingIntegration;
import com.holdmyspot.cat.common.installer.fakes.test.ExitedProgram;
import com.holdmyspot.cat.common.installer.secrets.InstallerProcessSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
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
	 * On Linux and macOS the launcher itself is started with the arguments, with the installer's environment and
	 * {@code CAT_INSTALLER_NO_QUESTIONS}, which makes it ask no question, the installer's standard error and its
	 * standard output read as the result, and the exit status and output are returned.
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
			requireThat(Map.copyOf(builder.environment()), "environment").isEqualTo(Map.of("HOME", root.toString(),
				"PATH", root.resolve("bin").toString(), "CAT_INSTALLER_NO_QUESTIONS", "1"));
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

	/**
	 * When the folder that holds the running installer's Java runtime was deleted, as Codex deletes the folder of the
	 * version that an update replaces, the folder that the runner puts back to start the launcher no longer exists
	 * once the started installer runs, so that a harness that selects its plugin's version by folder name selects the
	 * installed version's folder rather than the one that was put back; and nothing of it is left after the run.
	 *
	 * @throws IOException if the fixture cannot be created or the run fails
	 */
	@Test
	public void startedInstallerFindsNoRestoredFolder() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("installer-process-restored-")).toRealPath();
			Path replaced = root.resolve("cache/cat/cat/1.0+b96abd7d44d4b");
			Path spawnHelper = Files.createDirectories(replaced.resolve("runtime/lib")).resolve("jspawnhelper");
			Files.writeString(spawnHelper, "spawn helper");
			if (!spawnHelper.toFile().setExecutable(true))
				throw new IOException("Could not make " + spawnHelper + " executable");
			InstallerProcess installers = InstallerProcessSecrets.getInstallerProcessAccess().create(Platform.current(),
				RecordingIntegration.windowsVariables(), ProcessBuilder::start, replaced.resolve("runtime"));
			TemporaryDirectories.deleteRecursively(replaced);
			Path launcher = launcherReporting(root.resolve("cache/cat/cat/1.0+b043121dfc2e7/bin"), replaced);

			CommandResult result = installers.run(launcher, List.of());

			requireThat(result.exitCode(), "exitCode").withContext(result.output(), "output").isEqualTo(0);
			requireThat(result.output().strip(), "output").isEqualTo("absent");
			requireThat(Files.exists(replaced), "replacedExists").isFalse();
		}
	}

	/**
	 * Writes an installer launcher that prints whether a folder exists when it runs.
	 *
	 * @param bin    the folder of the launcher
	 * @param folder the folder
	 * @return the launcher
	 * @throws IOException if the launcher cannot be written
	 */
	private static Path launcherReporting(Path bin, Path folder) throws IOException
	{
		Files.createDirectories(bin);
		if (Platform.current().isWindows())
		{
			String literal = folder.toString().replace("'", "''");
			return Files.writeString(bin.resolve("installer.ps1"), "if (Test-Path -LiteralPath '" + literal +
				"') { 'present' } else { 'absent' }\n");
		}
		String literal = folder.toString().replace("'", "'\\''");
		Path launcher = Files.writeString(bin.resolve("installer"), "#!/bin/sh\nif [ -e '" + literal +
			"' ]; then echo present; else echo absent; fi\n");
		if (Files.getFileStore(launcher).supportsFileAttributeView(PosixFileAttributeView.class))
			Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwx------"));
		return launcher;
	}
}
