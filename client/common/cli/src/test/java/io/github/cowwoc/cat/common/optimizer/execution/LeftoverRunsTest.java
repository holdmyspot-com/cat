package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.execution.LeftoverRuns.RecordedProcess;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Covers how the processes of leftover runner runs are read from their manifests.
 */
public final class LeftoverRunsTest
{
	/**
	 * The recorded start time of every owner process.
	 */
	private static final Instant OWNER_START = Instant.parse("2026-10-01T11:00:00Z");
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
	 * Returns run manifests and the processes that each identifies.
	 *
	 * @return the cases
	 */
	@DataProvider
	public static Object[][] manifests()
	{
		return new Object[][]
			{
				{"""
					{"state": "running", "ownerPid": 4001, "ownerStartedAt": "2026-10-01T11:00:00Z",
					"harnessPid": 4002, "harnessStartedAt": "2026-10-01T11:00:01.250Z"}""",
					List.of(new RecordedProcess(4001, OWNER_START), new RecordedProcess(4002, HARNESS_START))},
				{"""
					{"state": "running", "ownerPid": "4001", "ownerStartedAt": "2026-10-01T11:00:00Z",
					"harnessPid": null, "harnessStartedAt": null}""",
					List.of(new RecordedProcess(4001, OWNER_START))},
				{"""
					{"state": "running", "ownerPid": 4001,
					"harnessPid": 4002, "harnessStartedAt": "2026-10-01T11:00:01.250Z"}""",
					List.of(new RecordedProcess(4002, HARNESS_START))},
				{"""
					{"state": "running", "ownerPid": "41a", "ownerStartedAt": "2026-10-01T11:00:00Z",
					"harnessPid": -4, "harnessStartedAt": "2026-10-01T11:00:01.250Z"}""",
					List.of()},
				{"""
					{"state": "running", "ownerPid": 4001, "ownerStartedAt": "yesterday"}""",
					List.of()},
				{"""
					{"state": "completed", "ownerPid": 4001, "ownerStartedAt": "2026-10-01T11:00:00Z"}""",
					List.of()},
				{"{\"state\": \"running\", \"ownerPid\": 4001", List.of()},
				{"[]", List.of()}
			};
	}

	/**
	 * Verifies that a manifest identifies a process only while its run is marked running and both the process's
	 * identifier and its start time are recorded and well formed.
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
			Path run = runRoot.resolve("claude").resolve("run-1");
			Files.createDirectories(run);
			Files.writeString(run.resolve("run.json"), manifest);
			requireThat(new LeftoverRuns(runRoot, JsonMapper.builder().build()).find(), "find").isEqualTo(expected);
		}
	}

	/**
	 * Verifies that a campaign without a run root has no leftover runs.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void missingRunRoot() throws IOException
	{
		// Requirement: R-E11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path runRoot = cleanup.add(Files.createTempDirectory("runs")).resolve("missing");
			requireThat(new LeftoverRuns(runRoot, JsonMapper.builder().build()).find(), "find").isEmpty();
		}
	}
}
