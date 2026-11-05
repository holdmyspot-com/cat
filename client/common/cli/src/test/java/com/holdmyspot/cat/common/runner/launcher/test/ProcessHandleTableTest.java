package com.holdmyspot.cat.common.runner.launcher.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.launcher.ProcessHandleTable;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.secrets.HarnessProcessesSecrets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests that the session launcher reads a request holder's whole command line, however long.
 * <p>
 * A temporary folder stands for Linux's process folder {@code /proc}: its {@code <pid>/cmdline} file holds the
 * NUL-separated command line of the test's own process, as the kernel reports it, where {@link ProcessHandle} would
 * report no arguments for a command line longer than 4 KiB.
 */
public final class ProcessHandleTableTest
{
	/**
	 * Creates the test.
	 */
	public ProcessHandleTableTest()
	{
	}

	/**
	 * Verifies that a process whose command line is longer than 4 KiB reports every argument after its program.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void readsLongCommandLine() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path processFolder = cleanup.add(Files.createTempDirectory("cat-proc-"));
			List<String> expected = List.of("--hold", "x".repeat(5000));
			ProcessIdentity process = writeCommandLine(processFolder, expected);
			HarnessProcesses processes = HarnessProcessesSecrets.getHarnessProcessesAccess().system(processFolder);

			Optional<List<String>> arguments = new ProcessHandleTable(processes).arguments(process);

			requireThat(arguments, "arguments").isEqualTo(Optional.of(expected));
		}
	}

	/**
	 * Verifies that a process whose identifier a later process reuses reports no arguments, so that the launcher
	 * never takes the later process's command line for the earlier one's.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void reusedIdentifierHasNoArguments() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path processFolder = cleanup.add(Files.createTempDirectory("cat-proc-"));
			ProcessIdentity current = writeCommandLine(processFolder, List.of("--hold", "request"));
			ProcessIdentity ended = new ProcessIdentity(current.pid(), current.start().minusSeconds(1));
			HarnessProcesses processes = HarnessProcessesSecrets.getHarnessProcessesAccess().system(processFolder);

			Optional<List<String>> arguments = new ProcessHandleTable(processes).arguments(ended);

			requireThat(arguments, "arguments").isEmpty();
		}
	}

	/**
	 * Writes the command line of the test's own process into a stand-in for the process folder.
	 *
	 * @param processFolder the stand-in folder
	 * @param arguments     the arguments after the program
	 * @return the identity of the test's own process
	 * @throws IOException if the file cannot be written
	 */
	private static ProcessIdentity writeCommandLine(Path processFolder, List<String> arguments) throws IOException
	{
		ProcessHandle current = ProcessHandle.current();
		Instant start = current.info().startInstant().orElseThrow();
		Path commandLine = Files.createDirectories(processFolder.resolve(String.valueOf(current.pid()))).
			resolve("cmdline");
		StringBuilder content = new StringBuilder("java\0");
		for (String argument : arguments)
			content.append(argument).append('\0');
		Files.writeString(commandLine, content, StandardCharsets.UTF_8);
		return new ProcessIdentity(current.pid(), start);
	}
}
