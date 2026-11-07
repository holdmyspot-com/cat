package com.holdmyspot.cat.common.runner.launcher.test;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.launcher.ProcessHandleTable;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.launcher.ProcessTable;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.secrets.HarnessProcessesSecrets;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Runs the {@link ProcessTable} contract against the operating system's processes, and tests that the session
 * launcher reads a request holder's whole command line, however long.
 * <p>
 * In the contract, the test's own process is the leaf's parent, the process that started the test is its grandparent,
 * and the leaf is a small JVM that runs until its standard input ends. In the command-line tests, a temporary folder
 * stands for Linux's process folder {@code /proc}: its {@code <pid>/cmdline} file holds the NUL-separated command line
 * of the test's own process, as the kernel reports it, where {@link ProcessHandle} would report no arguments for a
 * command line longer than 4 KiB.
 */
public final class ProcessHandleTableTest extends ProcessTableContract
{
	/**
	 * How long the leaf may take to start or end.
	 */
	private static final Duration LEAF_WAIT = Duration.ofSeconds(10);
	/**
	 * The line that the leaf prints once it runs its own command line.
	 */
	private static final String READY = "ready";

	/**
	 * Creates the test.
	 */
	public ProcessHandleTableTest()
	{
	}

	@Override
	protected ProcessTree start() throws IOException
	{
		ProcessHandle current = ProcessHandle.current();
		ProcessHandle parent = current.parent().orElseThrow();
		String executable = "java";
		if (Platform.current().isWindows())
			executable = "java.exe";
		Path java = Path.of(System.getProperty("java.home"), "bin", executable);
		String classes = System.getProperty("jdk.module.path", System.getProperty("java.class.path"));
		List<String> command = new ArrayList<>(List.of(java.toString(), "-XX:+PerfDisableSharedMem",
			"-XX:TieredStopAtLevel=1", "-cp", classes, Leaf.class.getName()));
		command.addAll(LEAF_ARGUMENTS);
		Process leaf = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		try
		{
			// Until the leaf prints, its process may still run the launcher's helper, under another command line
			String printed = new String(leaf.getInputStream().readNBytes(READY.length()), StandardCharsets.UTF_8);
			if (!printed.equals(READY))
				throw new IOException("The leaf printed \"" + printed + "\" rather than " + READY);
			return new SystemTree(new ProcessHandleTable(HarnessProcesses.system()), identity(parent),
				identity(current), identity(leaf.toHandle()), leaf);
		}
		catch (IOException | RuntimeException e)
		{
			leaf.destroyForcibly();
			throw e;
		}
	}

	/**
	 * Returns the identity of a live process.
	 *
	 * @param process the process
	 * @return its identity
	 */
	private static ProcessIdentity identity(ProcessHandle process)
	{
		return new ProcessIdentity(process.pid(), process.info().startInstant().orElseThrow());
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

	/**
	 * Three generations of the operating system's processes.
	 *
	 * @param table   the table
	 * @param root    the process that started the test
	 * @param middle  the test's own process
	 * @param leaf    the leaf
	 * @param process the leaf's process, which ends when its standard input ends
	 */
	private record SystemTree(ProcessTable table, ProcessIdentity root, ProcessIdentity middle, ProcessIdentity leaf,
		Process process) implements ProcessTree
	{
		@Override
		public void endLeaf() throws IOException
		{
			process.getOutputStream().close();
			try
			{
				if (!process.waitFor(LEAF_WAIT))
				{
					process.destroyForcibly();
					throw new IOException("The leaf did not end within " + LEAF_WAIT);
				}
			}
			catch (InterruptedException e)
			{
				process.destroyForcibly();
				Thread.currentThread().interrupt();
				throw new InterruptedIOException(e.getMessage());
			}
		}

		@Override
		public void close() throws IOException
		{
			if (process.isAlive())
				endLeaf();
		}
	}

	/**
	 * The leaf: it prints {@value #READY} and runs until its standard input ends.
	 */
	public static final class Leaf
	{
		/**
		 * Prevents instantiation.
		 */
		private Leaf()
		{
		}

		/**
		 * Runs the leaf.
		 *
		 * @param args the arguments, which the leaf ignores
		 * @throws IOException if standard input cannot be read
		 */
		public static void main(String[] args) throws IOException
		{
			System.out.println(READY);
			System.out.flush();
			System.in.transferTo(OutputStream.nullOutputStream());
		}
	}
}
