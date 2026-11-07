package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.execution.LeftoverRuns.RecordedProcess;
import com.holdmyspot.cat.common.optimizer.execution.LeftoverRuns;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.optimizer.port.test.FakeSessionPort;
import com.holdmyspot.cat.common.runner.ProcessHandleLiveness;
import com.holdmyspot.cat.common.secrets.LeftoverRunsSecrets;
import com.holdmyspot.cat.common.secrets.ProcessHandleLivenessSecrets;
import com.holdmyspot.cat.common.test.FakeProcessHandle;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Covers how the harness processes of leftover runner runs are found in the session port's run roots.
 * <p>
 * Except in {@link #readsOwnerLivenessFromProcessTable()}, the real {@link ProcessHandleLiveness} reads which owner
 * processes are alive from a table of {@link FakeProcessHandle}s that stands in for the operating system's process
 * table.
 */
public final class LeftoverRunsTest
{
	/**
	 * The recorded start time of every harness process.
	 */
	private static final Instant HARNESS_START = Instant.parse("2026-10-01T11:00:01.250Z");
	/**
	 * The recorded start time of every owner process.
	 */
	private static final Instant OWNER_START = Instant.parse("2026-10-01T11:00:00Z");

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
					{"state": "running", "owner_pid": 4001, "owner_started_at": "2026-10-01T11:00:00Z",
					"harness_pid": 4002, "harness_started_at": "2026-10-01T11:00:01.250Z"}""",
					List.of(new RecordedProcess(4002, HARNESS_START))},
				{"""
					{"state": "running", "owner_pid": "4001", "owner_started_at": "2026-10-01T11:00:00Z",
					"harness_pid": "4002", "harness_started_at": "2026-10-01T11:00:01.250Z"}""",
					List.of(new RecordedProcess(4002, HARNESS_START))},
				{"""
					{"state": "running", "owner_pid": 4001, "owner_started_at": "2026-10-01T11:00:00Z",
					"harness_pid": null, "harness_started_at": null}""",
					List.of()},
				{"""
					{"state": "running", "owner_pid": 4001, "owner_started_at": "2026-10-01T11:00:00Z",
					"harness_pid": 4002}""",
					List.of()},
				{"""
					{"state": "running", "owner_pid": 4001,
					"harness_pid": 4002, "harness_started_at": "2026-10-01T11:00:01.250Z"}""",
					List.of()},
				{"""
					{"state": "running", "owner_pid": "41a", "owner_started_at": "2026-10-01T11:00:00Z",
					"harness_pid": 4002, "harness_started_at": "2026-10-01T11:00:01.250Z"}""",
					List.of()},
				{"""
					{"state": "running", "owner_pid": 4001, "owner_started_at": "yesterday",
					"harness_pid": 4002, "harness_started_at": "2026-10-01T11:00:01.250Z"}""",
					List.of()},
				{"""
					{"state": "running", "owner_pid": 4001, "owner_started_at": "2026-10-01T11:00:00Z",
					"harness_pid": -4, "harness_started_at": "2026-10-01T11:00:01.250Z"}""",
					List.of()},
				{"""
					{"state": "completed", "owner_pid": 4001, "owner_started_at": "2026-10-01T11:00:00Z",
					"harness_pid": 4002, "harness_started_at": "2026-10-01T11:00:01.250Z"}""",
					List.of()},
				{"{\"state\": \"running\", \"owner_pid\": 4001", List.of()},
				{"[]", List.of()}
			};
	}

	/**
	 * Verifies that a manifest identifies a harness process only while its run is marked running, its owner process
	 * is identified by an identifier and a start time and no longer exists, and the harness process is identified
	 * the same way.
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
	 * identifier also has its recorded start time: a live process that reuses the identifier is not the owner.
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
				new FakeProcessHandle(7001, OWNER_START.plusSeconds(1), true));
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
			Instant started = current.info().startInstant().orElseThrow();
			writeManifest(runRoot.resolve("claude").resolve("run-1"), """
				{"state": "running", "owner_pid": %d, "owner_started_at": "%s",
				"harness_pid": 6002, "harness_started_at": "2026-10-01T11:00:01.250Z"}""".formatted(current.pid(), started));
			writeManifest(runRoot.resolve("claude").resolve("run-2"), running(5001, 5002));
			LeftoverRuns finder = new LeftoverRuns(new FakeSessionPort(HostLogin.UNKNOWN, null, List.of(runRoot)),
				JsonMapper.builder().build());
			requireThat(finder.find(), "find").isEqualTo(List.of(new RecordedProcess(5002, HARNESS_START)));
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
		ProcessHandleLiveness liveness = ProcessHandleLivenessSecrets.getProcessHandleLivenessAccess().create(
			FakeProcessHandle.table(processes.toArray(FakeProcessHandle[]::new)));
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
			{"state": "running", "owner_pid": %d, "owner_started_at": "2026-10-01T11:00:00Z",
			"harness_pid": %d, "harness_started_at": "2026-10-01T11:00:01.250Z"}""".formatted(ownerPid, harnessPid);
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
