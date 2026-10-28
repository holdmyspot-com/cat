package com.holdmyspot.cat.common.runner.test;


import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.runner.HarnessEventListener;
import com.holdmyspot.cat.common.runner.HarnessProcess;
import com.holdmyspot.cat.common.runner.HarnessProcessResult;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessRunReceipt;
import com.holdmyspot.cat.common.runner.HarnessRunSession;
import com.holdmyspot.cat.common.runner.HarnessRunnerBridge;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.fakes.test.ManualTicker;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
				new TestHarnessRunnerScope()), input, output);

			requireThat(process.turns, "turns").isEqualTo(2);
			requireThat(process.closed, "closed").isTrue();
			requireThat(result, "result").isEqualTo(run);
			requireThat(output.toString(), "output").contains("\"type\":\"started\"").
				contains("\"type\":\"harness_event\"").contains("\"type\":\"turn_completed\"").
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

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), input,
				output);

			requireThat(output.toString(), "output").contains(
				"\"assistant_response\":\"result with an injected \\\"quote\\\"\"");
		}
	}

	/**
	 * Verifies the bridge retains every assistant message in order and keeps the final response distinct.
	 * The supplied list models the text-event order recorded in each harness's assistant-message fixtures.
	 *
	 * @throws IOException if the bridge fails
	 */
	// Requirement: R-C12
	@Test
	public void recordsEveryAssistantMessage() throws IOException
	{
		try (TestHarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories();
			FakeProcess process = new FakeProcess(List.of("interim with a \"quote\"", "done"));
			StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"task\"}\n" +
				"{\"type\":\"complete\"}\n"))
		{
			Path directory = cleanup.add(Files.createTempDirectory(
				scope.temporaryDirectory(), "cat-run-"));
			HarnessRun run = new HarnessRun("harness", "run-1", directory);
			StringWriter output = new StringWriter();

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, scope), input,
				output);

			JsonMapper json = JsonMapper.builder().build();
			JsonNode completed = output.toString().lines().map(json::readTree).
				filter(event -> event.path("type").asString().equals("turn_completed")).findFirst().orElseThrow();
			requireThat(completed.path("assistant_messages").size(), "messageCount").isEqualTo(2);
			requireThat(completed.path("assistant_messages").get(0).asString(), "interim").
				isEqualTo("interim with a \"quote\"");
			requireThat(completed.path("assistant_messages").get(1).asString(), "final").isEqualTo("done");
			requireThat(completed.path("assistant_response").asString(), "assistantResponse").isEqualTo("done");
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
		FileLocks fileLocks = new FileLocks();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			FakeProcess process = new FakeProcess();
			StringReader input = new StringReader("{\"type\":\"complete\"}\n"))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), manager,
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

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), input,
				transcript);

			requireThat(Files.readString(transcript), "transcript").contains("\"type\":\"started\"").
				contains("\"type\":\"completed\"");
		}
	}

	/**
	 * Verifies that a bridge writes its transcript to the output file that the runner's options name, rather than to
	 * the caller's stream, and a receipt that names the retained project and that transcript.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test
	public void writesRequestedTranscriptAndReceipt() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			FakeProcess process = new FakeProcess();
			StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n" +
				"{\"type\":\"complete\"}\n"))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			Path project = Files.createDirectories(run.directory().resolve("project"));
			Path evidence = cleanup.add(Files.createTempDirectory("cat-evidence-"));
			Path transcript = evidence.resolve("transcript.jsonl");
			Path receipt = evidence.resolve("receipt.json");
			HarnessRunnerOptions options = new HarnessRunnerOptions(root.resolve("artifact"), root, root, "harness", "",
				"", transcript, true, receipt, false, false, true, 1, List.of(), List.of(), null, List.of(), 0, null, 0, null);
			StringWriter output = new StringWriter();
			JsonMapper json = JsonMapper.builder().build();

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), manager,
				options, json, Yielding.fileReplacer(), input, output);

			requireThat(output.toString(), "output").isEmpty();
			requireThat(Files.readString(transcript), "transcript").contains("\"type\":\"started\"").
				contains("\"type\":\"completed\"");
			requireThat(HarnessRunReceipt.read(json, receipt).retainedProject(), "retainedProject").
				isEqualTo(project);
			requireThat(HarnessRunReceipt.read(json, receipt).rawResult(), "rawResult").isEqualTo(transcript);
		}
	}

	/**
	 * Verifies that a bridge given an input file serves the turns that the file holds, so that a caller that cannot
	 * pipe records to the runner's standard input can name them instead.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test
	public void servesTurnsFromInputFile() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			FakeProcess process = new FakeProcess();
			StringReader standardInput = new StringReader(""))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			Files.createDirectories(run.directory().resolve("project"));
			Path evidence = cleanup.add(Files.createTempDirectory("cat-evidence-"));
			Path input = Files.writeString(evidence.resolve("turns.jsonl"), "{\"type\":\"turn\",\"prompt\":\"first\"}\n" +
				"{\"type\":\"turn\",\"prompt\":\"second\"}\n{\"type\":\"complete\"}\n");
			HarnessRunnerOptions options = new HarnessRunnerOptions(root.resolve("artifact"), root, root, "harness", "",
				"", null, true, null, false, false, true, 1, List.of(), List.of(), null, List.of(), 0, null, 0, input);
			StringWriter output = new StringWriter();

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), manager,
				options, JsonMapper.builder().build(), Yielding.fileReplacer(), standardInput, output);

			requireThat(process.turns, "turns").isEqualTo(2);
			requireThat(output.toString(), "output").contains("\"type\":\"completed\"");
		}
	}

	/**
	 * Verifies that a bridge writes one {@code session_transcript} record with the native transcript's path as soon as
	 * the session's transcript is known, not before and not twice, and that the receipt lists the transcript copies
	 * that the session left in the run before the run's runtime files were pruned.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test
	public void recordsSessionTranscriptOnce() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-I7
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n" +
				"{\"type\":\"turn\",\"prompt\":\"second\"}\n{\"type\":\"complete\"}\n"))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			Files.createDirectories(run.directory().resolve("project"));
			Path nativeFile = run.directory().resolve("home/session-7.jsonl");
			Path evidence = cleanup.add(Files.createTempDirectory("cat-evidence-"));
			Path transcript = evidence.resolve("transcript.jsonl");
			Path receipt = evidence.resolve("receipt.json");
			HarnessRunnerOptions options = new HarnessRunnerOptions(root.resolve("artifact"), root, root, "harness", "",
				"", transcript, true, receipt, true, false, true, 1, List.of(), List.of(), null, List.of(), 0, null, 0, null);
			JsonMapper json = JsonMapper.builder().build();

			try (TranscriptProcess process = new TranscriptProcess(nativeFile, run.sessionTranscripts()))
			{
				HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()),
					manager, options, json, Yielding.fileReplacer(), input, new StringWriter());
			}

			List<String> records = Files.readAllLines(transcript).stream().
				filter(line -> line.contains("\"type\":\"session_transcript\"")).toList();
			requireThat(records, "records").size().isEqualTo(1);
			requireThat(records.getFirst(), "record").contains(nativeFile.toString().replace("\\", "\\\\"));
			requireThat(Files.exists(run.directory().resolve("home")), "homeExists").isFalse();
			requireThat(HarnessRunReceipt.read(json, receipt).sessionTranscripts(), "sessionTranscripts").
				isEqualTo(List.of(run.sessionTranscripts().resolve("session-7.jsonl")));
		}
	}

	/**
	 * Verifies that a bridge whose options did not ask for session transcripts writes no record for them and lists none
	 * in the receipt, even if the process reports a transcript path.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test
	public void leavesSessionTranscriptsOutUnlessAsked() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-I7
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			FakeProcess process = new FakeProcess();
			StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n" +
				"{\"type\":\"complete\"}\n"))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			Files.createDirectories(run.directory().resolve("project"));
			Path evidence = cleanup.add(Files.createTempDirectory("cat-evidence-"));
			Path transcript = evidence.resolve("transcript.jsonl");
			Path receipt = evidence.resolve("receipt.json");
			HarnessRunnerOptions options = new HarnessRunnerOptions(root.resolve("artifact"), root, root, "harness", "",
				"", transcript, true, receipt, false, false, true, 1, List.of(), List.of(), null, List.of(), 0, null, 0, null);
			JsonMapper json = JsonMapper.builder().build();

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), manager,
				options, json, Yielding.fileReplacer(), input, new StringWriter());

			requireThat(Files.readString(transcript), "transcript").doesNotContain("session_transcript");
			requireThat(HarnessRunReceipt.read(json, receipt).sessionTranscripts(), "sessionTranscripts").isEmpty();
			requireThat(Files.exists(run.sessionTranscripts()), "directoryExists").isFalse();
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
		FileLocks fileLocks = new FileLocks();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			try (FakeProcess process = new FakeProcess();
				StringReader input = new StringReader("{\"type\":\"unknown\"}\n"))
			{
				HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), manager,
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
	 * Verifies a harness process that exits before completing a turn records its exit status and leaves the owned run
	 * failed rather than allowing the bridge to complete it.
	 *
	 * @throws IOException if filesystem setup fails
	 */
	@Test
	public void recordsFailedHarnessTurn() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			try (FailedProcess process = new FailedProcess();
				StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n"))
			{
				HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), manager,
					true, input, new StringWriter());
				throw new AssertionError("Expected the harness process failure");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("exit status 143");
			}

			String manifest = Files.readString(run.directory().resolve("run.json"));
			requireThat(manifest, "manifest").contains("\"state\": \"failed\"").
				contains("\"exit_code\": 143").contains("exit status 143");
		}
	}

	/**
	 * Verifies that the bridge reports a turn that fails, whether the harness ends before completing it or rejects it,
	 * as soon as the turn ends, without waiting for the client's next request. The client sends its next request only
	 * after the bridge has reported the failure, so a bridge that waits for that request fails the test's time limit.
	 *
	 * @throws IOException if filesystem setup fails
	 */
	@Test(timeOut = 10_000)
	public void reportsFailedTurnWhenItEnds() throws IOException
	{
		try (FailedProcess process = new FailedProcess())
		{
			requireTurnFailureReported(process, "exit status 143");
		}
		try (RejectingProcess process = new RejectingProcess())
		{
			requireTurnFailureReported(process, "the harness rejected the turn");
		}
	}

	/**
	 * Runs one turn that fails and verifies that the bridge reports the failure before the client's next request.
	 *
	 * @param process  the harness process, whose turn fails
	 * @param expected text that the failure's description contains
	 * @throws IOException if filesystem setup fails
	 */
	private static void requireTurnFailureReported(HarnessProcess process, String expected) throws IOException
	{
		CountDownLatch turnFailed = new CountDownLatch(1);
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			Reader input = new GatedReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n", turnFailed,
				"{\"type\":\"complete\"}\n", new CountDownLatch(1), "");
			Writer output = new SignalingWriter("\"type\":\"turn_failed\"", turnFailed, new CountDownLatch(0)))
		{
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));
			try
			{
				HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), input,
					output);
				throw new AssertionError("Expected the turn's failure");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains(expected);
			}
			JsonNode failure = null;
			JsonMapper json = JsonMapper.builder().build();
			for (String line : output.toString().lines().toList())
			{
				JsonNode record = json.readTree(line);
				if (record.path("type").asString().equals("turn_failed"))
					failure = record;
			}
			requireThat(failure, "failure").withContext(output, "output").isNotNull();
			requireThat(failure.path("run_id").asString(), "runId").isEqualTo("run-1");
			requireThat(failure.path("error").asString(), "error").contains(expected);
		}
	}

	/**
	 * Verifies a clean harness exit without a terminal turn event is still recorded as a failed bridge turn.
	 *
	 * @throws IOException if filesystem setup fails
	 */
	@Test
	public void rejectsCleanExitBeforeTurnCompletion() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			try (CleanExitProcess process = new CleanExitProcess();
				StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n"))
			{
				HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), manager,
					true, input, new StringWriter());
				throw new AssertionError("Expected the incomplete harness turn to fail");
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
	 * Verifies that a failure of the bridge's input while a turn is still running stops that turn and closes the
	 * session, instead of waiting for a turn that may never end. The bridge runs on its own thread so that a bridge
	 * that waits forever fails the test instead of hanging it.
	 *
	 * @throws IOException if filesystem setup fails
	 * @throws InterruptedException if the test is interrupted while it waits
	 */
	@Test(timeOut = 30_000)
	public void stopsRunningTurnWhenInputFails() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			HangingProcess process = new HangingProcess();
			FailingReader input = new FailingReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n", process.started))
		{
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));
			CompletableFuture<HarnessRun> serving = new CompletableFuture<>();
			Thread.ofPlatform().daemon().name("bridge-under-test").start(() ->
			{
				try
				{
					serving.complete(HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process,
						new TestHarnessRunnerScope()), input, new StringWriter()));
				}
				catch (IOException | RuntimeException e)
				{
					serving.completeExceptionally(e);
				}
			});
			try
			{
				serving.get(5, TimeUnit.SECONDS);
				throw new AssertionError("Expected the input failure");
			}
			catch (ExecutionException e)
			{
				requireThat(e.getCause(), "cause").isInstanceOf(IOException.class);
				requireThat(e.getCause().getMessage(), "message").contains("input failed");
			}
			catch (TimeoutException e)
			{
				throw new AssertionError("The bridge did not stop the running turn after its input failed", e);
			}
			finally
			{
				process.release();
			}

			requireThat(process.closed, "closed").isTrue();
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

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), input,
				output);

			requireThat(process.steers, "steers").isEqualTo(1);
			requireThat(process.interrupts, "interrupts").isEqualTo(1);
			requireThat(output.toString(), "output").contains("\"type\":\"steered\"").
				contains("\"type\":\"interrupted\"").contains("\"type\":\"turn_completed\"");
		}
	}

	/**
	 * Verifies an interrupt that reaches the bridge after its turn ended leaves the harness untouched and the bridge
	 * keeps serving later requests. The bridge's turn thread is held right after it writes {@code turn_completed}
	 * until the bridge has handled the interrupt, so the interrupt reaches the bridge while the thread that ran the
	 * turn has not yet returned.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test(timeOut = 10_000)
	public void ignoresInterruptAfterTurnEnded() throws IOException
	{
		// Requirement: R-E10, R-E15
		CountDownLatch firstTurnCompleted = new CountDownLatch(1);
		CountDownLatch interruptHandled = new CountDownLatch(1);
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			CountingInterruptProcess process = new CountingInterruptProcess(interruptHandled);
			Reader input = new GatedReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n", firstTurnCompleted,
				"{\"type\":\"interrupt\"}\n", interruptHandled,
				"{\"type\":\"turn\",\"prompt\":\"second\"}\n{\"type\":\"complete\"}\n");
			Writer output = new SignalingWriter("\"type\":\"turn_completed\"", firstTurnCompleted, interruptHandled))
		{
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), input,
				output);

			requireThat(process.interrupts, "interrupts").isEqualTo(0);
			requireThat(process.turns, "turns").isEqualTo(2);
			requireThat(output.toString(), "output").doesNotContain("\"type\":\"interrupted\"").
				contains("\"type\":\"completed\"");
		}
	}

	/**
	 * Verifies that while a turn writes no record for 30 seconds, the bridge writes a {@code waiting} record and
	 * repeats it every 30 seconds; that any other record of the turn restarts that count; and that no {@code waiting}
	 * record is written before a turn, after {@code turn_completed}, or between turns. The test moves the clock and runs
	 * the heartbeat itself.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test(timeOut = 10_000)
	public void reportsQuietTurnEveryThirtySeconds() throws IOException
	{
		// Requirement: R-E14
		try (TestHarnessRunnerScope scope = new TestHarnessRunnerScope())
		{
			verifyQuietTurnReports(scope);
		}
	}

	/**
	 * Runs a quiet turn whose clock and heartbeat the scope supplies, and verifies the bridge's records.
	 *
	 * @param scope the scope of the run's process, whose clock and heartbeat the test moves
	 * @throws IOException if the bridge fails
	 */
	private static void verifyQuietTurnReports(TestHarnessRunnerScope scope) throws IOException
	{
		ManualClock clock = scope.clock();
		ManualTicker ticker = scope.heartbeatTicker();
		CountDownLatch turnCompleted = new CountDownLatch(1);
		Runnable quietBetweenTurns = () ->
		{
			clock.advance(Duration.ofSeconds(90));
			ticker.tick();
			ticker.tick();
		};
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			QuietProcess process = new QuietProcess(clock, ticker);
			Reader input = new QuietReader(quietBetweenTurns, "{\"type\":\"turn\",\"prompt\":\"first\"}\n",
				turnCompleted, "{\"type\":\"complete\"}\n");
			Writer output = new SignalingWriter("\"type\":\"turn_completed\"", turnCompleted, new CountDownLatch(0)))
		{
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, scope), null, true, input, output);

			JsonMapper json = JsonMapper.builder().build();
			List<String> records = new ArrayList<>();
			for (String line : output.toString().lines().toList())
			{
				JsonNode record = json.readTree(line);
				String type = record.path("type").asString();
				if (type.equals("waiting"))
				{
					requireThat(record.path("run_id").asString(), "runId").isEqualTo("run-1");
					records.add(type + " " + record.path("quiet_seconds").asLong());
				}
				else
					records.add(type);
			}
			requireThat(records, "records").isEqualTo(List.of("started", "waiting 30", "waiting 60", "harness_event",
				"waiting 30", "turn_completed", "completed"));
		}
	}

	/**
	 * A process whose turn stays quiet while the test moves the clock and runs the heartbeat, prints one harness
	 * event, stays quiet again and then completes.
	 */
	private static final class QuietProcess implements HarnessProcess
	{
		private final ManualClock clock;
		private final ManualTicker ticker;

		/**
		 * Creates a process.
		 *
		 * @param clock  the bridge's clock
		 * @param ticker the bridge's heartbeat
		 */
		private QuietProcess(ManualClock clock, ManualTicker ticker)
		{
			this.clock = clock;
			this.ticker = ticker;
		}

		/**
		 * Moves the clock forward and runs the heartbeat.
		 *
		 * @param seconds how far to move the clock
		 */
		private void quiet(int seconds)
		{
			clock.advance(Duration.ofSeconds(seconds));
			ticker.tick();
		}

		@Override
		public HarnessProcessResult sendTurn(String request)
		{
			throw new AssertionError("The bridge relays harness events");
		}

		@Override
		public HarnessProcessResult sendTurn(String request, HarnessEventListener listener) throws IOException
		{
			quiet(29);
			quiet(1);
			quiet(29);
			quiet(1);
			listener.accept("harness line");
			quiet(29);
			quiet(1);
			return new HarnessProcessResult(0, "harness line\n", "", true, "done", List.of("done"));
		}

		@Override
		public boolean isAlive()
		{
			return true;
		}

		@Override
		public void close()
		{
		}
	}

	/**
	 * Client input that runs an action before its first request and again once the turn has completed, before it sends
	 * its last request.
	 */
	private static final class QuietReader extends Reader
	{
		private final Runnable quiet;
		private final StringReader turn;
		private final CountDownLatch turnCompleted;
		private final StringReader rest;
		private boolean started;
		private boolean turnExhausted;

		/**
		 * Creates the input.
		 *
		 * @param quiet         runs before the first request and once {@code turnCompleted} opens
		 * @param turn          the turn request
		 * @param turnCompleted opens once the bridge has written {@code turn_completed}
		 * @param rest          the requests sent after the turn completed
		 */
		private QuietReader(Runnable quiet, String turn, CountDownLatch turnCompleted, String rest)
		{
			this.quiet = quiet;
			this.turn = new StringReader(turn);
			this.turnCompleted = turnCompleted;
			this.rest = new StringReader(rest);
		}

		@Override
		public int read(char[] buffer, int offset, int length) throws IOException
		{
			if (!started)
			{
				started = true;
				quiet.run();
			}
			if (!turnExhausted)
			{
				int count = turn.read(buffer, offset, length);
				if (count != -1)
					return count;
				turnExhausted = true;
				try
				{
					turnCompleted.await();
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while waiting for the turn to complete", e);
				}
				quiet.run();
			}
			return rest.read(buffer, offset, length);
		}

		@Override
		public void close()
		{
			turn.close();
			rest.close();
		}
	}

	/**
	 * Client input that sends a request only after an earlier bridge response has been written, and reports when the
	 * bridge has handled that request.
	 */
	private static final class GatedReader extends Reader
	{
		private final StringReader first;
		private final CountDownLatch gate;
		private final StringReader gated;
		private final CountDownLatch gatedHandled;
		private final StringReader rest;
		private boolean firstExhausted;
		private boolean gatedExhausted;

		/**
		 * Creates input that serves {@code first}, waits for {@code gate}, serves {@code gated}, then opens
		 * {@code gatedHandled} and serves {@code rest}.
		 *
		 * @param first the requests available immediately
		 * @param gate opens once the bridge has written the response that {@code gated} must follow
		 * @param gated one request line sent after {@code gate} opens
		 * @param gatedHandled opened when the bridge asks for input after {@code gated}, which it does only once it
		 *   has handled that request
		 * @param rest the requests after {@code gated}
		 */
		private GatedReader(String first, CountDownLatch gate, String gated, CountDownLatch gatedHandled, String rest)
		{
			this.first = new StringReader(first);
			this.gate = gate;
			this.gated = new StringReader(gated);
			this.gatedHandled = gatedHandled;
			this.rest = new StringReader(rest);
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
			if (!gatedExhausted)
			{
				try
				{
					gate.await();
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while waiting for the gated bridge input", e);
				}
				int count = gated.read(buffer, offset, length);
				if (count != -1)
					return count;
				gatedExhausted = true;
			}
			gatedHandled.countDown();
			return rest.read(buffer, offset, length);
		}

		@Override
		public void close()
		{
			first.close();
			gated.close();
			rest.close();
		}
	}

	/**
	 * Bridge output that opens a latch once a response containing a marker has been written, then holds the writing
	 * thread until another latch opens.
	 */
	private static final class SignalingWriter extends StringWriter
	{
		private final String marker;
		private final CountDownLatch written;
		private final CountDownLatch release;

		/**
		 * Creates output that signals {@code written} after the first response containing {@code marker}, and holds
		 * the thread that wrote it until {@code release} opens.
		 *
		 * @param marker the text that identifies the awaited response
		 * @param written opened when that response is flushed
		 * @param release opens when the thread that wrote the response may continue
		 */
		private SignalingWriter(String marker, CountDownLatch written, CountDownLatch release)
		{
			this.marker = marker;
			this.written = written;
			this.release = release;
		}

		@Override
		public void flush()
		{
			super.flush();
			if (written.getCount() == 0 || !toString().contains(marker))
				return;
			written.countDown();
			try
			{
				release.await();
			}
			catch (InterruptedException _)
			{
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * A process that completes each turn immediately and counts interrupt requests.
	 */
	private static final class CountingInterruptProcess implements HarnessProcess
	{
		private final CountDownLatch interrupted;
		private boolean closed;
		private int turns;
		private int interrupts;

		/**
		 * Creates a process.
		 *
		 * @param interrupted opened when the process receives an interrupt
		 */
		private CountingInterruptProcess(CountDownLatch interrupted)
		{
			this.interrupted = interrupted;
		}

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
		}
	}

	/**
	 * A harness process that learns its session's transcript while serving its first turn and, when closed, copies the
	 * transcript into the run as a process that was asked to keep session transcripts does.
	 */
	private static final class TranscriptProcess implements HarnessProcess
	{
		private final Path nativeTranscript;
		private final Path copies;
		private boolean known;
		private boolean closed;

		/**
		 * Creates the process.
		 *
		 * @param nativeTranscript where the harness writes the session's transcript
		 * @param copies the directory that receives the copy when the process closes
		 */
		private TranscriptProcess(Path nativeTranscript, Path copies)
		{
			this.nativeTranscript = nativeTranscript;
			this.copies = copies;
		}

		@Override
		public HarnessProcessResult sendTurn(String request) throws IOException
		{
			assert request != null;
			Files.createDirectories(nativeTranscript.getParent());
			Files.writeString(nativeTranscript, "{}\n");
			known = true;
			return new HarnessProcessResult(0, request, "", true, "done", List.of("done"));
		}

		@Override
		public Optional<Path> sessionTranscript()
		{
			if (!known)
				return Optional.empty();
			return Optional.of(nativeTranscript);
		}

		@Override
		public boolean isAlive()
		{
			return !closed;
		}

		@Override
		public void close() throws IOException
		{
			if (closed)
				return;
			closed = true;
			Files.createDirectories(copies);
			Files.copy(nativeTranscript, copies.resolve(nativeTranscript.getFileName()));
		}
	}

	/**
	 * A deterministic persistent harness process.
	 */
	private static final class FakeProcess implements HarnessProcess
	{
		/**
		 * Indicates whether the bridge closed this process.
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
		private List<String> assistantMessages;
		/**
		 * The number of requests accepted by this process.
		 */
		private int turns;

		/**
		 * Creates a fake process with its terminal assistant responses.
		 *
		 * @param assistantMessages responses returned in request order
		 */
		private FakeProcess(String... assistantMessages)
		{
			this.assistantResponses = List.of(assistantMessages);
		}

		/**
		 * Creates a fake supplying every message of one completed turn.
		 *
		 * @param assistantMessages the ordered interim and final messages
		 */
		private FakeProcess(List<String> assistantMessages)
		{
			this.assistantMessages = assistantMessages;
			this.assistantResponses = List.of(assistantMessages.getLast());
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
			if (assistantMessages != null)
				return new HarnessProcessResult(0, request, "", true, response, assistantMessages);
			return new HarnessProcessResult(0, request, "", true, response, List.of(response));
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
	 * A harness process that has ended before returning a terminal turn result.
	 */
	private static final class FailedProcess implements HarnessProcess
	{
		private boolean closed;

		@Override
		public HarnessProcessResult sendTurn(String request)
		{
			assert request != null;
			return new HarnessProcessResult(143, "partial harness event\n", "terminated before completion");
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
	 * A harness process that rejects its turn.
	 */
	private static final class RejectingProcess implements HarnessProcess
	{
		private boolean closed;

		@Override
		public HarnessProcessResult sendTurn(String request) throws IOException
		{
			assert request != null;
			throw new IOException("the harness rejected the turn");
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
			return new HarnessProcessResult(0, "partial harness event\n", "ended before completion");
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

	/**
	 * A process whose turn does not end until the thread that runs it is interrupted.
	 */
	private static final class HangingProcess implements HarnessProcess
	{
		private final CountDownLatch started = new CountDownLatch(1);
		private final CountDownLatch released = new CountDownLatch(1);
		private volatile boolean closed;

		/**
		 * Lets the turn end, so that a test that failed does not leave a thread waiting.
		 */
		private void release()
		{
			released.countDown();
		}

		@Override
		public HarnessProcessResult sendTurn(String request) throws IOException
		{
			assert request != null;
			started.countDown();
			try
			{
				released.await();
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while the turn was running", e);
			}
			return new HarnessProcessResult(0, "", "");
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
	 * Input that supplies one request and then fails once a turn has started.
	 */
	private static final class FailingReader extends Reader
	{
		private final StringReader request;
		private final CountDownLatch turnStarted;

		/**
		 * Creates the input.
		 *
		 * @param request the request text to supply first
		 * @param turnStarted opened when the turn that the request starts is running
		 */
		private FailingReader(String request, CountDownLatch turnStarted)
		{
			this.request = new StringReader(request);
			this.turnStarted = turnStarted;
		}

		@Override
		public int read(char[] buffer, int offset, int length) throws IOException
		{
			int count = request.read(buffer, offset, length);
			if (count >= 0)
				return count;
			try
			{
				turnStarted.await();
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while waiting for the turn to start", e);
			}
			throw new IOException("input failed");
		}

		@Override
		public void close()
		{
			request.close();
		}
	}
}
