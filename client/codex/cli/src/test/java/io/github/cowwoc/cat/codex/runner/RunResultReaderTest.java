package io.github.cowwoc.cat.codex.runner;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.api.SessionMode;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunCompletion;
import io.github.cowwoc.cat.common.optimizer.runner.AdapterCallReconciliation;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailure;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailureKind;
import io.github.cowwoc.cat.common.optimizer.runner.NativeDispatchCompletion;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunRequest;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunResult;
import io.github.cowwoc.cat.common.optimizer.runner.ProjectExecutionSnapshot;
import io.github.cowwoc.cat.common.optimizer.runner.ProjectRunContext;
import io.github.cowwoc.cat.common.optimizer.runner.ProjectWorkspaceMode;
import io.github.cowwoc.cat.common.optimizer.runner.SterileWorkspaceContext;
import io.github.cowwoc.cat.common.optimizer.runner.SterileWorkspaceEntry;
import io.github.cowwoc.cat.common.optimizer.runner.SterileWorkspaceInputRole;
import io.github.cowwoc.cat.common.optimizer.runner.SterileWorkspaceKind;
import io.github.cowwoc.cat.common.optimizer.runner.SterileWorkspaceManifest;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import io.github.cowwoc.cat.common.optimizer.state.CampaignBinding;
import io.github.cowwoc.cat.common.optimizer.state.CallPreStartRecord;
import io.github.cowwoc.cat.common.optimizer.state.ProducerAttemptKind;
import io.github.cowwoc.cat.common.runner.HarnessBridgeCall;
import io.github.cowwoc.cat.common.runner.HarnessProcess;
import io.github.cowwoc.cat.common.runner.HarnessProcessResult;
import io.github.cowwoc.cat.common.runner.HarnessRun;
import io.github.cowwoc.cat.common.runner.HarnessRunWorkspace;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import io.github.cowwoc.cat.common.runner.HarnessRunSession;
import io.github.cowwoc.cat.common.runner.HarnessRunnerScope;
import io.github.cowwoc.cat.common.runner.HarnessRunnerBridge;
import org.testng.annotations.Test;
import org.testng.Assert;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Tests the retained Codex run-to-optimizer result handoff.
 */
public final class RunResultReaderTest
{
	/**
	 * An evaluator whose every native command failed keeps infrastructure evidence without a compliance result.
	 *
	 * @throws IOException if the test-owned native run or sterile inputs cannot be written
	 */
	@Test
	public void rejectsAllFailedEvaluatorCommands() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-evaluator-tools-"));
			CampaignStore store = new CampaignStore(root.resolve("campaign"));
			byte[] authority = "frozen evaluator".getBytes(UTF_8);
			EvidenceRef authorityRef = store.appendImmutableBytes("sterile-inputs", authority);
			SterileWorkspaceManifest manifest = new SterileWorkspaceManifest(1,
				SterileWorkspaceKind.EVALUATOR, new EvidenceRef("evaluator-requests", "a".repeat(64)),
				List.of("b".repeat(64)), List.of(new SterileWorkspaceEntry(
					Path.of("authority/evaluator.json"), SterileWorkspaceInputRole.AUTHORITY,
					authorityRef, authorityRef.evidenceId())));
			EvidenceRef manifestRef = store.appendImmutableBytes("sterile-workspace-manifests",
				manifest.bytes());
			NestedRunRequest request = new NestedRunRequest("call", EvidencePurpose.DEVELOPMENT,
				new SterileWorkspaceContext(SterileWorkspaceKind.EVALUATOR, manifestRef),
				List.of("judge"), SessionMode.SINGLE_TURN,
				new ModelProfile("model-a", Optional.of("low"), Map.of()), List.of());
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			HarnessRun run = manager.create("codex", "test-1");
			Path authorityFile = run.directory().resolve("project/authority/evaluator.json");
			Files.createDirectories(authorityFile.getParent());
			Files.write(authorityFile, authority);
			String events;
			try (InputStream fixture = Objects.requireNonNull(getClass().getResourceAsStream(
				"/codex-turn-profiles/completed-answer.events")))
			{
				events = new String(fixture.readAllBytes(), UTF_8);
			}
			events = events.replace("\"text\":\"hello\"", "\"text\":\"bad label\"").
				replace("{\"method\":\"turn/completed\"",
				"{\"method\":\"item/completed\",\"params\":{\"item\":{\"type\":\"commandExecution\"," +
					"\"status\":\"failed\"}}}\n{\"method\":\"turn/completed\"");
			manager.writeTurnOutput(run, 1, new HarnessProcessResult(0, events, "",
				HarnessProcessResult.Outcome.COMPLETED, "bad label"));
			manager.markCompleted(run);
			Path transcript = Files.writeString(root.resolve("bridge.jsonl"), """
				{"type":"started","runId":"test-1"}
				{"type":"turn-completed","runId":"test-1","exitCode":0,"assistant_response":"bad label"}
				{"type":"completed","runId":"test-1"}
				""");

