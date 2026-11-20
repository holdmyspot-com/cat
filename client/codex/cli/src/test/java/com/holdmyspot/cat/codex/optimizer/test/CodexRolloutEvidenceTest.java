package com.holdmyspot.cat.codex.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.optimizer.CodexSessions;
import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.NestedSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.core.JacksonException;

/**
 * Verifies that Codex rejects malformed retained rollout records with their source location while preserving valid
 * unrelated records and tool-result text.
 */
public final class CodexRolloutEvidenceTest
{
	/**
	 * A valid rollout header that identifies its owner.
	 */
	private static final String HEADER = "{\"type\":\"session_meta\",\"payload\":{\"id\":\"thread\"}}";

	/**
	 * Creates the test.
	 */
	public CodexRolloutEvidenceTest()
	{
	}

	/**
	 * Returns malformed records in each retained rollout record position.
	 *
	 * @return the record category, malformed JSON, and expected physical line
	 */
	@DataProvider
	public Object[][] malformedRecords()
	{
		return new Object[][] {
			{"metadata", "{\"type\":\"session_meta\",\"payload\":", 1},
			{"message", "{\"type\":\"response_item\",\"payload\":{\"type\":\"agent_message\",", 3},
			{"command", "{\"type\":\"event_msg\",\"payload\":{\"type\":\"item_completed\",", 3},
			{"tool result", "{\"type\":\"response_item\",\"payload\":{\"type\":\"function_call_output\",", 3}
		};
	}

	/**
	 * Verifies that reading evidence rejects malformed metadata, message, command, and tool-result records with the
	 * rollout's absolute filename, physical line, Jackson detail, and Jackson cause.
	 *
	 * @param category the position of the malformed record
	 * @param malformed the malformed record text
	 * @param expectedLine the physical line number
	 * @throws IOException if the trial files cannot be written
	 */
	@Test(dataProvider = "malformedRecords")
	public void rejectsMalformedRecord(String category, String malformed, int expectedLine)
		throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-rollout-malformed-"));
			List<String> lines;
			if (category.equals("metadata"))
				lines = List.of(malformed);
			else
				lines = List.of(HEADER, "", malformed);
			Path transcript = writeTrial(root, List.of(new Rollout("rollout-a.jsonl", lines)));
			Path rollout = transcript.resolveSibling(NestedSession.SESSION_TRANSCRIPTS).resolve("rollout-a.jsonl");

			IOException failure = readFailure(jvm, root, transcript);

