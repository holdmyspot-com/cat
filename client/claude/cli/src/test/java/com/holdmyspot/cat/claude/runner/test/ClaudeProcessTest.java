package com.holdmyspot.cat.claude.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.claude.runner.ClaudeProcess.LineEffect;
import com.holdmyspot.cat.claude.runner.ClaudeProcess.TurnBoundary;
import com.holdmyspot.cat.claude.runner.ClaudeProcess;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessProcessResult;
import com.holdmyspot.cat.common.runner.HarnessSessionTranscripts;
import com.holdmyspot.cat.common.runner.TerminalHarnessTurnFailure;
import com.holdmyspot.cat.common.runner.TurnMode;
import com.holdmyspot.cat.common.runner.fakes.test.FakeHarnessCli;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests how {@link ClaudeProcess} decides that a turn has ended. Each test plays the Claude CLI with a
 * {@link FakeHarnessCli} that prints stream-json lines shaped like those Claude Code 2.1.286 printed for a root
 * session that launched background child agents. The recorded timelines under {@code runner/turn-boundary} are trimmed
 * copies of the 2026-10-01 probes. The timelines whose names start with {@code s-} were recorded with
 * {@code CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS=1}, so Claude printed its {@code session_state_changed} events; the
 * others were recorded without it and show a Claude that does not report its session state.
 */
public final class ClaudeProcessTest
{
	/**
	 * Claude starts working on the root session's queued work.
	 */
	private static final String STATE_RUNNING = "{\"type\":\"system\",\"subtype\":\"session_state_changed\"," +
		"\"state\":\"running\",\"session_id\":\"s\"}";
	/**
	 * Claude has no queued work left for the root session.
	 */
	private static final String STATE_IDLE = "{\"type\":\"system\",\"subtype\":\"session_state_changed\"," +
		"\"state\":\"idle\",\"session_id\":\"s\"}";
	/**
	 * The first line Claude prints for each root reply, including a follow-up reply that Claude starts on its own.
	 */
	private static final String INIT = "{\"type\":\"system\",\"subtype\":\"init\"," +
		"\"claude_code_version\":\"2.1.286\",\"session_id\":\"s\"}";
	/**
	 * The start of one root model call.
	 */
	private static final String MESSAGE_START = "{\"type\":\"stream_event\",\"event\":{\"type\":\"message_start\"}," +
		"\"parent_tool_use_id\":null,\"session_id\":\"s\"}";
	/**
	 * The root agent launches one background child agent.
	 */
	private static final String CHILD_LAUNCH = "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\"," +
		"\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_root\",\"name\":\"Agent\",\"input\":{\"prompt\":" +
		"\"Run sleep 20, then reply DONE.\",\"run_in_background\":true}}]},\"parent_tool_use_id\":null," +
		"\"session_id\":\"s\"}";
	private static final String CHILD_RUNNING = "{\"type\":\"system\",\"subtype\":\"background_tasks_changed\"," +
		"\"tasks\":[{\"task_id\":\"child\",\"task_type\":\"local_agent\",\"description\":\"Run sleep 20\"}]," +
		"\"session_id\":\"s\"}";
	private static final String CHILD_STARTED = "{\"type\":\"system\",\"subtype\":\"task_started\"," +
		"\"task_id\":\"child\",\"tool_use_id\":\"toolu_root\",\"is_backgrounded\":true,\"spawn_depth\":1," +
		"\"task_type\":\"local_agent\",\"session_id\":\"s\"}";
	private static final String ROOT_REPLY = "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\"," +
		"\"content\":[{\"type\":\"text\",\"text\":\"LAUNCHED\"}]},\"parent_tool_use_id\":null,\"session_id\":\"s\"}";
	private static final String ROOT_RESULT = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false," +
		"\"result\":\"LAUNCHED\",\"result_index\":0,\"session_id\":\"s\"}";
	/**
	 * The background child's own final message, which belongs to the child rather than the root session.
	 */
	private static final String CHILD_REPLY = "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\"," +
		"\"content\":[{\"type\":\"text\",\"text\":\"DONE\"}]},\"parent_tool_use_id\":\"toolu_root\"," +
		"\"session_id\":\"s\"}";
	private static final String CHILDREN_FINISHED = "{\"type\":\"system\",\"subtype\":\"background_tasks_changed\"," +
		"\"tasks\":[],\"session_id\":\"s\"}";
	private static final String CHILD_UPDATED = "{\"type\":\"system\",\"subtype\":\"task_updated\"," +
		"\"task_id\":\"child\",\"patch\":{\"status\":\"completed\"},\"session_id\":\"s\"}";
	private static final String CHILD_NOTIFICATION = "{\"type\":\"system\",\"subtype\":\"task_notification\"," +
		"\"task_id\":\"child\",\"tool_use_id\":\"toolu_root\",\"status\":\"completed\",\"summary\":\"DONE\"," +
		"\"session_id\":\"s\"}";
	private static final String FOLLOW_UP_REPLY = "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\"," +
		"\"content\":[{\"type\":\"text\",\"text\":\"FINISHED\"}]},\"parent_tool_use_id\":null,\"session_id\":\"s\"}";
	/**
	 * The result of a follow-up reply that Claude started on its own after a completion notice.
	 */
	private static final String FOLLOW_UP_RESULT = "{\"type\":\"result\",\"subtype\":\"success\"," +
		"\"is_error\":false,\"result\":\"FINISHED\",\"origin\":{\"kind\":\"task-notification\"}," +
		"\"result_index\":1,\"session_id\":\"s\"}";
	/**
	 * The reply to a second user message.
	 */
	private static final String SECOND_REPLY = "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\"," +
		"\"content\":[{\"type\":\"text\",\"text\":\"TWO\"}]},\"parent_tool_use_id\":null,\"session_id\":\"s\"}";
	private static final String SECOND_RESULT = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false," +
		"\"result\":\"TWO\",\"session_id\":\"s\"}";
	/**
	 * The text of the error result that Claude Code 2.1.310 printed when it could not reach the model service.
	 */
	private static final String CONNECTION_REFUSED = "API Error: Connection refused — a firewall or proxy may be " +
		"blocking it (ECONNREFUSED)";
	/**
	 * The root's reply that Claude Code 2.1.310 printed after its retries of an unreachable model service ran out,
	 * trimmed from a recorded 2026-10-28 runner transcript. Claude prints its own error text as the reply, so the
	 * text alone does not reveal that the model never ran.
	 */
	private static final String ERROR_REPLY = "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\"," +
		"\"content\":[{\"type\":\"text\",\"text\":\"" + CONNECTION_REFUSED + "\"}]},\"parent_tool_use_id\":null," +
		"\"session_id\":\"s\"}";
	/**
	 * The root's error result that followed {@link #ERROR_REPLY} in the same recording; Claude then reported that it
	 * was idle.
	 */
	private static final String ERROR_RESULT = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true," +
		"\"api_error_status\":null,\"terminal_reason\":\"api_error\",\"num_turns\":1,\"result\":\"" +
		CONNECTION_REFUSED + "\",\"result_index\":0,\"session_id\":\"s\"}";

