package com.holdmyspot.cat.common.installer.cli.internal.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.activation.HarnessCommand;
import com.holdmyspot.cat.common.installer.cli.internal.ExecutableDiscovery;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests how a first install finds a harness's command-line program on the user's search path, and what it records.
 */
public final class ExecutableDiscoveryTest
{
	/**
	 * Creates the test.
	 */
	public ExecutableDiscoveryTest()
	{
	}

	/**
	 * Install records the path at which it found the first program on the search path, without resolving the link that
	 * a harness re-points when it updates itself, a search path without relative, empty or repeated entries, and the
	 * harness's own variables, but no other variable of the user's environment.
	 *
	 * @throws IOException if the fixture cannot be created or discovery fails
	 */
	@Test
	public void recordsProgramFoundOnPath() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Layout layout = new Layout(cleanup.add(testDirectory()), Platform.current());
			Path real = layout.program(layout.root.resolve("versions/2.1"));
			Path installed = Files.createDirectories(layout.root.resolve("installed"));
			Path link = Files.createSymbolicLink(installed.resolve(real.getFileName()), real);
			Path other = Files.createDirectories(layout.root.resolve("other"));
			layout.environment.put("PATH", String.join(File.pathSeparator, "relative-bin", "", installed.toString(),
				other.toString(), installed.toString()));
			layout.environment.put("UNRELATED", "value");
			Map<String, String> expected = layout.platformVariables();
			expected.put("HARNESS_A_HOME", layout.home.toString());
			expected.put("PATH", installed + File.pathSeparator + other);

			HarnessCommand command = layout.find();

			requireThat(command.command(), "command").isEqualTo(List.of(link.toString()));
			requireThat(command.environment(), "environment").isEqualTo(expected);
		}
	}

	/**
	 * Install reports the program's name and the search path when no program is found.
	 *
	 * @throws IOException if the fixture cannot be created or discovery fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "The program \"harness-a\" is not on PATH.*Install the harness.*")
	public void refusesMissingProgram() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Layout layout = new Layout(cleanup.add(testDirectory()), Platform.current());
			layout.environment.put("PATH", Files.createDirectories(layout.root.resolve("empty")).toString());

			layout.find();
		}
	}

	/**
	 * On Windows, install takes only an {@code .exe} or {@code .cmd} program, in the order that {@code PATHEXT} gives,
	 * runs a {@code .cmd} program through {@code cmd.exe} and records the variables that Windows programs need.
	 *
	 * @throws IOException if the fixture cannot be created or discovery fails
	 */
	@Test
	public void windowsRunsBatchProgramThroughCmd() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Layout layout = new Layout(cleanup.add(testDirectory()), Platform.WINDOWS_X64);
			Path first = Files.createDirectories(layout.root.resolve("first"));
			Files.writeString(first.resolve("harness-a.bat"), "@echo off\r\n");
			Files.writeString(first.resolve("harness-a.com"), "");
			Path second = Files.createDirectories(layout.root.resolve("second"));
			Path program = Files.writeString(second.resolve("harness-a.cmd"), "@echo off\r\n");
			Files.writeString(second.resolve("harness-a.exe"), "");
			Path systemRoot = Files.createDirectories(layout.root.resolve("Windows"));
			layout.environment.put("Path", first + ";" + second);
			layout.environment.put("PATHEXT", ".COM;.BAT;.CMD;.EXE");
			layout.environment.put("SystemRoot", systemRoot.toString());

			HarnessCommand command = layout.find();

			requireThat(command.command(), "command").isEqualTo(List.of(systemRoot.resolve("System32").
				resolve("cmd.exe").toString(), "/d", "/s", "/c", program.toString()));
			requireThat(command.environment(), "environment").isEqualTo(Map.of("HARNESS_A_HOME",
				layout.home.toString(), "PATH", first + ";" + second, "SystemRoot", systemRoot.toString(), "PATHEXT",
				".COM;.BAT;.CMD;.EXE", "NoDefaultCurrentDirectoryInExePath", "1"));
		}
	}

	/**
	 * On Windows, install refuses to record a program that it could not run, because {@code SystemRoot} does not name
	 * the Windows directory.
	 *
	 * @throws IOException if the fixture cannot be created or discovery fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = "SystemRoot must name.*")
	public void windowsRequiresSystemRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Layout layout = new Layout(cleanup.add(testDirectory()), Platform.WINDOWS_X64);
			Path directory = Files.createDirectories(layout.root.resolve("installed"));
			Files.writeString(directory.resolve("harness-a.exe"), "");
			layout.environment.put("PATH", directory.toString());
			layout.environment.remove("SystemRoot");

			layout.find();
		}
	}

	/**
	 * Creates a test-owned directory beneath this module's build directory.
	 *
	 * @return the new directory's real path, registered by the caller with its cleanup scope
	 * @throws IOException if the directory cannot be created
	 */
	private static Path testDirectory() throws IOException
	{
		Path base = Files.createDirectories(Path.of(System.getProperty("cat.installer.test.directory")));
		return Files.createTempDirectory(base, "discovery-").toRealPath();
	}

	/**
	 * The directories of one install and the user's environment.
	 */
	private static final class Layout
	{
		private final Path root;
		private final Platform platform;
		private final Path home;
		private final Map<String, String> environment = new HashMap<>();

		/**
		 * Creates the directories.
		 *
		 * @param root     the test directory
		 * @param platform the platform that discovery runs for
		 * @throws IOException if a directory cannot be created
		 */
		private Layout(Path root, Platform platform) throws IOException
		{
			this.root = root;
			this.platform = platform;
			this.home = Files.createDirectories(root.resolve("harness-home"));
			String systemRoot = System.getenv("SystemRoot");
			if (platform.isWindows() && systemRoot != null)
				environment.put("SystemRoot", systemRoot);
		}

		/**
		 * Creates the {@code harness-a} program that the platform starts.
		 *
		 * @param directory the directory to create it in
		 * @return the program
		 * @throws IOException if the program cannot be written
		 */
		private Path program(Path directory) throws IOException
		{
			String name = "harness-a";
			if (platform.isWindows())
				name += ".exe";
			Path program = Files.writeString(Files.createDirectories(directory).resolve(name), "#!/bin/sh\n");
			if (Files.getFileStore(program).supportsFileAttributeView(PosixFileAttributeView.class))
				Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));
			return program;
		}

		/**
		 * Returns the variables that discovery records for the platform.
		 *
		 * @return the variables, which the caller may change
		 */
		private Map<String, String> platformVariables()
		{
			Map<String, String> variables = new HashMap<>();
			if (platform.isWindows())
			{
				variables.put("SystemRoot", environment.get("SystemRoot"));
				variables.put("NoDefaultCurrentDirectoryInExePath", "1");
			}
			return variables;
		}

		/**
		 * Finds the {@code harness-a} program.
		 *
		 * @return the command that install records
		 * @throws IOException if discovery fails
		 */
		private HarnessCommand find() throws IOException
		{
			return new ExecutableDiscovery(platform).find("harness-a", environment,
				Map.of("HARNESS_A_HOME", home.toString()));
		}
	}
}
