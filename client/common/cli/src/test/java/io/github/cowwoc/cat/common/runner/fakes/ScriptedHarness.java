package io.github.cowwoc.cat.common.runner.fakes;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Plays a harness behind the real runner bridge.
 * <p>
 * {@link #serve} runs {@link HarnessRunnerBridge} over a harness process that follows a script, so a test sees the
 * bridge's real protocol, manifest and failure behavior without starting a harness.
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
		requireThat(runRoot, "runRoot").isNotNull();
		requireThat(harness, "harness").isNotBlank();
		requireThat(script, "script").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(output, "output").isNotNull();
		HarnessRunManager manager = new HarnessRunManager(runRoot, Clock.systemUTC());
		HarnessRunnerBridge.serve(() ->
		{
			HarnessRun run = manager.create(harness);
			manager.initialize(run, runRoot, runRoot, "scripted", List.of("scripted"));
			return new HarnessRunSession(run, new ScriptedProcess(run, script), new TestHarnessRunnerScope());
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
	 * @param files files that the turn writes into the run's {@code project} directory, by relative path
	 * @param entered counted down once the turn has printed its events and is about to end or block, or {@code null}
	 */
	public record Turn(List<String> events, Kind kind, String response, Map<String, String> files,
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
			 * The harness ends the turn without a terminal result and with exit status 2.
			 */
			FAILS,
			/**
			 * The turn does not end until it is interrupted. The interrupt then ends it with a terminal result whose
			 * response is {@code [Request interrupted by user]}.
			 */
			BLOCKS_UNTIL_INTERRUPTED,
			/**
			 * The turn does not end until it is interrupted. The interrupt then ends it without a terminal result and
			 * with exit status 1.
			 */
			FAILS_WHEN_INTERRUPTED,
			/**
			 * The turn never ends of its own accord and ignores interrupts.
			 */
			BLOCKS_FOREVER
		}

		/**
		 * Creates a turn.
		 *
		 * @param events the harness event lines that the harness prints during the turn
		 * @param kind how the turn ends
		 * @param response the final assistant response of a turn that completes
		 * @param files files that the turn writes into the run's {@code project} directory, by relative path
		 * @param entered counted down once the turn has printed its events and is about to end or block, or
		 *   {@code null}
		 * @throws NullPointerException if any argument other than {@code entered} is null
		 */
		public Turn
		{
			requireThat(events, "events").isNotNull();
			requireThat(kind, "kind").isNotNull();
			requireThat(response, "response").isNotNull();
			requireThat(files, "files").isNotNull();
			events = List.copyOf(events);
			files = Map.copyOf(files);
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
			return new Turn(List.of(events), Kind.REPLIES, response, Map.of(), null);
		}

		/**
		 * Returns a turn that ends of its own accord without a terminal result.
		 *
		 * @param events the harness event lines that the harness prints
		 * @return the turn
		 */
		public static Turn fails(String... events)
		{
			return new Turn(List.of(events), Kind.FAILS, "", Map.of(), null);
		}

		/**
		 * Returns a turn that continues until it is interrupted and then ends with a terminal result.
		 *
		 * @param events the harness event lines that the harness prints before it blocks
		 * @return the turn
		 */
		public static Turn blocksUntilInterrupted(String... events)
		{
			return new Turn(List.of(events), Kind.BLOCKS_UNTIL_INTERRUPTED, "", Map.of(), null);
		}

		/**
		 * Returns a turn that continues until it is interrupted and then ends without a terminal result.
		 *
		 * @param events the harness event lines that the harness prints before it blocks
		 * @return the turn
		 */
		public static Turn failsWhenInterrupted(String... events)
		{
			return new Turn(List.of(events), Kind.FAILS_WHEN_INTERRUPTED, "", Map.of(), null);
		}

		/**
		 * Returns a turn that never ends and ignores interrupts.
		 *
		 * @param events the harness event lines that the harness prints before it blocks
		 * @return the turn
		 */
		public static Turn blocksForever(String... events)
		{
			return new Turn(List.of(events), Kind.BLOCKS_FOREVER, "", Map.of(), null);
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
			return new Turn(events, kind, response, files, latch);
		}

		/**
		 * Returns a copy of this turn that writes a file into the run's project directory.
		 *
		 * @param path the file's path relative to the project directory
		 * @param content the file's content
		 * @return the turn
		 * @throws NullPointerException if any argument is null
		 */
		public Turn writing(String path, String content)
		{
			requireThat(path, "path").isNotNull();
			requireThat(content, "content").isNotNull();
			Map<String, String> written = new HashMap<>(files);
			written.put(path, content);
			return new Turn(events, kind, response, written, entered);
		}
	}

	/**
	 * A harness process that follows a script.
	 */
	private static final class ScriptedProcess implements HarnessProcess
	{
		private final HarnessRun run;
		private final List<Turn> script;
		private final List<CountDownLatch> interrupts = new ArrayList<>();
		private int turns;
		private volatile boolean closed;

		/**
		 * Creates a process.
		 *
		 * @param run the run whose project directory receives the turns' files
		 * @param script what the process does for each turn
		 */
		private ScriptedProcess(HarnessRun run, List<Turn> script)
		{
			this.run = run;
			this.script = script;
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
			for (Map.Entry<String, String> file : turn.files().entrySet())
			{
				Path path = run.directory().resolve("project").resolve(file.getKey());
				Files.createDirectories(path.getParent());
				Files.writeString(path, file.getValue());
			}
			if (turn.entered() != null)
				turn.entered().countDown();
			return switch (turn.kind())
			{
				case REPLIES -> new HarnessProcessResult(0, output.toString(), "", true, turn.response());
				case FAILS -> new HarnessProcessResult(2, output.toString(), "scripted failure", false);
				case BLOCKS_UNTIL_INTERRUPTED ->
				{
					await(interrupt);
					yield new HarnessProcessResult(0, output.toString(), "", true, "[Request interrupted by user]");
				}
				case FAILS_WHEN_INTERRUPTED ->
				{
					await(interrupt);
					yield new HarnessProcessResult(1, output.toString(), "interrupted", false);
				}
				case BLOCKS_FOREVER ->
				{
					await(new CountDownLatch(1));
					yield new HarnessProcessResult(0, output.toString(), "", true, "");
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
