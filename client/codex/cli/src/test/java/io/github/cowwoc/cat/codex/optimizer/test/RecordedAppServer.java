package io.github.cowwoc.cat.codex.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.codex.optimizer.CodexSessions;
import io.github.cowwoc.cat.codex.runner.CodexProcess;
import io.github.cowwoc.cat.codex.runner.CodexRunner;
import io.github.cowwoc.cat.common.optimizer.adapter.Bridge;
import io.github.cowwoc.cat.common.runner.HarnessRun;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import io.github.cowwoc.cat.common.runner.HarnessRunSession;
import io.github.cowwoc.cat.common.runner.HarnessRunnerBridge;
import io.github.cowwoc.cat.common.runner.fakes.test.FakeHarnessCli;
import io.github.cowwoc.cat.common.runner.fakes.test.ScriptedHarness;
import io.github.cowwoc.cat.common.runner.fakes.test.TestHarnessRunnerScope;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Supplies recorded app-server output to {@link CodexSessions} through its existing bridge seam.
 * <p>
 * The production {@link CodexProcess} and {@link HarnessRunnerBridge} interpret the stream. Only the external
 * app-server is replaced, using an in-memory {@link FakeHarnessCli}; no operating-system process starts.
 * Complete recorded inputs are described in {@code optimizer/sessions/README.md}. Turn response identifiers are
 * rebound to the replay connection's request identifiers; every notification remains unchanged and in order.
 * An interrupted recording pauses at its interrupt response until the adapter sends {@code turn/interrupt}.
 * Initialization replies model the documented handshake. Closing the process's input uses the existing double's
 * documented EOF lifecycle. The fixture does not model file effects or credential access.
 * The adapter owns the transcript under test. The bridge's caller-owned lifecycle avoids also persisting every event
 * to a second turn archive; the run manager marks completion before the adapter cleans the runtime home.
 */
final class RecordedAppServer implements Bridge
{
	private final JsonMapper json = JsonMapper.builder().build();
	private final List<List<String>> turns;
	private int nextTurn;
	private final FakeHarnessCli process = new FakeHarnessCli(this::answer);
	private final CountDownLatch turnStarted = new CountDownLatch(1);
	private List<String> interruptEvents = List.of();
	private volatile Path transcript;

	/**
	 * Creates one replay, owned by one session.
	 *
	 * @param turns output supplied for each turn/start request, in turn order
	 */
	RecordedAppServer(List<List<String>> turns)
	{
		this.turns = List.copyOf(turns);
	}

	/**
	 * Reads a complete recording.
	 *
	 * @param name the filename below optimizer/sessions
	 * @return the unchanged lines, without line terminators
	 * @throws IOException if the resource cannot be read
	 */
	static List<String> recorded(String name) throws IOException
	{
		try (InputStream stream = RecordedAppServer.class.getResourceAsStream("/optimizer/sessions/" + name))
		{
			requireThat(stream, "resource").isNotNull();
			return new String(stream.readAllBytes(), UTF_8).lines().toList();
		}
	}

	/**
	 * Rebinds recorded responses to consecutive connection requests, leaving notifications unchanged.
	 *
	 * @param events the complete recorded turn stream
	 * @param requestId the live turn/start request identifier
	 * @return every recorded line in order, with only top-level response identifiers replaced
	 */
	static List<String> rebindResponse(List<String> events, long requestId)
	{
		List<String> rebound = new ArrayList<>(events.size());
		long nextId = requestId;
		for (String line : events)
		{
			if (!line.startsWith("{\"id\":"))
				rebound.add(line);
			else
			{
				rebound.add("{\"id\":" + nextId + line.substring(line.indexOf(',')));
				nextId += 1;
			}
		}
		return List.copyOf(rebound);
	}

