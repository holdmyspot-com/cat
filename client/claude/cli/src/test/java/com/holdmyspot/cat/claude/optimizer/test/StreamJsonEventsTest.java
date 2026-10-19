package com.holdmyspot.cat.claude.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.claude.optimizer.StreamJsonEvents;
import com.holdmyspot.cat.claude.secrets.StreamJsonEventsSecrets;
import com.holdmyspot.cat.common.optimizer.adapter.HarnessEvents;
import com.holdmyspot.cat.common.optimizer.contract.ToolCall;
import com.holdmyspot.cat.common.optimizer.port.RateLimitWindow;
import com.holdmyspot.cat.common.optimizer.port.TokenUsage;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link StreamJsonEvents} against harness lines that the Claude runner recorded, and against synthetic lines for
 * the shapes that no probe observed.
 */
public final class StreamJsonEventsTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public StreamJsonEventsTest()
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
		try (InputStream stream = StreamJsonEventsTest.class.getResourceAsStream("/optimizer/" + name))
		{
			requireThat(stream, "resource").isNotNull();
			return new String(stream.readAllBytes(), UTF_8).lines().toList();
		}
	}

	/**
	 * Parses recorded lines.
	 *
	 * @param lines the harness event lines
	 * @return the parsed events
	 */
	private static HarnessEvents parse(List<String> lines)
	{
		HarnessEvents events = StreamJsonEventsSecrets.access().newStreamJsonEvents(JSON);
		for (String line : lines)
			events.accept(line);
		return events;
	}

	/**
	 * Verifies that the model is the one that the session's {@code init} record names, not the alias that was
	 * requested, and that an effort the record does not name stays unobserved.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void readsObservedModelFromInit() throws IOException
	{
		// Requirement: R-E16
		HarnessEvents events = parse(recorded("claude-single-turn.jsonl"));
		requireThat(events.model(), "model").isEqualTo("claude-haiku-4-5-20251001");
		requireThat(events.reasoning(), "reasoning").isNull();
	}

	/**
	 * Verifies that an effort that the {@code init} record names is the observed reasoning configuration.
	 */
	@Test
	public void readsObservedReasoningWhenInitNamesIt()
	{
		// Requirement: R-E16
		HarnessEvents events = parse(List.of(
			"{\"type\":\"system\",\"subtype\":\"init\",\"model\":\"claude-haiku-4-5-20251001\",\"effort\":\"medium\"}"));
		requireThat(events.reasoning(), "reasoning").isEqualTo("medium");
	}

	/**
	 * Verifies that both rate-limit windows come from the {@code rate_limit_event} record, with utilization as a
	 * fraction and the reset time as an instant.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void readsWindowsFromRateLimitEvent() throws IOException
	{
		// Requirement: R-E6
		HarnessEvents events = parse(recorded("claude-single-turn.jsonl"));
		requireThat(events.windows(), "windows").isEqualTo(List.of(
			new RateLimitWindow(0.3, Instant.ofEpochSecond(1_790_851_200L)),
			new RateLimitWindow(0.04, Instant.ofEpochSecond(1_791_064_800L))));
		requireThat(events.rejection(), "rejection").isEqualTo(Optional.empty());
	}

	/**
	 * Verifies that the usage of the last {@code result} record is the session's usage. Claude reports the model's
	 * usage cumulatively, including the usage of background children, while the per-reply {@code usage} object of the
	 * follow-up reply is smaller than the first reply's.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void readsCumulativeUsageFromLastResult() throws IOException
	{
		// Requirement: R-E16
		HarnessEvents single = parse(recorded("claude-single-turn.jsonl"));
		requireThat(single.usage(), "single.usage").isEqualTo(new TokenUsage(56_055, 40_099, 565, 397));

		HarnessEvents background = parse(recorded("claude-background-child.jsonl"));
		requireThat(background.usage(), "background.usage").isEqualTo(new TokenUsage(94_995, 69_217, 566, 349));
		requireThat(background.windows().size(), "background.windows.size()").isEqualTo(2);
	}

	/**
	 * Verifies that a session without events reports nothing, and that lines that are not JSON objects are skipped.
	 */
	@Test
	public void reportsNothingForUnrecognizedLines()
	{
		// Requirement: R-E16
		HarnessEvents events = parse(List.of("", "not json", "[1,2]", "{\"type\":\"assistant\"}"));
		requireThat(events.model(), "model").isNull();
		requireThat(events.usage(), "usage").isNull();
		requireThat(events.windows(), "windows").isEqualTo(List.of());
		requireThat(events.rejection(), "rejection").isEqualTo(Optional.empty());
		requireThat(events.error(), "error").isNull();
	}

	/**
	 * Verifies that a rejected rate-limit event is reported with its reset time.
	 */
	@Test
	public void reportsRejectedRateLimit()
	{
		// Requirement: R-E6
		HarnessEvents events = parse(List.of(
			"{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"rejected\",\"resetsAt\":1790851200," +
				"\"rateLimitType\":\"five_hour\",\"unifiedWindows\":{\"five_hour\":{\"utilization\":1.0," +
				"\"resetsAt\":1790851200}}}}"));
		requireThat(events.rejection(), "rejection").isEqualTo(
			Optional.of(new HarnessEvents.Rejection(Instant.ofEpochSecond(1_790_851_200L))));
		requireThat(events.windows(), "windows").isEqualTo(List.of(
			new RateLimitWindow(1.0, Instant.ofEpochSecond(1_790_851_200L))));
	}

	/**
	 * Verifies that a rejection without a reset time is still a rejection.
	 */
	@Test
	public void reportsRejectionWithoutResetTime()
	{
		// Requirement: R-E6
		HarnessEvents events = parse(List.of(
			"{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"rejected\"}}"));
		requireThat(events.rejection(), "rejection").isEqualTo(Optional.of(new HarnessEvents.Rejection(null)));
	}

	/**
	 * Verifies that an error result is a rate-limit rejection when its status is 429, a refusal when its status is 403,
	 * and otherwise an error that names the status and terminal reason without including the result text.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void classifiesErrorResults() throws IOException
	{
		// Requirement: R-E7
		HarnessEvents limited = parse(List.of(
			"{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"api_error_status\":429," +
				"\"terminal_reason\":\"completed\",\"result\":\"limit reached\"}"));
		requireThat(limited.rejection().isPresent(), "limited.rejection.isPresent()").isTrue();
		requireThat(limited.refused(), "limited.refused").isFalse();
		requireThat(limited.error(), "limited.error").isNull();

		HarnessEvents refused = parse(List.of(
			"{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"api_error_status\":403," +
				"\"terminal_reason\":\"model_error\",\"result\":\"forbidden\"}"));
		requireThat(refused.refused(), "refused.refused").isTrue();
		requireThat(refused.rejection().isPresent(), "refused.rejection.isPresent()").isFalse();
		requireThat(refused.error(), "refused.error").isNull();

		HarnessEvents failed = parse(List.of(
			"{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"api_error_status\":500," +
				"\"terminal_reason\":\"model_error\",\"result\":\"secret details\"}"));
		requireThat(failed.rejection().isPresent(), "failed.rejection.isPresent()").isFalse();
		requireThat(failed.refused(), "failed.refused").isFalse();
		requireThat(failed.error(), "failed.error").isEqualTo(
			"Claude reported an error result (api_error_status=500, terminal_reason=model_error)");

		HarnessEvents fine = parse(recorded("claude-single-turn.jsonl"));
		requireThat(fine.error(), "fine.error").isNull();
	}

	/**
	 * Verifies that the parser selects tool-use blocks and preserves their invocation order.
	 *
	 * @throws IOException if the recording cannot be read
	 */
	@Test
	public void recordsCallsInInvocationOrder() throws IOException
	{
		// Requirement: R-C9
		HarnessEvents events = parse(recorded("claude-tool-calls.jsonl"));

		requireThat(events.toolCalls(), "toolCalls").isEqualTo(List.of(
			new ToolCall("Bash", "true", 0), new ToolCall("Bash", "false", 1),
			new ToolCall("Read", null, null)));
	}

	/**
	 * Verifies that a command Claude reports as successful, with no interpretation of its exit code, exited with 0.
	 *
	 * @throws IOException if the recording cannot be read
	 */
	@Test
	public void readsZeroForSuccessfulCommand() throws IOException
	{
		// Requirement: R-C9
		HarnessEvents events = parse(recorded("claude-exit-statuses.jsonl"));

		requireThat(events.toolCalls().getFirst(), "successfulCommand").isEqualTo(new ToolCall("Bash", "true", 0));
	}

	/**
	 * Verifies that a failed command's status comes from the exit code that leads Claude's error result.
	 *
	 * @throws IOException if the recording cannot be read
	 */
	@Test
	public void readsExitCodeOfFailedCommand() throws IOException
	{
		// Requirement: R-C9
		HarnessEvents events = parse(recorded("claude-exit-statuses.jsonl"));

		requireThat(events.toolCalls().subList(1, 3), "failedCommands").isEqualTo(List.of(
			new ToolCall("Bash", "false", 1), new ToolCall("Bash", "sh -c \"exit 42\"", 42)));
	}

	/**
	 * Verifies that an exit code of 1 that Claude interprets as success, such as {@code grep} finding no match, is still
	 * recorded as 1 rather than 0.
	 *
	 * @throws IOException if the recording cannot be read
	 */
	@Test
	public void readsOneWhenClaudeInterpretsTheExitCode() throws IOException
	{
		// Requirement: R-C9
		HarnessEvents events = parse(recorded("claude-exit-statuses.jsonl"));

		requireThat(events.toolCalls().get(3), "interpretedCommand").
			isEqualTo(new ToolCall("Bash", "grep zzz sample.txt", 1));
	}

	/**
	 * Verifies that a background command, whose status is unknown when Claude returns the tool result, and a command
	 * that Claude never ran have no exit status.
	 *
	 * @throws IOException if the recording cannot be read
	 */
	@Test
	public void leavesStatusOfUnfinishedCommandsNull() throws IOException
	{
		// Requirement: R-C9
		HarnessEvents events = parse(recorded("claude-exit-statuses.jsonl"));

		requireThat(events.toolCalls().subList(4, 6), "unfinishedCommands").isEqualTo(List.of(
			new ToolCall("Bash", "sh -c \"sleep 1; exit 4\"", null), new ToolCall("Bash", "rm -f missing.txt", null)));
	}

	/**
	 * Verifies that an error result whose text does not start with the recorded exit-code form leaves the status
	 * unreported, so that a check that needs it cannot be decided.
	 */
	@Test
	public void leavesUnrecognizedErrorStatusNull()
	{
		// Requirement: R-C9
		HarnessEvents events = parse(List.of(
			"{\"type\":\"assistant\",\"parent_tool_use_id\":null,\"message\":{\"content\":[{\"type\":\"tool_use\"," +
				"\"id\":\"toolu_1\",\"name\":\"Bash\",\"input\":{\"command\":\"make\"}}]}}",
			"{\"type\":\"user\",\"parent_tool_use_id\":null,\"message\":{\"content\":[{\"type\":\"tool_result\"," +
				"\"tool_use_id\":\"toolu_1\",\"is_error\":true,\"content\":\"Exited with status 2\"}]}," +
				"\"tool_use_result\":\"Error: Exited with status 2\"}"));

		requireThat(events.toolCalls(), "toolCalls").isEqualTo(List.of(new ToolCall("Bash", "make", null)));
	}

	/**
	 * Verifies that the root's Agent call counts while the child's Bash call does not.
	 *
	 * @throws IOException if the recording cannot be read
	 */
	@Test
	public void excludesChildToolCalls() throws IOException
	{
		// Requirement: R-C9
		HarnessEvents events = parse(recorded("claude-child-tool-calls.jsonl"));

		requireThat(events.toolCalls(), "toolCalls").isEqualTo(List.of(new ToolCall("Agent", null, null)));
	}

	/**
	 * Returns a hook record.
	 *
	 * @param subtype {@code hook_started} or {@code hook_response}
	 * @param id the hook's identifier
	 * @return the record line
	 */
	private static String hook(String subtype, String id)
	{
		return "{\"type\":\"system\",\"subtype\":\"" + subtype + "\",\"hook_id\":\"" + id +
			"\",\"hook_name\":\"UserPromptSubmit:check\",\"hook_event\":\"UserPromptSubmit\"}";
	}

	/**
	 * Returns the records that show the model working on a turn.
	 *
	 * @return the record line
	 */
	@DataProvider
	public Object[][] outputRecords()
	{
		return new Object[][]
			{
				{"{\"type\":\"assistant\",\"parent_tool_use_id\":null,\"message\":{\"id\":\"msg_1\",\"content\":" +
					"[{\"type\":\"text\",\"text\":\"Hello\"}]}}"},
				{"{\"type\":\"assistant\",\"parent_tool_use_id\":null,\"message\":{\"id\":\"msg_1\",\"content\":" +
					"[{\"type\":\"thinking\",\"thinking\":\"Hmm\"}]}}"},
				{"{\"type\":\"assistant\",\"parent_tool_use_id\":null,\"message\":{\"id\":\"msg_1\",\"content\":" +
					"[{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"Bash\",\"input\":{\"command\":\"ls\"}}]}}"},
				{"{\"type\":\"assistant\",\"parent_tool_use_id\":null,\"message\":{\"id\":\"msg_1\",\"content\":[]," +
					"\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}}"},
				{"{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"delta\":" +
					"{\"type\":\"text_delta\",\"text\":\"Hel\"}}}"},
				{"{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"\",\"modelUsage\":" +
					"{\"claude-haiku-4-5-20251001\":{\"inputTokens\":10,\"outputTokens\":2}}}"}
			};
	}

	/**
	 * Verifies that each kind of model output sets the turn's output state.
	 *
	 * @param line the record
	 */
	@Test(dataProvider = "outputRecords")
	public void recordsModelOutput(String line)
	{
		// Requirement: R-E13
		HarnessEvents events = parse(List.of());
		requireThat(events.outputProduced(), "before").isFalse();

		events.accept(line);

		requireThat(events.outputProduced(), "after").isTrue();
	}

	/**
	 * Returns the records that the model did not produce.
	 *
	 * @return the record line
	 */
	@DataProvider
	public Object[][] recordsWithoutOutput()
	{
		return new Object[][]
			{
				{"{\"type\":\"user\",\"parent_tool_use_id\":null,\"message\":{\"role\":\"user\",\"content\":" +
					"[{\"type\":\"text\",\"text\":\"Do the task\"}]}}"},
				{"{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"allowed\",\"resetsAt\":1790851200}}"},
				{"{\"type\":\"system\",\"subtype\":\"init\",\"model\":\"claude-haiku-4-5-20251001\"}"},
				{"{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":true,\"modelUsage\":{}}"},
				{hook("hook_started", "hook-1")},
				{hook("hook_response", "hook-1")}
			};
	}

	/**
	 * Verifies that a user message, a rate-limit update, a status record and a hook are not model output.
	 *
	 * @param line the record
	 */
	@Test(dataProvider = "recordsWithoutOutput")
	public void ignoresNonModelRecords(String line)
	{
		// Requirement: R-E13
		HarnessEvents events = parse(List.of(line));

		requireThat(events.outputProduced(), "outputProduced").isFalse();
	}

	/**
	 * Verifies that a new turn starts with no model output while an earlier turn's output stays out of it.
	 */
	@Test
	public void clearsOutputWhenATurnBegins()
	{
		// Requirement: R-E13
		HarnessEvents events = parse(List.of("{\"type\":\"assistant\",\"parent_tool_use_id\":null,\"message\":" +
			"{\"id\":\"msg_1\",\"content\":[{\"type\":\"text\",\"text\":\"Hello\"}]}}"));
		requireThat(events.outputProduced(), "first").isTrue();

		events.startTurn();

		requireThat(events.outputProduced(), "second").isFalse();
	}

	/**
	 * Verifies that a hook is running from its start until its response, and that one hook's response leaves another
	 * running.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void tracksRunningHooks() throws IOException
	{
		// Requirement: R-E13
		HarnessEvents events = parse(List.of());
		requireThat(events.hookRunning(), "initial").isFalse();

		events.accept(hook("hook_started", "first"));
		requireThat(events.hookRunning(), "firstStarted").isTrue();

		events.accept(hook("hook_started", "second"));
		events.accept(hook("hook_response", "first"));
		requireThat(events.hookRunning(), "firstResponded").isTrue();

		events.accept(hook("hook_response", "second"));
		requireThat(events.hookRunning(), "secondResponded").isFalse();

		requireThat(parse(recorded("claude-single-turn.jsonl")).hookRunning(), "recorded").isFalse();
	}

	/**
	 * Verifies that a hook that is still running when a new turn begins is still running.
	 */
	@Test
	public void keepsRunningHookAcrossTurns()
	{
		// Requirement: R-E13
		HarnessEvents events = parse(List.of(hook("hook_started", "slow")));

		events.startTurn();

		requireThat(events.hookRunning(), "hookRunning").isTrue();
	}
}