	/**
	 * Creates the test fixture.
	 */
	public ClaudeProcessTest()
	{
	}

	/**
	 * Reads a recorded timeline.
	 *
	 * @param name the file name below {@code runner/turn-boundary}
	 * @return the timeline's lines in the order Claude printed them
	 * @throws IOException if the resource cannot be read
	 */
	private static List<String> recorded(String name) throws IOException
	{
		return recorded("turn-boundary", name);
	}

	/**
	 * Reads a recorded timeline.
	 *
	 * @param directory the directory below {@code runner} that holds the recording
	 * @param name      the file name in {@code directory}
	 * @return the timeline's lines in the order Claude printed them
	 * @throws IOException if the resource cannot be read
	 */
	private static List<String> recorded(String directory, String name) throws IOException
	{
		try (InputStream stream = ClaudeProcessTest.class.getResourceAsStream("/runner/" + directory + "/" + name))
		{
			requireThat(stream, "resource").isNotNull();
			return new String(stream.readAllBytes(), UTF_8).lines().toList();
		}
	}

	/**
	 * Returns the text of the last result line in a timeline.
	 *
	 * @param lines a timeline
	 * @return the last result's text
	 */
	private static String lastResult(List<String> lines)
	{
		JsonMapper json = JsonMapper.builder().build();
		String text = "";
		for (String line : lines)
		{
			if (line.contains("\"type\":\"result\""))
				text = json.readTree(line).path("result").asString();
		}
		return text;
	}

	/**
	 * Sends one turn to a Claude that prints a timeline, and verifies the turn relays the whole timeline, replies with
	 * its last result and reports no diagnostic.
	 *
	 * @param lines the timeline
	 * @throws IOException if the turn fails
	 */
	private static void assertTurnSpans(List<String> lines) throws IOException
	{
		FakeHarnessCli claude = new FakeHarnessCli(_ ->
		{
		});
		try (ClaudeProcess process = new ClaudeProcess(new TestHarnessRunnerScope(), claude))
		{
			claude.emit(lines.toArray(String[]::new));
			List<String> relayed = new ArrayList<>();

			HarnessProcessResult result = process.sendTurn("Launch a background child.", relayed::add);

			requireThat(result.completed(), "completed").isTrue();
			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo(lastResult(lines));
			requireThat(result.standardError(), "standardError").isEmpty();
			requireThat(relayed, "relayed").isEqualTo(lines);
		}
	}

	/**
	 * Verifies recorded root text before a tool is preserved and the terminal result does not duplicate final text.
	 *
	 * @throws IOException if the recording or turn cannot be read
	 */
	// Requirement: R-C12
	@Test
	public void retainsRecordedAssistantMessages() throws IOException
	{
		FakeHarnessCli claude = new FakeHarnessCli(_ ->
		{
		});
		try (ClaudeProcess process = new ClaudeProcess(new TestHarnessRunnerScope(), claude))
		{
			claude.emit(STATE_RUNNING);
			claude.emit(recorded("assistant-messages.jsonl").toArray(String[]::new));
			claude.emit(STATE_IDLE);

			HarnessProcessResult result = process.sendTurn("Run the recorded task.");

			requireThat(result.assistantMessages().size(), "messageCount").isEqualTo(2);
			requireThat(result.assistantMessages().getFirst(), "interim").isEqualTo(
				"I’ll inspect the project conventions and required Java design rules, then add the checkout calculator " +
				"with focused tests.");
			requireThat(result.assistantMessages().getLast(), "final").isEqualTo(result.assistantResponse());
			requireThat(result.assistantResponse(), "assistantResponse").startsWith("Implemented `CheckoutPriceCalculator`");
		}
	}

	/**
	 * Verifies blank root text blocks and result text add no messages, non-blank blocks keep their exact bytes,
	 * and a terminal-only reply remains available.
	 * Assistant and result envelopes follow the neighboring Claude recordings; text values are controlled inputs.
	 *
	 * @throws IOException if a turn fails
	 */
	// Requirement: R-C12
	@Test(timeOut = 1000)
	public void skipsBlankRootTextBlocks() throws IOException
	{
		FakeHarnessCli claude = new FakeHarnessCli(_ ->
		{
		});
		try (ClaudeProcess process = new ClaudeProcess(new TestHarnessRunnerScope(), claude))
		{
			claude.emit(STATE_RUNNING, INIT, MESSAGE_START, """
				{"type":"assistant","message":{"role":"assistant","content":[
				{"type":"text","text":" interim "},{"type":"text","text":""},
				{"type":"text","text":" "},{"type":"text","text":"LAUNCHED"}]},
				"parent_tool_use_id":null,"session_id":"s"}
				""".replace("\n", ""), ROOT_RESULT.replace("LAUNCHED", " "), STATE_IDLE);

			HarnessProcessResult reply = process.sendTurn("Reply in multiple text blocks.");

			requireThat(reply.assistantMessages(), "messages").isEqualTo(List.of(" interim ", "LAUNCHED"));
			requireThat(reply.assistantResponse(), "response").isEqualTo("LAUNCHED");
			claude.emit(STATE_RUNNING, INIT, MESSAGE_START, SECOND_RESULT, STATE_IDLE);
			HarnessProcessResult terminalOnly = process.sendTurn("Reply TWO.");
			requireThat(terminalOnly.assistantMessages(), "terminalOnlyMessages").isEqualTo(List.of("TWO"));
			requireThat(terminalOnly.assistantResponse(), "terminalOnlyResponse").isEqualTo("TWO");

			claude.emit(STATE_RUNNING, INIT, MESSAGE_START, ROOT_RESULT.replace("LAUNCHED", ""), STATE_IDLE);
			HarnessProcessResult blank = process.sendTurn("Run the blank-result task.");
			requireThat(blank.assistantMessages(), "blankMessages").isEmpty();
			requireThat(blank.assistantResponse(), "blankResponse").isEmpty();
		}
	}

	/**
	 * Verifies a final response supplied only by the terminal result follows the interim assistant text.
	 * The controlled text uses assistant and result envelopes documented by the neighboring recordings.
	 *
	 * @throws IOException if the turn fails
	 */
	// Requirement: R-C12
	@Test
	public void retainsTerminalTextAfterInterim() throws IOException
	{
		FakeHarnessCli claude = new FakeHarnessCli(_ ->
		{
		});
		try (ClaudeProcess process = new ClaudeProcess(new TestHarnessRunnerScope(), claude))
		{
			claude.emit(STATE_RUNNING, INIT, MESSAGE_START, ROOT_REPLY, SECOND_RESULT, STATE_IDLE);

			HarnessProcessResult result = process.sendTurn("Reply TWO after the interim note.");

			requireThat(result.assistantMessages(), "assistantMessages").isEqualTo(List.of("LAUNCHED", "TWO"));
			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo("TWO");
		}
	}

