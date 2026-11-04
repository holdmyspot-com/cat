package com.holdmyspot.cat.common.updater.cli.internal.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.updater.activation.HarnessCommand;
import com.holdmyspot.cat.common.updater.cli.internal.ExecutableDiscovery;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests how setup finds a harness's command-line program on the user's search path: which directories it never takes
 * the program from, and what it records.
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
	 * Setup records the path at which it found the first program on the search path, without resolving the link that
	 * a harness re-points when it updates itself, a search path without relative, empty, repeated or excluded
	 * entries, and the harness's own variables, but no other variable of the user's environment.
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
			Path decoy = layout.program(layout.home.resolve("bin")).getParent();
			Path other = Files.createDirectories(layout.root.resolve("other"));
			layout.environment.put("PATH", String.join(File.pathSeparator, decoy.toString(), "relative-bin", "",
				installed.toString(), other.toString(), installed.toString()));
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
	 * Setup never takes the program from a directory that a project, a download or the updater controls, so a
	 * program there that comes first on the search path is skipped, and the search path that setup records leaves
	 * the directory out.
	 *
	 * @param excluded the excluded directory's name in the fixture
	 * @throws IOException if the fixture cannot be created or discovery fails
	 */
	@Test(dataProvider = "excludedDirectories")
	public void skipsExcludedDirectory(String excluded) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Layout layout = new Layout(cleanup.add(testDirectory()), Platform.current());
			Path inExcluded = layout.program(layout.directory(excluded).resolve("bin")).getParent();
			Path allowed = layout.program(layout.root.resolve("allowed"));
			layout.environment.put("PATH", inExcluded + File.pathSeparator + allowed.getParent());

			HarnessCommand command = layout.find();

			requireThat(command.command(), "command").isEqualTo(List.of(allowed.toString()));
			requireThat(command.environment().get("PATH"), "PATH").isEqualTo(allowed.getParent().toString());
		}
	}

	/**
	 * Setup refuses a program on an allowed search directory whose link leads into an excluded directory, because a
	 * project, a download or the updater could replace the program there.
	 *
	 * @param excluded the excluded directory's name in the fixture
	 * @throws IOException if the fixture cannot be created or discovery fails
	 */
	@Test(dataProvider = "excludedDirectories", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*resolves into a directory that setup never records.*")
	public void refusesLinkIntoExcludedDirectory(String excluded) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Layout layout = new Layout(cleanup.add(testDirectory()), Platform.current());
			Path target = layout.program(layout.directory(excluded).resolve("bin"));
			Path installed = Files.createDirectories(layout.root.resolve("installed"));
			Files.createSymbolicLink(installed.resolve(target.getFileName()), target);
			layout.environment.put("PATH", installed.toString());

			layout.find();
		}
	}

	/**
	 * Supplies the directories that setup never takes the harness program from.
	 *
	 * @return the working directory, the system temporary directory, the directories that {@code TMPDIR},
	 * 	{@code TEMP} and {@code TMP} name, the harness home, {@code <user home>/.cat}, the directory that holds every
	 * 	harness's updater installation and the installation's data directory
	 */
	@DataProvider
	public static Object[][] excludedDirectories()
	{
		return new Object[][]{{"working"}, {"temporary"}, {"TMPDIR"}, {"TEMP"}, {"TMP"}, {"home"}, {"cat"},
			{"base"}, {"data"}};
	}

	/**
	 * Setup reports the program's name and the search path when no program is found.
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
	 * Setup cannot keep {@code <user home>/.cat} off the search path without the user's home directory.
	 *
	 * @throws IOException if the fixture cannot be created or discovery fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*must name an absolute directory.*")
	public void refusesUnknownUserHome() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Layout layout = new Layout(cleanup.add(testDirectory()), Platform.current());
			layout.environment.put("PATH", layout.program(layout.root.resolve("allowed")).getParent().toString());
			layout.environment.remove(Platform.current().homeVariable());

			layout.find();
		}
	}

	/**
	 * On Windows, setup takes only an {@code .exe} or {@code .cmd} program, in the order that {@code PATHEXT} gives,
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
	 * On Windows, setup refuses to record a program that it could not run, because {@code SystemRoot} does not name
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
		Path base = Files.createDirectories(Path.of(System.getProperty("cat.updater.test.directory")));
		return Files.createTempDirectory(base, "discovery-").toRealPath();
	}

	/**
	 * The directories of one setup run and the user's environment.
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
			environment.put(platform.homeVariable(), Files.createDirectories(root.resolve("user")).toString());
			for (String variable : List.of("TMPDIR", "TEMP", "TMP"))
				environment.put(variable, Files.createDirectories(root.resolve(variable)).toString());
			String systemRoot = System.getenv("SystemRoot");
			if (platform.isWindows() && systemRoot != null)
				environment.put("SystemRoot", systemRoot);
		}

		/**
		 * Returns one of the directories that setup never takes the program from.
		 *
		 * @param name the directory's name in the fixture
		 * @return the directory
		 * @throws IOException if the directory cannot be created
		 */
		private Path directory(String name) throws IOException
		{
			Path directory = switch (name)
			{
				case "working" -> root.resolve("project");
				case "temporary" -> root.resolve("java-temporary");
				case "TMPDIR", "TEMP", "TMP" -> Path.of(environment.get(name));
				case "home" -> home;
				case "cat" -> root.resolve("user/.cat");
				case "base" -> root.resolve("updaters/harness-b");
				case "data" -> data();
				default -> throw new AssertionError(name);
			};
			return Files.createDirectories(directory);
		}

		/**
		 * Returns the installation's data directory.
		 *
		 * @return the directory
		 */
		private Path data()
		{
			return root.resolve("updaters/harness-a");
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
		 * @return the command that setup records
		 * @throws IOException if discovery fails
		 */
		private HarnessCommand find() throws IOException
		{
			return new ExecutableDiscovery(platform, root.resolve("project"), root.resolve("java-temporary")).
				find("harness-a", home, data(), environment, Map.of("HARNESS_A_HOME", home.toString()));
		}
	}
}
