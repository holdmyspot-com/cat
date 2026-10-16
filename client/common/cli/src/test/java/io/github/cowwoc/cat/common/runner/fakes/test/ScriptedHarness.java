package io.github.cowwoc.cat.common.runner.fakes.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.runner.HarnessEventListener;
import io.github.cowwoc.cat.common.runner.HarnessProcess;
import io.github.cowwoc.cat.common.runner.HarnessProcessResult;
import io.github.cowwoc.cat.common.runner.HarnessRun;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import io.github.cowwoc.cat.common.runner.HarnessRunSession;
import io.github.cowwoc.cat.common.runner.HarnessRunnerBridge;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Plays a harness behind the real runner bridge.
 * <p>
 * {@link #serve} runs {@link HarnessRunnerBridge} over a harness process that follows a script, so a test sees the
 * bridge's real protocol, manifest and failure behavior without starting a harness.
 * <p>
 * Event inputs come from Claude Code 2.1.286's
 * {@code client/claude/cli/src/test/resources/optimizer/claude-single-turn.jsonl}, Claude Code 2.1.287 recordings
 * documented in {@code client/claude/cli/src/test/resources/optimizer/tool-calls.md}, codex-cli 0.159.1's
 * {@code client/codex/cli/src/test/resources/optimizer/codex-children.jsonl}, and codex-cli 0.147.0/0.159.1+23
 * recordings documented in {@code client/codex/cli/src/test/resources/optimizer/codex-tool-calls.md}.
 * {@code client/codex/cli/src/test/resources/optimizer/codex-hooks.jsonl} records hook notifications; that excerpt
 * does not retain the harness version. The double preserves its lines as input to CAT's prompt-source transformation.
 * Normal terminal replies model those recordings. Response text is caller-controlled input; no test may use it as
 * evidence that a harness would produce that reply.
 * <p>
 * Nonzero process failure follows Anthropic's Agent SDK v0.2.163, which bundles Claude Code 2.1.286:
 * <a href="https://github.com/anthropics/claude-agent-sdk-python/blob/v0.2.163/src/claude_agent_sdk/_internal/transport/subprocess_cli.py">
 * SubprocessCLITransport</a> reports a ProcessError for any nonzero exit. Status 1 is a representative input, not a
 * claim that a particular harness failure has that status. Interrupt completion follows codex-cli 0.147.0's
 * <a href="https://github.com/openai/codex/blob/rust-v0.147.0/codex-rs/app-server/README.md">app-server README</a>
 * (Lifecycle Overview and Interrupt an active turn): interruption produces a terminal notification and may have no
 * final assistant message. Claude interrupt support is documented by Agent SDK v0.2.163's
 * <a href="https://github.com/anthropics/claude-agent-sdk-python/blob/v0.2.163/src/claude_agent_sdk/client.py">
 * ClaudeSDKClient.interrupt</a>; these tests do not establish the exact Claude completion after interruption.
 * Agent SDK v0.2.163's
 * <a href="https://github.com/anthropics/claude-agent-sdk-python/blob/v0.2.163/src/claude_agent_sdk/types.py">
 * RateLimitInfo and ResultMessage</a> also document rate-limit rejection, error-result fields, interruption's terminal
 * reasons and an optional result. Representative rejection and error events follow those fields and the app-server
 * README's Rate limits, Turn events and Errors sections; their values are parser inputs, not recorded outcomes.
 * The double scripts no
 * stopped-child events or interrupt response text.
 * <p>
 * Latches control when the test delivers input and observe the outgoing interrupt. They impose no elapsed-time
 * guarantee on a harness. Session tests verify CAT's classification given these inputs, not harness reactions.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class ScriptedHarness
{
	/**
	 * Serves one session through the real bridge.
	 *
	 * @param runRoot the directory that holds the run directories
	 * @param harness the harness name
	 * @param script what the harness does for each turn, in turn order
	 * @param input the bridge requests
	 * @param output the bridge responses
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if the bridge fails
	 */
	public static void serve(Path runRoot, String harness, List<Turn> script, Reader input, Writer output)
		throws IOException
	{
		serve(runRoot, harness, script, input, output, new ArrayList<>());
	}

	/**
	 * Serves one session and records the requests CAT sends to the harness, in turn order.
	 *
	 * @param runRoot the directory that holds the run directories
	 * @param harness the harness name
	 * @param script what the harness does for each turn, in turn order
	 * @param input the bridge requests
	 * @param output the bridge responses
	 * @param requests receives each decoded turn request before the turn's events are delivered
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if the bridge fails
	 */
	public static void serve(Path runRoot, String harness, List<Turn> script, Reader input, Writer output,
		List<String> requests) throws IOException
	{
		requireThat(runRoot, "runRoot").isNotNull();
		requireThat(harness, "harness").isNotBlank();
		requireThat(script, "script").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(output, "output").isNotNull();
		requireThat(requests, "requests").isNotNull();
		HarnessRunManager manager = new HarnessRunManager(runRoot, Clock.systemUTC());
		HarnessRunnerBridge.serve(() ->
		{
			HarnessRun run = manager.create(harness);
			manager.initialize(run, runRoot, runRoot, "scripted", List.of("scripted"));
			return new HarnessRunSession(run, new ScriptedProcess(script, requests), new TestHarnessRunnerScope());
		}, manager, true, input, output);
	}

	/**
	 * Returns the value that follows an option.
	 *
	 * @param arguments command-line arguments
	 * @param option the option name
	 * @return the value
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code arguments} do not contain {@code option} followed by a value
	 */
	public static String optionValue(List<String> arguments, String option)
	{
		requireThat(arguments, "arguments").isNotNull();
		requireThat(option, "option").isNotNull();
		int index = arguments.indexOf(option);
		if (index < 0 || index + 1 >= arguments.size())
			throw new IllegalArgumentException("Missing " + option);
		return arguments.get(index + 1);
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private ScriptedHarness()
	{
	}

	/**
	 * What the harness does for one turn.
	 *
	 * @param events the harness event lines that the harness prints during the turn
	 * @param kind how the turn ends
	 * @param response the final assistant response of a turn that completes
	 * @param entered counted down once the turn has printed its events and is about to end or block, or {@code null}
	 */
	public record Turn(List<String> events, Kind kind, String response,
		CountDownLatch entered)
	{
		/**
		 * How a turn ends.
		 */
		public enum Kind
		{
			/**
			 * The harness replies.
			 */
			REPLIES,
			/**
			 * The harness ends the turn without a terminal result and with a representative nonzero exit status 1.
			 */
			FAILS,
			/**
			 * The harness rejects the turn, so the turn fails without a result or an exit status.
			 */
			REJECTED,
			/**
			 * The turn does not end until it is interrupted. The interrupt then ends it with a terminal result whose
			 * response is empty.
			 */
			BLOCKS_UNTIL_INTERRUPTED
		}

		/**
		 * Creates a turn.
		 *
		 * @param events the harness event lines that the harness prints during the turn
		 * @param kind how the turn ends
		 * @param response the final assistant response of a turn that completes
		 * @param entered counted down once the turn has printed its events and is about to end or block, or
		 *   {@code null}
		 * @throws NullPointerException if any argument other than {@code entered} is null
		 */
		public Turn
		{
			requireThat(events, "events").isNotNull();
			requireThat(kind, "kind").isNotNull();
			requireThat(response, "response").isNotNull();
			events = List.copyOf(events);
		}

		/**
		 * Returns a turn that replies.
		 *
		 * @param response the final assistant response
		 * @param events the harness event lines that the harness prints
		 * @return the turn
		 */
		public static Turn replies(String response, String... events)
		{
			return new Turn(List.of(events), Kind.REPLIES, response, null);
		}

		/**
		 * Returns a turn that ends of its own accord without a terminal result.
		 *
		 * @param events the harness event lines that the harness prints
		 * @return the turn
		 */
		public static Turn fails(String... events)
		{
			return new Turn(List.of(events), Kind.FAILS, "", null);
		}

		/**
		 * Returns a turn that the harness rejects.
		 *
		 * @return the turn
		 */
		public static Turn rejected()
		{
			return new Turn(List.of(), Kind.REJECTED, "", null);
		}

		/**
		 * Returns a turn that continues until it is interrupted and then ends with a terminal result.
		 *
		 * @param events the harness event lines that the harness prints before it blocks
		 * @return the turn
		 */
		public static Turn blocksUntilInterrupted(String... events)
		{
			return new Turn(List.of(events), Kind.BLOCKS_UNTIL_INTERRUPTED, "", null);
		}

		/**
		 * Returns a copy of this turn that tells the test when it has printed its events.
		 *
		 * @param latch the latch to count down
		 * @return the turn
		 * @throws NullPointerException if {@code latch} is null
		 */
		public Turn announcing(CountDownLatch latch)
		{
			requireThat(latch, "latch").isNotNull();
			return new Turn(events, kind, response, latch);
		}
	}

	/**
	 * A harness process that follows a script.
	 */
	private static final class ScriptedProcess implements HarnessProcess
	{
		private final List<Turn> script;
		private final List<String> requests;
		private final List<CountDownLatch> interrupts = new ArrayList<>();
		private int turns;
		private volatile boolean closed;

		/**
		 * Creates a process.
		 *
		 * @param script what the process does for each turn
		 * @param requests receives each decoded turn request
		 */
		private ScriptedProcess(List<Turn> script, List<String> requests)
		{
			this.script = List.copyOf(script);
			this.requests = requests;
		}

		@Override
		public HarnessProcessResult sendTurn(String request, HarnessEventListener listener) throws IOException
		{
			requireThat(request, "request").isNotBlank();
			Turn turn;
			CountDownLatch interrupt = new CountDownLatch(1);
			synchronized (interrupts)
			{
				if (turns >= script.size())
					throw new IOException("The script has no turn " + (turns + 1));
				requests.add(request);
				turn = script.get(turns);
				turns += 1;
				interrupts.add(interrupt);
			}
			StringBuilder output = new StringBuilder();
			for (String event : turn.events())
			{
				output.append(event).append('\n');
				listener.accept(event);
			}
			if (turn.entered() != null)
				turn.entered().countDown();
			return switch (turn.kind())
			{
				case REPLIES -> new HarnessProcessResult(0, output.toString(), "", true, turn.response(),
					List.of(turn.response()));
				case FAILS -> new HarnessProcessResult(1, output.toString(), "scripted failure", false);
				case REJECTED -> throw new IOException("The harness rejected the turn");
				case BLOCKS_UNTIL_INTERRUPTED ->
				{
					await(interrupt);
					yield new HarnessProcessResult(0, output.toString(), "", true, "", List.of());
				}
			};
		}

		@Override
		public HarnessProcessResult sendTurn(String request) throws IOException
		{
			return sendTurn(request, _ ->
			{
			});
		}

		@Override
		public void interrupt()
		{
			synchronized (interrupts)
			{
				if (interrupts.isEmpty())
					return;
				interrupts.get(interrupts.size() - 1).countDown();
			}
		}

		@Override
		public boolean isAlive()
		{
			return !closed;
		}

		@Override
		public void close()
		{
			closed = true;
		}

		/**
		 * Waits for a latch.
		 *
		 * @param latch the latch
		 * @throws IOException if the thread is interrupted
		 */
		private static void await(CountDownLatch latch) throws IOException
		{
			try
			{
				latch.await();
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while the scripted turn was running", e);
			}
		}
	}
}
