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
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Runs the {@link ProcessTable} contract against the operating system's processes, and tests that the table tells a
 * process apart from a later one with its identifier in whatever process reads its identity.
 * <p>
 * In the contract, the process is a small JVM that runs until its standard input ends. In the other tests, a
 * {@link ProcessFolder} stands for Linux's process folder {@code /proc}: its {@code <pid>/stat} file holds the start of
 * the test's own process.
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
	 * The boot time that the kernel reports once the wall clock is set back.
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

	@Override
	protected LiveProcess start() throws IOException
	{
		String executable = "java";
		if (Platform.current().isWindows())
			executable = "java.exe";
		Path java = Path.of(System.getProperty("java.home"), "bin", executable);
		String classes = System.getProperty("jdk.module.path", System.getProperty("java.class.path"));
		List<String> command = new ArrayList<>(List.of(java.toString(), "-XX:+PerfDisableSharedMem",
			"-XX:TieredStopAtLevel=1", "-cp", classes, Leaf.class.getName()));
		Process leaf = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		try
		{
			// The leaf prints once its own code runs, so a start that it reads afterwards is its own
			BufferedReader output = new BufferedReader(new InputStreamReader(leaf.getInputStream(),
				StandardCharsets.UTF_8));
			String printed = output.readLine();
			if (!READY.equals(printed))
				throw new IOException("The leaf printed \"" + printed + "\" rather than " + READY);
			return new SystemProcess(new ProcessHandleTable(HarnessProcesses.system()), identity(leaf.toHandle()), leaf,
				output);
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
	 * Verifies that the leaf, a process of its own, reads the start that this process reads for it, so that every
	 * process that reads a harness process's identity agrees on it.
	 *
	 * @throws IOException if the leaf cannot be started or ended
	 */
	@Test
	public void otherProcessReadsSameStart() throws IOException
	{
		try (SystemProcess leaf = (SystemProcess) start())
		{
			String leafStart = leaf.output().readLine();

			requireThat(leafStart, "leafStart").isEqualTo(leaf.process().start());
		}
	}

	/**
	 * Verifies that a process whose identifier a later process reuses has ended, so that the request server never
	 * takes the later process for its session's harness.
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
			ProcessIdentity ended = writeOwnProcess(folder);
			folder.start(ended.pid(), "java", TICKS + 100);
			HarnessProcesses processes = HarnessProcessesSecrets.getHarnessProcessesAccess().system(folder.path());

			boolean alive = new ProcessHandleTable(processes).isAlive(ended);

			requireThat(alive, "alive").isFalse();
		}
	}

	/**
	 * Verifies that a process of an earlier boot whose identifier and clock ticks a process of a later boot has
	 * ended, so that the request server of a harness process that ran before a restart never takes a later process for
	 * it.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void processOfEarlierBootHasEnded() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			ProcessFolder folder = new ProcessFolder(cleanup.add(Files.createTempDirectory("cat-proc-")), BOOT_TIME);
			ProcessIdentity ended = writeOwnProcess(folder);
			folder.reboot("8a7b6c5d-4e3f-4a1b-9c8d-7e6f5a4b3c2d");
			HarnessProcesses processes = HarnessProcessesSecrets.getHarnessProcessesAccess().system(folder.path());

			boolean alive = new ProcessHandleTable(processes).isAlive(ended);

			requireThat(alive, "alive").isFalse();
		}
	}

	/**
	 * Verifies that a process whose identity one process read stays alive in a process that reads it after the wall
	 * clock was set back, and so the boot time that the kernel derives from it moved. The request server thus does not
	 * take its harness process for ended.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void processStaysAliveWhenBootTimeMoves() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			ProcessFolder folder = new ProcessFolder(cleanup.add(Files.createTempDirectory("cat-proc-")), BOOT_TIME);
			ProcessIdentity process = writeOwnProcess(folder);
			folder.setBootTime(MOVED_BOOT_TIME);
			ProcessHandleTable later = new ProcessHandleTable(HarnessProcessesSecrets.getHarnessProcessesAccess().
				system(folder.path()));

			boolean alive = later.isAlive(process);

			requireThat(alive, "alive").isTrue();
		}
	}

	/**
	 * Writes the start of the test's own process into a stand-in for the process folder.
	 *
	 * @param folder the stand-in folder
	 * @return the identity of the test's own process, as a process that reads the folder now reads it
	 * @throws IOException if a file cannot be written
	 */
	private static ProcessIdentity writeOwnProcess(ProcessFolder folder) throws IOException
	{
		long pid = ProcessHandle.current().pid();
		folder.start(pid, "java", TICKS);
		return identity(HarnessProcessesSecrets.getHarnessProcessesAccess().system(folder.path()), pid);
	}

	/**
	 * A process of the operating system's.
	 *
	 * @param table   the table
	 * @param process the leaf's identity
	 * @param leaf    the leaf, which ends when its standard input ends
	 * @param output  the leaf's output after the line {@value #READY}
	 */
	private record SystemProcess(ProcessTable table, ProcessIdentity process, Process leaf, BufferedReader output)
		implements LiveProcess
	{
		@Override
		public void end() throws IOException
		{
			leaf.getOutputStream().close();
			try
			{
				if (!leaf.waitFor(LEAF_WAIT))
				{
					leaf.destroyForcibly();
					throw new IOException("The leaf did not end within " + LEAF_WAIT);
				}
			}
			catch (InterruptedException e)
			{
				leaf.destroyForcibly();
				Thread.currentThread().interrupt();
				throw new InterruptedIOException(e.getMessage());
			}
		}

		@Override
		public void close() throws IOException
		{
			if (leaf.isAlive())
				end();
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