			NestedRunCompletion completion = RunResultReader.readCompleted(request, manager, run.id(),
				transcript, store, "a".repeat(64));
			requireThat(completion instanceof NestedRunCompletion.Failed, "failedEvaluator").isTrue();
			requireThat(((NestedRunCompletion.Failed) completion).failure().kind(), "failureKind").
				isEqualTo(NestedRunFailureKind.RECEIPT_FAILED);
		}
	}

	/**
	 * Creates a test instance.
	 */
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
			Path root = cleanup.add(Files.createTempDirectory("codex-finalizer-failure-"));
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			HarnessRun run = manager.create("codex", "test-1");
			Files.createDirectories(run.directory().resolve("project"));
			String events;
			try (InputStream fixture = Objects.requireNonNull(getClass().getResourceAsStream(
				"/codex-turn-profiles/completed-answer.events")))
			{
				events = new String(fixture.readAllBytes(), UTF_8);
			}
			try (HarnessProcess nativeProcess = new HarnessProcess()
			{
				@Override
				public HarnessProcessResult sendTurn(String prompt)
				{
					return new HarnessProcessResult(0, events, "", HarnessProcessResult.Outcome.COMPLETED, "hello");
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
			})
			{
				Path transcript = root.resolve("bridge.jsonl");
				try (StringReader input = new StringReader("""
					{"type":"turn","prompt":"hello"}
					{"type":"complete"}
					"""))
				{
					Assert.expectThrows(IOException.class, () -> HarnessRunnerBridge.serve(
						() -> new HarnessRunSession(run, nativeProcess, new HarnessRunnerScope()), manager, true, input,
						transcript, ignored ->
						{
							throw new IOException("Final report could not be written");
						}));
				}
				Assert.assertEquals(manager.observe("codex", run.id()).orElseThrow().state(),
					io.github.cowwoc.cat.common.runner.HarnessRunObservation.State.FAILED);
				CampaignStore store = new CampaignStore(root.resolve("campaign"));
				NestedRunCompletion completion = RunResultReader.readCompleted(request(root), manager, run.id(),
					transcript, store, "a".repeat(64));
				Assert.assertTrue(completion instanceof NestedRunCompletion.Succeeded);
				Assert.assertEquals(((NestedRunCompletion.Succeeded) completion).result().turnResults().getFirst().
					responseText(), "hello");
				String original = Files.readString(transcript);
				Files.writeString(transcript, original.replace("hello", "different"));
				Assert.expectThrows(IOException.class, () -> RunResultReader.readCompleted(request(root), manager,
					run.id(), transcript, store, "a".repeat(64)));
				Files.writeString(transcript, original.replace("\"exitCode\":0", "\"exitCode\":1"));
				Assert.expectThrows(IOException.class, () -> RunResultReader.readCompleted(request(root), manager,
					run.id(), transcript, store, "a".repeat(64)));

				HarnessRun stopped = manager.create("codex", "test-2");
				Files.createDirectories(stopped.directory().resolve("project"));
				Path stoppedTranscript = root.resolve("stopped.jsonl");
				try (StringReader input = new StringReader("{\"type\":\"complete\"}\n"))
				{
					Assert.expectThrows(IOException.class, () -> HarnessRunnerBridge.serve(
						() -> new HarnessRunSession(stopped, nativeProcess, new HarnessRunnerScope()), manager, true, input,
						stoppedTranscript, ignored ->
						{
							throw new IOException("Final report could not be written");
						}));
				}
				Assert.expectThrows(IOException.class, () -> RunResultReader.readCompleted(request(root), manager,
					stopped.id(), stoppedTranscript, store, "a".repeat(64)));
			}
		}
	}

	/**
	 * Verifies a completed bridge response cites its exact terminal native event and observed model settings.
	 *
	 * @throws IOException if the test-owned runner and campaign evidence cannot be written or read
	 */
	@Test
	public void settlesCompletedTurnFromRetainedEvidence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-result-reader-"));
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			HarnessRun run = manager.create("codex", "test-1");
			String events;
			try (InputStream fixture = Objects.requireNonNull(getClass().getResourceAsStream(
				"/codex-turn-profiles/completed-answer.events")))
			{
				events = new String(fixture.readAllBytes(), UTF_8);
			}
			manager.writeTurnOutput(run, 1, new HarnessProcessResult(0, events, "",
				HarnessProcessResult.Outcome.COMPLETED, "hello"));
			manager.markCompleted(run);
			Path transcript = Files.writeString(root.resolve("test-1.jsonl"), """
				{"type":"started","runId":"test-1"}
				{"type":"turn-completed","runId":"test-1","exitCode":0,"assistant_response":"hello"}
				{"type":"completed","runId":"test-1"}
				""");
			CampaignStore store = new CampaignStore(root.resolve("campaign"));

			NestedRunResult result = RunResultReader.readSucceeded(manager, run.id(), transcript, store,
				"a".repeat(64));
			requireThat(result, "result").isNotNull();
			requireThat(result.runId(), "runId").isEqualTo(run.id());
			requireThat(result.observedModelProfile().reasoningProfile(), "effort").isEqualTo(
				Optional.of("low"));
			requireThat(result.turnResults().getFirst().responseText(), "response").isEqualTo("hello");
			requireThat(result.turnResults().getFirst().nativeTurnEvent().eventOrdinal(), "terminalEventOrdinal").
				isEqualTo(4L);
			requireThat(new String(store.readImmutableBytes(result.turnResults().getFirst().nativeTurnEvent().
				evidence()), UTF_8), "terminalEvent").contains("\"method\":\"turn/completed\"");
			NestedRunCompletion completion = RunResultReader.readCompleted(request(root), manager, run.id(),
				transcript, store, "a".repeat(64));
			requireThat(completion instanceof NestedRunCompletion.Succeeded, "succeeded").isTrue();
			CallPreStartRecord preStart = new CallPreStartRecord(3, "call",
				new EvidenceRef("run-requests", "a".repeat(64)),
				new EvidenceRef("call-slot-claims", "b".repeat(64)), Optional.empty(), Optional.empty(),
				Optional.empty(),
				ProducerAttemptKind.CANDIDATE, new EvidenceRef("trial-transport-attempts", "c".repeat(64)),
				new EvidenceRef("prepared-lineage-calls", "d".repeat(64)), run.id(), "a".repeat(64));
			CodexOptimizerAdapter adapter = new CodexOptimizerAdapter(store,
				new CampaignBinding("campaign", root.resolve("campaign")), manager,
				root.resolve("artifact"), root, root, "codex", Map.of());
			AdapterCallReconciliation recovered = adapter.reconcileRetained(request(root), Optional.of(run.id()),
				preStart);
			requireThat(recovered instanceof AdapterCallReconciliation.Settled, "recovered").isTrue();
			requireThat(adapter.reconcileRetained(request(root), Optional.empty(), preStart) instanceof
				AdapterCallReconciliation.Settled, "recoveredFromPreStartOnly").isTrue();
			AdapterCallReconciliation foreign = adapter.reconcileRetained(request(root),
				Optional.of("foreign-1"), preStart);
			requireThat(foreign instanceof AdapterCallReconciliation.Unknown, "foreignIdentity").isTrue();
		}
	}

	/**
	 * An active-worktree call cannot adopt a retained run that never bound its execution workspace.
	 *
	 * @throws IOException when the retained workspace identity is absent
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Active harness workspace binding is missing.*")
	public void rejectsActiveResultWithoutWorkspace() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-active-result-"));
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			HarnessRun run = manager.create("codex", "test-1");
			manager.markCompleted(run);
			Path transcript = Files.writeString(root.resolve("bridge.jsonl"), """
				{"type":"started","runId":"test-1"}
				{"type":"completed","runId":"test-1"}
				""");
			RunResultReader.readCompleted(activeRequest(root), manager, run.id(), transcript,
				new CampaignStore(root.resolve("campaign")), "a".repeat(64));
		}
	}

	/**
	 * A sealed active-worktree binding permits recovery of a stop before the first native turn.
	 *
	 * @throws IOException if test-owned run evidence cannot be retained or read
	 */
	@Test
	public void settlesBoundActiveStop() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-bound-result-"));
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			HarnessRun run = manager.create("codex", "test-1");
			NestedRunRequest request = activeRequest(root);
			new HarnessRunWorkspace(run.id(), (ProjectRunContext) request.context(), root.resolve("project")).seal(run);
			manager.markCompleted(run);
			Path transcript = Files.writeString(root.resolve("bridge.jsonl"), """
				{"type":"started","runId":"test-1"}
				{"type":"completed","runId":"test-1"}
				""");

			NestedRunCompletion completion = RunResultReader.readCompleted(request, manager, run.id(), transcript,
				new CampaignStore(root.resolve("campaign")), "a".repeat(64));
			requireThat(completion instanceof NestedRunCompletion.Failed, "failed").isTrue();
			requireThat(((NestedRunCompletion.Failed) completion).failure().kind(), "kind").
				isEqualTo(NestedRunFailureKind.INTERRUPTED);
		}
	}

	/**
	 * A retained active binding for another starting snapshot cannot settle this request.
	 *
	 * @throws IOException if test-owned run evidence cannot be retained or read
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*harness workspace binding differs from requested run.*")
	public void rejectsDifferentActiveSnapshot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-bound-result-"));
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			HarnessRun run = manager.create("codex", "test-1");
			NestedRunRequest request = activeRequest(root);
			ProjectRunContext other = new ProjectRunContext(new ProjectExecutionSnapshot(root.resolve("project"),
				new EvidenceRef("snapshot-manifests", "d".repeat(64))), ProjectWorkspaceMode.ACTIVE_WORKTREE,
				((ProjectRunContext) request.context()).confirmedEditableRootsRef(),
				((ProjectRunContext) request.context()).liveCheckpointRef());
			new HarnessRunWorkspace(run.id(), other, root.resolve("project")).seal(run);
			manager.markCompleted(run);
			Path transcript = Files.writeString(root.resolve("bridge.jsonl"), """
				{"type":"started","runId":"test-1"}
				{"type":"completed","runId":"test-1"}
				""");

			RunResultReader.readCompleted(request, manager, run.id(), transcript,
				new CampaignStore(root.resolve("campaign")), "a".repeat(64));
		}
	}

	/**
	 * Verifies a bridge success cannot override a native interrupted terminal turn.
	 *
	 * @throws IOException when the retained native terminal state contradicts the bridge response
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*native turn does not match the completed bridge response.*")
	public void rejectsInterruptedNativeTurnAsSuccess() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-result-reader-"));
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			HarnessRun run = manager.create("codex", "test-1");
			String events;
			try (InputStream fixture = Objects.requireNonNull(getClass().getResourceAsStream(
				"/codex-turn-profiles/completed-answer.events")))
			{
				events = new String(fixture.readAllBytes(), UTF_8).
					replace("\"status\":\"completed\"", "\"status\":\"interrupted\"");
			}
			manager.writeTurnOutput(run, 1, new HarnessProcessResult(0, events, "",
				HarnessProcessResult.Outcome.COMPLETED, "hello"));
			manager.markCompleted(run);
			Path transcript = Files.writeString(root.resolve("bridge.jsonl"), """
				{"type":"started","runId":"test-1"}
				{"type":"turn-completed","runId":"test-1","exitCode":0,"assistant_response":"hello"}
				{"type":"completed","runId":"test-1"}
				""");

			RunResultReader.readSucceeded(manager, run.id(), transcript,
				new CampaignStore(root.resolve("campaign")), "a".repeat(64));
		}
	}

	/**
	 * Verifies an interrupted native turn settles as interruption with retained native evidence.
	 *
	 * @throws IOException if the test-owned runner and campaign evidence cannot be read
	 */
	@Test
	public void settlesInterruptedNativeTurn() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-result-reader-"));
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			HarnessRun run = manager.create("codex", "test-1");
			String events;
			try (InputStream fixture = Objects.requireNonNull(getClass().getResourceAsStream(
				"/codex-turn-profiles/completed-answer.events")))
			{
				events = new String(fixture.readAllBytes(), UTF_8).
					replace("\"status\":\"completed\"", "\"status\":\"interrupted\"");
			}
			manager.writeTurnOutput(run, 1, new HarnessProcessResult(130, events, "",
				HarnessProcessResult.Outcome.INTERRUPTED, ""));
			manager.markCompleted(run);
			Path transcript = Files.writeString(root.resolve("bridge.jsonl"), """
				{"type":"started","runId":"test-1"}
				{"type":"turn-interrupted","runId":"test-1","exitCode":130}
				{"type":"completed","runId":"test-1"}
				""");
			NestedRunCompletion completion = RunResultReader.readCompleted(request(root), manager, run.id(), transcript,
				new CampaignStore(root.resolve("campaign")), "a".repeat(64));
			requireThat(completion instanceof NestedRunCompletion.Failed, "failed").isTrue();
			NestedRunFailure failure = ((NestedRunCompletion.Failed) completion).failure();
			requireThat(failure.kind(), "kind").isEqualTo(NestedRunFailureKind.INTERRUPTED);
			requireThat(failure.nativeEvidence().size(), "evidenceCount").isEqualTo(4);
		}
	}

	/**
	 * A completed owned run with no reserved native turn settles the interrupted request with durable no-dispatch proof.
	 *
	 * @throws IOException if the retained run or campaign evidence cannot be read
	 */
	@Test
	public void settlesStopBeforeNativeTurn() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-no-dispatch-"));
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			HarnessRun run = manager.create("codex", "test-1");
			manager.markCompleted(run);
			Path transcript = Files.writeString(root.resolve("bridge.jsonl"), """
				{"type":"started","runId":"test-1"}
				{"type":"completed","runId":"test-1"}
				""");
			CampaignStore store = new CampaignStore(root.resolve("campaign"));

			NestedRunCompletion first = RunResultReader.readCompleted(request(root), manager, run.id(), transcript,
				store, "a".repeat(64));
			requireThat(first instanceof NestedRunCompletion.Failed, "failed").isTrue();
			NestedRunFailure failure = ((NestedRunCompletion.Failed) first).failure();
			requireThat(failure.kind(), "kind").isEqualTo(NestedRunFailureKind.INTERRUPTED);
			requireThat(failure.noNativeTurnDispatchProof(), "proof").isPresent();
			byte[] receipt = store.readImmutableBytes(failure.noNativeTurnDispatchProof().orElseThrow().completionRef());
			requireThat(NativeDispatchCompletion.fromBytes(receipt).runId(), "receiptRunId").
				isEqualTo(Optional.of(run.id()));
			NestedRunCompletion replay = RunResultReader.readCompleted(request(root), manager, run.id(), transcript,
				store, "a".repeat(64));
			requireThat(((NestedRunCompletion.Failed) replay).failure().noNativeTurnDispatchProof(),
				"recoveredProof").isEqualTo(failure.noNativeTurnDispatchProof());
		}
	}

	/**
	 * A native turn event contradicts a zero-turn no-dispatch claim, even if the manifest says zero turns.
	 *
	 * @throws IOException when contradictory native evidence is retained
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*native turn artifacts contradict zero native turns.*")
	public void rejectsNoDispatchWhenNativeEventExists() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-no-dispatch-"));
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			HarnessRun run = manager.create("codex", "test-1");
			Files.writeString(run.directory().resolve("artifacts/turn-1.events"), "native event\n");
			manager.markCompleted(run);
			Path transcript = Files.writeString(root.resolve("bridge.jsonl"), """
				{"type":"started","runId":"test-1"}
				{"type":"completed","runId":"test-1"}
				""");

			RunResultReader.readCompleted(request(root), manager, run.id(), transcript,
				new CampaignStore(root.resolve("campaign")), "a".repeat(64));
		}
	}

	/**
	 * Stopping the retained bridge while its native session starts produces evidence the result reader can recover.
	 * The test process represents the native harness and fails if the bridge sends it a turn.
	 *
	 * @throws IOException if the retained bridge or campaign evidence cannot be written
	 * @throws InterruptedException if startup or completion waiting is interrupted
	 * @throws ExecutionException if the bridge fails
	 * @throws TimeoutException if startup or completion does not finish
	 */
	@Test
	public void bridgeStopProducesNoDispatchProof() throws IOException, InterruptedException,
		ExecutionException, TimeoutException
	{
		CountDownLatch startupEntered = new CountDownLatch(1);
		CountDownLatch allowStartup = new CountDownLatch(1);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-bridge-stop-"));
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			Path transcript = root.resolve("bridge.jsonl");
			HarnessProcess nativeProcess = new HarnessProcess()
			{
				@Override
				public HarnessProcessResult sendTurn(String request)
				{
					throw new AssertionError("A stopped bridge must not send a native turn");
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
			};
			try (HarnessBridgeCall call = HarnessBridgeCall.start(() ->
			{
				startupEntered.countDown();
				try
				{
					if (!allowStartup.await(5, TimeUnit.SECONDS))
						throw new IOException("Native startup was not released");
				}
				catch (InterruptedException failure)
				{
					Thread.currentThread().interrupt();
					throw new IOException("Native startup was interrupted", failure);
				}
				return new HarnessRunSession(manager.create("codex", "test-1"), nativeProcess,
					new HarnessRunnerScope());
			}, manager, "test-1", transcript, List.of("never dispatch"), ignored -> {}))
			{
				try
				{
					requireThat(startupEntered.await(2, TimeUnit.SECONDS), "startupEntered").isTrue();
					call.interrupt();
				}
				finally
				{
					allowStartup.countDown();
				}
				HarnessRun run = call.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
				CampaignStore store = new CampaignStore(root.resolve("campaign"));
				NestedRunCompletion completion = RunResultReader.readCompleted(request(root), manager, run.id(),
					transcript, store, "a".repeat(64));
				NestedRunFailure failure = ((NestedRunCompletion.Failed) completion).failure();
				requireThat(failure.kind(), "kind").isEqualTo(NestedRunFailureKind.INTERRUPTED);
				requireThat(failure.noNativeTurnDispatchProof(), "proof").isPresent();
			}
		}
	}

	/**
	 * Verifies a malformed runner identity fails before retained native evidence is observed.
	 *
	 * @throws IOException if the test directory cannot be created
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "Invalid runner runtime manifest SHA-256")
	public void rejectsMalformedRuntimeHashEarly() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-result-reader-"));
			RunResultReader.readSucceeded(new HarnessRunManager(root.resolve("runs"), Clock.systemUTC()),
				"test-1", root.resolve("bridge.jsonl"), new CampaignStore(root.resolve("campaign")), "invalid");
		}
	}

	/**
	 * Creates one isolated-copy native request for the retained runner fixture.
	 *
	 * @param root the test-owned directory
	 * @return a one-turn Codex request
	 */
	private static NestedRunRequest request(Path root)
	{
		return new NestedRunRequest("call", EvidencePurpose.DEVELOPMENT,
			new ProjectRunContext(new ProjectExecutionSnapshot(root.resolve("project"),
				new EvidenceRef("snapshot-manifests", "a".repeat(64))), ProjectWorkspaceMode.ISOLATED_COPY,
				Optional.empty(), Optional.empty()), List.of("hello"), SessionMode.SINGLE_TURN,
			new ModelProfile("gpt-6-sol", Optional.of("low"), Map.of()), List.of());
	}

	/**
	 * Creates one active-worktree request for the retained runner fixture.
	 *
	 * @param root the test-owned directory
	 * @return a one-turn active Codex request
	 */
	private static NestedRunRequest activeRequest(Path root)
	{
		ProjectRunContext context = new ProjectRunContext(new ProjectExecutionSnapshot(root.resolve("project"),
			new EvidenceRef("snapshot-manifests", "a".repeat(64))), ProjectWorkspaceMode.ACTIVE_WORKTREE,
			Optional.of(new EvidenceRef("confirmed-editable-roots", "b".repeat(64))),
			Optional.of(new EvidenceRef("live-generation-checkpoints", "c".repeat(64))));
		return new NestedRunRequest("call", EvidencePurpose.DEVELOPMENT, context,
			List.of("hello"), SessionMode.SINGLE_TURN,
			new ModelProfile("gpt-6-sol", Optional.of("low"), Map.of()), List.of());
	}
}
