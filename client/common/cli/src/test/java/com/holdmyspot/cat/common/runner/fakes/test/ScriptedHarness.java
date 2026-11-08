package com.holdmyspot.cat.common.runner.fakes.test;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessRunSession;
import com.holdmyspot.cat.common.runner.HarnessRunnerBridge;

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
 * {@link #serve} runs {@link HarnessRunnerBridge} over a {@link ScriptedProcess} that follows a script, so a test sees
 * the bridge's real protocol, manifest and failure behavior without starting a harness.
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
 * Latches and checkpoints control when the test delivers input and observe the outgoing interrupt. They impose no
 * elapsed-time guarantee on a harness. Session tests verify CAT's classification given these inputs, not harness
 * reactions.
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
	 * @param requests receives each turn prompt and steering direction, in the order the harness receives them
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
		HarnessRunManager manager = new HarnessRunManager(new FileLocks(), runRoot,
			Clock.systemUTC(), Yielding.fileReplacer());
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
	 * @param events     the harness event lines that the harness prints during the turn
	 * @param kind       how the turn ends
	 * @param messages   the assistant messages of a turn that replies, in order; the last is the turn's response
	 * @param exitStatus the exit status of a harness that exits during the turn
	 * @param checkpoint runs the test's code while the harness works on the turn
	 */
	public record Turn(List<String> events, Kind kind, List<String> messages, int exitStatus, Checkpoint checkpoint)
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
			 * The harness exits without a terminal result.
			 */
			EXITS,
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
		 * Code that a test runs on the turn's thread while the harness works on the turn, to act at a known point of
		 * the turn, such as moving a clock. It imposes no behavior on a harness.
		 */
		@FunctionalInterface
		public interface Checkpoint
		{
			/**
			 * Runs before the harness prints each event line of the turn, and once more after the last line, before the
			 * turn ends or blocks.
			 *
			 * @param printed the number of the turn's event lines that the harness has printed
			 * @throws IOException if the test's code fails, which fails the turn
			 */
			void reached(int printed) throws IOException;
		}

		/**
		 * Creates a turn.
		 *
		 * @param events     the harness event lines that the harness prints during the turn
		 * @param kind       how the turn ends
		 * @param messages   the assistant messages of a turn that replies, in order; the last is the turn's response
		 * @param exitStatus the exit status of a harness that exits during the turn
		 * @param checkpoint runs the test's code while the harness works on the turn
		 * @throws NullPointerException     if any argument is null
		 * @throws IllegalArgumentException if a turn that replies has no message
		 */
		public Turn
		{
			requireThat(events, "events").isNotNull();
			requireThat(kind, "kind").isNotNull();
			requireThat(messages, "messages").isNotNull();
			requireThat(checkpoint, "checkpoint").isNotNull();
			if (kind == Kind.REPLIES)
				requireThat(messages, "messages").isNotEmpty();
			events = List.copyOf(events);
			messages = List.copyOf(messages);
		}

		/**
		 * Returns a turn that replies with one message.
		 *
		 * @param response the final assistant response
		 * @param events   the harness event lines that the harness prints
		 * @return the turn
		 */
		public static Turn replies(String response, String... events)
		{
			return replies(List.of(response), events);
		}

		/**
		 * Returns a turn that replies with several messages.
		 *
		 * @param messages the assistant messages, in order; the last is the final response
		 * @param events   the harness event lines that the harness prints
		 * @return the turn
		 * @throws IllegalArgumentException if {@code messages} is empty
		 */
		public static Turn replies(List<String> messages, String... events)
		{
			return new Turn(List.of(events), Kind.REPLIES, messages, 0, _ ->
			{
			});
		}

		/**
		 * Returns a turn during which the harness exits with status 1, a representative failure status, without a
		 * terminal result.
		 *
		 * @param events the harness event lines that the harness prints
		 * @return the turn
		 */
		public static Turn fails(String... events)
		{
			return exits(1, events);
		}

		/**
		 * Returns a turn during which the harness exits without a terminal result.
		 *
		 * @param exitStatus the harness's exit status
		 * @param events     the harness event lines that the harness prints
		 * @return the turn
		 */
		public static Turn exits(int exitStatus, String... events)
		{
			return new Turn(List.of(events), Kind.EXITS, List.of(), exitStatus, _ ->
			{
			});
		}

		/**
		 * Returns a turn that the harness rejects.
		 *
		 * @return the turn
		 */
		public static Turn rejected()
		{
			return new Turn(List.of(), Kind.REJECTED, List.of(), 0, _ ->
			{
			});
		}

		/**
		 * Returns a turn that continues until it is interrupted and then ends with a terminal result.
		 *
		 * @param events the harness event lines that the harness prints before it blocks
		 * @return the turn
		 */
		public static Turn blocksUntilInterrupted(String... events)
		{
			return new Turn(List.of(events), Kind.BLOCKS_UNTIL_INTERRUPTED, List.of(), 0, _ ->
			{
			});
		}

		/**
		 * Returns a copy of this turn that tells the test when it has printed its events and is about to end or block.
		 *
		 * @param latch the latch to count down
		 * @return the turn
		 * @throws NullPointerException if {@code latch} is null
		 */
		public Turn announcing(CountDownLatch latch)
		{
			requireThat(latch, "latch").isNotNull();
			int count = events.size();
			return withCheckpoint(printed ->
			{
				if (printed == count)
					latch.countDown();
			});
		}

		/**
		 * Returns a copy of this turn that runs the test's code while the harness works on the turn.
		 *
		 * @param checkpoint the test's code
		 * @return the turn
		 * @throws NullPointerException if {@code checkpoint} is null
		 */
		public Turn withCheckpoint(Checkpoint checkpoint)
		{
			return new Turn(events, kind, messages, exitStatus, checkpoint);
		}
	}
}