	/**
	 * Waits until the replay has supplied the active turn's output, withholding its interrupted completion.
	 *
	 * @throws InterruptedException if the wait is interrupted
	 */
	void awaitTurnInProgress() throws InterruptedException
	{
		requireThat(turnStarted.await(30, TimeUnit.SECONDS), "turnStarted").isTrue();
	}

	/**
	 * Returns the adapter-owned transcript after the bridge has returned normally.
	 *
	 * @return the transcript
	 */
	Path transcript()
	{
		requireThat(transcript, "transcript").isNotNull();
		return transcript;
	}

	/**
	 * Returns the observed interrupt requests in write order.
	 *
	 * @return the decoded JSON-RPC requests
	 */
	List<JsonNode> interrupts()
	{
		return process.requests().stream().map(json::readTree).
			filter(request -> request.path("method").asString("").equals("turn/interrupt")).toList();
	}

	/**
	 * Ends the in-memory app server if it is still running.
	 */
	void close()
	{
		process.destroy();
	}

	@Override
	public void serve(List<String> arguments, Reader input, Writer output) throws IOException
	{
		Path runRoot = Path.of(ScriptedHarness.optionValue(arguments, "--run-root"));
		HarnessRunManager manager = new HarnessRunManager(runRoot, Clock.systemUTC());
		HarnessRun completed = HarnessRunnerBridge.serve(() ->
		{
			HarnessRun run = manager.create("codex");
			manager.initialize(run, runRoot, Path.of(ScriptedHarness.optionValue(arguments, "--project")),
				"recorded", List.of("recorded"));
			TestHarnessRunnerScope scope = new TestHarnessRunnerScope();
			CodexProcess adapter = new CodexProcess(process, scope, false, run.directory().resolve("home"),
				run.directory().resolve("artifacts").resolve(CodexRunner.ROLLOUTS));
			adapter.initialize(run.directory().resolve("project"), "requested-alias");
			return new HarnessRunSession(run, adapter, scope);
		}, input, output);
		manager.markCompleted(completed);
		transcript = completed.directory().resolve("artifacts/transcript.jsonl");
	}

	/**
	 * Answers the production adapter's request using the selected recording.
	 *
	 * @param line the JSON-RPC request
	 */
	private void answer(String line)
	{
		JsonNode request = json.readTree(line);
		long id = request.path("id").asLong(0);
		switch (request.path("method").asString(""))
		{
			case "initialize" -> process.emit(json.createObjectNode().put("id", id).
				set("result", json.createObjectNode().put("userAgent", "codex/0.159.1")).toString());
			case "thread/start" ->
			{
				String started = turns.getFirst().stream().filter(event ->
					event.startsWith("{\"method\":\"thread/started\"")).findFirst().orElseThrow();
				JsonNode thread = json.readTree(started).path("params").path("thread");
				process.emit(json.createObjectNode().put("id", id).
					set("result", json.createObjectNode().set("thread", thread)).toString());
			}
			case "turn/start" ->
			{
				List<String> events = rebindResponse(turns.get(nextTurn), id);
				JsonNode terminal = json.readTree(events.getLast()).path("params").path("turn");
				if (terminal.path("status").asString("").equals("interrupted"))
				{
					int response = 0;
					while (!events.get(response).startsWith("{\"id\":" + (id + 1) + ","))
						response += 1;
					interruptEvents = events.subList(response, events.size());
					process.emit(events.subList(0, response).toArray(String[]::new));
				}
				else
					process.emit(events.toArray(String[]::new));
				nextTurn += 1;
				turnStarted.countDown();
			}
			case "turn/interrupt" ->
			{
				requireThat(interruptEvents.isEmpty(), "interruptEvents.isEmpty").isFalse();
				requireThat(json.readTree(interruptEvents.getFirst()).path("id").asLong(), "responseId").isEqualTo(id);
				process.emit(interruptEvents.toArray(String[]::new));
				interruptEvents = List.of();
			}
			default ->
			{
			}
		}
	}
}
