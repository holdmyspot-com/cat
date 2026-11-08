package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.execution.LeftoverRuns.RecordedProcess;
import com.holdmyspot.cat.common.optimizer.execution.LeftoverRuns;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.optimizer.port.test.FakeSessionPort;
import com.holdmyspot.cat.common.fakes.test.ProcessFolder;
import com.holdmyspot.cat.common.runner.ProcessHandleLiveness;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import com.holdmyspot.cat.common.secrets.HarnessProcessesSecrets;
import com.holdmyspot.cat.common.secrets.LeftoverRunsSecrets;
import com.holdmyspot.cat.common.secrets.ProcessHandleLivenessSecrets;
import com.holdmyspot.cat.common.test.FakeProcessHandle;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.LongFunction;

/**
 * Covers how the harness processes of leftover runner runs are found in the session port's run roots.
 * <p>
 * Except in {@link #readsOwnerLivenessFromProcessTable()} and {@link #leavesRunOfLiveOwnerAfterClockIsSet()}, the
 * real {@link ProcessHandleLiveness} reads which owner processes are alive from a table of {@link FakeProcessHandle}s
 * that stands in for the operating system's process table.
 */
public final class LeftoverRunsTest
{
	/**
	 * The recorded start of every harness process.
	 */
	private static final String HARNESS_START = "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5125";
	/**
	 * The recorded start of every owner process.
	 */
	private static final String OWNER_START = "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5000";
	/**
	 * The boot time, in seconds since the epoch, that a process folder shows until a test sets the wall clock.
	 */
	private static final long BOOT_TIME = 1_794_069_622;

	/**
	 * Creates the test.
	 */
	public LeftoverRunsTest()
	{
	}

	/**
	 * Returns run manifests and the processes that each identifies. The process table reports no owner process
	 * alive.
	 *
	 * @return the cases
	 */
	@DataProvider
	public static Object[][] manifests()
	{
		return new Object[][]
			{
				{"""
					{"state": "running", "owner_pid": 4001, "owner_started_at": "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5000",
					"harness_pid": 4002, "harness_started_at": "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5125"}""",
					List.of(new RecordedProcess(4002, HARNESS_START))},
				{"""
					{"state": "running", "owner_pid": "4001", "owner_started_at": "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5000",
					"harness_pid": "4002", "harness_started_at": "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5125"}""",
					List.of(new RecordedProcess(4002, HARNESS_START))},
				{"""
					{"state": "running", "owner_pid": 4001, "owner_started_at": "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5000",
					"harness_pid": null, "harness_started_at": null}""",
					List.of()},
				{"""
					{"state": "running", "owner_pid": 4001, "owner_started_at": "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5000",
					"harness_pid": 4002}""",
					List.of()},
				{"""
					{"state": "running", "owner_pid": 4001,
					"harness_pid": 4002, "harness_started_at": "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5125"}""",
					List.of()},
				{"""
					{"state": "running", "owner_pid": "41a", "owner_started_at": "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5000",
					"harness_pid": 4002, "harness_started_at": "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5125"}""",
					List.of()},
				{"""
					{"state": "running", "owner_pid": 4001, "owner_started_at": "yesterday at noon",
					"harness_pid": 4002, "harness_started_at": "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5125"}""",
					List.of()},
				{"""
					{"state": "running", "owner_pid": 4001, "owner_started_at": "2026-10-01T11:00:00Z",
					"harness_pid": 4002, "harness_started_at": "2026-10-01T11:00:01.250Z"}""",
					List.of()},
				{"""
					{"state": "running", "owner_pid": 4001, "owner_started_at": "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5000",
					"harness_pid": 4002, "harness_started_at": "2026-10-01T11:00:01.250Z"}""",
					List.of()},
				{"""
					{"state": "running", "owner_pid": 4001, "owner_started_at": "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5000",
					"harness_pid": -4, "harness_started_at": "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5125"}""",
					List.of()},
				{"""
					{"state": "completed", "owner_pid": 4001, "owner_started_at": "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5000",
					"harness_pid": 4002, "harness_started_at": "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5125"}""",
					List.of()},
				{"{\"state\": \"running\", \"owner_pid\": 4001", List.of()},
				{"[]", List.of()}
			};
	}

