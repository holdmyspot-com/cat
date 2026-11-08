package com.holdmyspot.cat.common.runner.policy.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.ProcessFolder;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.secrets.HarnessProcessesSecrets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests that the system's process lookup reports a Linux harness's whole command line, which a policy reader parses,
 * and a start that every process reads alike.
 * <p>
 * A temporary folder stands for Linux's process folder {@code /proc}: its {@code <pid>/cmdline} file holds the
 * NUL-separated command line of the test's own process, as the kernel reports it, where {@link ProcessHandle} would
 * report no arguments for a command line longer than 4 KiB, and its {@code <pid>/stat} file holds the process's
 * start.
 */
public final class HarnessProcessesTest
{
	/**
	 * Creates the test.
	 */
	public HarnessProcessesTest()
	{
	}

	/**
	 * Verifies that a Linux process whose command line is longer than 4 KiB reports every argument after its
	 * program.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void reportsLongLinuxCommandLine() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path processFolder = cleanup.add(Files.createTempDirectory("cat-proc-"));
			long processId = ProcessHandle.current().pid();
			String settings = "{\"sandbox\":{\"enabled\":true,\"note\":\"" + "x".repeat(5000) + "\"}}";
			Path commandLine = Files.createDirectories(processFolder.resolve(String.valueOf(processId))).
				resolve("cmdline");
			Files.write(commandLine, ("claude\0--settings\0" + settings + "\0-p\0").getBytes(StandardCharsets.UTF_8));
			HarnessProcesses processes = HarnessProcessesSecrets.getHarnessProcessesAccess().system(processFolder);

			Optional<List<String>> arguments = processes.find(processId).flatMap(ProcessDescription::arguments);

			requireThat(arguments, "arguments").isEqualTo(Optional.of(List.of("--settings", settings, "-p")));
		}
	}

	/**
	 * Verifies that a Linux process whose command line cannot be read reports no arguments, so that a policy reader
	 * refuses it rather than reading a partial command line.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void noArgumentsWithoutLinuxCommandLine() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path processFolder = cleanup.add(Files.createTempDirectory("cat-proc-"));
			long processId = ProcessHandle.current().pid();
			HarnessProcesses processes = HarnessProcessesSecrets.getHarnessProcessesAccess().system(processFolder);

			Optional<List<String>> arguments = processes.find(processId).flatMap(ProcessDescription::arguments);

			requireThat(arguments, "arguments").isEmpty();
		}
	}

	/**
	 * Verifies that a Linux process's start is the boot's identifier and the clock ticks between the boot and the
	 * process's start, read past a program name that holds a parenthesis and spaces, so that it does not depend on the
	 * boot time that the kernel derives from the wall clock.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void reportsLinuxStartFromBootAndTicks() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			ProcessFolder folder = new ProcessFolder(cleanup.add(Files.createTempDirectory("cat-proc-")), 1_794_069_622);
			long processId = ProcessHandle.current().pid();
			folder.start(processId, "a) S 1 (b", 123_456);
			HarnessProcesses processes = HarnessProcessesSecrets.getHarnessProcessesAccess().system(folder.path());

			Optional<String> start = processes.find(processId).flatMap(ProcessDescription::start);

			requireThat(start, "start").isEqualTo(Optional.of("0f1e2d3c4b5a69788796a5b4c3d2e1f0t123456"));
		}
	}

	/**
	 * Verifies that a Linux process whose start cannot be read reports none, so that no reader takes another
	 * process's identity for it.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void noStartWithoutLinuxStat() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			ProcessFolder folder = new ProcessFolder(cleanup.add(Files.createTempDirectory("cat-proc-")), 1_794_069_622);
			long processId = ProcessHandle.current().pid();
			HarnessProcesses processes = HarnessProcessesSecrets.getHarnessProcessesAccess().system(folder.path());

			Optional<String> start = processes.find(processId).flatMap(ProcessDescription::start);

			requireThat(start, "start").isEmpty();
		}
	}
}
