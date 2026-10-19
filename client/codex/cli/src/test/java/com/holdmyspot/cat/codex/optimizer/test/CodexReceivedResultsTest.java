package com.holdmyspot.cat.codex.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.codex.optimizer.CodexSessions;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.runner.HarnessCapacity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests that the evidence read from a Codex trial holds the tool results that the trial's agents received, as the
 * rollouts that the runner kept record them.
 */
public final class CodexReceivedResultsTest
{
	/**
	 * Recorded harness events whose content these tests do not depend on.
	 */
	private static final Path EVENTS = Path.of("src/test/resources/optimizer/codex-tool-calls.jsonl");

	/**
	 * Creates the test.
	 */
	public CodexReceivedResultsTest()
	{
	}

	/**
	 * Verifies that a result whose output is a string, an array of text items or an object with content is received
	 * as that text, and that rollout lines that hold no tool result, or are not JSON, add nothing.
	 *
	 * @throws IOException if the trial cannot be written or read
	 */
	@Test
	public void readsEachOutputShape() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-received-"));
			Path transcript = RecordedTrial.write(root, EVENTS, List.of(
				"{\"type\":\"session_meta\",\"payload\":{\"id\":\"thread\"}}",
				"{\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"content\":\"not a result\"}}",
				"not JSON",
				RecordedTrial.toolResult("plain text"),
				"{\"type\":\"response_item\",\"payload\":{\"type\":\"custom_tool_call_output\",\"call_id\":\"call_2\"," +
					"\"output\":[{\"type\":\"input_text\",\"text\":\"first item\"},{\"type\":\"input_image\"}," +
					"{\"type\":\"input_text\",\"text\":\"second item\"}]}}",
				"{\"type\":\"response_item\",\"payload\":{\"type\":\"function_call_output\",\"call_id\":\"call_3\"," +
					"\"output\":{\"content\":\"object content\",\"success\":true}}}"));

			requireThat(sessions(root).readEvidence(transcript).received(), "received").
				isEqualTo(List.of("plain text", "first item", "second item", "object content"));
		}
	}

	/**
	 * Verifies that a result that a tool wrapped in JSON is also received as each string of that JSON, and that a
	 * wrapped result that Codex cut short is also received with its escape sequences replaced.
	 *
	 * @throws IOException if the trial cannot be written or read
	 */
	@Test
	public void decodesWrappedResults() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-received-"));
			String wrapped = "{\"output\":\"line 1\\nline 2\\n\",\"metadata\":{\"exit_code\":0}}";
			String cut = "{\"output\":\"line 1\\nline \\\"2\\\"\\tend\\u00e9";
			Path transcript = RecordedTrial.write(root, EVENTS, List.of(RecordedTrial.toolResult(wrapped),
				RecordedTrial.toolResult(cut)));

			requireThat(sessions(root).readEvidence(transcript).received(), "received").
				isEqualTo(List.of(wrapped, "line 1\nline 2\n", cut, "{\"output\":\"line 1\nline \"2\"\tendé"));
		}
	}

	/**
	 * Verifies that only the rollout files are read: files that do not end with {@code .jsonl} add nothing, and the
	 * rollouts are read in the order of their names.
	 *
	 * @throws IOException if the trial cannot be written or read
	 */
	@Test
	public void readsOnlyRollouts() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-received-"));
			Path transcript = RecordedTrial.write(root, EVENTS, List.of(RecordedTrial.toolResult("root result")));
			Path rollouts = transcript.resolveSibling("rollouts");
			Files.writeString(rollouts.resolve("rollout-2026-10-15T11-00-00-child.jsonl"),
				RecordedTrial.toolResult("child result") + "\n", UTF_8);
			Files.writeString(rollouts.resolve("notes.txt"), RecordedTrial.toolResult("not a rollout") + "\n", UTF_8);

			requireThat(sessions(root).readEvidence(transcript).received(), "received").
				isEqualTo(List.of("child result", "root result"));
		}
	}

	/**
	 * Verifies that evidence cannot be read from a trial whose rollouts were not kept, since what its agents received
	 * would be unknown.
	 *
	 * @throws IOException if the trial cannot be written or its rollouts are missing
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*rollouts were not retained.*")
	public void missingRolloutsFail() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-received-"));
			Path transcript = Files.createDirectories(root.resolve("artifacts")).resolve("transcript.jsonl");
			Files.copy(EVENTS, transcript);

			sessions(root).readEvidence(transcript);
		}
	}

	/**
	 * Returns a port whose run directories are below a directory.
	 *
	 * @param root the directory
	 * @return the port
	 */
	private static CodexSessions sessions(Path root)
	{
		return new CodexSessions(new RunnerSessions.Settings(root, root, List::of, "", false, Map.of(), root,
			HarnessCapacity.unlimited(), Clock.systemUTC()));
	}
}