	/**
	 * Verifies a follow-up reply remains in its turn, child text is excluded and a later turn starts a new list.
	 *
	 * @throws IOException if either turn fails
	 */
	// Requirement: R-C12
	@Test
	public void retainsRootFollowUpWithinTurn() throws IOException
	{
		FakeHarnessCli claude = new FakeHarnessCli(_ ->
		{
		});
		try (ClaudeProcess process = new ClaudeProcess(new TestHarnessRunnerScope(), claude))
		{
			claude.emit(STATE_RUNNING, INIT, MESSAGE_START, CHILD_LAUNCH, CHILD_RUNNING, CHILD_STARTED,
				ROOT_REPLY, ROOT_RESULT, CHILD_REPLY, CHILDREN_FINISHED, CHILD_NOTIFICATION, INIT, MESSAGE_START,
				FOLLOW_UP_REPLY, FOLLOW_UP_RESULT, STATE_IDLE);

			HarnessProcessResult result = process.sendTurn("Launch a background child.");

			requireThat(result.assistantMessages(), "assistantMessages").isEqualTo(List.of("LAUNCHED", "FINISHED"));
			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo("FINISHED");
			claude.emit(STATE_RUNNING, INIT, MESSAGE_START, SECOND_REPLY, SECOND_RESULT, STATE_IDLE);
			requireThat(process.sendTurn("Second turn.").assistantMessages(), "secondTurn").isEqualTo(List.of("TWO"));
		}
	}

	/**
	 * Feeds lines to the turn boundary of a standard turn of a process that has printed no session state yet.
	 *
	 * @param lines the lines Claude printed
	 * @return the boundary after the lines
	 */
	private static TurnBoundary boundaryAfter(String... lines)
	{
		return boundaryAfter(TurnMode.STANDARD, lines);
	}

	/**
	 * Feeds lines to the turn boundary of a process that has printed no session state yet.
	 *
	 * @param mode  what the turn is for
	 * @param lines the lines Claude printed
	 * @return the boundary after the lines
	 */
	private static TurnBoundary boundaryAfter(TurnMode mode, String... lines)
	{
		TurnBoundary boundary = new TurnBoundary(JsonMapper.builder().build(), false, mode);
		for (String line : lines)
			requireThat(boundary.accept(line), "effect").isEqualTo(LineEffect.NONE);
		return boundary;
	}

	/**
	 * Verifies a turn without background children ends at the {@code idle} state that follows Claude's result, without
	 * waiting for more output.
	 *
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000)
	public void endsTurnAtIdleWithoutChildren() throws IOException
	{
		// Requirement: R-E14
		assertTurnSpans(List.of(STATE_RUNNING, INIT, MESSAGE_START, ROOT_REPLY, ROOT_RESULT, STATE_IDLE));
	}

	/**
	 * Verifies that a result alone does not end the turn, and the {@code idle} state that follows it does.
	 */
	@Test
	public void endsAtIdleAfterResult()
	{
		// Requirement: R-E14
		TurnBoundary boundary = boundaryAfter(STATE_RUNNING, INIT, MESSAGE_START, ROOT_REPLY, ROOT_RESULT);

		requireThat(boundary.accept(STATE_IDLE), "effect").isEqualTo(LineEffect.TURN_ENDS);
	}

	/**
	 * Returns the recorded timelines in which Claude printed its session state.
	 *
	 * @return the timeline file names
	 */
	@DataProvider
	public static Object[][] sessionStateTimelines()
	{
		return new Object[][]{{"s-race-3.jsonl"}, {"s-race-4.jsonl"}, {"s-race-6.jsonl"}, {"s-two-children.jsonl"},
			{"s-nested.jsonl"}};
	}

	/**
	 * Verifies the turn ends at the first {@code idle} state that follows the turn's reply while no background task is
	 * listed, and at no earlier line: not at a result, not at an {@code idle} state while a task is listed, and not when
	 * the task list empties between two states. In {@code s-race-3}, {@code s-race-4} and {@code s-race-6} a child
	 * finished during the root's last model call; in {@code s-two-children} Claude was idle while a child's command was
	 * still listed, and the list was briefly empty before that child restarted; in {@code s-nested} a grandchild
	 * outlived its parent.
	 *
	 * @param timeline the recorded timeline
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000, dataProvider = "sessionStateTimelines")
	public void endsAtIdleWithNoTaskListed(String timeline) throws IOException
	{
		// Requirement: R-E14
		List<String> lines = recorded(timeline);
		TurnBoundary boundary = new TurnBoundary(JsonMapper.builder().build(), false, TurnMode.STANDARD);
		List<Integer> ends = new ArrayList<>();
		for (int i = 0; i < lines.size(); ++i)
		{
			if (boundary.accept(lines.get(i)) == LineEffect.TURN_ENDS)
				ends.add(i);
		}
		requireThat(ends, "ends").isEqualTo(List.of(lines.size() - 1));

		assertTurnSpans(lines);
	}

	/**
	 * Verifies steering writes the supplied user direction while a turn is active, preserving quotes and newlines,
	 * and that consuming a recorded queued-message timeline continues through the final idle state.
	 *
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000)
	public void steersWithinActiveTurn() throws IOException
	{
		// Requirement: R-E14
		List<String> beforeMessage = recorded("s-queued-1.jsonl");
		List<String> afterMessage = recorded("s-queued-2.jsonl");
		String rootResult = beforeMessage.getLast();
		String direction = "Reply with \"TWO\".\nThen stop.";
		FakeHarnessCli claude = new FakeHarnessCli(_ ->
		{
		});
		try (ClaudeProcess process = new ClaudeProcess(new TestHarnessRunnerScope(), claude))
		{
			claude.emit(beforeMessage.toArray(String[]::new));
			List<String> relayed = new ArrayList<>();

			HarnessProcessResult result = process.sendTurn("Launch a background child.", line ->
			{
				relayed.add(line);
				if (line.equals(rootResult))
				{
					process.steer(direction);
					claude.emit(afterMessage.toArray(String[]::new));
				}
			});

			List<String> expected = new ArrayList<>(beforeMessage);
			expected.addAll(afterMessage);
			requireThat(result.completed(), "completed").isTrue();
			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo(lastResult(afterMessage));
			requireThat(relayed, "relayed").isEqualTo(expected);
			requireThat(claude.requests().size(), "requests").isEqualTo(2);
			JsonNode steering = JsonMapper.builder().build().readTree(claude.requests().getLast());
			requireThat(steering.path("type").asString(), "type").isEqualTo("user");
			requireThat(steering.path("message").path("role").asString(), "role").isEqualTo("user");
			requireThat(steering.path("message").path("content").asString(), "content").isEqualTo(direction);
		}
	}

	/**
	 * Verifies that a follow-up reply Claude finishes after the previous turn ended, and the {@code idle} state that
	 * follows it, do not end the next turn or give it its reply: the next turn relays the follow-up's lines, ends once
	 * Claude is idle after the reply to its own message, and reports the follow-up in its diagnostic.
	 *
	 * @throws IOException if a turn fails
	 */
	@Test(timeOut = 10_000)
	public void reportsFollowUpThatArrivesAfterTurnEnded() throws IOException
	{
		// Requirement: R-E14
		// Requirement: R-C12
		FakeHarnessCli claude = new FakeHarnessCli(_ ->
		{
		});
		try (ClaudeProcess process = new ClaudeProcess(new TestHarnessRunnerScope(), claude))
		{
			claude.emit(STATE_RUNNING, INIT, MESSAGE_START, ROOT_REPLY, ROOT_RESULT, STATE_IDLE);
			process.sendTurn("Reply LAUNCHED.");
			List<String> lines = List.of(STATE_RUNNING, INIT, MESSAGE_START, FOLLOW_UP_REPLY, FOLLOW_UP_RESULT,
				STATE_IDLE, STATE_RUNNING, INIT, MESSAGE_START, SECOND_REPLY, SECOND_RESULT, STATE_IDLE);
			claude.emit(lines.toArray(String[]::new));
			List<String> relayed = new ArrayList<>();

			HarnessProcessResult next = process.sendTurn("Reply TWO.", relayed::add);

			requireThat(next.completed(), "completed").isTrue();
			requireThat(next.assistantResponse(), "assistantResponse").isEqualTo("TWO");
			requireThat(next.assistantMessages(), "assistantMessages").isEqualTo(List.of("TWO"));
			requireThat(next.standardError(), "standardError").isEqualTo(ClaudeProcess.LATE_FOLLOW_UP);
			requireThat(relayed, "relayed").isEqualTo(lines);
		}
	}

