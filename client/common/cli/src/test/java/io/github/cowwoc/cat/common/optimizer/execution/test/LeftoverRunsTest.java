package io.github.cowwoc.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.execution.LeftoverRuns.RecordedProcess;
import io.github.cowwoc.cat.common.optimizer.execution.LeftoverRuns;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.optimizer.port.test.FakeSessionPort;
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
 */
public final class LeftoverRunsTest
{
	/**
	 * The recorded start time of every harness process.
	 */
	private static final Instant HARNESS_START = Instant.parse("2026-10-01T11:00:01.250Z");

	/**
	 * Creates the test.
	 */
	public LeftoverRunsTest()
	{
	}

	/**
	 * Returns run manifests and the processes that each identifies. The owner process of each manifest recorded as
	 * running no longer exists, because no process started at the recorded time.
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
			requireThat(finder(runRoot).find(), "find").isEqualTo(expected);
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
			requireThat(finder(runRoot, heldOutRunRoot).find(), "find").isEqualTo(
				List.of(new RecordedProcess(4002, HARNESS_START), new RecordedProcess(5002, HARNESS_START)));
		}
	}

	/**
	 * Verifies that a run whose owner process is alive, which belongs to a running driver, is left alone while a run
	 * of a dead owner beside it is found.
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
			ProcessHandle current = ProcessHandle.current();
			Instant started = current.info().startInstant().orElseThrow();
			String live = """
				{"state": "running", "owner_pid": %d, "owner_started_at": "%s",
				"harness_pid": 6002, "harness_started_at": "2026-10-01T11:00:01.250Z"}""".formatted(current.pid(), started);
			writeManifest(runRoot.resolve("claude").resolve("run-1"), live);
			writeManifest(heldOutRunRoot.resolve("claude").resolve("run-2"), live);
			writeManifest(heldOutRunRoot.resolve("claude").resolve("run-3"), running(5001, 5002));
			requireThat(finder(runRoot, heldOutRunRoot).find(), "find").isEqualTo(
				List.of(new RecordedProcess(5002, HARNESS_START)));
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
			requireThat(finder().find(), "withoutRoots").isEmpty();
			requireThat(finder(directory.resolve("missing"), directory.resolve("missing-held-out")).find(),
				"withMissingRoots").isEmpty();
		}
	}

	/**
	 * Creates a finder over the run roots of a session port.
	 *
	 * @param runRoots the run roots that the port declares
	 * @return the finder
	 */
	private static LeftoverRuns finder(Path... runRoots)
	{
		return new LeftoverRuns(new FakeSessionPort(HostLogin.UNKNOWN, null, List.of(runRoots)),
			JsonMapper.builder().build());
	}

	/**
	 * Returns the manifest of a running run whose owner no longer exists.
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
