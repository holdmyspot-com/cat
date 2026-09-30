package io.github.cowwoc.cat.claude.runner;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.api.SessionMode;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunRequest;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunCompletion;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailure;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailureKind;
import io.github.cowwoc.cat.common.optimizer.runner.NativeDispatchCompletion;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunResult;
import io.github.cowwoc.cat.common.optimizer.runner.ProjectExecutionSnapshot;
import io.github.cowwoc.cat.common.optimizer.runner.ProjectRunContext;
import io.github.cowwoc.cat.common.optimizer.runner.ProjectWorkspaceMode;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import io.github.cowwoc.cat.common.optimizer.state.CampaignBinding;
import io.github.cowwoc.cat.common.runner.HarnessProcessResult;
import io.github.cowwoc.cat.common.runner.HarnessProcess;
import io.github.cowwoc.cat.common.runner.HarnessRun;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import io.github.cowwoc.cat.common.runner.HarnessRunSession;
import io.github.cowwoc.cat.common.runner.HarnessRunnerBridge;
import io.github.cowwoc.cat.common.runner.HarnessRunnerScope;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Checks the owned-run to typed-result handoff without campaign validation or a native process. */
public final class RunResultReaderTest
{
	private static final String INITIALIZATION = """
		{"type":"system","subtype":"init","session_id":"s","model":"alias"}
		""";
	private static final String MESSAGE = """
		{"type":"assistant","session_id":"s","parent_tool_use_id":null,\
		 "message":{"role":"assistant","id":"m","model":"actual"}}
		""";
	private static final String RESULT = """
		{"type":"result","session_id":"s","uuid":"r","subtype":"success","is_error":false,"result":"answer"}
		""";

	/** Creates a test instance. */
	public RunResultReaderTest()
	{
	}

	/**
	 * A finalizer failure preserves a completed native response only when it agrees with the bridge.
	 *
	 * @throws IOException if fixture evidence cannot be retained
	 */
	@Test
	public void recoversCompletionAfterFinalizerFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claude-finalizer-failure-"));
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			HarnessRun run = manager.create("claude", "test-1");
			Files.createDirectories(run.directory().resolve("project"));
			Path transcript = root.resolve("bridge.jsonl");
			try (StringReader input = new StringReader("""
				{"type":"turn","prompt":"hello"}
				{"type":"complete"}
				"""))
			{
				Assert.expectThrows(IOException.class, () -> HarnessRunnerBridge.serve(
					() -> new HarnessRunSession(run, new FixedNativeProcess(completed(INITIALIZATION + MESSAGE + RESULT)),
						new HarnessRunnerScope()), manager, true, input, transcript, ignored ->
					{
						throw new IOException("Final report could not be written");
					}));
			}
			Assert.assertEquals(manager.observe("claude", run.id()).orElseThrow().state(),
				io.github.cowwoc.cat.common.runner.HarnessRunObservation.State.FAILED);
			CampaignStore store = new CampaignStore(root.resolve("campaign"));
			ClaudeOptimizerAdapter adapter = new ClaudeOptimizerAdapter(store,
				new CampaignBinding("campaign", root.resolve("campaign")), manager, root.resolve("artifact"),
				root, root, "claude", Map.of());
			NestedRunCompletion completion = adapter.readCompleted(request(root, 1), run.id(), transcript,
				"a".repeat(64));
			Assert.assertTrue(completion instanceof NestedRunCompletion.Succeeded);
			NestedRunResult result = ((NestedRunCompletion.Succeeded) completion).result();
			Assert.assertEquals(result.turnResults().getFirst().responseText(), "answer");
			String original = Files.readString(transcript);
			Files.writeString(transcript, original.replace("answer", "different"));
			Assert.expectThrows(IOException.class, () -> RunResultReader.readSucceeded(request(root, 1), manager,
				run.id(), transcript, store, "a".repeat(64)));