	/**
	 * Verifies that Claude's {@code idle} state after the turn's reply ends a conversation turn although a background
	 * task is still listed, and that the same lines leave a standard turn open, because each standard turn's reply is
	 * judged and a child's completion can restart the root.
	 */
	@Test
	public void conversationEndsAtIdleWhileTaskListed()
	{
		String[] lines = {STATE_RUNNING, INIT, MESSAGE_START, CHILD_LAUNCH, CHILD_RUNNING, CHILD_STARTED,
			ROOT_REPLY, ROOT_RESULT};

		requireThat(boundaryAfter(TurnMode.CONVERSATION, lines).accept(STATE_IDLE), "conversationEffect").
			isEqualTo(LineEffect.TURN_ENDS);
		requireThat(boundaryAfter(TurnMode.STANDARD, lines).accept(STATE_IDLE), "standardEffect").
			isEqualTo(LineEffect.NONE);
	}

	/**
	 * Verifies that a conversation turn still needs the reply to its own message: an {@code idle} state before the
	 * reply does not end it.
	 */
	@Test
	public void conversationWaitsForReplyBeforeEnding()
	{
		TurnBoundary boundary = boundaryAfter(TurnMode.CONVERSATION, STATE_RUNNING, INIT, MESSAGE_START);

		requireThat(boundary.accept(STATE_IDLE), "effect").isEqualTo(LineEffect.NONE);
	}

	/**
	 * Verifies a process ends a conversation turn at the {@code idle} state with a child agent still listed, relaying
	 * every line and replying with the root's result.
	 *
	 * @throws IOException if a turn fails
	 */
	@Test(timeOut = 10_000)
	public void conversationTurnEndsWithChildListed() throws IOException
	{
		List<String> lines = List.of(STATE_RUNNING, INIT, MESSAGE_START, CHILD_LAUNCH, CHILD_RUNNING, CHILD_STARTED,
			ROOT_REPLY, ROOT_RESULT, STATE_IDLE);
		FakeHarnessCli claude = new FakeHarnessCli(_ ->
		{
		});
		try (ClaudeProcess process = new ClaudeProcess(new TestHarnessRunnerScope(), claude))
		{
			claude.emit(lines.toArray(String[]::new));
			List<String> relayed = new ArrayList<>();

			HarnessProcessResult result = process.sendTurn("Launch a background child.", relayed::add,
				TurnMode.CONVERSATION);

			requireThat(result.completed(), "completed").isTrue();
			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo("LAUNCHED");
			requireThat(result.standardError(), "standardError").isEmpty();
			requireThat(relayed, "relayed").isEqualTo(lines);
		}
	}

	/**
	 * Verifies that a follow-up reply that a child's completion starts after a conversation turn ended is reported as a
	 * late follow-up of that turn when it arrives in the next turn: the next turn ends at the {@code idle} state that
	 * follows the reply to its own message, with a child agent still listed, and does not take the follow-up for its
	 * reply.
	 *
	 * @throws IOException if a turn fails
	 */
	@Test(timeOut = 10_000)
	public void conversationReportsLateFollowUp() throws IOException
	{
		FakeHarnessCli claude = new FakeHarnessCli(_ ->
		{
		});
		try (ClaudeProcess process = new ClaudeProcess(new TestHarnessRunnerScope(), claude))
		{
			claude.emit(STATE_RUNNING, INIT, MESSAGE_START, CHILD_LAUNCH, CHILD_RUNNING, CHILD_STARTED, ROOT_REPLY,
				ROOT_RESULT, STATE_IDLE);
			process.sendTurn("Launch a background child.", _ ->
			{
			}, TurnMode.CONVERSATION);
			List<String> lines = List.of(STATE_RUNNING, INIT, MESSAGE_START, FOLLOW_UP_REPLY, FOLLOW_UP_RESULT,
				STATE_IDLE, STATE_RUNNING, INIT, MESSAGE_START, SECOND_REPLY, SECOND_RESULT, CHILD_RUNNING, STATE_IDLE);
			claude.emit(lines.toArray(String[]::new));
			List<String> relayed = new ArrayList<>();

			HarnessProcessResult next = process.sendTurn("Reply TWO.", relayed::add, TurnMode.CONVERSATION);

			requireThat(next.completed(), "completed").isTrue();
			requireThat(next.assistantResponse(), "assistantResponse").isEqualTo("TWO");
			requireThat(next.assistantMessages(), "assistantMessages").isEqualTo(List.of("TWO"));
			requireThat(next.standardError(), "standardError").isEqualTo(ClaudeProcess.LATE_FOLLOW_UP);
			requireThat(relayed, "relayed").isEqualTo(lines);
		}
	}

