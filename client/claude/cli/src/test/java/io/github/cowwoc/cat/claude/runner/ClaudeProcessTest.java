package io.github.cowwoc.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.claude.runner.ClaudeProcess.LineEffect;
import io.github.cowwoc.cat.claude.runner.ClaudeProcess.TurnBoundary;
import io.github.cowwoc.cat.common.runner.fakes.FakeHarnessCli;
import io.github.cowwoc.cat.common.runner.fakes.TestHarnessRunnerScope;
import io.github.cowwoc.cat.common.runner.HarnessProcessResult;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
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
	 * What Claude Code 2.1.286 printed when SIGINT arrived after the root reply while a background child still ran: it
	 * stopped the root's own background shell command and kept the child listed.
	 */
	private static final String SHELL_STOPPED = "{\"type\":\"system\",\"subtype\":\"task_notification\"," +
		"\"task_id\":\"shell\",\"status\":\"stopped\",\"session_id\":\"s\"}";

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
		try (InputStream stream = ClaudeProcessTest.class.getResourceAsStream("/runner/turn-boundary/" + name))
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
	 * Feeds lines to the turn boundary of a process that has printed no session state yet.
	 *
	 * @param lines the lines Claude printed
	 * @return the boundary after the lines
	 */
	private static TurnBoundary boundaryAfter(String... lines)
	{
		TurnBoundary boundary = new TurnBoundary(JsonMapper.builder().build(), false);
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
		requireThat(lines.getLast(), "lastLine").isEqualTo(STATE_IDLE);
		TurnBoundary boundary = new TurnBoundary(JsonMapper.builder().build(), false);
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
	 * Verifies that a user message sent while a child runs is answered within the same turn, and the turn still ends
	 * only once Claude is idle after replying to the child's completion.
	 *
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000)
	public void answersQueuedMessageWithinTurn() throws IOException
	{
		// Requirement: R-E14
		List<String> beforeMessage = recorded("s-queued-1.jsonl");
		List<String> afterMessage = recorded("s-queued-2.jsonl");
		String rootResult = beforeMessage.getLast();
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
					process.steer("Reply with the single word TWO.");
					claude.emit(afterMessage.toArray(String[]::new));
				}
			});

			List<String> expected = new ArrayList<>(beforeMessage);
			expected.addAll(afterMessage);
			requireThat(result.completed(), "completed").isTrue();
			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo(lastResult(afterMessage));
			requireThat(relayed, "relayed").isEqualTo(expected);
			requireThat(claude.requests().size(), "requests").isEqualTo(2);
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
			requireThat(next.standardError(), "standardError").isEqualTo(ClaudeProcess.LATE_FOLLOW_UP);
			requireThat(relayed, "relayed").isEqualTo(lines);
		}
	}

	/**
	 * Verifies that when the task list empties after Claude became idle, the turn does not end then, however long
	 * Claude stays quiet, but at the next {@code idle} state, once Claude has replied to the child's completion.
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
	 * Verifies an interrupt that arrives after the turn has ended does nothing, and the process still serves the next
	 * turn. The in-memory Claude has no operating-system process, so signalling it would fail the test.
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
	 * Verifies that an interrupt of an active turn goes through the process's interrupt sender to the in-memory
	 * Claude, and that the turn then ends as the lines Claude prints after the interrupt dictate. Claude is idle after
	 * the root reply while a background child is still listed, so the turn waits; after the interrupt it reports that
	 * no task is listed and that it is idle again, which ends the turn.
	 *
	 * @throws IOException if the turn or the interrupt fails
	 */
	@Test(timeOut = 10_000)
	public void deliversInterruptThroughSender() throws IOException
	{
		// Requirement: R-E15
		List<String> beforeInterrupt = List.of(STATE_RUNNING, INIT, MESSAGE_START, CHILD_LAUNCH, CHILD_RUNNING,
			CHILD_STARTED, MESSAGE_START, ROOT_REPLY, ROOT_RESULT, STATE_IDLE);
		List<String> afterInterrupt = List.of(SHELL_STOPPED, CHILDREN_FINISHED, STATE_IDLE);
		FakeHarnessCli claude = new FakeHarnessCli(_ ->
		{
		}, afterInterrupt);
		try (ClaudeProcess process = new ClaudeProcess(new TestHarnessRunnerScope(), claude, claude::interrupt))
		{
			claude.emit(beforeInterrupt.toArray(String[]::new));
			List<String> relayed = new ArrayList<>();

			HarnessProcessResult result = process.sendTurn("Launch a background child.", line ->
			{
				relayed.add(line);
				if (relayed.size() == beforeInterrupt.size())
					process.interrupt();
			});

			List<String> expected = new ArrayList<>(beforeInterrupt);
			expected.addAll(afterInterrupt);
			requireThat(relayed, "relayed").isEqualTo(expected);
			requireThat(result.completed(), "completed").isTrue();
			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo("LAUNCHED");
		}
	}

	/**
	 * Verifies that a turn which ends because Claude closed its output waits for the operating system to report the
	 * exit, instead of reading an exit status that does not exist yet.
	 *
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000)
	public void waitsForExitAfterOutputEnds() throws IOException
	{
		// Requirement: R-E14
		FakeHarnessCli claude = new FakeHarnessCli(_ ->
		{
		});
		try (ClaudeProcess process = new ClaudeProcess(new TestHarnessRunnerScope(), claude))
		{
			claude.emit(STATE_RUNNING, INIT);
			claude.closeOutputThenExitWhenAwaited();

			HarnessProcessResult result = process.sendTurn("Launch a background child.", _ ->
			{
			});

			requireThat(result.completed(), "completed").isFalse();
			requireThat(result.standardError(), "standardError").contains("Claude process ended before result");
		}
	}
}
