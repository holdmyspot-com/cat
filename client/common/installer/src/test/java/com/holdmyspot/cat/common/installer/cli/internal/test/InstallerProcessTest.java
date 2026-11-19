package com.holdmyspot.cat.common.installer.cli.internal.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.cli.internal.InstallerProcess;
import com.holdmyspot.cat.common.installer.engine.test.RecordingIntegration;
import com.holdmyspot.cat.common.installer.fakes.test.ExitedProgram;
import com.holdmyspot.cat.common.installer.secrets.InstallerProcessSecrets;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testng.SkipException;
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
	 * On Linux, a launcher that the installer has just extracted runs once no process holds it open for writing any
	 * more, although the first start is refused while one does.
	 * <p>
	 * The JVM opens files without close-on-exec, so a process that the installer starts while it extracts the launcher
	 * inherits the open file until it closes the descriptors that it inherited. A write channel that the test keeps
	 * open stands in for that process, and the pause between start attempts closes it, as the inherited descriptor
	 * would close during the retries.
	 *
	 * @throws IOException if the fixture cannot be created or the run fails
	 */
	@Test
	public void runsLauncherOnceItsWriterCloses() throws IOException
	{
		if (!Platform.current().isLinux())
			throw new SkipException("Only Linux refuses to run a file that a process holds open for writing");
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("installer-process-busy-")).toRealPath();
			Path launcher = launcherReporting(root.resolve("bin"), root.resolve("missing"));
			try (FileChannel inherited = FileChannel.open(launcher, StandardOpenOption.WRITE))
			{
				InstallerProcess installers = new InstallerProcess(Platform.current(), Map.of(),
					new ExecutableFiles(inherited::close, new ManualClock(Instant.EPOCH)));

				CommandResult result = installers.run(launcher, List.of());

				requireThat(result.exitCode(), "exitCode").withContext(result.output(), "output").isEqualTo(0);
				requireThat(result.output().strip(), "output").isEqualTo("absent");
				// Only a refused start pauses, so a closed channel shows that the first start was refused
				requireThat(inherited.isOpen(), "inherited.isOpen").isFalse();
			}
		}
	}

	/**
	 * On Linux, starting a launcher fails with the operating system's refusal once the retry limit passes while a
	 * process holds the launcher open for writing.
	 *
	 * @throws IOException if the fixture cannot be created, or, as expected, the launcher cannot start
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*Text file busy.*")
	public void launcherFailsWhileWriterStaysOpen() throws IOException
	{
		if (!Platform.current().isLinux())
			throw new SkipException("Only Linux refuses to run a file that a process holds open for writing");
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("installer-process-stays-busy-")).toRealPath();
			Path launcher = launcherReporting(root.resolve("bin"), root.resolve("missing"));
			// Stands in for a process that never closes the descriptor that it inherited during the extraction
			try (FileChannel _ = FileChannel.open(launcher, StandardOpenOption.WRITE))
			{
				ManualClock clock = new ManualClock(Instant.EPOCH);
				InstallerProcess installers = new InstallerProcess(Platform.current(), Map.of(),
					new ExecutableFiles(() -> clock.advance(Duration.ofMillis(400)), clock));

				installers.run(launcher, List.of());
			}
		}
	}

	/**
	 * On Linux and macOS, where the Java runtime starts every process through its program that starts processes, a
	 * run that finds that program deleted puts it back while the launcher is started, starts the launcher through a
	 * shell that holds it back until what was put back is removed, and leaves nothing of it after the run.
	 *
	 * @throws IOException if the fixture cannot be created or the run fails
	 */
	@Test
	public void deletedSpawnHelperIsPutBackOnPosix() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("installer-process-posix-restored-")).toRealPath();
			Path launcher = root.resolve("cache/cat/cat/1.0+b043121dfc2e7/bin/installer");
			DeletedRuntimeRun run = runWithDeletedRuntime(Platform.LINUX_X64, Map.of("PATH", root.toString()),
				root.resolve("cache/cat/cat/1.0+b96abd7d44d4b"), launcher);

			requireThat(run.spawnHelperExisted(), "spawnHelperExisted").isTrue();
			requireThat(run.command().subList(0, 2), "shell").isEqualTo(List.of("/bin/sh", "-c"));
			requireThat(run.command().subList(3, run.command().size()), "launcherCommand").
				isEqualTo(List.of(launcher.toString(), "install", "--harness", "codex"));
			requireThat(run.replacedExists(), "replacedExists").isFalse();
		}
	}

	/**
	 * On Windows, where the Java runtime starts processes without a program that starts them, a run that finds the
	 * runtime's folder deleted puts nothing back and starts the launcher through Windows PowerShell without a shell.
	 *
	 * @throws IOException if the fixture cannot be created or the run fails
	 */
	@Test
	public void deletedRuntimeIsNotPutBackOnWindows() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("installer-process-windows-restored-")).toRealPath();
			Path systemRoot = root.resolve("Windows");
			Path launcher = root.resolve("cache/cat/cat/1.0+b043121dfc2e7/bin/installer.ps1");
			DeletedRuntimeRun run = runWithDeletedRuntime(Platform.WINDOWS_X64,
				Map.of("SystemRoot", systemRoot.toString()), root.resolve("cache/cat/cat/1.0+b96abd7d44d4b"), launcher);

			requireThat(run.spawnHelperExisted(), "spawnHelperExisted").isFalse();
			Path powershell = systemRoot.resolve("System32/WindowsPowerShell/v1.0/powershell.exe");
			requireThat(run.command(), "command").isEqualTo(List.of(powershell.toString(), "-NoProfile",
				"-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", launcher.toString(), "install", "--harness",
				"codex"));
			requireThat(run.replacedExists(), "replacedExists").isFalse();
		}
	}

	/**
	 * Runs a launcher with a runner whose Java runtime, inside a version folder, has a program that starts processes
	 * when the runner is created and is deleted with that folder before the run.
	 *
	 * @param platform    the platform that the runner runs on
	 * @param environment the installer's environment
	 * @param replaced    the version folder that holds the runtime
	 * @param launcher    the launcher
	 * @return what the process starter observed and whether the version folder exists after the run
	 * @throws IOException if the fixture cannot be created or the run fails
	 */
	private static DeletedRuntimeRun runWithDeletedRuntime(Platform platform, Map<String, String> environment,
		Path replaced, Path launcher) throws IOException
	{
		Path spawnHelper = Files.createDirectories(replaced.resolve("runtime/lib")).resolve("jspawnhelper");
		Files.writeString(spawnHelper, "spawn helper");
		if (!spawnHelper.toFile().setExecutable(true))
			throw new IOException("Could not make " + spawnHelper + " executable");
		List<Boolean> existed = new ArrayList<>();
		List<List<String>> started = new ArrayList<>();
		InstallerProcess installers = InstallerProcessSecrets.getInstallerProcessAccess().create(platform,
			environment, builder ->
			{
				existed.add(Files.exists(spawnHelper));
				started.add(List.copyOf(builder.command()));
				return new ExitedProgram(new CommandResult(0, ""));
			}, replaced.resolve("runtime"));
		TemporaryDirectories.deleteRecursively(replaced);

		installers.run(launcher, List.of("install", "--harness", "codex"));

		requireThat(started, "started").size().isEqualTo(1);
		return new DeletedRuntimeRun(existed.getFirst(), started.getFirst(), Files.exists(replaced));
	}

	/**
	 * What a run whose runtime folder was deleted showed.
	 *
	 * @param spawnHelperExisted whether the runtime's program that starts processes existed when the process was
	 *                           started
	 * @param command            the command line of the process
	 * @param replacedExists     whether the version folder that held the runtime existed after the run
	 */
	private record DeletedRuntimeRun(boolean spawnHelperExisted, List<String> command, boolean replacedExists)
	{
		/**
		 * Creates a run.
		 *
		 * @param spawnHelperExisted whether the runtime's program that starts processes existed when the process was
		 *                           started
		 * @param command            the command line of the process
		 * @param replacedExists     whether the version folder that held the runtime existed after the run
		 * @throws NullPointerException if {@code command} is null
		 */
		private DeletedRuntimeRun
		{
			requireThat(command, "command").isNotNull();
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
