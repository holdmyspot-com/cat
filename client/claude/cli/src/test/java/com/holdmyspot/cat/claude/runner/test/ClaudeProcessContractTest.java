package com.holdmyspot.cat.claude.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.claude.runner.ClaudeProcess;
import com.holdmyspot.cat.common.runner.HarnessProcess;
import com.holdmyspot.cat.common.runner.HarnessSessionTranscripts;
import com.holdmyspot.cat.common.runner.contract.test.HarnessProcessContract;
import com.holdmyspot.cat.common.runner.fakes.test.FakeHarnessCli;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness.Turn;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs {@link HarnessProcessContract} on {@link ClaudeProcess} over a {@link FakeHarnessCli} that plays the Claude CLI.
 * <p>
 * A reply's lines have the shape of those that Claude Code 2.1.286 printed in the timelines under
 * {@code runner/turn-boundary}: the {@code running} state, {@code system/init}, one assistant event per message, the
 * root result and the {@code idle} state. A rejected turn prints the error result that Claude Code 2.1.310 printed
 * when it could not reach the model service, trimmed from a 2026-10-28 runner transcript, as in
 * {@link ClaudeProcessTest}. A turn that blocks until it is interrupted plays Claude Code 2.1.304's recorded
 * interrupted judge turn, {@code optimizer/sessions/interrupted-judge-turn-1.jsonl}: it prints the lines before the
 * {@code control_response} line, and prints the rest, which end the turn with an {@code error_during_execution}
 * result, once the runner asks Claude to interrupt. A harness that exits prints the {@code running} state and
 * {@code system/init} and then ends its output and exits.
 */
public final class ClaudeProcessContractTest extends HarnessProcessContract
{
	/**
	 * Where the recorded interrupted turn begins its answer to the interrupt request.
	 */
	private static final String INTERRUPT_ANSWER = "{\"type\":\"control_response\"";
	/**
	 * The line with which Claude reports that its session started working.
	 */
	private static final String STATE_RUNNING = "{\"type\":\"system\",\"subtype\":\"session_state_changed\"," +
		"\"state\":\"running\",\"session_id\":\"s\"}";
	/**
	 * The line with which Claude reports that its session stopped working.
	 */
	private static final String STATE_IDLE = "{\"type\":\"system\",\"subtype\":\"session_state_changed\"," +
		"\"state\":\"idle\",\"session_id\":\"s\"}";
	/**
	 * The line with which Claude starts each turn's reply.
	 */
	private static final String INIT = "{\"type\":\"system\",\"subtype\":\"init\"," +
		"\"claude_code_version\":\"2.1.286\",\"session_id\":\"s\"}";
	/**
	 * What Claude Code 2.1.310 replied when it could not reach the model service.
	 */
	private static final String CONNECTION_REFUSED = "API Error: Connection refused — a firewall or proxy may be " +
		"blocking it (ECONNREFUSED)";
	/**
	 * The assistant event that carries Claude's error reply.
	 */
	private static final String ERROR_REPLY = "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\"," +
		"\"content\":[{\"type\":\"text\",\"text\":\"" + CONNECTION_REFUSED + "\"}]},\"parent_tool_use_id\":null," +
		"\"session_id\":\"s\"}";
	/**
	 * The root result that reports Claude's error.
	 */
	private static final String ERROR_RESULT = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true," +
		"\"api_error_status\":null,\"terminal_reason\":\"api_error\",\"num_turns\":1,\"result\":\"" +
		CONNECTION_REFUSED + "\",\"result_index\":0,\"session_id\":\"s\"}";
	/**
	 * Reads the runner's requests and writes JSON text.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Creates the test fixture.
	 */
	public ClaudeProcessContractTest()
	{
	}

	@Override
	protected Harness start(List<Turn> script, Path directory) throws IOException
	{
		ClaudeCli claude = new ClaudeCli(script);
		ClaudeProcess process;
		Path transcript;
		if (directory == null)
		{
			process = new ClaudeProcess(new TestHarnessRunnerScope(), claude.cli);
			transcript = null;
		}
		else
		{
			Path home = directory.resolve("home");
			Path projectDirectory = home.resolve("projects/-project");
			// Claude names the session's transcript after the session_id that its system/init line reports
			transcript = projectDirectory.resolve("s.jsonl");
			process = new ClaudeProcess(new TestHarnessRunnerScope(), claude.cli, projectDirectory,
				new HarnessSessionTranscripts(home, directory.resolve("copies"), name -> name.endsWith(".jsonl")));
		}
		return new Harness()
		{
			@Override
			public HarnessProcess process()
			{
				return process;
			}

			@Override
			public List<String> printed(int turn)
			{
				return claude.printed(turn);
			}

			@Override
			public List<String> received()
			{
				return claude.cli.requests().stream().map(JSON::readTree).
					filter(request -> request.path("type").asString("").equals("user")).
					map(request -> request.path("message").path("content").asString()).
					toList();
			}

			@Override
			public Path transcript()
			{
				return transcript;
			}
		};
	}