	/**
	 * Verifies that a manifest identifies a harness process only while its run is marked running, its owner process
	 * is identified by an identifier and a start and no longer exists, and the harness process is identified the same
	 * way. A start of another form, such as an instant, identifies no process.
	 *
	 * @param manifest the manifest's content
	 * @param expected the processes that the manifest identifies
	 * @throws IOException if the test directory cannot be used
	 */
	@Test(dataProvider = "manifests")
	public void find(String manifest, List<RecordedProcess> expected) throws IOException
	{
		// Requirement: R-E11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path runRoot = cleanup.add(Files.createTempDirectory("runs"));
			writeManifest(runRoot.resolve("claude").resolve("run-1"), manifest);
			requireThat(finder(List.of(), runRoot).find(), "find").isEqualTo(expected);
		}
	}

	/**
	 * Verifies that runs are found in every run root that the session port declares.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void findsRunsInEveryRunRoot() throws IOException
	{
		// Requirement: R-E11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path runRoot = cleanup.add(Files.createTempDirectory("runs"));
			Path heldOutRunRoot = cleanup.add(Files.createTempDirectory("runs-held_out"));
			writeManifest(runRoot.resolve("claude").resolve("run-1"), running(4001, 4002));
			writeManifest(heldOutRunRoot.resolve("claude").resolve("run-2"), running(5001, 5002));
			requireThat(finder(List.of(), runRoot, heldOutRunRoot).find(), "find").isEqualTo(
				List.of(new RecordedProcess(4002, HARNESS_START), new RecordedProcess(5002, HARNESS_START)));
		}
	}

	/**
	 * Verifies that a run whose owner process is alive, which belongs to a running driver, is left alone in every run
	 * root, while a run of a dead owner beside it is found. An owner is alive only if the process with its recorded
	 * identifier also has its recorded start: a live process that reuses the identifier is not the owner.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void leavesRunWithLiveOwner() throws IOException
	{
		// Requirement: R-E11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path runRoot = cleanup.add(Files.createTempDirectory("runs"));
			Path heldOutRunRoot = cleanup.add(Files.createTempDirectory("runs-held_out"));
			List<FakeProcessHandle> processes = List.of(new FakeProcessHandle(6001, OWNER_START, true),
				new FakeProcessHandle(7001, OWNER_START + "1", true));
			writeManifest(runRoot.resolve("claude").resolve("run-1"), running(6001, 6002));
			writeManifest(heldOutRunRoot.resolve("claude").resolve("run-2"), running(6001, 6003));
			writeManifest(heldOutRunRoot.resolve("claude").resolve("run-3"), running(5001, 5002));
			writeManifest(heldOutRunRoot.resolve("claude").resolve("run-4"), running(7001, 7002));
			requireThat(finder(processes, runRoot, heldOutRunRoot).find(), "find").isEqualTo(
				List.of(new RecordedProcess(5002, HARNESS_START), new RecordedProcess(7002, HARNESS_START)));
		}
	}

	/**
	 * Verifies, against the operating system's real process table, that a finder created for production leaves the
	 * run of a live owner, the test's own process, and finds the run of an owner that does not exist.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void readsOwnerLivenessFromProcessTable() throws IOException
	{
		// Requirement: R-E11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path runRoot = cleanup.add(Files.createTempDirectory("runs"));
			ProcessHandle current = ProcessHandle.current();
			String started = HarnessProcesses.system().find(current.pid()).flatMap(ProcessDescription::start).
				orElseThrow();
			writeManifest(runRoot.resolve("claude").resolve("run-1"), """
				{"state": "running", "owner_pid": %d, "owner_started_at": "%s",
				"harness_pid": 6002, "harness_started_at": "%s"}""".formatted(current.pid(), started, HARNESS_START));
			writeManifest(runRoot.resolve("claude").resolve("run-2"), running(5001, 5002));
			LeftoverRuns finder = new LeftoverRuns(new FakeSessionPort(HostLogin.UNKNOWN, null, List.of(runRoot)),
				JsonMapper.builder().build());
			requireThat(finder.find(), "find").isEqualTo(List.of(new RecordedProcess(5002, HARNESS_START)));
		}
	}

	/**
	 * Verifies that the run of a live owner is left alone after the wall clock was set, which moves the boot time that
	 * Linux derives from it, between the owner's start and the finder's check. The real process checker reads the
	 * starts of the test's own process, which stands for the owner, from a process folder in place of {@code /proc}.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void leavesRunOfLiveOwnerAfterClockIsSet() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path runRoot = cleanup.add(Files.createTempDirectory("runs"));
			ProcessFolder folder = new ProcessFolder(cleanup.add(Files.createTempDirectory("proc")), BOOT_TIME);
			long owner = ProcessHandle.current().pid();
			folder.start(owner, "java", 5000);
			HarnessProcesses processes = HarnessProcessesSecrets.getHarnessProcessesAccess().system(folder.path());
			String ownerStart = processes.find(owner).flatMap(ProcessDescription::start).orElseThrow();
			writeManifest(runRoot.resolve("claude").resolve("run-1"), """
				{"state": "running", "owner_pid": %d, "owner_started_at": "%s",
				"harness_pid": 6002, "harness_started_at": "%s"}""".formatted(owner, ownerStart, HARNESS_START));
			// The wall clock is set an hour back
			folder.setBootTime(BOOT_TIME - 3600);

			requireThat(finder(processes, ProcessHandle::of, runRoot).find(), "find").isEmpty();
		}
	}

	/**
	 * Verifies that a session port without run roots, or whose run roots do not exist yet, has no leftover runs.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void missingRunRoots() throws IOException
	{
		// Requirement: R-E11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("runs"));
			requireThat(finder(List.of()).find(), "withoutRoots").isEmpty();
			requireThat(finder(List.of(), directory.resolve("missing"),
				directory.resolve("missing-held-out")).find(), "withMissingRoots").isEmpty();
		}
	}

	/**
	 * Creates a finder over the run roots of a session port.
	 *
	 * @param processes the live processes of the operating system's process table
	 * @param runRoots  the run roots that the port declares
	 * @return the finder
	 */
	private static LeftoverRuns finder(List<FakeProcessHandle> processes, Path... runRoots)
	{
		FakeProcessHandle[] table = processes.toArray(FakeProcessHandle[]::new);
		return finder(FakeProcessHandle.processes(table), FakeProcessHandle.table(table), runRoots);
	}

	/**
	 * Creates a finder over the run roots of a session port whose real process checker reads the supplied processes.
	 *
	 * @param processes reports the starts of running processes
	 * @param handles   finds the process with an identifier
	 * @param runRoots  the run roots that the port declares
	 * @return the finder
	 */
	private static LeftoverRuns finder(HarnessProcesses processes, LongFunction<Optional<ProcessHandle>> handles,
		Path... runRoots)
	{
		ProcessHandleLiveness liveness = ProcessHandleLivenessSecrets.getProcessHandleLivenessAccess().create(processes,
			handles);
		return LeftoverRunsSecrets.getLeftoverRunsAccess().create(
			new FakeSessionPort(HostLogin.UNKNOWN, null, List.of(runRoots)), JsonMapper.builder().build(), liveness);
	}

	/**
	 * Returns the manifest of a running run whose owner process started at {@link #OWNER_START}.
	 *
	 * @param ownerPid the owner's identifier
	 * @param harnessPid the harness process's identifier
	 * @return the manifest
	 */
	private static String running(long ownerPid, long harnessPid)
	{
		return """
			{"state": "running", "owner_pid": %d, "owner_started_at": "%s",
			"harness_pid": %d, "harness_started_at": "%s"}""".formatted(ownerPid, OWNER_START, harnessPid, HARNESS_START);
	}

	/**
	 * Writes a run manifest.
	 *
	 * @param run the run's directory
	 * @param manifest the manifest's content
	 * @throws IOException if the file cannot be written
	 */
	private static void writeManifest(Path run, String manifest) throws IOException
	{
		Files.createDirectories(run);
		Files.writeString(run.resolve("run.json"), manifest);
	}
}
