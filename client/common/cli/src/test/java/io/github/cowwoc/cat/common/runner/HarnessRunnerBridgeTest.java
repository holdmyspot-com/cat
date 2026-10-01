package io.github.cowwoc.cat.common.runner;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Tests {@link HarnessRunnerBridge}.
 */
public final class HarnessRunnerBridgeTest
{
	/**
	 * Creates the test fixture.
	 */
	public HarnessRunnerBridgeTest()
	{
	}

	/**
	 * Verifies two turns share one live harness process until explicit completion.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test
	public void keepsOneHarnessProcessForMultipleTurns() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			FakeProcess process = new FakeProcess();
			StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n" +
				"{\"type\":\"turn\",\"prompt\":\"second\"}\n{\"type\":\"complete\"}\n"))
		{
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));
			StringWriter output = new StringWriter();

			HarnessRun result = HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process,
				new HarnessRunnerScope()), input, output);

			requireThat(process.turns, "turns").isEqualTo(2);
			requireThat(process.closed, "closed").isTrue();
			requireThat(result, "result").isEqualTo(run);
			requireThat(output.toString(), "output").contains("\"type\":\"started\"").
				contains("\"type\":\"native-event\"").contains("\"type\":\"turn-completed\"").
				contains("\"type\":\"completed\"");
			requireThat(output.toString().lines().count(), "lineCount").isEqualTo(6L);
			requireThat(output.toString(), "output").doesNotContain("\"events\":");
		}
	}

	/**
	 * Verifies the harness-neutral transcript retains the exact final assistant response for evaluator consumption.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test
	public void recordsFinalAssistantResponse() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			FakeProcess process = new FakeProcess("result with an injected \"quote\"");
			StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"task\"}\n" +
				"{\"type\":\"complete\"}\n"))
		{
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));
			StringWriter output = new StringWriter();

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new HarnessRunnerScope()), input,
				output);

			requireThat(output.toString(), "output").contains(
				"\"assistant_response\":\"result with an injected \\\"quote\\\"\"");
		}
	}

	/**
	 * Verifies a completed bridge run is removed when the caller did not request retention.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test
	public void removesCompletedManagedRun() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			FakeProcess process = new FakeProcess();
			StringReader input = new StringReader("{\"type\":\"complete\"}\n"))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC());
			HarnessRun run = manager.create("harness");
			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new HarnessRunnerScope()), manager,
				false,
				input, new StringWriter());

			requireThat(Files.exists(run.directory()), "runDirectory").isFalse();
		}
	}

	/**
	 * Verifies a bridge can retain its JSONL transcript without a harness-specific output path.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test
	public void writesRetainedTranscript() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			FakeProcess process = new FakeProcess();
			StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n" +
				"{\"type\":\"complete\"}\n"))
		{
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));
			Path transcript = cleanup.add(Files.createTempFile("cat-bridge-", ".jsonl"));

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new HarnessRunnerScope()), input,
				transcript);

			requireThat(Files.readString(transcript), "transcript").contains("\"type\":\"started\"").
				contains("\"type\":\"completed\"");
		}
	}

	/**
	 * Verifies a bridge protocol failure retains an owned run with its failed state.
	 *
	 * @throws IOException if the bridge fails unexpectedly
	 */
	@Test
	public void retainsFailedManagedRun() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC());
			HarnessRun run = manager.create("harness");
			try (FakeProcess process = new FakeProcess();
				StringReader input = new StringReader("{\"type\":\"unknown\"}\n"))
			{
				HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new HarnessRunnerScope()), manager,
				false,
					input, new StringWriter());
				throw new AssertionError("Expected the bridge protocol failure");
			}
			catch (IOException e)
			{
				requireThat(e, "failure").isNotNull();
			}

			requireThat(Files.exists(run.directory()), "runDirectory").isTrue();
			requireThat(Files.readString(run.directory().resolve("run.json")), "manifest").contains("\"failed\"");
		}
	}

	/**
	 * Verifies a native process that exits before completing a turn records its exit status and leaves the owned run
	 * failed rather than allowing the bridge to complete it.
	 *
	 * @throws IOException if filesystem setup fails
	 */
	@Test
	public void recordsFailedNativeTurn() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC());
			HarnessRun run = manager.create("harness");
			try (FailedProcess process = new FailedProcess();
				StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n"))
			{
				HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new HarnessRunnerScope()), manager,
					true, input, new StringWriter());
				throw new AssertionError("Expected the native process failure");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("exit status 143");
			}

			String manifest = Files.readString(run.directory().resolve("run.json"));
			requireThat(manifest, "manifest").contains("\"state\": \"failed\"").
				contains("\"exitCode\": 143").contains("exit status 143");
		}
	}

	/**
	 * Verifies a clean native exit without a terminal turn event is still recorded as a failed bridge turn.
	 *
	 * @throws IOException if filesystem setup fails
	 */
	@Test
	public void rejectsCleanExitBeforeTurnCompletion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC());
			HarnessRun run = manager.create("harness");
			try (CleanExitProcess process = new CleanExitProcess();
				StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n"))
			{
				HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new HarnessRunnerScope()), manager,
					true, input, new StringWriter());
				throw new AssertionError("Expected the incomplete native turn to fail");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("before completing the requested turn");
			}

			requireThat(Files.readString(run.directory().resolve("run.json")), "manifest").
				contains("\"state\": \"failed\"");
		}
	}

	/**
	 * Verifies a test can steer and interrupt an active turn before asking the bridge to complete the session.
	 *
	 * @throws IOException if the bridge protocol fails
	 */
	@Test
	public void acceptsActiveTurnControls() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			BlockingProcess process = new BlockingProcess();
			StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n" +
				"{\"type\":\"steer\",\"prompt\":\"change course\"}\n{\"type\":\"interrupt\"}\n" +
				"{\"type\":\"complete\"}\n"))
		{
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));
			StringWriter output = new StringWriter();

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new HarnessRunnerScope()), input,
				output);

			requireThat(process.steers, "steers").isEqualTo(1);
			requireThat(process.interrupts, "interrupts").isEqualTo(1);
			requireThat(output.toString(), "output").contains("\"type\":\"steered\"").
				contains("\"type\":\"interrupted\"").contains("\"type\":\"turn-completed\"");
		}
	}

	/**
	 * Verifies an interrupt that reaches the bridge after its turn ended leaves the harness untouched and the bridge
	 * keeps serving later requests.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test(timeOut = 10_000)
	public void ignoresInterruptAfterTurnEnded() throws IOException
	{
		// Requirement: R-E10, R-E15
		CountDownLatch firstTurnCompleted = new CountDownLatch(1);
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			CountingInterruptProcess process = new CountingInterruptProcess();
			Reader input = new GatedReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n", firstTurnCompleted,
				"{\"type\":\"interrupt\"}\n{\"type\":\"turn\",\"prompt\":\"second\"}\n{\"type\":\"complete\"}\n");
			Writer output = new SignalingWriter("\"type\":\"turn-completed\"", firstTurnCompleted))
		{
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new HarnessRunnerScope()), input,
				output);

			requireThat(process.interrupts, "interrupts").isEqualTo(0);
			requireThat(process.turns, "turns").isEqualTo(2);
			requireThat(output.toString(), "output").doesNotContain("\"type\":\"interrupted\"").
				contains("\"type\":\"completed\"");
		}
	}

	/**
	 * Client input that withholds its later requests until an earlier bridge response has been written, so a test can
	 * order a request after the turn that precedes it has ended.
	 */
	private static final class GatedReader extends Reader
	{
		private final StringReader first;
		private final CountDownLatch gate;
		private final StringReader second;
		private boolean firstExhausted;

		/**
		 * Creates input that serves {@code first}, waits for {@code gate}, then serves {@code second}.
		 *
		 * @param first the requests available immediately
		 * @param gate opens once the bridge has written the response that the later requests must follow
		 * @param second the requests available after the gate opens
		 */
		private GatedReader(String first, CountDownLatch gate, String second)
		{
			this.first = new StringReader(first);
			this.gate = gate;
			this.second = new StringReader(second);
		}

		@Override
		public int read(char[] buffer, int offset, int length) throws IOException
		{
			if (!firstExhausted)
			{
				int count = first.read(buffer, offset, length);
				if (count != -1)
					return count;
				firstExhausted = true;
			}
			try
			{
				gate.await();
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while waiting for the gated bridge input", e);
			}
			return second.read(buffer, offset, length);
		}

		@Override
		public void close()
		{
			first.close();
			second.close();
		}
	}

	/**
	 * Bridge output that opens a latch once a response containing a marker has been written.
	 */
	private static final class SignalingWriter extends StringWriter
	{
		private final String marker;
		private final CountDownLatch written;

		/**
		 * Creates output that signals {@code written} after the first response containing {@code marker}.
		 *
		 * @param marker the text that identifies the awaited response
		 * @param written opened when that response is flushed
		 */
		private SignalingWriter(String marker, CountDownLatch written)
		{
			this.marker = marker;
			this.written = written;
		}

		@Override
		public void flush()
		{
			super.flush();
			if (toString().contains(marker))
				written.countDown();
		}
	}

	/**
	 * A process that completes each turn immediately and counts interrupt requests.
	 */
	private static final class CountingInterruptProcess implements HarnessProcess
	{
		private boolean closed;
		private int turns;
		private int interrupts;

		@Override
		public HarnessProcessResult sendTurn(String request)
		{
			assert request != null;
			turns += 1;
			return new HarnessProcessResult(0, request + "\n", "");
		}

		@Override
		public void interrupt()
		{
			interrupts += 1;
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
	}

	/**
	 * A deterministic persistent harness process.
	 */
	private static final class FakeProcess implements HarnessProcess
	{
		/**
		 * Whether the bridge closed this process.
		 */
		private boolean closed;
		/**
		 * Every completed request in bridge order.
		 */
		private final List<String> requests = new ArrayList<>();
		/**
		 * Final assistant responses returned in turn order.
		 */
		private final List<String> assistantResponses;
		/**
		 * The number of requests accepted by this process.
		 */
		private int turns;

		/**
		 * Creates a fake process with its terminal assistant responses.
		 *
		 * @param assistantResponses responses returned in request order
		 */
		private FakeProcess(String... assistantResponses)
		{
			this.assistantResponses = List.of(assistantResponses);
		}

		@Override
		public HarnessProcessResult sendTurn(String request)
		{
			assert request != null;
			int responseIndex = turns;
			turns += 1;
			requests.add(request);
			String response = "";
			if (responseIndex < assistantResponses.size())
				response = assistantResponses.get(responseIndex);
			return new HarnessProcessResult(0, request, "", true, response);
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
	}

	/**
	 * A native process that has ended before returning a terminal turn result.
	 */
	private static final class FailedProcess implements HarnessProcess
	{
		private boolean closed;

		@Override
		public HarnessProcessResult sendTurn(String request)
		{
			assert request != null;
			return new HarnessProcessResult(143, "partial native event\n", "terminated before completion");
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
	}

	/**
	 * A process that exits cleanly before emitting a terminal event for its turn.
	 */
	private static final class CleanExitProcess implements HarnessProcess
	{
		private boolean closed;

		@Override
		public HarnessProcessResult sendTurn(String request)
		{
			assert request != null;
			return new HarnessProcessResult(0, "partial native event\n", "ended before completion");
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
	}

	/**
	 * A process whose first turn remains active until the bridge sends an interruption.
	 */
	private static final class BlockingProcess implements HarnessProcess
	{
		private final CountDownLatch interrupted = new CountDownLatch(1);
		private boolean closed;
		private int steers;
		private int interrupts;

		@Override
		public HarnessProcessResult sendTurn(String request) throws IOException
		{
			assert request != null;
			try
			{
				interrupted.await();
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while waiting for bridge control", e);
			}
			return new HarnessProcessResult(0, "interrupted\n", "");
		}

		@Override
		public void steer(String request)
		{
			assert request != null;
			steers += 1;
		}

		@Override
		public void interrupt()
		{
			interrupts += 1;
			interrupted.countDown();
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
			interrupted.countDown();
		}
	}
}