	/**
	 * Verifies that an empty task list after an idle state does not end the turn; consuming the follow-up reply and
	 * its idle state does.
	 *
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000)
	public void waitsForIdleAfterListEmpties() throws IOException
	{
		// Requirement: R-E14
		assertTurnSpans(List.of(STATE_RUNNING, INIT, MESSAGE_START, CHILD_LAUNCH, CHILD_RUNNING, CHILD_STARTED,
			MESSAGE_START, ROOT_REPLY, ROOT_RESULT, STATE_IDLE, CHILD_REPLY, CHILDREN_FINISHED, CHILD_UPDATED,
			CHILD_NOTIFICATION, STATE_RUNNING, INIT, MESSAGE_START, FOLLOW_UP_REPLY, FOLLOW_UP_RESULT, STATE_IDLE));
	}

	/**
	 * Returns the recorded timelines of a Claude that did not report its session state.
	 *
	 * @return the timeline file names
	 */
	@DataProvider
	public static Object[][] noSessionStateTimelines()
	{
		return new Object[][]{{"race-3.jsonl"}, {"race-6.jsonl"}, {"race-10.jsonl"}, {"two-children.jsonl"},
			{"nested.jsonl"}, {"queued-1.jsonl"}};
	}

	/**
	 * Verifies that a turn fails at Claude's first {@code system/init} line when the process has not reported its
	 * session state, and that the failure names Claude's version and the setting that Claude needs to honour.
	 *
	 * @param timeline the recorded timeline
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000, dataProvider = "noSessionStateTimelines")
	public void failsWithoutSessionState(String timeline) throws IOException
	{
		// Requirement: R-E14
		List<String> lines = recorded(timeline);
		FakeHarnessCli claude = new FakeHarnessCli(_ ->
		{
		});
		try (ClaudeProcess process = new ClaudeProcess(new TestHarnessRunnerScope(), claude))
		{
			claude.emit(lines.toArray(String[]::new));
			List<String> relayed = new ArrayList<>();

			HarnessProcessResult result = process.sendTurn("Launch a background child.", relayed::add);

			requireThat(result.completed(), "completed").isFalse();
			requireThat(result.exitCode(), "exitCode").isNotEqualTo(0);
			requireThat(result.standardError(), "standardError").contains("2.1.286").
				contains("CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS");
			requireThat(relayed, "relayed").isEqualTo(List.of(lines.getFirst()));
		}
	}

	/**
	 * Verifies that a turn whose root result is an error, as when Claude could not reach the model service and then
	 * reported that it was idle, fails with a reason that names Claude's error result, its API status, its terminal
	 * reason and its text, instead of completing with the error text as the reply.
	 *
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000, expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		"Claude reported an error result \\(api_error_status=none, terminal_reason=api_error\\): " +
			"API Error: Connection refused .*")
	public void failsTurnAtErrorResult() throws IOException
	{
		FakeHarnessCli claude = new FakeHarnessCli(_ ->
		{
		});
		try (ClaudeProcess process = new ClaudeProcess(new TestHarnessRunnerScope(), claude))
		{
			claude.emit(STATE_RUNNING, INIT, ERROR_REPLY, ERROR_RESULT, STATE_IDLE);

			process.sendTurn("Reply LAUNCHED.");
		}
	}

	/**
	 * Returns timelines whose root result is not an error: one that says so, one that omits {@code is_error}, and one
	 * in which only a child agent's result is an error.
	 *
	 * @return each timeline and the reply that its turn completes with
	 */
	@DataProvider
	public static Object[][] rootResultsWithoutError()
	{
		String childError = ERROR_RESULT.replace("\"session_id\":\"s\"}",
			"\"parent_tool_use_id\":\"toolu_root\",\"session_id\":\"s\"}");
		return new Object[][]{
			{List.of(STATE_RUNNING, INIT, ERROR_REPLY, ERROR_RESULT.replace("\"is_error\":true", "\"is_error\":false"),
				STATE_IDLE), CONNECTION_REFUSED},
			{List.of(STATE_RUNNING, INIT, ROOT_REPLY, ROOT_RESULT.replace("\"is_error\":false,", ""), STATE_IDLE),
				"LAUNCHED"},
			{List.of(STATE_RUNNING, INIT, CHILD_LAUNCH, childError, ROOT_REPLY, ROOT_RESULT, STATE_IDLE), "LAUNCHED"}};
	}

	/**
	 * Verifies that a turn whose root result is not an error completes as before, with the root's reply, even when the
	 * timeline is otherwise that of an error result or a child agent's result is an error.
	 *
	 * @param timeline the lines Claude prints for the turn
	 * @param reply    the reply that the turn completes with
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000, dataProvider = "rootResultsWithoutError")
	public void completesTurnWithoutRootErrorResult(List<String> timeline, String reply) throws IOException
	{
		FakeHarnessCli claude = new FakeHarnessCli(_ ->
		{
		});
		try (ClaudeProcess process = new ClaudeProcess(new TestHarnessRunnerScope(), claude))
		{
			claude.emit(timeline.toArray(String[]::new));

			HarnessProcessResult result = process.sendTurn("Reply LAUNCHED.");

			requireThat(result.completed(), "completed").isTrue();
			requireThat(result.exitCode(), "exitCode").isEqualTo(0);
			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo(reply);
		}
	}

	/**
	 * Verifies that a process which reported its session state in an earlier turn does not fail a later turn whose
	 * output does not begin with a state.
	 *
	 * @throws IOException if a turn fails
	 */
	@Test(timeOut = 10_000)
	public void remembersSessionStateAcrossTurns() throws IOException
	{
		// Requirement: R-E14
		FakeHarnessCli claude = new FakeHarnessCli(_ ->
		{
		});
		try (ClaudeProcess process = new ClaudeProcess(new TestHarnessRunnerScope(), claude))
		{
			claude.emit(STATE_RUNNING, INIT, MESSAGE_START, ROOT_REPLY, ROOT_RESULT, STATE_IDLE);
			process.sendTurn("Reply LAUNCHED.");
			claude.emit(INIT, MESSAGE_START, SECOND_REPLY, SECOND_RESULT, STATE_IDLE);

			HarnessProcessResult next = process.sendTurn("Reply TWO.");

			requireThat(next.completed(), "completed").isTrue();
			requireThat(next.assistantResponse(), "assistantResponse").isEqualTo("TWO");
		}
	}

