package com.holdmyspot.cat.codex.runner.test;

import com.holdmyspot.cat.codex.runner.CodexProcess;
import com.holdmyspot.cat.common.runner.HarnessProcess;
import com.holdmyspot.cat.common.runner.HarnessSessionTranscripts;
import com.holdmyspot.cat.common.runner.contract.test.HarnessProcessContract;
import com.holdmyspot.cat.common.runner.fakes.test.FakeHarnessCli;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness.Turn;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs {@link HarnessProcessContract} on {@link CodexProcess} over a {@link FakeHarnessCli} that plays the Codex
 * app-server.
 * <p>
 * The app-server's lines have the shape of those in {@link CodexProcessTest}: the answers to {@code initialize}
 * (codex-cli 0.147.0) and {@code thread/start}, then for each turn the answer to {@code turn/start}, the
 * {@code turn/started} notification, an {@code item/completed} notification per {@code agentMessage} and the
 * {@code turn/completed} notification. A turn that blocks until it is interrupted prints no message and ends with
 * status {@code interrupted} once the runner sends {@code turn/interrupt}. A harness that exits prints the turn's start
 * and then ends its output and exits. A rejected turn is one whose output the runner cannot read, which the JDK reports
 * as an {@link IOException}.
 */
public final class CodexProcessContractTest extends HarnessProcessContract
{
	/**
	 * The root thread that Codex starts for the session.
	 */
	private static final String ROOT_THREAD = "thread-root";
	/**
	 * The turn that Codex starts for each request.
	 */
	private static final String ROOT_TURN = "turn-root";
	/**
	 * The project that the session works in.
	 */
	private static final Path PROJECT = Path.of("/project").toAbsolutePath();
	/**
	 * Reads the runner's requests and writes JSON text.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Creates the test fixture.
	 */
	public CodexProcessContractTest()
	{
	}

	@Override
	protected Harness start(List<Turn> script, Path directory) throws IOException
	{
		HarnessSessionTranscripts transcripts;
		Path transcript;
		if (directory == null)
		{
			transcripts = null;
			transcript = null;
		}
		else
		{
			Path home = directory.resolve("home");
			transcripts = CodexProcess.rollouts(home, directory.resolve("copies"));
			transcript = home.resolve("sessions/rollout-root.jsonl");
		}
		Path rollout = transcript;
		if (rollout == null)
			rollout = Path.of("/codex-home/sessions/rollout-root.jsonl").toAbsolutePath();
		AppServer server = new AppServer(script, rollout);
		CodexProcess process = new CodexProcess(server.cli, new TestHarnessRunnerScope(), false, transcripts);
		process.initialize(PROJECT, "");
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
				return server.printed(turn);
			}

			@Override
			public List<String> received()
			{
				return server.cli.requests().stream().map(JSON::readTree).
					filter(request -> request.path("method").asString("").equals("turn/start")).
					map(request -> request.path("params").path("input").path(0).path("text").asString()).
					toList();
			}

