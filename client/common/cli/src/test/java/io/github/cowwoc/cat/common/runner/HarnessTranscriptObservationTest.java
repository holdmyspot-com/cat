package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

/** Tests retained bridge transcript interpretation for exact-run recovery. */
public final class HarnessTranscriptObservationTest
{
	/** Creates the test fixture. */
	public HarnessTranscriptObservationTest()
	{
	}

	/**
	 * Checks that the mapper supplied to the reader preserves the terminal type and response presence.
	 *
	 * @throws IOException if JSON parsing fails
	 */
	@Test
	public void mapperRetainsTerminalDistinctions() throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			JsonNode completed = scope.json().readTree("{\"type\":\"turn-completed\"," +
				"\"assistant_response\":\"done\"}");
			JsonNode interrupted = scope.json().readTree("{\"type\":\"turn-interrupted\"}");
			requireThat(completed.path("type").stringValue(), "completedType").isEqualTo("turn-completed");
			requireThat(interrupted.path("type").stringValue(), "interruptedType").isEqualTo("turn-interrupted");
			requireThat(completed.path("assistant_response").isString(), "responsePresent").isTrue();
			requireThat(interrupted.path("assistant_response").isMissingNode(), "responseAbsent").isTrue();
		}
	}

	/**
	 * Verifies a complete native turn remains distinct from an interrupted turn.
	 *
	 * @throws IOException if test-owned transcript I/O fails
	 */
	@Test
	public void distinguishesTurnOutcomes() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path directory = cleanup.add(Files.createTempDirectory("bridge-transcript-"));
			Path completed = Files.writeString(directory.resolve("completed.jsonl"),
				"{\"type\":\"started\",\"runId\":\"run-001\"}\n" +
				"{\"type\":\"native-event\",\"runId\":\"run-001\",\"event\":\"native\"}\n" +
				"{\"type\":\"turn-completed\",\"runId\":\"run-001\",\"exitCode\":0," +
				"\"error\":\"\",\"assistant_response\":\"done\"}\n" +
				"{\"type\":\"interrupt-raced-completion\",\"runId\":\"run-001\"}\n" +
				"{\"type\":\"completed\",\"runId\":\"run-001\"}\n");
			Path interrupted = Files.writeString(directory.resolve("interrupted.jsonl"),
				"{\"type\":\"started\",\"runId\":\"run-001\"}\n" +
				"{\"type\":\"interrupted\",\"runId\":\"run-001\"}\n" +
				"{\"type\":\"turn-interrupted\",\"runId\":\"run-001\",\"exitCode\":130}\n" +
				"{\"type\":\"completed\",\"runId\":\"run-001\"}\n");

			HarnessTranscriptObservation success = HarnessTranscriptObservation.read(scope.json(), completed,
				"run-001").orElseThrow();
			HarnessTranscriptObservation stop = HarnessTranscriptObservation.read(scope.json(), interrupted,
				"run-001").orElseThrow();
			requireThat(success.state(), "successState").isEqualTo(HarnessTranscriptObservation.SessionState.COMPLETED);
			requireThat(success.turns().getFirst().state(), "successTurn").isEqualTo(
				HarnessTranscriptObservation.TurnState.COMPLETED);
			requireThat(success.turns().getFirst().assistantResponse(), "response").isEqualTo(
				java.util.Optional.of("done"));
			requireThat(stop.turns().getFirst().state(), "stopTurn").isEqualTo(
				HarnessTranscriptObservation.TurnState.INTERRUPTED);
			requireThat(stop.turns().getFirst().assistantResponse(), "stopResponse").isEmpty();
		}
	}

	/**
	 * Verifies absent evidence and a started bridge retain distinct observations.
	 *
	 * @throws IOException if test-owned transcript I/O fails
	 */
	@Test
	public void distinguishesAbsentAndOpen() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path directory = cleanup.add(Files.createTempDirectory("bridge-transcript-"));
			Path transcript = directory.resolve("open.jsonl");
			requireThat(HarnessTranscriptObservation.read(scope.json(), transcript, "run-001"), "absent").isEmpty();
			Files.writeString(transcript, "{\"type\":\"started\",\"runId\":\"run-001\"}\n");
			HarnessTranscriptObservation open = HarnessTranscriptObservation.read(scope.json(), transcript,
				"run-001").orElseThrow();
			requireThat(open.state(), "state").isEqualTo(HarnessTranscriptObservation.SessionState.IN_PROGRESS);
			requireThat(open.turns(), "turns").isEmpty();
		}
	}

	/**
	 * Verifies another run's transcript cannot settle the requested identity.
	 *
	 * @throws IOException if test-owned transcript I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*another run.*")
	public void rejectsAnotherRun() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path directory = cleanup.add(Files.createTempDirectory("bridge-transcript-"));
			Path transcript = Files.writeString(directory.resolve("other.jsonl"),
				"{\"type\":\"started\",\"runId\":\"other-001\"}\n");
			HarnessTranscriptObservation.read(scope.json(), transcript, "run-001");
		}
	}

	/**
	 * Verifies a completed-looking event without its final newline is not authoritative.
	 *
	 * @throws IOException if test-owned transcript I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*incomplete final line.*")
	public void rejectsUnterminatedFinalEvent() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path directory = cleanup.add(Files.createTempDirectory("bridge-transcript-"));
			Path transcript = Files.writeString(directory.resolve("torn.jsonl"),
				"{\"type\":\"started\",\"runId\":\"run-001\"}\n" +
				"{\"type\":\"completed\",\"runId\":\"run-001\"}");
			HarnessTranscriptObservation.read(scope.json(), transcript, "run-001");
		}
	}

	/**
	 * Verifies terminal publication cannot be followed by another bridge event.
	 *
	 * @throws IOException if test-owned transcript I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*after completion.*")
	public void rejectsEventsAfterCompletion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path directory = cleanup.add(Files.createTempDirectory("bridge-transcript-"));
			Path transcript = Files.writeString(directory.resolve("extra.jsonl"),
				"{\"type\":\"started\",\"runId\":\"run-001\"}\n" +
				"{\"type\":\"completed\",\"runId\":\"run-001\"}\n" +
				"{\"type\":\"turn-interrupted\",\"runId\":\"run-001\",\"exitCode\":130}\n");
			HarnessTranscriptObservation.read(scope.json(), transcript, "run-001");
		}
	}

	/**
	 * Verifies a symbolic link cannot substitute another transcript for the owned file.
	 *
	 * @throws IOException if test-owned transcript I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*not a regular file.*")
	public void rejectsLinkedTranscript() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path directory = cleanup.add(Files.createTempDirectory("bridge-transcript-"));
			Path target = Files.writeString(directory.resolve("target.jsonl"),
				"{\"type\":\"started\",\"runId\":\"run-001\"}\n");
			Path link = Files.createSymbolicLink(directory.resolve("linked.jsonl"), target);
			HarnessTranscriptObservation.read(scope.json(), link, "run-001");
		}
	}
}
