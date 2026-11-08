package com.holdmyspot.cat.common.runner.launcher.test;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.ProcessFolder;
import com.holdmyspot.cat.common.runner.launcher.ProcessHandleTable;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.launcher.ProcessTable;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import com.holdmyspot.cat.common.secrets.HarnessProcessesSecrets;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Runs the {@link ProcessTable} contract against the operating system's processes, and tests that the session
 * launcher reads a request holder's whole command line, however long, and tells a process apart from a later one with
 * its identifier in whatever process reads its identity.
 * <p>
 * In the contract, the test's own process is the leaf's parent, the process that started the test is its grandparent,
 * and the leaf is a small JVM that runs until its standard input ends. In the other tests, a {@link ProcessFolder}
 * stands for Linux's process folder {@code /proc}: its {@code <pid>/cmdline} file holds the NUL-separated command line
 * of the test's own process, as the kernel reports it, where {@link ProcessHandle} would report no arguments for a
 * command line longer than 4 KiB, and its {@code <pid>/stat} file holds the process's start.
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
	 * The boot time that the kernel reports in the stand-in process folder before the wall clock is set.
	 */
	private static final long BOOT_TIME = 1_794_069_622;
	/**
	 * The boot time that the kernel reports once the wall clock is set back, as it was on a computer whose hook
	 * started a second launcher for one harness process.
	 */
	private static final long MOVED_BOOT_TIME = 1_794_069_343;
	/**
	 * The clock ticks between the boot and the start of the test's own process, in the stand-in process folder.
	 */
	private static final long TICKS = 1_234_563;

	/**
	 * Creates the test.
	 */
	public ProcessHandleTableTest()
	{
	}

	/**
	 * {@link ProcessHandle} reports no arguments of another process on Windows, where the session launcher is not
	 * served, so the table reports none there.
	 *
	 * @return {@code false} on Windows
	 */
	@Override
	protected boolean reportsArguments()
	{
		return !Platform.current().isWindows();
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
			BufferedReader output = new BufferedReader(new InputStreamReader(leaf.getInputStream(),
				StandardCharsets.UTF_8));
			String printed = output.readLine();
			if (!READY.equals(printed))
				throw new IOException("The leaf printed \"" + printed + "\" rather than " + READY);
			return new SystemTree(new ProcessHandleTable(HarnessProcesses.system()), identity(parent),
				identity(current), identity(leaf.toHandle()), leaf, output);
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
		return identity(HarnessProcesses.system(), process.pid());
	}

	/**
	 * Returns the identity of a live process.
	 *
	 * @param processes the operating system's processes
	 * @param pid       the process's identifier
	 * @return its identity
	 */
	private static ProcessIdentity identity(HarnessProcesses processes, long pid)
	{
		return new ProcessIdentity(pid, processes.find(pid).flatMap(ProcessDescription::start).orElseThrow());
	}

	/**
	 * Verifies that the leaf, a process of its own, reads the start that this process reads for it, so that the hook
	 * that names a harness process and the launcher that serves it agree on its identity.
	 *
	 * @throws IOException if the leaf cannot be started or ended
	 */
	@Test
	public void otherProcessReadsSameStart() throws IOException
	{
		try (SystemTree tree = (SystemTree) start())
		{
			String leafStart = tree.output().readLine();

			requireThat(leafStart, "leafStart").isEqualTo(tree.leaf().start());
		}
	}

	/**
	 * Verifies that a process whose command line is longer than 4 KiB reports every argument after its program.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void readsLongCommandLine() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			ProcessFolder folder = new ProcessFolder(cleanup.add(Files.createTempDirectory("cat-proc-")), BOOT_TIME);
			List<String> expected = List.of("--hold", "x".repeat(5000));
			ProcessIdentity process = writeOwnProcess(folder, expected);
			HarnessProcesses processes = HarnessProcessesSecrets.getHarnessProcessesAccess().system(folder.path());

			Optional<List<String>> arguments = new ProcessHandleTable(processes).arguments(process);

			requireThat(arguments, "arguments").isEqualTo(Optional.of(expected));
		}
	}

	/**
	 * Verifies that a process whose identifier a later process reuses has ended and reports no arguments, so that the
	 * launcher never takes the later process's command line for the earlier one's.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void reusedIdentifierHasEnded() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			ProcessFolder folder = new ProcessFolder(cleanup.add(Files.createTempDirectory("cat-proc-")), BOOT_TIME);
			ProcessIdentity ended = writeOwnProcess(folder, List.of("--hold", "request"));
			folder.start(ended.pid(), "java", TICKS + 100);
			HarnessProcesses processes = HarnessProcessesSecrets.getHarnessProcessesAccess().system(folder.path());
			ProcessHandleTable table = new ProcessHandleTable(processes);

			Optional<List<String>> arguments = table.arguments(ended);

			requireThat(arguments, "arguments").isEmpty();
			requireThat(table.isAlive(ended), "isAlive").isFalse();
		}
	}

	/**
	 * Verifies that a process of an earlier boot whose identifier and clock ticks a process of a later boot has
	 * ended, so that the launcher of a harness process that ran before a restart never takes a later process for it.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void processOfEarlierBootHasEnded() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			ProcessFolder folder = new ProcessFolder(cleanup.add(Files.createTempDirectory("cat-proc-")), BOOT_TIME);
			ProcessIdentity ended = writeOwnProcess(folder, List.of("--hold", "request"));
			folder.reboot("8a7b6c5d-4e3f-4a1b-9c8d-7e6f5a4b3c2d");
			HarnessProcesses processes = HarnessProcessesSecrets.getHarnessProcessesAccess().system(folder.path());

			boolean alive = new ProcessHandleTable(processes).isAlive(ended);

			requireThat(alive, "alive").isFalse();
		}
	}

	/**
	 * Verifies that a process whose identity one process read stays alive, with its parent and its command line, in
	 * a process that reads it after the wall clock was set back, and so the boot time that the kernel derives from it
	 * moved. The hook that names a harness process, the launcher that serves it and a later hook of the same process
	 * thus agree on its identity, and the launcher does not take its harness process for ended.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void processStaysAliveWhenBootTimeMoves() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			ProcessFolder folder = new ProcessFolder(cleanup.add(Files.createTempDirectory("cat-proc-")), BOOT_TIME);
			List<String> arguments = List.of("--hold", "request");
			ProcessIdentity process = writeOwnProcess(folder, arguments);
			long parentPid = ProcessHandle.current().parent().orElseThrow().pid();
			folder.start(parentPid, "java", TICKS - 100);
			ProcessIdentity parent = identity(HarnessProcessesSecrets.getHarnessProcessesAccess().
				system(folder.path()), parentPid);
			folder.setBootTime(MOVED_BOOT_TIME);
			ProcessHandleTable later = new ProcessHandleTable(HarnessProcessesSecrets.getHarnessProcessesAccess().
				system(folder.path()));

			requireThat(later.isAlive(process), "isAlive").isTrue();
			requireThat(later.parent(process), "parent").isEqualTo(Optional.of(parent));
			requireThat(later.arguments(process), "arguments").isEqualTo(Optional.of(arguments));
		}
	}

	/**
	 * Writes the command line and the start of the test's own process into a stand-in for the process folder.
	 *
	 * @param folder    the stand-in folder
	 * @param arguments the arguments after the program
	 * @return the identity of the test's own process, as a process that reads the folder now reads it
	 * @throws IOException if a file cannot be written
	 */
	private static ProcessIdentity writeOwnProcess(ProcessFolder folder, List<String> arguments) throws IOException
	{
		long pid = ProcessHandle.current().pid();
		List<String> commandLine = new ArrayList<>(List.of("java"));
		commandLine.addAll(arguments);
		folder.writeCommandLine(pid, commandLine);
		folder.start(pid, "java", TICKS);
		return identity(HarnessProcessesSecrets.getHarnessProcessesAccess().system(folder.path()), pid);
	}

	/**
	 * Three generations of the operating system's processes.
	 *
	 * @param table   the table
	 * @param root    the process that started the test
	 * @param middle  the test's own process
	 * @param leaf    the leaf
	 * @param process the leaf's process, which ends when its standard input ends
	 * @param output  the leaf's output after the line {@value #READY}
	 */
	private record SystemTree(ProcessTable table, ProcessIdentity root, ProcessIdentity middle, ProcessIdentity leaf,
		Process process, BufferedReader output) implements ProcessTree
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
	 * The leaf: it prints {@value #READY} and the start that it reads for itself, each on a line of its own, and runs
	 * until its standard input ends.
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
			long self = ProcessHandle.current().pid();
			System.out.println(HarnessProcesses.system().find(self).flatMap(ProcessDescription::start).orElse(""));
			System.out.flush();
			System.in.transferTo(OutputStream.nullOutputStream());
		}
	}
}