			requireThat(failure.getMessage(), "failure.message").contains(rollout.toAbsolutePath() + ":" + expectedLine +
				":");
			requireThat(failure.getMessage(), "failure.message").contains(failure.getCause().getMessage());
			requireThat(failure.getCause(), "failure.cause").isInstanceOf(JacksonException.class);
		}
	}

	/**
	 * Verifies that reading evidence reports the filename of the second rollout when that file contains malformed
	 * JSON after a blank physical line.
	 *
	 * @throws IOException if the trial files cannot be written
	 */
	@Test
	public void reportsMalformedRecordInSecondRollout() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-rollout-second-file-"));
			Path transcript = writeTrial(root, List.of(new Rollout("rollout-a.jsonl", List.of(HEADER)),
				new Rollout("rollout-z.jsonl", List.of(HEADER, "", "{"))));
			Path rollout = transcript.resolveSibling(NestedSession.SESSION_TRANSCRIPTS).resolve("rollout-z.jsonl");

			IOException failure = readFailure(jvm, root, transcript);

			requireThat(failure.getMessage(), "failure.message").contains(rollout.toAbsolutePath() + ":3:");
			requireThat(failure.getCause(), "failure.cause").isInstanceOf(JacksonException.class);
		}
	}

	/**
	 * Verifies that a valid final record without a newline and a well-formed unrelated event type remain readable.
	 *
	 * @throws IOException if the trial files cannot be written or read
	 */
	@Test
	public void acceptsValidUnrelatedFinalWithoutNewline() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-rollout-valid-final-"));
			String unrelated = "{\"type\":\"unrelated_event\",\"payload\":{\"optional\":true}}";
			Path transcript = writeTrial(root, List.of(new Rollout("rollout-a.jsonl", List.of(HEADER, unrelated))));

			SessionEvidence evidence = sessions(jvm, root).readEvidence(transcript);

			requireThat(evidence.received(), "received").isEmpty();
		}
	}

	/**
	 * Verifies that malformed JSON in an unterminated final record fails with its physical line number.
	 *
	 * @throws IOException if the trial files cannot be written
	 */
	@Test
	public void rejectsMalformedFinalWithoutNewline() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-rollout-bad-final-"));
			Path transcript = writeTrial(root, List.of(new Rollout("rollout-a.jsonl", List.of(HEADER, "", "{"))));
			Path rollout = transcript.resolveSibling(NestedSession.SESSION_TRANSCRIPTS).resolve("rollout-a.jsonl");

			IOException failure = readFailure(jvm, root, transcript);

			requireThat(failure.getMessage(), "failure.message").contains(rollout.toAbsolutePath() + ":3:");
			requireThat(failure.getCause(), "failure.cause").isInstanceOf(JacksonException.class);
		}
	}

	/**
	 * Verifies that a tool result whose body resembles malformed JSON remains ordinary received text.
	 *
	 * @throws IOException if the trial files cannot be written or read
	 */
	@Test
	public void preservesMalformedLookingToolBody() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-rollout-tool-body-"));
			String body = "{\"output\": ";
			Path transcript = writeTrial(root, List.of(new Rollout("rollout-a.jsonl", List.of(HEADER,
				RecordedTrial.toolResult(body)))));

			requireThat(sessions(jvm, root).readEvidence(transcript).received(), "received").isEqualTo(List.of(body));
		}
	}

	/**
	 * Creates the transcript and rollout files that CodexSessions reads as a retained trial.
	 *
	 * @param root the trial directory
	 * @param rollouts the rollout files
	 * @return the transcript path
	 * @throws IOException if a trial file cannot be written
	 */
	private static Path writeTrial(Path root, List<Rollout> rollouts) throws IOException
	{
		Path artifacts = Files.createDirectories(root.resolve("artifacts"));
		Path transcript = Files.copy(Path.of("src/test/resources/optimizer/codex-tool-calls.jsonl"),
			artifacts.resolve("transcript.jsonl"));
		Path directory = Files.createDirectories(artifacts.resolve(NestedSession.SESSION_TRANSCRIPTS));
		for (Rollout rollout : rollouts)
		{
			Files.writeString(directory.resolve(rollout.fileName()), String.join("\n", rollout.lines()));
		}
		return transcript;
	}

	/**
	 * Reads retained evidence and returns its IOException, failing if the evidence was accepted.
	 *
	 * @param jvm the borrowed process scope
	 * @param root the trial directory
	 * @param transcript the transcript path
	 * @return the malformed-record failure
	 * @throws IOException if the evidence read fails for another checked reason
	 */
	private static IOException readFailure(JvmScope jvm, Path root, Path transcript) throws IOException
	{
		try
		{
			sessions(jvm, root).readEvidence(transcript);
		}
		catch (IOException e)
		{
			return e;
		}
		throw new AssertionError("CodexSessions accepted malformed retained JSON");
	}

	/**
	 * Creates a Codex port rooted at a directory.
	 *
	 * @param jvm the borrowed process scope
	 * @param root the directory
	 * @return the port
	 */
	private static CodexSessions sessions(JvmScope jvm, Path root)
	{
		return new CodexSessions(new RunnerSessions.Settings(jvm, root, root, List::of, false, Map.of(), root,
			HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer()));
	}

	/**
	 * A rollout file and its JSONL records.
	 *
	 * @param fileName the rollout filename
	 * @param lines the records to write
	 */
	private record Rollout(String fileName, List<String> lines)
	{
	}
}
