package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.testng.annotations.Test;

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
			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new HarnessRunnerScope()), manager, false,
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

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new HarnessRunnerScope()), input, transcript);

			requireThat(Files.readString(transcript), "transcript").contains("\"type\":\"started\"").
				contains("\"type\":\"completed\"");
		}
	}

	/**
	 * Verifies a route receipt names the installed rules and the following task turn in the retained transcript.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test
	public void recordsDeliveredRulesBeforeTaskTurn() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			FakeProcess process = new FakeProcess();
			StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"task\",\"route_preparation\":[" +
				"\"include/common/prefer-reuse.md\"]}\n{\"type\":\"complete\"}\n"))
		{
			Path ruleRoot = cleanup.add(Files.createTempDirectory("route-preparation-rules-"));
			Path rule = ruleRoot.resolve("include/common/prefer-reuse.md");
			Files.createDirectories(rule.getParent());
			Files.writeString(rule, "Use existing facilities.");
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));
			StringWriter output = new StringWriter();

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new HarnessRunnerScope(),
				HarnessRunLock.unlocked(), ruleRoot), input, output);

			requireThat(output.toString(), "output").contains("\"type\":\"route-delivered\"").
				contains("\"delivery_turn\":1").contains("\"precedes_task_turn\":2").
				contains("\"relative_path\":\"include/common/prefer-reuse.md\"").
				doesNotContain("\"delivery_turn\":2");
			requireThat(process.requests.size(), "nativeRequestCount").isEqualTo(2);
			requireThat(process.requests.getFirst(), "preTaskNativeRequest").contains("Use existing facilities.");
			requireThat(process.requests.get(1), "taskNativeRequest").isEqualTo("task");
		}
	}

	/**
	 * Verifies a routing-only diagnostic sends the ordinary route-selection turn without sending its later task turn.
	 *
	 * @throws IOException if bridge setup or transport fails
	 */
	@Test
	public void stopsAfterRoutingPreparation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			FakeProcess process = new FakeProcess("{\"selected_rules\":[]}");
			StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"task\",\"routing_only\":true}\n" +
				"{\"type\":\"complete\"}\n"))
		{
			Path ruleRoot = cleanup.add(Files.createTempDirectory("route-preparation-rules-"));
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new HarnessRunnerScope(),
				HarnessRunLock.unlocked(), ruleRoot), input, new StringWriter());

			requireThat(process.requests.size(), "nativeRequestCount").isEqualTo(1);
			requireThat(process.requests.getFirst(), "routeSelectionNativeRequest").contains("task").
				contains("You are preparing to execute the later user task");
		}
	}

	/**
	 * Verifies an ordinary model-selected route is recorded and its exact installed content reaches a native pre-task
	 * turn before the later task.
	 *
	 * @throws IOException if bridge setup or transport fails
	 */
	@Test
	public void deliversModelSelectedRulesBeforeTaskTurn() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			FakeProcess process = new FakeProcess("{\"selected_rules\":[\"include/common/prefer-reuse.md\"]}",
				"ROUTE STEP READY", "task result");
			StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"task\"}\n" +
				"{\"type\":\"complete\"}\n"))
		{
			Path ruleRoot = cleanup.add(Files.createTempDirectory("route-preparation-rules-"));
			Path rule = ruleRoot.resolve("include/common/prefer-reuse.md");
			Files.createDirectories(rule.getParent());
			Files.writeString(rule, "Use existing facilities.");
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));
			StringWriter output = new StringWriter();

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new HarnessRunnerScope(),
				HarnessRunLock.unlocked(), ruleRoot), input, output);

			requireThat(process.requests.size(), "nativeRequestCount").isEqualTo(3);
			requireThat(process.requests.getFirst(), "selectionRequest").contains("selected_rules").contains("task");
			requireThat(process.requests.get(1), "deliveryRequest").contains("Use existing facilities.");
			requireThat(process.requests.get(2), "taskRequest").isEqualTo("task");
			requireThat(output.toString(), "output").contains("\"type\":\"route-selected\"").
			contains("\"selection_turn\":1").contains("\"type\":\"route-delivered\"").
			contains("\"delivery_turn\":2").contains("\"precedes_task_turn\":3");
		}
	}

	/**
	 * Verifies an unstructured selection response stops before the later task starts.
	 *
	 * @throws IOException if bridge setup or validation fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = "CAT route selection must be JSON")
	public void rejectsUnstructuredRouteSelection() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			FakeProcess process = new FakeProcess("ROUTES READY");
			StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"task\"}\n" +
				"{\"type\":\"complete\"}\n"))
		{
			Path ruleRoot = cleanup.add(Files.createTempDirectory("route-preparation-rules-"));
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new HarnessRunnerScope(),
				HarnessRunLock.unlocked(), ruleRoot), input, new StringWriter());
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
				HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new HarnessRunnerScope()), manager, false,
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

			requireThat(Files.readString(run.directory().resolve("run.json")), "manifest").contains("\"state\": \"failed\"");
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

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new HarnessRunnerScope()), input, output);

			requireThat(process.steers, "steers").isEqualTo(1);
			requireThat(process.interrupts, "interrupts").isEqualTo(1);
			requireThat(output.toString(), "output").contains("\"type\":\"steered\"").
				contains("\"type\":\"interrupted\"").contains("\"type\":\"turn-completed\"");
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
