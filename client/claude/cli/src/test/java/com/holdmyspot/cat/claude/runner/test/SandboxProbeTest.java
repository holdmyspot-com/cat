package com.holdmyspot.cat.claude.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.runner.ClaudeSessionSandbox.ProbeResult;
import com.holdmyspot.cat.claude.runner.SandboxProbe;
import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.Yielding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.Test;

/**
 * Verifies what the pre-launch probe observes: the exit status and output of a program that tries to start a sandbox,
 * and whether a file can be created in the temporary folder. Each test runs a program that the test owns, never the
 * host's.
 */
public final class SandboxProbeTest
{
	/**
	 * Creates the test.
	 */
	public SandboxProbeTest()
	{
	}

	/**
	 * Verifies that a program that exits 0 gives status 0, and that one that exits 1 gives status 1 and the text that it
	 * wrote to its error stream, so that the refusal quotes
	 * what the program said.
	 *
	 * @throws IOException if a fixture cannot be written
	 */
	@Test
	public void reportsExitStatusAndOutput() throws IOException
	{
		// Requirement: R-I16
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sandbox-probe-"));
			Path passing = program(root, "passing", 0, "");
			Path failing = program(root, "failing", 1, "bwrap: setting up uid map: Permission denied");

			ProbeResult passed = SandboxProbe.run(List.of(passing.toString()), root, Map.of(), Yielding.executableFiles());
			ProbeResult failed = SandboxProbe.run(List.of(failing.toString(), "--unshare-user"), root, Map.of(),
				Yielding.executableFiles());

			requireThat(passed.exitStatus(), "passedStatus").isEqualTo(0);
			requireThat(failed.exitStatus(), "failedStatus").isEqualTo(1);
			requireThat(failed.output(), "failedOutput").contains("bwrap: setting up uid map: Permission denied");
		}
	}

	/**
	 * Verifies that the program receives its arguments and sees exactly the
	 * environment that the caller gives it, so that the probe sees the Claude process's {@code PATH}, not the
	 * runner's.
	 *
	 * @throws IOException if a fixture cannot be written
	 */
	@Test
	public void runsWithGivenEnvironment() throws IOException
	{
		// Requirement: R-I16
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sandbox-probe-"));
			Path directory = Files.createDirectory(root.resolve("work"));
			Path echo = echo(root);

			ProbeResult result = SandboxProbe.run(List.of(echo.toString(), "first-argument"), directory,
				Map.of("CAT_PROBE_VALUE", "given-value"), Yielding.executableFiles());

			requireThat(result.exitStatus(), "status").isEqualTo(0);
			requireThat(result.output(), "output").contains("argument=first-argument").contains("value=given-value");
		}
	}

	/**
	 * Verifies that a program that cannot be started gives {@link ProbeResult#NOT_FINISHED} and the reason, instead of
	 * an exception, so that the launch is refused with the reason.
	 *
	 * @throws IOException if a fixture cannot be created
	 */
	@Test
	public void reportsProgramThatCannotStart() throws IOException
	{
		// Requirement: R-I16
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sandbox-probe-"));
			Path missing = root.resolve("missing-program");

			ProbeResult result = SandboxProbe.run(List.of(missing.toString()), root, Map.of(),
				Yielding.executableFiles());

			requireThat(result.exitStatus(), "status").isEqualTo(ProbeResult.NOT_FINISHED);
			requireThat(result.output(), "output").contains(missing.toString());
		}
	}

	/**
	 * Verifies that a folder in which a file can be created passes and keeps no file, that a folder that does not
	 * exist and a path that is a file are each a failure, and that, where the file system has permission bits and the
	 * user does not bypass them, a folder in which the user cannot create a file is a failure.
	 *
	 * @throws IOException if a fixture cannot be created
	 */
	@Test
	public void reportsFolderWithoutFileCreation() throws IOException
	{
		// Requirement: R-I16
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sandbox-probe-"));
			Path writable = Files.createDirectory(root.resolve("writable"));
			Path file = Files.createFile(root.resolve("file"));

			requireThat(SandboxProbe.fileCreationFailure(writable), "writable").isEmpty();
			try (Stream<Path> entries = Files.list(writable))
			{
				requireThat(entries.toList(), "leftover").isEmpty();
			}
			requireThat(SandboxProbe.fileCreationFailure(root.resolve("missing")), "missing").isPresent();
			requireThat(SandboxProbe.fileCreationFailure(file), "file").isPresent();
			if (PortableFiles.hasModes(root))
			{
				Path readOnly = Files.createDirectory(root.resolve("read-only"));
				Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-xr-xr-x"));
				// A user who bypasses permission bits, such as root, creates a file here anyway
				if (!Files.isWritable(readOnly))
					requireThat(SandboxProbe.fileCreationFailure(readOnly), "readOnly").isPresent();
				Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwxr-xr-x"));
			}
		}
	}

	/**
	 * Writes a program that prints text and exits with a status.
	 *
	 * @param directory the directory of the program
	 * @param name      the program's name, without an extension
	 * @param status    the exit status
	 * @param text      the text that the program prints to its error stream, or an empty string for none
	 * @return the program's file
	 * @throws IOException if the file cannot be written
	 */
	private static Path program(Path directory, String name, int status, String text) throws IOException
	{
		if (PortableFiles.hasModes(directory))
		{
			String stderr = "";
			if (!text.isEmpty())
				stderr = "echo '" + text + "' >&2\n";
			return script(directory.resolve(name + ".sh"), "#!/bin/sh\n" + stderr + "exit " + status + "\n");
		}
		String stderr = "";
		if (!text.isEmpty())
			stderr = "echo " + text + " 1>&2\r\n";
		return script(directory.resolve(name + ".cmd"), "@echo off\r\n" + stderr + "exit /b " + status + "\r\n");
	}

	/**
	 * Writes a program that prints its first argument and the value of the {@code CAT_PROBE_VALUE} variable.
	 *
	 * @param directory the directory of the program
	 * @return the program's file
	 * @throws IOException if the file cannot be written
	 */
	private static Path echo(Path directory) throws IOException
	{
		if (PortableFiles.hasModes(directory))
		{
			return script(directory.resolve("echo.sh"),
				"#!/bin/sh\necho \"argument=$1\"\necho \"value=$CAT_PROBE_VALUE\"\n");
		}
		return script(directory.resolve("echo.cmd"),
			"@echo off\r\necho argument=%1\r\necho value=%CAT_PROBE_VALUE%\r\n");
	}

	/**
	 * Writes an executable script.
	 *
	 * @param file    the file
	 * @param content the script
	 * @return the file
	 * @throws IOException if the file cannot be written
	 */
	private static Path script(Path file, String content) throws IOException
	{
		Files.writeString(file, content);
		if (PortableFiles.hasModes(file.getParent()))
			Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
		return file;
	}
}
