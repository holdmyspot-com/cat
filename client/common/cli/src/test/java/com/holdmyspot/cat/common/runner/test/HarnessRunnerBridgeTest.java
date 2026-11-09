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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.runner.HarnessProcess;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessRunReceipt;
import com.holdmyspot.cat.common.runner.HarnessRunSession;
import com.holdmyspot.cat.common.runner.HarnessRunnerBridge;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessSessionTranscripts;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import com.holdmyspot.cat.common.runner.fakes.test.ManualTicker;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness.Turn;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedProcess;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedProcess.InterruptedTurn;
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
		List<String> received = new CopyOnWriteArrayList<>();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("one", "harness event"),
				Turn.replies("two", "harness event")), received);
			StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n" +
				"{\"type\":\"turn\",\"prompt\":\"second\"}\n{\"type\":\"complete\"}\n"))
		{
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));
			StringWriter output = new StringWriter();

			HarnessRun result = HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process,
				new TestHarnessRunnerScope()), input, output);

			requireThat(received, "received").isEqualTo(List.of("first", "second"));
			requireThat(process.isAlive(), "alive").isFalse();
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
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("result with an injected \"quote\"")),
				new ArrayList<>());
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
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies(List.of("interim with a \"quote\"",
				"done"))), new ArrayList<>());
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
			ScriptedProcess process = new ScriptedProcess(List.of(), new ArrayList<>());
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
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("done")), new ArrayList<>());
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
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("done")), new ArrayList<>());
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
			HarnessRunnerOptions options = new HarnessRunnerOptions(root.resolve("artifact"), root, root, "model-a",
				"effort-a", transcript, true, receipt, false, false, 1,
				List.of(), List.of(), null, List.of(), 0, null, 0, null, null, null, false, null,
				null, null);
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
		List<String> received = new CopyOnWriteArrayList<>();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("one"), Turn.replies("two")), received);
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
			HarnessRunnerOptions options = new HarnessRunnerOptions(root.resolve("artifact"), root, root, "model-a",
				"effort-a", null, true, null, false, false, 1,
				List.of(), List.of(), null, List.of(), 0, null, 0, input, null, null, false, null,
				null, null);
			StringWriter output = new StringWriter();

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), manager,
				options, JsonMapper.builder().build(), Yielding.fileReplacer(), standardInput, output);

			requireThat(received, "received").isEqualTo(List.of("first", "second"));
			requireThat(output.toString(), "output").contains("\"type\":\"completed\"");
		}
	}

	/**
	 * Verifies that a runner that no harness runs holds a conversation in place: the bridge serves the messages that
	 * the asking session appended to {@code to-child.jsonl} as its records, an interrupt ends only the turn in
	 * progress although the harness fails that turn, as Claude Code does, and {@code from-child.jsonl} receives the
	 * bridge's records, a {@code turn_end} line after each turn and an {@code ended} line last.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test(timeOut = 10_000)
	public void holdsConversationInPlace() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		List<String> received = new CopyOnWriteArrayList<>();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.blocksUntilInterrupted(), Turn.replies("two")),
				received, InterruptedTurn.FAILS);
			StringReader standardInput = new StringReader(""))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			Files.createDirectories(run.directory().resolve("project"));
			Path folder = Files.createDirectory(cleanup.add(Files.createTempDirectory("cat-asking-")).
				resolve("conversation"));
			// The asking session may write its messages before it starts the runner
			Files.writeString(folder.resolve("to-child.jsonl"), "{\"turn\": \"first\"}\n{\"interrupt\": true}\n" +
				"{\"turn\": \"second\"}\n{\"end\": true}\n");
			HarnessRunnerOptions options = HarnessRunnerOptions.parse(new String[]{"--artifact",
				root.resolve("artifact").toString(), "--run-root", root.toString(), "--keep-run", "--model", "model-a",
				"--effort", "effort-a", "--conversation", folder.toString()}, "harness",
				HarnessTemporaryRoot.of(root));
			StringWriter output = new StringWriter();

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), manager,
				options, JsonMapper.builder().build(), Yielding.fileReplacer(), standardInput, output);

			requireThat(received, "received").isEqualTo(List.of("first", "second"));
			JsonMapper json = JsonMapper.builder().build();
			List<String> fromChild = new ArrayList<>();
			for (String line : Files.readAllLines(folder.resolve("from-child.jsonl")))
			{
				String type = json.readTree(line).path("type").asString("");
				if (type.isEmpty())
					fromChild.add(line);
				else if (List.of("interrupted", "turn_failed", "turn_completed", "completed").contains(type))
					fromChild.add(type);
			}
			requireThat(fromChild, "fromChild").isEqualTo(List.of("interrupted", "turn_failed",
				"{\"turn_end\":1,\"status\":\"interrupted\"}", "turn_completed",
				"{\"turn_end\":2,\"status\":\"completed\"}", "completed", "{\"ended\":\"completed\"}"));
			requireThat(output.toString(), "output").contains("\"assistant_response\":\"two\"");
		}
	}

	/**
	 * Verifies that an asking session that ends a conversation while a turn is in progress can still stop the turn:
	 * the {@code interrupt} that follows {@code end} ends the turn, and the conversation then ends. Otherwise a turn
	 * that never finishes keeps the launcher running, and nothing that the asking session appends can stop it.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test(timeOut = 10_000)
	public void interruptsTurnThatEndWaitsFor() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		List<String> received = new CopyOnWriteArrayList<>();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.blocksUntilInterrupted()), received,
				InterruptedTurn.FAILS);
			StringReader standardInput = new StringReader(""))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			Files.createDirectories(run.directory().resolve("project"));
			Path folder = Files.createDirectory(cleanup.add(Files.createTempDirectory("cat-asking-")).
				resolve("conversation"));
			Files.writeString(folder.resolve("to-child.jsonl"), "{\"turn\": \"first\"}\n{\"end\": true}\n" +
				"{\"interrupt\": true}\n");
			HarnessRunnerOptions options = HarnessRunnerOptions.parse(new String[]{"--artifact",
				root.resolve("artifact").toString(), "--run-root", root.toString(), "--keep-run", "--model", "model-a",
				"--effort", "effort-a", "--conversation", folder.toString()}, "harness",
				HarnessTemporaryRoot.of(root));

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), manager,
				options, JsonMapper.builder().build(), Yielding.fileReplacer(), standardInput, new StringWriter());

			requireThat(received, "received").isEqualTo(List.of("first"));
			JsonMapper json = JsonMapper.builder().build();
			List<String> fromChild = new ArrayList<>();
			for (String line : Files.readAllLines(folder.resolve("from-child.jsonl")))
			{
				String type = json.readTree(line).path("type").asString("");
				if (type.isEmpty())
					fromChild.add(line);
				else if (List.of("interrupted", "turn_failed", "completed").contains(type))
					fromChild.add(type);
			}
			requireThat(fromChild, "fromChild").isEqualTo(List.of("interrupted", "turn_failed",
				"{\"turn_end\":1,\"status\":\"interrupted\"}", "completed", "{\"ended\":\"completed\"}"));
		}
	}

	/**
	 * Verifies that a bridge writes one {@code session_transcript} record with the native transcript's path once the
	 * process names the session's transcript, not once per turn, and that the receipt lists the transcript copies that
	 * the session left in the run before the run's runtime files were pruned.
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
			HarnessRunnerOptions options = new HarnessRunnerOptions(root.resolve("artifact"), root, root, "model-a",
				"effort-a", transcript, true, receipt, true, false, 1,
				List.of(), List.of(), null, List.of(), 0, null, 0, null, null, null, false, null,
				null, null);
			JsonMapper json = JsonMapper.builder().build();
			// The harness writes the session's transcript in its home as it works on the session
			Files.createDirectories(nativeFile.getParent());
			Files.writeString(nativeFile, "{}\n");

			try (ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("one"), Turn.replies("two")),
				new ArrayList<>(), nativeFile, new HarnessSessionTranscripts(run.directory().resolve("home"),
				run.sessionTranscripts(), name -> name.endsWith(".jsonl"))))
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
	 * Verifies that a bridge whose options did not ask for session transcripts, and whose process therefore keeps none,
	 * writes no record for them and lists none in the receipt.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test
	public void leavesSessionTranscriptsOutUnlessAsked() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-I7
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("done")), new ArrayList<>());
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
			HarnessRunnerOptions options = new HarnessRunnerOptions(root.resolve("artifact"), root, root, "model-a",
				"effort-a", transcript, true, receipt, false, false, 1,
				List.of(), List.of(), null, List.of(), 0, null, 0, null, null, null, false, null,
				null, null);
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
			try (ScriptedProcess process = new ScriptedProcess(List.of(), new ArrayList<>());
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
			try (ScriptedProcess process = new ScriptedProcess(List.of(Turn.exits(143, "partial harness event")),
				new ArrayList<>());
				StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n" +
					"{\"type\":\"complete\"}\n"))
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
		try (ScriptedProcess process = new ScriptedProcess(List.of(Turn.exits(143, "partial harness event")),
			new ArrayList<>()))
		{
			requireTurnFailureReported(process, "exit status 143");
		}
		try (ScriptedProcess process = new ScriptedProcess(List.of(Turn.rejected()), new ArrayList<>()))
		{
			requireTurnFailureReported(process, "The harness rejected the turn");
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
			try (ScriptedProcess process = new ScriptedProcess(List.of(Turn.exits(0, "partial harness event")),
				new ArrayList<>());
				StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n" +
					"{\"type\":\"complete\"}\n"))
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
		CountDownLatch started = new CountDownLatch(1);
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.blocksUntilInterrupted().announcing(started)),
				new ArrayList<>());
			FailingReader input = new FailingReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n", started))
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
				// Ends the turn, so that a test that failed does not leave a thread waiting
				process.interrupt();
			}

			requireThat(process.isAlive(), "alive").isFalse();
		}
	}

	/**
	 * Verifies that a bridge whose input ends without {@code complete} while a turn is still running stops that turn
	 * and closes the session, so that a runner whose requester has gone away stops instead of serving a turn that no
	 * one reads. The bridge runs on its own thread so that a bridge that waits forever fails the test instead of
	 * hanging it.
	 *
	 * @throws IOException if filesystem setup fails
	 * @throws InterruptedException if the test is interrupted while it waits
	 */
	@Test(timeOut = 30_000)
	public void stopsRunningTurnWhenInputEnds() throws IOException, InterruptedException
	{
		CountDownLatch started = new CountDownLatch(1);
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.blocksUntilInterrupted().announcing(started)),
				new ArrayList<>());
			FailingReader input = FailingReader.ending("{\"type\":\"turn\",\"prompt\":\"first\"}\n",
				started))
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
				throw new AssertionError("Expected the bridge to fail for its unfinished input");
			}
			catch (ExecutionException e)
			{
				requireThat(e.getCause(), "cause").isInstanceOf(IOException.class);
				requireThat(e.getCause().getMessage(), "message").contains("without a complete message");
			}
			catch (TimeoutException e)
			{
				throw new AssertionError("The bridge did not stop the running turn after its input ended", e);
			}
			finally
			{
				// Ends the turn, so that a test that failed does not leave a thread waiting
				process.interrupt();
			}

			requireThat(process.isAlive(), "alive").isFalse();
		}
	}

	/**
	 * Verifies a client can steer and interrupt an active turn before asking the bridge to complete the session. The
	 * client sends its controls once the harness has printed the turn's first line.
	 *
	 * @throws IOException if the bridge protocol fails
	 */
	@Test(timeOut = 10_000)
	public void acceptsActiveTurnControls() throws IOException
	{
		List<String> received = new CopyOnWriteArrayList<>();
		CountDownLatch printed = new CountDownLatch(1);
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.blocksUntilInterrupted("harness line").
				announcing(printed)), received);
			Reader input = new GatedReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n", printed,
				"{\"type\":\"steer\",\"prompt\":\"change course\"}\n{\"type\":\"interrupt\"}\n",
				new CountDownLatch(1), "{\"type\":\"complete\"}\n"))
		{
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));
			StringWriter output = new StringWriter();

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), input,
				output);

			requireThat(received, "received").isEqualTo(List.of("first", "change course"));
			requireThat(output.toString(), "output").contains("\"type\":\"steered\"").
				contains("\"type\":\"interrupted\"").contains("\"type\":\"turn_completed\"");
		}
	}

	/**
	 * Verifies that an interrupt ends only the turn in progress when the harness fails the interrupted turn, as Claude
	 * Code does: the bridge reports the turn's end, keeps the harness and serves the client's next turn.
	 *
	 * @throws IOException if the bridge fails
	 */
	@Test(timeOut = 10_000)
	public void servesTurnAfterInterruptedTurnFails() throws IOException
	{
		List<String> received = new CopyOnWriteArrayList<>();
		CountDownLatch printed = new CountDownLatch(1);
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.blocksUntilInterrupted("harness line").
				announcing(printed), Turn.replies("two")), received, InterruptedTurn.FAILS);
			Reader input = new GatedReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n", printed,
				"{\"type\":\"interrupt\"}\n", new CountDownLatch(1),
				"{\"type\":\"turn\",\"prompt\":\"second\"}\n{\"type\":\"complete\"}\n"))
		{
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));
			StringWriter output = new StringWriter();

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), input,
				output);

			requireThat(received, "received").isEqualTo(List.of("first", "second"));
			requireThat(output.toString(), "output").contains("\"type\":\"interrupted\"").
				contains("\"type\":\"turn_failed\"").contains("\"assistant_response\":\"two\"").
				contains("\"type\":\"completed\"");
		}
	}

	/**
	 * Verifies that an interrupt that the bridge handles after it started a turn but before the turn's thread sent the
	 * turn to the harness stops that turn once the harness receives it. A session sends a turn while it holds the
	 * process's monitor, so the test holds that monitor until the bridge has handled the interrupt, which keeps the
	 * turn's thread from sending the turn until then. The bridge runs on its own thread so that a bridge that waits for
	 * a turn that was never stopped fails the test instead of hanging it.
	 *
	 * @throws IOException          if filesystem setup fails
	 * @throws InterruptedException if the test is interrupted while it waits
	 */
	@Test(timeOut = 30_000)
	public void stopsTurnInterruptedBeforeSending() throws IOException, InterruptedException
	{
		CountDownLatch interruptHandled = new CountDownLatch(1);
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.blocksUntilInterrupted()), new ArrayList<>());
			Reader input = new GatedReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n", new CountDownLatch(0),
				"{\"type\":\"interrupt\"}\n", interruptHandled, "{\"type\":\"complete\"}\n"))
		{
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));
			StringWriter output = new StringWriter();
			CompletableFuture<HarnessRun> serving = new CompletableFuture<>();
			synchronized (process)
			{
				Thread.ofPlatform().daemon().name("bridge-under-test").start(() ->
				{
					try
					{
						serving.complete(HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process,
							new TestHarnessRunnerScope()), input, output));
					}
					catch (IOException | RuntimeException e)
					{
						serving.completeExceptionally(e);
					}
				});
				requireThat(interruptHandled.await(5, TimeUnit.SECONDS), "interruptHandled").isTrue();
			}
			try
			{
				serving.get(5, TimeUnit.SECONDS);
			}
			catch (ExecutionException e)
			{
				throw new AssertionError(e.getCause());
			}
			catch (TimeoutException e)
			{
				throw new AssertionError("The interrupt did not stop the turn that had not yet reached the harness", e);
			}
			finally
			{
				// Ends the turn, so that a test that failed does not leave a thread waiting
				process.interrupt();
			}

			requireThat(output.toString(), "output").contains("\"type\":\"interrupted\"").
				contains("\"type\":\"turn_completed\"").contains("\"type\":\"completed\"");
		}
	}

	/**
	 * Verifies an interrupt that reaches the bridge after its turn ended is ignored and the bridge keeps serving later
	 * requests. The bridge's turn thread is held right after it writes {@code turn_completed}
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
		List<String> received = new CopyOnWriteArrayList<>();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("one"), Turn.replies("two")), received);
			Reader input = new GatedReader("{\"type\":\"turn\",\"prompt\":\"first\"}\n", firstTurnCompleted,
				"{\"type\":\"interrupt\"}\n", interruptHandled,
				"{\"type\":\"turn\",\"prompt\":\"second\"}\n{\"type\":\"complete\"}\n");
			Writer output = new SignalingWriter("\"type\":\"turn_completed\"", firstTurnCompleted, interruptHandled))
		{
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));

			HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, process, new TestHarnessRunnerScope()), input,
				output);

			requireThat(received, "received").isEqualTo(List.of("first", "second"));
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
		// The turn stays quiet while the test moves the clock and runs the heartbeat, prints one harness event, stays
		// quiet again and then completes
		Turn.Checkpoint quietTurn = printed ->
		{
			if (printed == 0)
			{
				quiet(clock, ticker, 29);
				quiet(clock, ticker, 1);
			}
			quiet(clock, ticker, 29);
			quiet(clock, ticker, 1);
		};
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("done", "harness line").
				withCheckpoint(quietTurn)), new ArrayList<>());
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
	 * Moves the clock forward and runs the heartbeat.
	 *
	 * @param clock   the bridge's clock
	 * @param ticker  the bridge's heartbeat
	 * @param seconds how far to move the clock
	 */
	private static void quiet(ManualClock clock, ManualTicker ticker, int seconds)
	{
		clock.advance(Duration.ofSeconds(seconds));
		ticker.tick();
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
	 * Input that supplies one request and then fails, or ends, once a turn has started.
	 */
	private static final class FailingReader extends Reader
	{
		private final StringReader request;
		private final CountDownLatch turnStarted;
		private final boolean fails;

		/**
		 * Creates the input.
		 *
		 * @param request the request text to supply first
		 * @param turnStarted opened when the turn that the request starts is running
		 */
		private FailingReader(String request, CountDownLatch turnStarted)
		{
			this(request, turnStarted, true);
		}

		/**
		 * Creates the input.
		 *
		 * @param request     the request text to supply first
		 * @param turnStarted opened when the turn that the request starts is running
		 * @param fails       {@code true} to fail once the turn runs, {@code false} to end
		 */
		private FailingReader(String request, CountDownLatch turnStarted, boolean fails)
		{
			this.request = new StringReader(request);
			this.turnStarted = turnStarted;
			this.fails = fails;
		}

		/**
		 * Returns input that ends, instead of failing, once the turn that its request starts is running.
		 *
		 * @param request     the request text to supply first
		 * @param turnStarted opened when the turn that the request starts is running
		 * @return the input
		 */
		private static FailingReader ending(String request, CountDownLatch turnStarted)
		{
			return new FailingReader(request, turnStarted, false);
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
			if (!fails)
				return -1;
			throw new IOException("input failed");
		}

		@Override
		public void close()
		{
			request.close();
		}
	}
}
