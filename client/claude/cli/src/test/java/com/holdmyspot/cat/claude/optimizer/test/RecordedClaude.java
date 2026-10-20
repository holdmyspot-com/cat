package com.holdmyspot.cat.claude.optimizer.test;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.claude.optimizer.ClaudeSessions;
import com.holdmyspot.cat.claude.runner.ClaudeProcess;
import com.holdmyspot.cat.common.optimizer.adapter.Bridge;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessRunSession;
import com.holdmyspot.cat.common.runner.HarnessRunnerBridge;
import com.holdmyspot.cat.common.runner.fakes.test.FakeHarnessCli;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Supplies recorded Claude {@code stream-json} output to {@link ClaudeSessions} through its existing bridge seam.
 * <p>
 * The production {@link ClaudeProcess} and {@link HarnessRunnerBridge} interpret the stream. Only the Claude
 * process is replaced, using an in-memory {@link FakeHarnessCli}; no operating-system process starts. Each user
 * message that the adapter writes to Claude's standard input receives the next complete recorded turn, unchanged
 * and in order. Recording provenance and substitutions are described in {@code optimizer/sessions/README.md}. The
 * fixture does not model file effects or credential access.
 * <p>
 * A turn that Claude ended because CAT asked it to interrupt contains Claude's answer to that request, a
 * {@code control_response} line, followed by the lines that ended the turn. The replay supplies such a turn up to
 * that answer, and supplies the answer and the remaining lines when the adapter writes an interrupt request, as Claude
 * did in the recording.
 * <p>
 * The adapter owns the transcript under test. The bridge's caller-owned lifecycle avoids also persisting every event
 * to a second turn archive; the run manager marks completion before the adapter cleans the runtime home.
 */
final class RecordedClaude implements Bridge
{
	private final JsonMapper json = JsonMapper.builder().build();
	private final List<List<String>> turns;
	private final FakeHarnessCli process = new FakeHarnessCli(this::answer);
	/**
	 * Released once the adapter has sent the first user message, after its recorded turn has started.
	 */
	private final CountDownLatch turnStarted = new CountDownLatch(1);
	/**
	 * Guards {@link #nextTurn} and {@link #afterInterrupt}, because the adapter's user messages and interrupt requests
	 * reach {@link #answer(String)} from different threads.
	 */
	private final Object replayLock = new Object();
	private int nextTurn;
	/**
	 * The lines of the current turn that Claude recorded from its answer to an interrupt request onward; empty when the
	 * turn was not interrupted or those lines were already supplied.
	 */
	private List<String> afterInterrupt = List.of();

	/**
	 * Creates one replay, owned by one session.
	 *
	 * @param turns the output supplied for each user message, in turn order
	 */
	RecordedClaude(List<List<String>> turns)
	{
		this.turns = List.copyOf(turns);
	}

	/**
	 * Reads a complete recording.
	 *
	 * @param name the filename below {@code optimizer/sessions}
	 * @return the unchanged lines, without line terminators
	 * @throws IOException if the resource cannot be read
	 */
	static List<String> recorded(String name) throws IOException
	{
		try (InputStream stream = RecordedClaude.class.getResourceAsStream("/optimizer/sessions/" + name))
		{
			requireThat(stream, "resource").isNotNull();
			return new String(stream.readAllBytes(), UTF_8).lines().toList();
		}
	}

	/**
	 * Returns the stream-json control requests that the adapter wrote to Claude's standard input, such as interrupts.
	 *
	 * @return the decoded requests in write order
	 */
	List<JsonNode> controlRequests()
	{
		return process.requests().stream().map(json::readTree).
			filter(request -> request.path("type").asString("").equals("control_request")).toList();
	}

	/**
	 * Waits until the adapter has sent the first user message and the replay has supplied the start of its turn.
	 *
	 * @throws InterruptedException if the wait is interrupted
	 */
	void awaitTurnStarted() throws InterruptedException
	{
		turnStarted.await();
	}

	/**
	 * Ends the in-memory Claude process if it is still running.
	 */
	void close()
	{
		process.destroy();
	}

	@Override
	public void serve(List<String> arguments, Reader input, Writer output) throws IOException
	{
		Path runRoot = Path.of(ScriptedHarness.optionValue(arguments, "--run-root"));
		HarnessRunManager manager = new HarnessRunManager(runRoot, Clock.systemUTC(), Yielding.fileReplacer());
		HarnessRun completed = HarnessRunnerBridge.serve(() ->
		{
			HarnessRun run = manager.create("claude");
			manager.initialize(run, runRoot, Path.of(ScriptedHarness.optionValue(arguments, "--project")),
				"recorded", List.of("recorded"));
			TestHarnessRunnerScope scope = new TestHarnessRunnerScope();
			return new HarnessRunSession(run, new ClaudeProcess(scope, process), scope);
		}, input, output);
		manager.markCompleted(completed);
	}

	/**
	 * Answers a user message with the next recorded turn, up to Claude's answer to an interrupt request if the turn has
	 * one, and an interrupt request with the rest of that turn. Other input gets no output.
	 *
	 * @param line a line that the adapter wrote to Claude's standard input
	 */
	private void answer(String line)
	{
		JsonNode request = json.readTree(line);
		String type = request.path("type").asString("");
		synchronized (replayLock)
		{
			if (type.equals("control_request") &&
				request.path("request").path("subtype").asString("").equals("interrupt"))
			{
				process.emit(afterInterrupt.toArray(String[]::new));
				afterInterrupt = List.of();
				return;
			}
			if (!type.equals("user"))
				return;
			requireThat(nextTurn, "nextTurn").isLessThan(turns.size());
			List<String> turn = turns.get(nextTurn);
			int interruptAnswer = interruptAnswer(turn);
			process.emit(turn.subList(0, interruptAnswer).toArray(String[]::new));
			afterInterrupt = turn.subList(interruptAnswer, turn.size());
			nextTurn += 1;
		}
		turnStarted.countDown();
	}

	/**
	 * Finds Claude's answer to an interrupt request in a recorded turn.
	 *
	 * @param turn the recorded lines of one turn
	 * @return the index of the turn's first {@code control_response} line, or the number of lines if it has none
	 */
	private int interruptAnswer(List<String> turn)
	{
		for (int i = 0; i < turn.size(); ++i)
		{
			if (json.readTree(turn.get(i)).path("type").asString("").equals("control_response"))
				return i;
		}
		return turn.size();
	}
}