			HarnessRun stopped = manager.create("claude", "test-2");
			Files.createDirectories(stopped.directory().resolve("project"));
			Path stoppedTranscript = root.resolve("stopped.jsonl");
			try (StringReader input = new StringReader("{\"type\":\"complete\"}\n"))
			{
				Assert.expectThrows(IOException.class, () -> HarnessRunnerBridge.serve(
					() -> new HarnessRunSession(stopped, new FixedNativeProcess(), new HarnessRunnerScope()), manager,
					true, input,
					stoppedTranscript, ignored ->
					{
						throw new IOException("Final report could not be written");
					}));
			}
			Assert.expectThrows(IOException.class, () -> RunResultReader.readCompleted(request(root, 1), manager,
				stopped.id(), stoppedTranscript, store, "a".repeat(64)));
		}
	}

	/**
	 * An actual interrupted bridge publication preserves available records without asserting no dispatch.
	 *
	 * @throws IOException if fixture evidence cannot be retained
	 */
	@Test
	public void settlesReservedInterruptedTurn() throws IOException
	{
		List<String> outputs = List.of("", INITIALIZATION + MESSAGE,
			INITIALIZATION + MESSAGE + RESULT.replace("\"subtype\":\"success\"",
				"\"subtype\":\"success\",\"terminal_reason\":\"aborted_streaming\""));
		for (String output : outputs)
		{
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory("claude-interrupted-result-"));
				HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
				HarnessRun run = manager.create("claude", "test-1");
				Files.createDirectories(run.directory().resolve("project"));
				Path transcript = root.resolve("bridge.jsonl");
				HarnessProcessResult interrupted = new HarnessProcessResult(130, output, "",
					HarnessProcessResult.Outcome.INTERRUPTED, "");
				try (StringReader input = new StringReader(
					"{\"type\":\"turn\",\"prompt\":\"hello\"}\n{\"type\":\"complete\"}\n"))
				{
					HarnessRunnerBridge.serve(() -> new HarnessRunSession(run, new FixedNativeProcess(interrupted),
						new HarnessRunnerScope()), manager, true, input, transcript);
				}
				CampaignStore store = new CampaignStore(root.resolve("campaign"));
				NestedRunCompletion.Failed completion = (NestedRunCompletion.Failed) RunResultReader.readCompleted(
					request(root, 2), manager, run.id(), transcript, store, "a".repeat(64));
				Assert.assertEquals(completion.failure().kind(), NestedRunFailureKind.INTERRUPTED);
				Assert.assertEquals(completion.failure().nativeEvidence().size(), (int) output.lines().count());
				Assert.assertTrue(completion.failure().noNativeTurnDispatchProof().isEmpty());
				Assert.assertTrue(completion.failure().terminalResponseEvidence().isEmpty());
			}
		}
	}

	/**
	 * Native failure before a result retains empty or partial output without inventing no-dispatch evidence.
	 *
	 * @throws IOException if fixture evidence cannot be retained
	 */
	@Test
	public void settlesNativeFailureWithoutResult() throws IOException
	{
		for (String output : List.of("", INITIALIZATION + MESSAGE))
		{
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory("claude-incomplete-failure-"));
				HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
				HarnessRun run = manager.create("claude", "test-1");
				Files.createDirectories(run.directory().resolve("project"));
				Path transcript = root.resolve("bridge.jsonl");
				HarnessProcessResult failed = new HarnessProcessResult(1, output, "native ended before result",
					HarnessProcessResult.Outcome.FAILED, "");
				try (StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"hello\"}\n"))
				{
					Assert.expectThrows(IOException.class, () -> HarnessRunnerBridge.serve(
						() -> new HarnessRunSession(run, new FixedNativeProcess(failed), new HarnessRunnerScope()),
						manager, true, input, transcript));
				}
				CampaignStore store = new CampaignStore(root.resolve("campaign"));
				NestedRunCompletion.Failed result = (NestedRunCompletion.Failed) RunResultReader.readCompleted(
					request(root, 1), manager, run.id(), transcript, store, "a".repeat(64));
				Assert.assertEquals(result.failure().kind(), NestedRunFailureKind.TRANSPORT_FAILED);
				Assert.assertEquals(result.failure().nativeEvidence().size(), (int) output.lines().count());
				Assert.assertTrue(result.failure().noNativeTurnDispatchProof().isEmpty());
				Files.writeString(run.directory().resolve("artifacts/turn-1.events"), INITIALIZATION + MESSAGE + RESULT);
				Assert.expectThrows(IOException.class, () -> RunResultReader.readCompleted(request(root, 1), manager,
					run.id(), transcript, store, "a".repeat(64)));
			}
		}
	}

	/**
	 * Interruption preserves preceding evidence and rejects conflicting bridge responses or native completion.
	 *
	 * @throws IOException if fixture evidence cannot be retained
	 */
	@Test
	public void validatesPrefixBeforeInterruption() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claude-interrupted-prefix-"));
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			HarnessRun run = manager.create("claude", "test-1");
			Files.createDirectories(run.directory().resolve("project"));
			Path transcript = root.resolve("bridge.jsonl");
			HarnessProcessResult interrupted = new HarnessProcessResult(130, "", "",
				HarnessProcessResult.Outcome.INTERRUPTED, "");
			try (StringReader input = new StringReader("""
				{"type":"turn","prompt":"hello"}
				{"type":"turn","prompt":"again"}
				{"type":"complete"}
				"""))
			{
				HarnessRunnerBridge.serve(() -> new HarnessRunSession(run,
					new FixedNativeProcess(completed(INITIALIZATION + MESSAGE + RESULT), interrupted),
					new HarnessRunnerScope()), manager, true, input, transcript);
			}
			CampaignStore store = new CampaignStore(root.resolve("campaign"));
			NestedRunCompletion.Failed result = (NestedRunCompletion.Failed) RunResultReader.readCompleted(
				request(root, 2), manager, run.id(), transcript, store, "a".repeat(64));
			Assert.assertEquals(result.failure().nativeEvidence().size(), 3);
			String original = Files.readString(transcript);
			Files.writeString(transcript, original.replace("answer", "different"));
			Assert.expectThrows(IOException.class, () -> RunResultReader.readCompleted(request(root, 2), manager,
				run.id(), transcript, store, "a".repeat(64)));
			Files.writeString(transcript, original);
			Path events = run.directory().resolve("artifacts/turn-2.events");
			Files.writeString(events, RESULT);
			Assert.expectThrows(IOException.class, () -> RunResultReader.readCompleted(request(root, 2), manager,
				run.id(), transcript, store, "a".repeat(64)));
			Files.writeString(events, "{invalid}\n");
			Assert.expectThrows(IOException.class, () -> RunResultReader.readCompleted(request(root, 2), manager,
				run.id(), transcript, store, "a".repeat(64)));
		}
	}

	/**
	 * A native failure before a model response retains its actual evidence without supplying a semantic outcome.
	 *
	 * @param nativeResult native terminal error record
	 * @throws IOException if the owned fixture cannot be retained
	 */
	@Test(dataProvider = "nativeFailures")
	public void retainsEarlyNativeFailure(String nativeResult) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claude-native-failure-"));
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			HarnessRun run = manager.create("claude", "test-1");
			Files.createDirectories(run.directory().resolve("project"));
			CampaignStore store = new CampaignStore(root.resolve("campaign"));
			Path transcript = root.resolve("bridge.jsonl");
			HarnessProcessResult failed = new HarnessProcessResult(1,
				INITIALIZATION + nativeResult, "native failure",
				HarnessProcessResult.Outcome.FAILED, "");
			try (StringReader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"hello\"}\n"))
			{
				Assert.expectThrows(IOException.class, () -> HarnessRunnerBridge.serve(
					() -> new HarnessRunSession(run, new FixedNativeProcess(failed), new HarnessRunnerScope()), manager,
					true, input, transcript));
			}
			NestedRunCompletion completion = RunResultReader.readCompleted(request(root, 2), manager, run.id(),
				transcript, store, "a".repeat(64));
			Assert.assertTrue(completion instanceof NestedRunCompletion.Failed);
			NestedRunFailure failure = ((NestedRunCompletion.Failed) completion).failure();
			Assert.assertEquals(failure.kind(), NestedRunFailureKind.TRANSPORT_FAILED);
			Assert.assertEquals(failure.nativeEvidence().size(), 2);
			Assert.assertTrue(failure.terminalResponseEvidence().isEmpty());
			Assert.assertTrue(failure.noNativeTurnDispatchProof().isEmpty());
			Assert.assertEquals(new String(store.readImmutableBytes(failure.nativeEvidence().getLast()), UTF_8),
				nativeResult);
			bridge(root, 1, "answer");
			Assert.expectThrows(IOException.class, () -> RunResultReader.readCompleted(request(root, 1), manager,
				run.id(), transcript, store, "a".repeat(64)));
		}
	}

	/**
	 * Supplies one result for each supported native failure representation being joined.
	 *
	 * @return published error subtype and success subtype carrying an error terminal reason
	 */
	@DataProvider
	public Object[][] nativeFailures()
	{
		return new Object[][]
		{
			{RESULT.replace("\"success\"", "\"error_during_execution\"")},
			{RESULT.replace("\"subtype\":\"success\"",
				"\"subtype\":\"success\",\"terminal_reason\":\"api_error\"")}
		};
	}

	/**
	 * A failed followup retains preceding evidence but cannot hide a contradictory preceding bridge response.
	 *
	 * @throws IOException if the owned fixture cannot be retained
	 */
	@Test
	public void validatesPrefixBeforeNativeFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claude-followup-failure-"));
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			HarnessRun run = manager.create("claude", "test-1");
			Files.createDirectories(run.directory().resolve("project"));
			manager.writeTurnOutput(run, 1, completed(INITIALIZATION + MESSAGE + RESULT));
			manager.writeTurnOutput(run, 2, new HarnessProcessResult(1,
				RESULT.replace("\"success\"", "\"error_during_execution\""), "native failure",
				HarnessProcessResult.Outcome.FAILED, ""));
			manager.fail(run, "native failure");
			CampaignStore store = new CampaignStore(root.resolve("campaign"));
			Path transcript = Files.writeString(root.resolve("bridge.jsonl"), """
				{"type":"started","runId":"test-1"}
				{"type":"turn-completed","runId":"test-1","exitCode":0,"assistant_response":"answer"}
				""");
			NestedRunCompletion.Failed completion = (NestedRunCompletion.Failed) RunResultReader.readCompleted(
				request(root, 2), manager, run.id(), transcript, store, "a".repeat(64));
			Assert.assertEquals(completion.failure().nativeEvidence().size(), 4);
			Files.writeString(transcript, Files.readString(transcript).replace("answer", "different"));
			Assert.expectThrows(IOException.class, () -> RunResultReader.readCompleted(request(root, 2), manager,
				run.id(), transcript, store, "a".repeat(64)));
		}
	}

	/**
	 * Stopping without reserving a turn produces the same retained no-dispatch proof on a cold retry.
	 *
	 * @throws IOException if the owned fixture cannot be retained
	 */
	@Test
	public void settlesStopBeforeNativeTurn() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claude-zero-turn-"));
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			HarnessRun run = manager.create("claude", "test-1");
			Files.createDirectories(run.directory().resolve("project"));
			manager.markCompleted(run);
			CampaignStore store = new CampaignStore(root.resolve("campaign"));
			Path transcript = bridge(root, 0, "");
			NestedRunCompletion completion = RunResultReader.readCompleted(request(root, 1), manager, run.id(),
				transcript, store, "a".repeat(64));
			Assert.assertTrue(completion instanceof NestedRunCompletion.Failed);
			NestedRunFailure failure = ((NestedRunCompletion.Failed) completion).failure();
			Assert.assertEquals(failure.kind(), NestedRunFailureKind.INTERRUPTED);
			EvidenceRef reference = failure.noNativeTurnDispatchProof().orElseThrow().completionRef();
			Assert.assertEquals(NativeDispatchCompletion.fromBytes(store.readImmutableBytes(reference)).runId(),
				Optional.of(run.id()));
			NestedRunCompletion.Failed retry = (NestedRunCompletion.Failed) RunResultReader.readCompleted(
				request(root, 1), manager, run.id(), transcript, new CampaignStore(store.directory()), "a".repeat(64));
			Assert.assertEquals(retry.failure().noNativeTurnDispatchProof(), failure.noNativeTurnDispatchProof());
			Files.writeString(run.directory().resolve("artifacts/turn-1.events"), INITIALIZATION);
			Assert.expectThrows(IOException.class, () -> RunResultReader.readCompleted(request(root, 1), manager,
				run.id(), transcript, store, "a".repeat(64)));
		}
	}

	/**
	 * Two native turns retain their global terminal ordinals and report the parent model rather than its alias.
	 *
	 * @throws IOException if the owned fixture cannot be retained
	 */
	@Test
	public void joinsRetainedTurns() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claude-result-"));
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			HarnessRun run = manager.create("claude", "test-1");
			Files.createDirectories(run.directory().resolve("project"));
			manager.writeTurnOutput(run, 1, completed(INITIALIZATION + MESSAGE + RESULT));
			manager.writeTurnOutput(run, 2, completed(MESSAGE + RESULT));
			manager.markCompleted(run);
			CampaignStore store = new CampaignStore(root.resolve("campaign"));
			Path transcript = bridge(root, 2, "answer");
			NestedRunResult result = RunResultReader.readSucceeded(request(root, 2), manager, run.id(),
				transcript, store, "a".repeat(64));
			Assert.assertEquals(result.observedModelProfile().modelId(), "actual");
			Assert.assertTrue(result.observedModelProfile().reasoningProfile().isEmpty());
			Assert.assertEquals(result.turnResults().size(), 2);
			Assert.assertEquals(result.nativeEventEvidence().size(), 5);
			Assert.assertEquals(result.turnResults().getFirst().nativeTurnEvent().eventOrdinal(), 3L);
			Assert.assertEquals(result.turnResults().getLast().nativeTurnEvent().eventOrdinal(), 5L);
			Assert.assertEquals(result.turnResults().getLast().nativeTurnEvent(), result.nativeEventEvidence().getLast());
			Assert.assertTrue(result.runnerIdentity().providerIdentity().isEmpty());
			Assert.assertFalse(result.runnerIdentity().limitations().isEmpty());
		}
	}

	/**
	 * A contradictory response, native failure, or incomplete requested sequence cannot become success.
	 *
	 * @throws IOException if the owned fixture cannot be retained
	 */
	@Test
	public void rejectsContradictoryCompletion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claude-conflicting-result-"));
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			HarnessRun run = manager.create("claude", "test-1");
			Files.createDirectories(run.directory().resolve("project"));
			manager.writeTurnOutput(run, 1, completed(INITIALIZATION + MESSAGE + RESULT));
			manager.markCompleted(run);
			CampaignStore store = new CampaignStore(root.resolve("campaign"));
			Path transcript = bridge(root, 1, "different");
			Assert.expectThrows(IOException.class, () -> RunResultReader.readSucceeded(request(root, 1), manager,
				run.id(), transcript, store, "a".repeat(64)));
			bridge(root, 1, "answer");
			Assert.expectThrows(IOException.class, () -> RunResultReader.readSucceeded(request(root, 2), manager,
				run.id(), transcript, store, "a".repeat(64)));
			manager.writeTurnOutput(run, 1, completed(INITIALIZATION + MESSAGE +
				RESULT.replace("\"is_error\":false", "\"is_error\":true")));
			manager.markCompleted(run);
			Assert.expectThrows(IOException.class, () -> RunResultReader.readSucceeded(request(root, 1), manager,
				run.id(), transcript, store, "a".repeat(64)));
		}
	}

	/**
	 * Distinct reported parent models across turns cannot be represented by one run profile.
	 *
	 * @throws IOException if the owned fixture cannot be retained
	 */
	@Test
	public void rejectsModelChangeAcrossTurns() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claude-model-change-"));
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			HarnessRun run = manager.create("claude", "test-1");
			Files.createDirectories(run.directory().resolve("project"));
			manager.writeTurnOutput(run, 1, completed(INITIALIZATION + MESSAGE + RESULT));
			manager.writeTurnOutput(run, 2, completed(MESSAGE.replace("actual", "other") + RESULT));
			manager.markCompleted(run);
			CampaignStore store = new CampaignStore(root.resolve("campaign"));
			Path transcript = bridge(root, 2, "answer");
			Assert.expectThrows(IOException.class, () -> RunResultReader.readSucceeded(request(root, 2), manager,
				run.id(), transcript, store, "a".repeat(64)));
		}
	}

	/**
	 * Supplies a completed producer turn with the exact native bytes to archive.
	 *
	 * @param events native JSONL records
	 * @return completed producer observation
	 */
	private static HarnessProcessResult completed(String events)
	{
		return new HarnessProcessResult(0, events, "", HarnessProcessResult.Outcome.COMPLETED, "answer");
	}

	/**
	 * Writes only the bridge records required to assert agreement.
	 *
	 * @param root fixture directory
	 * @param count completed turn count
	 * @param response bridge response
	 * @return transcript path
	 * @throws IOException if the transcript cannot be written
	 */
	private static Path bridge(Path root, int count, String response) throws IOException
	{
		String turn = "{\"type\":\"turn-completed\",\"runId\":\"test-1\",\"exitCode\":0," +
			"\"assistant_response\":\"" + response + "\"}\n";
		return Files.writeString(root.resolve("bridge.jsonl"), "{\"type\":\"started\",\"runId\":\"test-1\"}\n" +
			turn.repeat(count) + "{\"type\":\"completed\",\"runId\":\"test-1\"}\n");
	}

	/**
	 * Specifies the same isolated execution context without registering an unrelated campaign.
	 *
	 * @param root fixture directory
	 * @param count requested turn count
	 * @return original call request
	 */
	private static NestedRunRequest request(Path root, int count)
	{
		SessionMode mode = SessionMode.SINGLE_TURN;
		List<String> turns = List.of("hello");
		if (count == 2)
		{
			mode = SessionMode.MULTI_TURN;
			turns = List.of("hello", "again");
		}
		return new NestedRunRequest("call", EvidencePurpose.DEVELOPMENT,
			new ProjectRunContext(new ProjectExecutionSnapshot(root.resolve("source"),
				new EvidenceRef("snapshot-manifests", "a".repeat(64))), ProjectWorkspaceMode.ISOLATED_COPY,
				Optional.empty(), Optional.empty()), turns, mode,
			new ModelProfile("alias", Optional.empty(), Map.of()), List.of());
	}

	/** Supplies the native result whose actual bridge publication is under test. */
	private static final class FixedNativeProcess implements HarnessProcess
	{
		private final List<HarnessProcessResult> results;
		private int index;
		private boolean closed;

		/**
		 * Supplies predetermined native settlements in order.
		 *
		 * @param results native settlements consumed by the real bridge
		 */
		private FixedNativeProcess(HarnessProcessResult... results)
		{
			this.results = List.of(results);
		}

		@Override
		public HarnessProcessResult sendTurn(String request)
		{
			assert request != null;
			HarnessProcessResult result = results.get(index);
			index += 1;
			return result;
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
}