	/**
	 * Verifies that a process asked to keep session transcripts names the root session's transcript once Claude
	 * reports the session's identifier, not before, and that closing the process copies the root's transcript and its
	 * subagents' beside each other, and no other file.
	 *
	 * @throws IOException if the fixture cannot be written or read, or a turn fails
	 */
	@Test(timeOut = 10_000)
	public void copiesSessionTranscriptsOnClose() throws IOException
	{
		// Requirement: R-I7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-transcripts-"));
			Path projectDirectory = Files.createDirectories(root.resolve("home/.claude/projects/-work-project"));
			Path subagents = Files.createDirectories(projectDirectory.resolve("s/subagents"));
			Files.writeString(projectDirectory.resolve("s.jsonl"), "{\"root\":true}\n", UTF_8);
			Files.writeString(subagents.resolve("agent-a.jsonl"), "{\"child\":true}\n", UTF_8);
			Files.writeString(projectDirectory.resolve("spill.txt"), "context\n", UTF_8);
			Path copies = root.resolve("artifacts/session-transcripts");
			FakeHarnessCli claude = new FakeHarnessCli(_ ->
			{
			});
			HarnessSessionTranscripts transcripts = new HarnessSessionTranscripts(projectDirectory.getParent(), copies,
				name -> name.endsWith(".jsonl"));
			try (ClaudeProcess process = new ClaudeProcess(new TestHarnessRunnerScope(), claude, projectDirectory,
				transcripts))
			{
				requireThat(process.sessionTranscript().isPresent(), "knownBeforeInit").isFalse();
				claude.emit(STATE_RUNNING, INIT, MESSAGE_START, ROOT_REPLY, ROOT_RESULT, STATE_IDLE);

				process.sendTurn("Reply LAUNCHED.");

				requireThat(process.sessionTranscript().orElseThrow(), "transcript").
					isEqualTo(projectDirectory.resolve("s.jsonl"));
			}

			try (Stream<Path> files = Files.list(copies))
			{
				requireThat(files.map(file -> file.getFileName().toString()).sorted().toList(), "copies").
					isEqualTo(List.of("agent-a.jsonl", "s.jsonl"));
			}
		}
	}

	/**
	 * Verifies that a process that was not asked to keep session transcripts names none.
	 *
	 * @throws IOException if a turn fails
	 */
	@Test(timeOut = 10_000)
	public void namesNoTranscriptUnlessAsked() throws IOException
	{
		// Requirement: R-I7
		FakeHarnessCli claude = new FakeHarnessCli(_ ->
		{
		});
		try (ClaudeProcess process = new ClaudeProcess(new TestHarnessRunnerScope(), claude))
		{
			claude.emit(STATE_RUNNING, INIT, MESSAGE_START, ROOT_REPLY, ROOT_RESULT, STATE_IDLE);

			process.sendTurn("Reply LAUNCHED.");

			requireThat(process.sessionTranscript().isPresent(), "known").isFalse();
		}
	}

	/**
	 * Verifies an interrupt after a turn ends writes no request, and the process still serves the next turn.
	 *
	 * @throws IOException if a turn or the interrupt fails
	 */
	@Test(timeOut = 10_000)
	public void ignoresInterruptAfterTurnEnded() throws IOException
	{
		// Requirement: R-E15
		FakeHarnessCli claude = new FakeHarnessCli(_ ->
		{
		});
		try (ClaudeProcess process = new ClaudeProcess(new TestHarnessRunnerScope(), claude))
		{
			claude.emit(STATE_RUNNING, INIT, MESSAGE_START, ROOT_REPLY, ROOT_RESULT, STATE_IDLE);
			process.sendTurn("Reply LAUNCHED.");

			process.interrupt();

			claude.emit(STATE_RUNNING, INIT, MESSAGE_START, SECOND_REPLY, SECOND_RESULT, STATE_IDLE);
			HarnessProcessResult next = process.sendTurn("Reply TWO.");
			requireThat(next.assistantResponse(), "assistantResponse").isEqualTo("TWO");
			requireThat(claude.requests().size(), "requests").isEqualTo(2);
		}
	}

	/**
	 * Verifies each interrupt of an active turn writes a stream-json interrupt request with a distinct identifier.
	 * The ordinary turn output is supplied independently of the requests; this test makes no claim about Claude's
	 * response to an interrupt.
	 *
	 * @throws IOException if the turn or an interrupt fails
	 */
	@Test(timeOut = 10_000)
	public void interruptsThroughStandardInput() throws IOException
	{
		// Requirement: R-E15
		FakeHarnessCli claude = new FakeHarnessCli(_ ->
		{
		});
		try (ClaudeProcess process = new ClaudeProcess(new TestHarnessRunnerScope(), claude))
		{
			claude.emit(STATE_RUNNING, INIT, MESSAGE_START, ROOT_REPLY, ROOT_RESULT, STATE_IDLE);

			process.sendTurn("Reply LAUNCHED.", line ->
			{
				if (line.equals(ROOT_REPLY))
				{
					process.interrupt();
					process.interrupt();
				}
			});

			requireThat(claude.requests().size(), "requests").isEqualTo(3);
			JsonMapper json = JsonMapper.builder().build();
			JsonNode first = json.readTree(claude.requests().get(1));
			JsonNode second = json.readTree(claude.requests().get(2));
			for (JsonNode request : List.of(first, second))
			{
				requireThat(request.path("type").asString(), "type").isEqualTo("control_request");
				requireThat(request.path("request").path("subtype").asString(), "subtype").isEqualTo("interrupt");
				requireThat(request.path("request_id").asString(), "requestId").isNotBlank();
			}
			requireThat(second.path("request_id").asString(), "secondRequestId").
				isNotEqualTo(first.path("request_id").asString());
		}
	}

	/**
	 * Verifies an interrupt ends the turn at Claude's next {@code idle} state although a background task is still
	 * listed. An interrupt does not stop a background command, so without this the interrupted turn would wait for a
	 * command that may run for as long as the session lives, and the next turn would wait with it.
	 *
	 * @throws IOException if the turn or the interrupt fails
	 */
	@Test(timeOut = 10_000)
	public void interruptEndsTurnWhileBackgroundTaskRuns() throws IOException
	{
		FakeHarnessCli claude = new FakeHarnessCli(_ ->
		{
		});
		try (ClaudeProcess process = new ClaudeProcess(new TestHarnessRunnerScope(), claude))
		{
			claude.emit(STATE_RUNNING, INIT, MESSAGE_START, CHILD_LAUNCH, CHILD_RUNNING, ROOT_REPLY, ROOT_RESULT,
				STATE_IDLE);

			HarnessProcessResult result = process.sendTurn("Launch a background child.", line ->
			{
				if (line.equals(CHILD_RUNNING))
					process.interrupt();
			});

			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo("LAUNCHED");
			claude.emit(STATE_RUNNING, INIT, MESSAGE_START, SECOND_REPLY, SECOND_RESULT, STATE_IDLE);
			requireThat(process.sendTurn("Reply TWO.").assistantResponse(), "secondTurn").isEqualTo("TWO");
		}
	}

	/**
	 * Verifies an incomplete turn remains pending after child stdout EOF until the child exits with status 37.
	 *
	 * @throws IOException            if the turn fails
	 * @throws InterruptedException   if waiting for the turn is interrupted
	 * @throws ExecutionException     if processing the turn fails
	 * @throws TimeoutException       if the turn does not finish after the child exits
	 */
	@Test(timeOut = 10_000)
	public void waitsForExitAfterOutputEnds() throws IOException, InterruptedException, ExecutionException,
		TimeoutException
	{
		// Requirement: R-E14
		try (ExecutorService executor = Executors.newSingleThreadExecutor())
		{
			CountDownLatch turnSubmitted = new CountDownLatch(1);
			try (FakeHarnessCli claude = new FakeHarnessCli(_ -> turnSubmitted.countDown(), 37))
			{
				try (ClaudeProcess process = new ClaudeProcess(new TestHarnessRunnerScope(), claude))
				{
					try
					{
						claude.emit(STATE_RUNNING, INIT);
						Future<HarnessProcessResult> turn = executor.submit(() ->
							process.sendTurn("Launch a background child.", _ -> {}));
						requireThat(turnSubmitted.await(5, TimeUnit.SECONDS), "turnSubmitted").isTrue();
						claude.closeOutput();
						expectThrows(TimeoutException.class, () -> turn.get(100, TimeUnit.MILLISECONDS));
						requireThat(claude.isAlive(), "childAliveAfterOutputEnds").isTrue();
						claude.exit();
						HarnessProcessResult result = turn.get(5, TimeUnit.SECONDS);

						requireThat(result.completed(), "completed").isFalse();
						requireThat(result.standardError(), "standardError").contains("Claude process ended before result");
						requireThat(result.exitCode(), "exitCode").isEqualTo(37);
					}
					finally
					{
						claude.exit();
					}
				}
				finally
				{
					claude.exit();
				}
			}
		}
	}

	/**
	 * Verifies that closing a session whose Claude exits once its input closes does not signal the process, so that
	 * Claude can finish saving its session and ending its own background tasks.
	 *
	 * @throws IOException if closing the session fails
	 */
	@Test(timeOut = 10_000)
	public void closeLetsClaudeExitWithoutSignal() throws IOException
	{
		FakeHarnessCli claude = new FakeHarnessCli(_ ->
		{
		});
		try (ClaudeProcess _ = new ClaudeProcess(new TestHarnessRunnerScope(), claude, null, null,
			Duration.ofSeconds(5)))
		{
			// Closing the session at the end of the block is the behavior under test.
		}

		requireThat(claude.isAlive(), "claude.isAlive()").isFalse();
		requireThat(claude.terminationRequests(), "terminationRequests").isEqualTo(0);
	}

	/**
	 * Verifies that closing a session whose Claude does not exit within the grace period terminates the process.
	 *
	 * @throws IOException if closing the session fails
	 */
	@Test(timeOut = 10_000)
	public void closeTerminatesClaudeAfterGrace() throws IOException
	{
		FakeHarnessCli claude = new FakeHarnessCli(_ ->
		{
		});
		try (ClaudeProcess _ = new ClaudeProcess(new TestHarnessRunnerScope(), claude, null, null,
			Duration.ofMillis(50)))
		{
			claude.keepRunningWhenInputCloses();
			// Closing the session at the end of the block is the behavior under test.
		}

		requireThat(claude.isAlive(), "claude.isAlive()").isFalse();
		requireThat(claude.terminationRequests(), "terminationRequests").isGreaterThan(0);
	}

	/**
	 * Returns the recorded sessions whose first Bash result is Claude Code's report that its command sandbox failed to
	 * start. The two reports have the same frame and different reasons, and each is recorded twice: once with nothing
	 * stopping the session, so the results of later Bash calls follow the report, and once with the session stopped
	 * as soon as the report was read, so the stream ends after it.
	 *
	 * @return the recording, the report in its first Bash result, and whether the recording holds a later Bash result
	 */
	@DataProvider
	public static Object[][] sandboxStartReports()
	{
		String frameEnd = ". Sandboxing is disabled for the rest of this session; restart to retry.";
		String bridgeSockets = "Sandbox is enabled but failed to initialize: Failed to create bridge sockets after 5 " +
			"attempts" + frameEnd;
		String socketListen = "Sandbox is enabled but failed to initialize: EPERM: operation not permitted, listen " +
			"'<tmp>/srt-mux-16-2.sock'" + frameEnd;
		return new Object[][]{
			{"bridge-sockets-report-then-commands-ran.jsonl", bridgeSockets, true},
			{"bridge-sockets-report-stopped.jsonl", bridgeSockets, false},
			{"socket-listen-report-then-commands-ran.jsonl", socketListen, true},
			{"socket-listen-report-stopped.jsonl", socketListen, false}};
	}

	/**
	 * Verifies that a turn whose first Bash result is Claude Code's report that its command sandbox failed to start
	 * ends as an infrastructure failure instead of waiting for the session's reply: the runner stops the Claude process,
	 * writes it no input after the turn's user message, and gives a reason that quotes the report and says whether a Bash
	 * result followed it.
	 *
	 * @param recording      the recorded session
	 * @param report         the report in the session's first Bash result
	 * @param resultFollowed whether the recording holds a Bash result after the report
	 * @throws IOException if the recording cannot be read or the process cannot be closed
	 */
	// Requirement: R-I16
	@Test(timeOut = 10_000, dataProvider = "sandboxStartReports")
	public void endsTurnAtSandboxStartReport(String recording, String report, boolean resultFollowed)
		throws IOException
	{
		FakeHarnessCli claude = new FakeHarnessCli(_ ->
		{
		});
		try (ClaudeProcess process = new ClaudeProcess(new TestHarnessRunnerScope(), claude))
		{
			claude.emit(STATE_RUNNING);
			claude.emit(recorded("sandbox-start", recording).toArray(String[]::new));

			TerminalHarnessTurnFailure failure = expectThrows(TerminalHarnessTurnFailure.class,
				() -> process.sendTurn("Run four commands."));

			requireThat(failure.getMessage(), "reason").contains(report);
			if (resultFollowed)
			{
				requireThat(failure.getMessage(), "reason").contains("A Bash result followed the report").
					doesNotContain("No Bash result");
			}
			else
			{
				requireThat(failure.getMessage(), "reason").contains("No Bash result followed the report").
					doesNotContain("A Bash result");
			}
			HarnessProcessResult result = failure.result();
			requireThat(result.completed(), "completed").isFalse();
			requireThat(result.terminal(), "terminal").isTrue();
			requireThat(result.standardOutput(), "standardOutput").contains(report);
			requireThat(claude.terminationRequests(), "terminationRequests").isGreaterThan(0);
			requireThat(claude.isAlive(), "claude.isAlive()").isFalse();
			requireThat(claude.requests().size(), "requests").isEqualTo(1);
		}
	}

	/**
	 * Verifies that a turn of a session whose sandbox started ends as before: the recorded session runs four Bash
	 * commands inside Claude Code's sandbox, and the runner neither stops it nor reports a failure.
	 *
	 * @throws IOException if the recording cannot be read or the turn fails
	 */
	// Requirement: R-I16
	@Test(timeOut = 10_000)
	public void completesTurnWhenSandboxStarted() throws IOException
	{
		FakeHarnessCli claude = new FakeHarnessCli(_ ->
		{
		});
		try (ClaudeProcess process = new ClaudeProcess(new TestHarnessRunnerScope(), claude))
		{
			claude.emit(STATE_RUNNING);
			claude.emit(recorded("sandbox-start", "sandbox-started.jsonl").toArray(String[]::new));
			claude.emit(STATE_IDLE);

			HarnessProcessResult result = process.sendTurn("Run four commands.");

			requireThat(result.completed(), "completed").isTrue();
			requireThat(result.standardError(), "standardError").isEmpty();
			requireThat(claude.terminationRequests(), "terminationRequests").isEqualTo(0);
			requireThat(claude.isAlive(), "claude.isAlive()").isTrue();
		}
	}

	/**
	 * Returns Bash results that are not Claude Code's report of a sandbox that failed to start, although some print its
	 * words: a denied permission, a command that exits with status 1, the report's text in a later result, in a result
	 * that is not an error and after other text.
	 *
	 * @return the session's Bash results, in the order Claude prints them
	 */
	@DataProvider
	public static Object[][] bashResultsWithoutSandboxReport()
	{
		String report = "Sandbox is enabled but failed to initialize: Failed to create bridge sockets after 5 attempts. " +
			"Sandboxing is disabled for the rest of this session; restart to retry.";
		return new Object[][]{
			// Claude Code refuses a Bash call that no permission rule allows
			{List.of(new BashResult("\"Claude requested permissions to use Bash, but you haven't granted it yet.\"",
				true))},
			// A command that fails
			{List.of(new BashResult("\"Exit code 1\\nfalse\"", true))},
			// The report is recognized in the session's first Bash result only; a later result may repeat its words
			{List.of(new BashResult("\"MARKER_ALPHA\"", false), new BashResult("\"" + report + "\"", true))},
			// A command that prints the report's words has not failed to start the sandbox
			{List.of(new BashResult("\"" + report + "\"", false))},
			// The report is Claude Code's whole result, so words after other text are a command's own output
			{List.of(new BashResult("\"Exit code 1\\n" + report + "\"", true))}};
	}

	/**
	 * Verifies that a turn whose Bash results fail for other reasons, or that only print the words of the sandbox report,
	 * ends as before: it completes with the session's reply, and the runner does not stop the process.
	 *
	 * @param results the session's Bash results
	 * @throws IOException if the turn fails
	 */
	// Requirement: R-I16
	@Test(timeOut = 10_000, dataProvider = "bashResultsWithoutSandboxReport")
	public void completesTurnWithoutSandboxReport(List<BashResult> results) throws IOException
	{
		FakeHarnessCli claude = new FakeHarnessCli(_ ->
		{
		});
		try (ClaudeProcess process = new ClaudeProcess(new TestHarnessRunnerScope(), claude))
		{
			claude.emit(STATE_RUNNING, INIT);
			for (int i = 0; i < results.size(); ++i)
				claude.emit(bashCall("toolu_" + i), bashResult("toolu_" + i, results.get(i)));
			claude.emit(ROOT_REPLY, ROOT_RESULT, STATE_IDLE);

			HarnessProcessResult result = process.sendTurn("Run the commands.");

			requireThat(result.completed(), "completed").isTrue();
			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo("LAUNCHED");
			requireThat(claude.terminationRequests(), "terminationRequests").isEqualTo(0);
		}
	}

	/**
	 * Verifies that the report is recognized when Claude prints the result's content as a list of text blocks, and in the
	 * first Bash result of a later turn of the same process, since the process starts one sandbox for all its turns.
	 *
	 * @throws IOException if a turn fails unexpectedly
	 */
	// Requirement: R-I16
	@Test(timeOut = 10_000)
	public void endsLaterTurnAtReportInTextBlocks() throws IOException
	{
		String report = "Sandbox is enabled but failed to initialize: Failed to create bridge sockets after 5 attempts. " +
			"Sandboxing is disabled for the rest of this session; restart to retry.";
		FakeHarnessCli claude = new FakeHarnessCli(_ ->
		{
		});
		try (ClaudeProcess process = new ClaudeProcess(new TestHarnessRunnerScope(), claude))
		{
			claude.emit(STATE_RUNNING, INIT, ROOT_REPLY, ROOT_RESULT, STATE_IDLE);
			process.sendTurn("Reply LAUNCHED.");

			claude.emit(STATE_RUNNING, INIT, bashCall("toolu_1"), bashResult("toolu_1",
				new BashResult("[{\"type\":\"text\",\"text\":\"" + report + "\"}]", true)), ROOT_REPLY, ROOT_RESULT,
				STATE_IDLE);
			TerminalHarnessTurnFailure failure = expectThrows(TerminalHarnessTurnFailure.class,
				() -> process.sendTurn("Run a command."));

			requireThat(failure.getMessage(), "reason").contains(report);
			requireThat(claude.terminationRequests(), "terminationRequests").isGreaterThan(0);
		}
	}

	/**
	 * A Bash result as Claude Code 2.1.332 prints it in a {@code tool_result} block.
	 *
	 * @param content the block's {@code content} as JSON text
	 * @param error   whether the block reports an error
	 */
	private record BashResult(String content, boolean error)
	{
	}

	/**
	 * Returns the line in which the root agent calls Bash, shaped like the recorded ones.
	 *
	 * @param id the call's identifier
	 * @return the line
	 */
	private static String bashCall(String id)
	{
		return "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"" +
			id + "\",\"name\":\"Bash\",\"input\":{\"command\":\"true\"}}]},\"parent_tool_use_id\":null," +
			"\"session_id\":\"s\"}";
	}

	/**
	 * Returns the line that holds a Bash call's result, shaped like the recorded ones.
	 *
	 * @param id     the identifier of the call
	 * @param result the result
	 * @return the line
	 */
	private static String bashResult(String id, BashResult result)
	{
		return "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"tool_use_id\":\"" + id +
			"\",\"type\":\"tool_result\",\"content\":" + result.content() + ",\"is_error\":" + result.error() +
			"}]},\"parent_tool_use_id\":null,\"session_id\":\"s\"}";
	}
}