			@Override
			public Path transcript()
			{
				return transcript;
			}
		};
	}

	/**
	 * Codex's app-server offers the runner no way to steer a turn in progress.
	 *
	 * @return {@code false}
	 */
	@Override
	protected boolean steers()
	{
		return false;
	}

	/**
	 * Codex ends an interrupted turn with status {@code interrupted}, which completes the turn the runner interrupted.
	 *
	 * @return {@code true}
	 */
	@Override
	protected boolean completesInterruptedTurn()
	{
		return true;
	}

	/**
	 * Plays the Codex app-server: answers the start-up requests, and each {@code turn/start} request with the lines of
	 * the script's next turn.
	 */
	private static final class AppServer
	{
		private final List<Turn> script;
		private final Path rollout;
		private final FakeHarnessCli cli;
		private final List<List<String>> printed = new ArrayList<>();

		/**
		 * Creates an app-server that has not received a request.
		 *
		 * @param script  what Codex does for each turn
		 * @param rollout the root thread's rollout
		 */
		private AppServer(List<Turn> script, Path rollout)
		{
			this.script = script;
			this.rollout = rollout;
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
				// The runner reads standard error from the start, so Codex's last words must be there before it starts
				cli.printError("Codex ended before the turn completed");
			}
		}

		/**
		 * Returns the lines that Codex printed during a turn.
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
		 * Answers one request that the runner wrote.
		 *
		 * @param line the request line
		 */
		private void answer(String line)
		{
			JsonNode request = JSON.readTree(line);
			long id = request.path("id").asLong(0);
			synchronized (printed)
			{
				switch (request.path("method").asString(""))
				{
					case "initialize" -> cli.emit("{\"id\":" + id + ",\"result\":{\"userAgent\":\"codex/0.147.0\"," +
						"\"codexHome\":\"/codex-home\",\"platformFamily\":\"unix\",\"platformOs\":\"linux\"}}");
					case "thread/start" -> cli.emit("{\"id\":" + id + ",\"result\":{\"thread\":{\"id\":\"" + ROOT_THREAD +
						"\",\"path\":" + JSON.writeValueAsString(rollout.toString()) + "}}}");
					case "turn/start" ->
					{
						if (printed.size() < script.size())
							startTurn(id, script.get(printed.size()));
					}
					case "turn/interrupt" -> print(List.of("{\"id\":" + id + ",\"result\":{}}",
						turnCompleted("interrupted")));
					default ->
					{
					}
				}
			}
		}

		/**
		 * Prints the lines of a turn that has started.
		 *
		 * @param requestId the {@code turn/start} request's identifier
		 * @param turn      what Codex does
		 */
		private void startTurn(long requestId, Turn turn)
		{
			assert Thread.holdsLock(printed);
			printed.add(new ArrayList<>());
			if (turn.kind() == Turn.Kind.REJECTED)
			{
				cli.failOutput("Codex's output could not be read");
				return;
			}
			List<String> lines = new ArrayList<>(List.of(
				"{\"id\":" + requestId + ",\"result\":{\"turn\":{\"id\":\"" + ROOT_TURN + "\",\"items\":[]," +
					"\"status\":\"inProgress\",\"error\":null}}}",
				"{\"method\":\"turn/started\",\"params\":{\"threadId\":\"" + ROOT_THREAD + "\",\"turn\":{\"id\":\"" +
					ROOT_TURN + "\",\"status\":\"inProgress\"}}}"));
			if (turn.kind() == Turn.Kind.REPLIES)
			{
				for (String message : turn.messages())
				{
					lines.add("{\"method\":\"item/completed\",\"params\":{\"item\":{\"type\":\"agentMessage\"," +
						"\"id\":\"msg\",\"text\":" + JSON.writeValueAsString(message) + "},\"threadId\":\"" + ROOT_THREAD +
						"\",\"turnId\":\"" + ROOT_TURN + "\"}}");
				}
				lines.add(turnCompleted("completed"));
			}
			print(lines);
			if (turn.kind() == Turn.Kind.EXITS)
				cli.exit();
		}

		/**
		 * Prints lines on Codex's standard output as part of the latest turn.
		 *
		 * @param lines the lines
		 */
		private void print(List<String> lines)
		{
			assert Thread.holdsLock(printed);
			printed.getLast().addAll(lines);
			cli.emit(lines.toArray(String[]::new));
		}

		/**
		 * Returns the notification that the root turn ended.
		 *
		 * @param status how the turn ended
		 * @return the notification line
		 */
		private static String turnCompleted(String status)
		{
			return "{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"" + ROOT_THREAD + "\",\"turn\":{\"id\":\"" +
				ROOT_TURN + "\",\"items\":[],\"status\":\"" + status + "\",\"error\":null}}}";
		}
	}
}
