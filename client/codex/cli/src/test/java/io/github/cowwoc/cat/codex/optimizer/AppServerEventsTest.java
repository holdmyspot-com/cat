package io.github.cowwoc.cat.codex.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.optimizer.adapter.HarnessEvents;
import io.github.cowwoc.cat.common.optimizer.contract.ToolCall;
import io.github.cowwoc.cat.common.optimizer.port.RateLimitWindow;
import io.github.cowwoc.cat.common.optimizer.port.TokenUsage;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link AppServerEvents} against harness lines that the Codex runner recorded, and against synthetic lines for
 * the shapes that no probe observed.
 */
public final class AppServerEventsTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public AppServerEventsTest()
	{
	}

	/**
	 * Reads the lines of a recorded harness event file.
	 *
	 * @param name the resource name below {@code optimizer}
	 * @return the lines
	 * @throws IOException if the resource cannot be read
	 */
	private static List<String> recorded(String name) throws IOException
	{
		try (InputStream stream = AppServerEventsTest.class.getResourceAsStream("/optimizer/" + name))
		{
			requireThat(stream, "resource").isNotNull();
			return new String(stream.readAllBytes(), UTF_8).lines().toList();
		}
	}

	/**
	 * Returns a {@code thread/started} notification.
	 *
	 * @param id        the thread's identifier
	 * @param model     the thread's default model
	 * @param reasoning the thread's default reasoning effort
	 * @return the notification line
	 */
	private static String threadStarted(String id, String model, String reasoning)
	{
		return "{\"method\":\"thread/started\",\"params\":{\"thread\":{\"id\":\"" + id +
			"\",\"parentThreadId\":null,\"modelProvider\":\"openai\",\"model\":\"" + model +
			"\",\"reasoningEffort\":\"" + reasoning + "\"}}}";
	}

	/**
	 * Returns a {@code thread/settings/updated} notification.
	 *
	 * @param threadId the thread's identifier
	 * @param model    the model that the thread's turn runs
	 * @param effort   the reasoning effort that the thread's turn runs at
	 * @return the notification line
	 */
	private static String threadSettings(String threadId, String model, String effort)
	{
		return "{\"method\":\"thread/settings/updated\",\"params\":{\"threadId\":\"" + threadId +
			"\",\"threadSettings\":{\"model\":\"" + model + "\",\"modelProvider\":\"openai\",\"effort\":\"" +
			effort + "\"}}}";
	}

	/**
	 * Parses recorded lines.
	 *
	 * @param lines the harness event lines
	 * @return the parsed events
	 */
	private static HarnessEvents parse(List<String> lines)
	{
		HarnessEvents events = new AppServerEvents(JSON);
		for (String line : lines)
			events.accept(line);
		return events;
	}

	/**
	 * Verifies that start and completion describe one root command, retaining its shell wrapper and exit code.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void recordsSuccessfulCommandOnce() throws IOException
	{
		// Requirement: R-C9
		HarnessEvents events = parse(recorded("codex-command-success.jsonl"));
		requireThat(events.toolCalls(), "toolCalls").isEqualTo(List.of(new ToolCall("commandExecution",
			"/bin/bash -lc \"ls -la && rg --files -g '\"'!*node_modules*'\"'" +
				" | sed -n '1,160p' && find .. -name AGENTS.md -print\"",
			0)));
	}

	/**
	 * Verifies that a started command has an absent status until the harness reports its completion.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void leavesStartedCommandExitStatusAbsent() throws IOException
	{
		// Requirement: R-C9
		List<String> lines = recorded("codex-tool-calls.jsonl");
		HarnessEvents events = parse(lines.subList(0, 2));
		requireThat(events.toolCalls(), "toolCalls").isEqualTo(
			List.of(new ToolCall("commandExecution", "/bin/bash -lc true", null)));
	}

	/**
	 * Verifies that failed command completions update their calls, without duplicating them or adding child calls.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void recordsOnlyRootToolCalls() throws IOException
	{
		// Requirement: R-C9
		HarnessEvents events = parse(recorded("codex-tool-calls.jsonl"));
		requireThat(events.toolCalls(), "toolCalls").isEqualTo(List.of(
			new ToolCall("commandExecution", "/bin/bash -lc true", 1),
			new ToolCall("commandExecution", "/bin/bash -lc false", 1), new ToolCall("wait", null, null)));
	}

	/**
	 * Verifies that file-change start and completion describe one non-command root call with no exit status.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void recordsFileChangeOnce() throws IOException
	{
		// Requirement: R-C9
		HarnessEvents events = parse(recorded("codex-file-change.jsonl"));
		requireThat(events.toolCalls(), "toolCalls").isEqualTo(List.of(new ToolCall("fileChange", null, null)));
	}

	/**
	 * Verifies that a recorded web search is counted once and has no command or inferred exit status.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void recordsWebSearchOnce() throws IOException
	{
		// Requirement: R-C9
		HarnessEvents events = parse(recorded("codex-web-search.jsonl"));
		requireThat(events.toolCalls(), "toolCalls").isEqualTo(List.of(new ToolCall("webSearch", null, null)));
	}

	/**
	 * Verifies that a dynamic tool keeps its reported name and does not turn its success flag into an exit code.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void recordsDynamicToolWithoutExitStatus() throws IOException
	{
		// Requirement: R-C9
		HarnessEvents events = parse(recorded("codex-dynamic-tool.jsonl"));
		requireThat(events.toolCalls(), "toolCalls").isEqualTo(List.of(new ToolCall("recording_echo", null, null)));
	}

	/**
	 * Verifies that the model and reasoning effort come from the root thread's {@code thread/started} notification.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void readsObservedModelAndReasoning() throws IOException
	{
		// Requirement: R-E16
		HarnessEvents events = parse(recorded("codex-children.jsonl"));
		requireThat(events.model(), "model").isEqualTo("gpt-6-luna");
		requireThat(events.reasoning(), "reasoning").isEqualTo("medium");
	}

	/**
	 * Verifies that the model and reasoning effort of the root thread's settings update replace the defaults that
	 * {@code thread/started} reported, and that a later update replaces an earlier one.
	 */
	@Test
	public void takesModelAndEffortFromSettings()
	{
		// Requirement: R-E16
		HarnessEvents events = parse(List.of(threadStarted("T1", "gpt-6-luna", "medium"),
			threadSettings("T1", "gpt-6-luna", "low")));
		requireThat(events.model(), "model").isEqualTo("gpt-6-luna");
		requireThat(events.reasoning(), "reasoning").isEqualTo("low");

		events = parse(List.of(threadStarted("T1", "gpt-6-luna", "medium"), threadSettings("T1", "gpt-6-luna", "low"),
			threadSettings("T1", "gpt-6-terra", "high")));
		requireThat(events.model(), "model").isEqualTo("gpt-6-terra");
		requireThat(events.reasoning(), "reasoning").isEqualTo("high");
	}

	/**
	 * Verifies that a settings update of a thread other than the root changes neither the model nor the reasoning
	 * effort.
	 */
	@Test
	public void ignoresSettingsOfOtherThreads()
	{
		// Requirement: R-E16
		HarnessEvents events = parse(List.of(threadStarted("T1", "gpt-6-luna", "medium"),
			threadSettings("T2", "gpt-6-terra", "high")));
		requireThat(events.model(), "model").isEqualTo("gpt-6-luna");
		requireThat(events.reasoning(), "reasoning").isEqualTo("medium");
	}

	/**
	 * Verifies that a settings update names the root thread when no root is known yet, and that a blank value keeps
	 * the value that was already observed.
	 */
	@Test
	public void takesRootFromFirstSettingsUpdate()
	{
		// Requirement: R-E16
		HarnessEvents events = parse(List.of(threadSettings("T1", "gpt-6-luna", "low"),
			threadSettings("T2", "gpt-6-terra", "high"), threadSettings("T1", " ", "")));
		requireThat(events.model(), "model").isEqualTo("gpt-6-luna");
		requireThat(events.reasoning(), "reasoning").isEqualTo("low");
	}

	/**
	 * Verifies that the usage is the sum of every thread's latest cumulative total, so that the usage of child threads
	 * counts toward the session.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void sumsLatestTotalOfEveryThread() throws IOException
	{
		// Requirement: R-E16
		HarnessEvents events = parse(recorded("codex-children.jsonl"));
		requireThat(events.usage(), "usage").isEqualTo(new TokenUsage(203_636, 148_992, 1_626, 531));
	}

	/**
	 * Verifies that the window comes from the latest {@code account/rateLimits/updated} notification, with usage as a
	 * fraction, and that an absent {@code secondary} window is not reported.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void readsWindowsFromRateLimitsUpdated() throws IOException
	{
		// Requirement: R-E6
		HarnessEvents events = parse(recorded("codex-children.jsonl"));
		requireThat(events.windows(), "windows").isEqualTo(List.of(
			new RateLimitWindow(0.36, Instant.ofEpochSecond(1_791_046_710L))));
		requireThat(events.rejection(), "rejection").isEqualTo(Optional.empty());
		requireThat(events.error(), "error").isNull();
	}

	/**
	 * Verifies that a session without events reports nothing, and that lines that are not JSON objects are skipped.
	 */
	@Test
	public void reportsNothingForUnrecognizedLines()
	{
		// Requirement: R-E16
		HarnessEvents events = parse(List.of("", "not json", "[1,2]", "{\"method\":\"item/started\"}"));
		requireThat(events.model(), "model").isNull();
		requireThat(events.reasoning(), "reasoning").isNull();
		requireThat(events.usage(), "usage").isNull();
		requireThat(events.windows(), "windows").isEqualTo(List.of());
		requireThat(events.rejection(), "rejection").isEqualTo(Optional.empty());
	}

	/**
	 * Verifies that a reached rate limit is reported as a rejection that resets when the exhausted window does, and
	 * that both windows are reported.
	 */
	@Test
	public void reportsReachedRateLimit()
	{
		// Requirement: R-E6
		HarnessEvents events = parse(List.of("{\"method\":\"account/rateLimits/updated\",\"params\":{\"rateLimits\":" +
			"{\"primary\":{\"usedPercent\":100,\"windowDurationMins\":300,\"resetsAt\":1791000000}," +
			"\"secondary\":{\"usedPercent\":40,\"windowDurationMins\":10080,\"resetsAt\":1791500000}," +
			"\"rateLimitReachedType\":\"primary\"}}}"));
		requireThat(events.rejection(), "rejection").isEqualTo(
			Optional.of(new HarnessEvents.Rejection(Instant.ofEpochSecond(1_791_000_000L))));
		requireThat(events.windows(), "windows").isEqualTo(List.of(
			new RateLimitWindow(1.0, Instant.ofEpochSecond(1_791_000_000L)),
			new RateLimitWindow(0.4, Instant.ofEpochSecond(1_791_500_000L))));
	}

	/**
	 * Verifies that a reached limit that names no exhausted window is a rejection without a reset time.
	 */
	@Test
	public void reportsReachedRateLimitWithoutResetTime()
	{
		// Requirement: R-E6
		HarnessEvents events = parse(List.of("{\"method\":\"account/rateLimits/updated\",\"params\":{\"rateLimits\":" +
			"{\"primary\":null,\"secondary\":null,\"rateLimitReachedType\":\"workspace_member_usage_limit_reached\"}}}"));
		requireThat(events.rejection(), "rejection").isEqualTo(Optional.of(new HarnessEvents.Rejection(null)));
	}

	/**
	 * Verifies that the root thread's turn that did not complete is an error, while a child thread's does not count
	 * and a completed or interrupted turn is not an error.
	 */
	@Test
	public void reportsRootTurnThatDidNotComplete()
	{
		// Requirement: R-E7
		String started = "{\"method\":\"thread/started\",\"params\":{\"thread\":{\"id\":\"root\"," +
			"\"parentThreadId\":null,\"model\":\"gpt-6-luna\",\"reasoningEffort\":\"medium\"}}}";
		HarnessEvents child = parse(List.of(started, "{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"child\"," +
			"\"turn\":{\"status\":\"failed\"}}}"));
		requireThat(child.error(), "child.error").isNull();

		HarnessEvents interrupted = parse(List.of(started, "{\"method\":\"turn/completed\",\"params\":{\"threadId\":" +
			"\"root\",\"turn\":{\"status\":\"interrupted\"}}}"));
		requireThat(interrupted.error(), "interrupted.error").isNull();

		HarnessEvents failed = parse(List.of(started, "{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"root\"," +
			"\"turn\":{\"status\":\"failed\",\"error\":{\"message\":\"secret details\"}}}}"));
		requireThat(failed.error(), "failed.error").isEqualTo("Codex turn finished with status failed");
	}
}