	@Override
	protected boolean steers()
	{
		return true;
	}

	/**
	 * Claude ends an interrupted turn with an error result, which fails the turn.
	 *
	 * @return {@code false}
	 */
	@Override
	protected boolean completesInterruptedTurn()
	{
		return false;
	}

	/**
	 * Reads Claude Code 2.1.304's recorded interrupted turn.
	 *
	 * @return the turn's lines in the order Claude printed them
	 * @throws IOException if the resource cannot be read
	 */
	private static List<String> interruptedTurn() throws IOException
	{
		try (InputStream stream = ClaudeProcessContractTest.class.getResourceAsStream(
			"/optimizer/sessions/interrupted-judge-turn-1.jsonl"))
		{
			requireThat(stream, "resource").isNotNull();
			return new String(stream.readAllBytes(), UTF_8).lines().toList();
		}
	}

	/**
	 * Plays the Claude CLI: answers each user message with the lines of the script's next turn.
	 */
	private static final class ClaudeCli
	{
		private final List<Turn> script;
		private final List<String> interrupted;
		private final FakeHarnessCli cli;
		private final List<List<String>> printed = new ArrayList<>();
		/**
		 * The lines that answer an interrupt request, or empty when no turn waits for one; guarded by {@link #printed}.
		 */
		private List<String> interruptAnswer = List.of();

		/**
		 * Creates a CLI that has not received a message.
		 *
		 * @param script what Claude does for each turn
		 * @throws IOException if the recorded interrupted turn cannot be read
		 */
		private ClaudeCli(List<Turn> script) throws IOException
		{
			this.script = script;
			this.interrupted = interruptedTurn();
			Turn exit = null;
			for (Turn turn : script)
			{
				if (turn.kind() == Turn.Kind.EXITS)
				{
					exit = turn;
					break;
				}
			}
			if (exit == null)
				this.cli = new FakeHarnessCli(this::answer);
			else
			{
				this.cli = new FakeHarnessCli(this::answer, exit.exitStatus());
				// The runner reads standard error from the start, so Claude's last words must be there before it starts
				cli.printError("Claude ended before the turn's result");
			}
		}

		/**
		 * Returns the lines that Claude printed during a turn.
		 *
		 * @param turn the turn's index
		 * @return the lines
		 */
		private List<String> printed(int turn)
		{
			synchronized (printed)
			{
				return List.copyOf(printed.get(turn));
			}
		}

		/**
		 * Answers one line that the runner wrote to Claude's standard input. A user message that arrives once the
		 * script has no turn left is a steering direction, which Claude takes in without printing anything.
		 *
		 * @param line the line
		 */
		private void answer(String line)
		{
			JsonNode request = JSON.readTree(line);
			synchronized (printed)
			{
				switch (request.path("type").asString(""))
				{
					case "user" ->
					{
						if (printed.size() < script.size())
							startTurn(script.get(printed.size()));
					}
					case "control_request" ->
					{
						print(interruptAnswer);
						interruptAnswer = List.of();
					}
					default ->
					{
					}
				}
			}
		}

		/**
		 * Prints the lines of a turn that has started.
		 *
		 * @param turn what Claude does
		 */
		private void startTurn(Turn turn)
		{
			assert Thread.holdsLock(printed);
			printed.add(new ArrayList<>());
			switch (turn.kind())
			{
				case REPLIES ->
				{
					List<String> lines = new ArrayList<>(List.of(STATE_RUNNING, INIT));
					for (String message : turn.messages())
						lines.add(assistant(message));
					lines.add("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":" +
						JSON.writeValueAsString(turn.messages().getLast()) + ",\"session_id\":\"s\"}");
					lines.add(STATE_IDLE);
					print(lines);
				}
				case EXITS ->
				{
					print(List.of(STATE_RUNNING, INIT));
					cli.exit();
				}
				case REJECTED, FAILS_AND_RUNS ->
					print(List.of(STATE_RUNNING, INIT, ERROR_REPLY, ERROR_RESULT, STATE_IDLE));
				case BLOCKS_UNTIL_INTERRUPTED ->
				{
					int answer = 0;
					while (!interrupted.get(answer).startsWith(INTERRUPT_ANSWER))
						answer += 1;
					print(interrupted.subList(0, answer));
					interruptAnswer = interrupted.subList(answer, interrupted.size());
				}
			}
		}

		/**
		 * Prints lines on Claude's standard output as part of the latest turn.
		 *
		 * @param lines the lines
		 */
		private void print(List<String> lines)
		{
			assert Thread.holdsLock(printed);
			if (lines.isEmpty())
				return;
			printed.getLast().addAll(lines);
			cli.emit(lines.toArray(String[]::new));
		}

		/**
		 * Returns the assistant event of a root message.
		 *
		 * @param text the message
		 * @return the event
		 */
		private static String assistant(String text)
		{
			return "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\"," +
				"\"text\":" + JSON.writeValueAsString(text) + "}]},\"parent_tool_use_id\":null,\"session_id\":\"s\"}";
		}
	}
}
