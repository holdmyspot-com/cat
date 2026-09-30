package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.candidate.InitialCandidateService;
import io.github.cowwoc.cat.common.optimizer.contract.ConfirmedContract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractService;
import io.github.cowwoc.cat.common.optimizer.contract.ContractTestFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.EvaluatorLabel;
import io.github.cowwoc.cat.common.optimizer.contract.EvaluatorResponse;
import io.github.cowwoc.cat.common.optimizer.runner.ActiveCall;
import io.github.cowwoc.cat.common.optimizer.runner.Adapter;
import io.github.cowwoc.cat.common.optimizer.runner.AdapterCall;
import io.github.cowwoc.cat.common.optimizer.runner.AdapterCallReconciliation;
import io.github.cowwoc.cat.common.optimizer.runner.NativeEventEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionCodec;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.NestedAgent;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunCompletion;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunException;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailure;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailureKind;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunRequest;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunResult;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverage;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverageStatus;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationDomain;
import io.github.cowwoc.cat.common.optimizer.runner.RunnerIdentity;
import io.github.cowwoc.cat.common.optimizer.runner.SterileWorkspaceContext;
import io.github.cowwoc.cat.common.optimizer.runner.SterileWorkspaceEntry;
import io.github.cowwoc.cat.common.optimizer.runner.SterileWorkspaceInputRole;
import io.github.cowwoc.cat.common.optimizer.runner.SterileWorkspaceKind;
import io.github.cowwoc.cat.common.optimizer.runner.SterileWorkspaceManifest;
import io.github.cowwoc.cat.common.optimizer.runner.SterileWorkspaceMaterializer;
import io.github.cowwoc.cat.common.optimizer.runner.TurnResult;
import io.github.cowwoc.cat.common.optimizer.statistics.CategoryCounts;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import io.github.cowwoc.cat.common.runner.RunnerRuntimeManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;
import org.testng.Assert;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests preparation of a frozen evaluator request after one successful native trial.
 */
public final class InitialEvaluatorRequestServiceTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Creates the test fixture.
	 */
	public InitialEvaluatorRequestServiceTest()
	{
	}

	/**
	 * An adopted trial yields the same authority and inert evidence after a campaign-store reopen.
	 *
	 * @throws IOException if test-owned campaign or runner evidence cannot be written
	 */
	@Test
	public void preparesFromAdoptedSuccessfulTrial() throws IOException, NestedRunException
	{
		runEvaluatorScenario(false, false, false, null);
	}

	/**
	 * A newly opened campaign store applies a published semantic outcome once to its assigned trial.
	 *
	 * @throws IOException if test-owned campaign or runner evidence cannot be written
	 * @throws NestedRunException if the controlled native boundary rejects its request
	 */
	@Test
	public void appliesPublishedOutcomeAfterStoreReopen() throws IOException, NestedRunException
	{
		runEvaluatorScenario(false, false, false, EvaluatorLabel.COMPLIANT);
	}

	/**
	 * A valid failure label remains a counted trial outcome after the store is reopened.
	 *
	 * @throws IOException if test-owned campaign or runner evidence cannot be written
	 * @throws NestedRunException if the controlled native boundary rejects its request
	 */
	@Test
	public void countsFailureAfterStoreReopen() throws IOException, NestedRunException
	{
		runEvaluatorScenario(false, false, false, EvaluatorLabel.NONCOMPLIANT);
	}

	/**
	 * A failed evaluator call retains infrastructure evidence without a semantic compliance result.
	 *
	 * @throws IOException if test-owned campaign or runner evidence cannot be written
	 * @throws NestedRunException if the controlled native boundary rejects its request
	 */
	@Test
	public void retainsFailedNativeEvaluator() throws IOException, NestedRunException
	{
		runEvaluatorScenario(true, false, false, null);
	}

	/**
	 * A malformed native evaluator response remains infrastructure evidence without a label.
	 *
	 * @throws IOException if test-owned campaign or runner evidence cannot be written
	 * @throws NestedRunException if the controlled native boundary rejects its request
	 */
	@Test
	public void rejectsMalformedEvaluatorResponse() throws IOException, NestedRunException
	{
		runEvaluatorScenario(false, true, false, null);
	}

	/**
	 * An indexed native result from a changed runner cannot be adopted into a semantic outcome.
	 *
	 * @throws IOException if test-owned campaign or runner evidence cannot be written
	 * @throws NestedRunException if the controlled native boundary rejects its request
	 */
	@Test
	public void rejectsIndexedChangedRunner() throws IOException, NestedRunException
	{
		runEvaluatorScenario(false, false, true, null);
	}

	/**
	 * Runs a retained trial and one controlled native evaluator outcome.
	 *
	 * @param evaluatorFails whether the evaluator ends with a native transport failure
	 * @param malformedResponse whether the native evaluator returns a malformed semantic response
	 * @param forgedRunner whether a direct store caller indexes a result from another runner
	 * @param countedLabel semantic label applied by a newly opened store, or null when only publication is checked
	 * @throws IOException if test-owned campaign or runner evidence cannot be written
	 * @throws NestedRunException if the controlled native boundary rejects its request
	 */
	private static void runEvaluatorScenario(boolean evaluatorFails, boolean malformedResponse,
		boolean forgedRunner, EvaluatorLabel countedLabel)
		throws IOException, NestedRunException
	{
		EvaluatorLabel nativeLabel = Objects.requireNonNullElse(countedLabel, EvaluatorLabel.COMPLIANT);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("initial-evaluator-request-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignBinding binding = store.binding();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			new InitialCandidateService(locator, store).capture();
			new InitialCategoryDecisionService(locator, store).publish();
			EvidenceRef assignmentRef = new TrialAssignmentService(locator, store).
				assignInitialDevelopment("first");
			TrialAssignment assignment = store.readTrialAssignment(assignmentRef);
			Path artifact = Files.createDirectory(root.resolve("artifact"));
			RunnerRuntimeManifest.write(artifact);
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			EvidenceRef completionRef;
			EvidenceRef afterCallRef;

			try (SchedulerLease lease = acquire(locator, binding, "first-trial"))
			{
				new InitialTrialAdmissionService(locator, store).admit(lease, assignmentRef, artifact);
				TrialTransportAttemptService attempts = new TrialTransportAttemptService(locator, store);
				EvidenceRef attemptRef = attempts.publishAttempt(lease, assignmentRef);
				EvidenceRef preStartRef = attempts.publishPreStart(lease, attemptRef, manager, artifact);
				String callId = assignment.logicalTrialId();
				lease.prepareStart(callId, preStartRef);
				String runId = store.readCallPreStartRecord(binding, callId).orElseThrow().runId();
				lease.activate(callId, runId);
				InitialTrialRunRequest request = JSON.readValue(store.readImmutableJson(
					store.readCallSlotClaim(binding, callId).slot().requestRef()), InitialTrialRunRequest.class);
				List<NativeEventEvidence> events = new ArrayList<>();
				List<TurnResult> turns = new ArrayList<>();
				for (int index = 0; index < request.turns().size(); ++index)
				{
					EvidenceRef eventRef = store.appendImmutableBytes("native-events",
						("turn " + index).getBytes(StandardCharsets.UTF_8));
					NativeEventEvidence event = new NativeEventEvidence("conversation", index + 1, eventRef);
					events.add(event);
					turns.add(new TurnResult(index + 1, "conversation", "result", event));
				}
				List<ObservationCoverage> coverage = List.of(
					new ObservationCoverage(ObservationDomain.PROMPT_SOURCE,
						ObservationCoverageStatus.LIMITED, Optional.of("No source path events"), List.of()),
					new ObservationCoverage(ObservationDomain.DIRECT_TOOL,
						ObservationCoverageStatus.COMPLETE, Optional.empty(), List.of()),
					new ObservationCoverage(ObservationDomain.EXTERNAL_SOURCE,
						ObservationCoverageStatus.COMPLETE, Optional.empty(), List.of()));
				NestedRunResult result = new NestedRunResult("test", runId, root.resolve("native-project"),
					turns, events, new RunnerIdentity("test", "1",
						RunnerRuntimeManifest.fingerprint(artifact), Optional.of("test-provider"), Map.of(),
						List.of()), request.modelProfile(), List.of(), List.of(), List.of(), coverage);
				InitialTrialCompletionService completions = new InitialTrialCompletionService(locator, store);
				completionRef = completions.publishCompletion(lease, assignmentRef,
					new NestedRunCompletion.Succeeded(result));
				completions.adoptIndexed(lease, assignmentRef);
				afterCallRef = store.load().measurementState().baselineRootRef().orElseThrow();
				MeasurementObservation afterCall = JSON.readValue(store.readImmutableJson(afterCallRef),
					MeasurementObservation.class);
				requireThat(afterCall.stage(), "trialAfterCall").
					isEqualTo(MeasurementObservation.Stage.AFTER_CALL);
				requireThat(afterCall.logicalCallId(), "observedTrial").isEqualTo(callId);
			}

			EvidenceRef requestRef = new InitialEvaluatorRequestService(locator, store).prepare(assignmentRef);
			InitialEvaluatorRunRequest request = JSON.readValue(store.readImmutableJson(requestRef),
				InitialEvaluatorRunRequest.class);
			if (testsRequestAndRecovery(evaluatorFails, malformedResponse, forgedRunner, countedLabel))
			{
				ConfirmedContract contract = new ContractService(store).
					loadConfirmedContract(assignment.lineage().contractId());
				requireThat(request.context().kind(), "kind").isEqualTo(SterileWorkspaceKind.EVALUATOR);
				requireThat(request.modelProfile().modelId(), "evaluatorModel").isEqualTo(contract.evaluator().model());
				requireThat(request.modelProfile().reasoningProfile(), "reasoningProfile").
					isEqualTo(Optional.of(contract.evaluator().reasoningProfile()));
				requireThat(request.turn(), "frozenResultInstruction").contains("exactly two string fields");
				SterileWorkspaceManifest manifest = SterileWorkspaceManifest.fromBytes(
					store.readImmutableBytes(request.context().manifestRef()));
				requireThat(manifest.semanticRequestRef(), "semanticRequest").
					isEqualTo(request.semanticRequestRef());
				Path workspace = root.resolve("evaluator-workspace");
				SterileWorkspaceMaterializer.materialize(request.context(), store::readImmutableEvidence, workspace);
				SterileWorkspaceEntry authority = manifest.entries().stream().filter(entry ->
					entry.role() == SterileWorkspaceInputRole.AUTHORITY).findFirst().orElseThrow();
				requireThat(Files.readAllBytes(workspace.resolve(authority.relativePath())), "frozenEvaluator").
					isEqualTo(JSON.writeValueAsBytes(contract.evaluator()));
				SterileWorkspaceEntry nativeCompletion = manifest.entries().stream().filter(entry ->
					entry.contentEvidence().equals(completionRef)).findFirst().orElseThrow();
				requireThat(nativeCompletion.role(), "completionRole").
					isEqualTo(SterileWorkspaceInputRole.INERT_EVIDENCE);
			}
			CampaignStore reopened = CampaignStore.open(store.directory());
			requireThat(new InitialEvaluatorRequestService(locator, reopened).prepare(assignmentRef), "sameRequest").
				isEqualTo(requestRef);
			if (testsRequestAndRecovery(evaluatorFails, malformedResponse, forgedRunner, countedLabel))
			{
				try (Stream<Path> indexes = Files.list(store.directory().resolve("measurement-after-call-index")))
				{
					Files.delete(indexes.findFirst().orElseThrow());
				}
			}
			EvidenceRef evaluatorAttemptRef;
			EvidenceRef evaluatorCompletionRef;
			NestedAgent evaluatorAgent;
			NestedRunCompletion[] nativeSettlement = {null};
			int[] nativeStarts = {0};
			int[] nativeReconciliations = {0};
			try (SchedulerLease successor = acquire(locator, binding, "retry-trial-measurement"))
			{
				InitialEvaluatorAdmissionService admissions =
					new InitialEvaluatorAdmissionService(locator, reopened);
				if (testsRequestAndRecovery(evaluatorFails, malformedResponse, forgedRunner, countedLabel))
				{
					new InitialTrialCompletionService(locator, reopened).adoptIndexed(successor, assignmentRef);
					requireThat(reopened.load().measurementState().baselineRootRef(), "sameAfterCall").
						isEqualTo(Optional.of(afterCallRef));
					try (Stream<Path> repaired = Files.list(store.directory().resolve("measurement-after-call-index")))
					{
						requireThat(repaired.count(), "repairedAfterCallIndex").isEqualTo(1L);
					}
					Path changedArtifact = Files.createDirectory(root.resolve("changed-artifact"));
					Files.createDirectory(changedArtifact.resolve("hooks"));
					Files.writeString(changedArtifact.resolve("hooks/runner-change.txt"), "changed");
					RunnerRuntimeManifest.write(changedArtifact);
					InitialEvaluatorAdmission changed = admissions.admit(successor, assignmentRef,
						changedArtifact);
					requireThat(changed instanceof InitialEvaluatorAdmission.Paused, "changedRunnerPause").isTrue();
					requireThat(reopened.load().scheduler().slots().size(), "noEvaluatorClaim").isEqualTo(1);
					MeasurementObservation afterCall = reopened.readMeasurementObservation(afterCallRef);
					MeasurementObservationDecision restored = locator.openMeasurementObserver(binding).
						restoreBeforeCall(successor, request.logicalCallId(), afterCall.conditions());
					requireThat(restored.status(), "restoredEvaluatorAdmission").
						isEqualTo(MeasurementObservationDecision.Status.ACCEPTED);
				}
				InitialEvaluatorAdmission admitted = admissions.admit(successor, assignmentRef, artifact);
				requireThat(admitted instanceof InitialEvaluatorAdmission.Claimed, "evaluatorClaimed").isTrue();
				requireThat(((InitialEvaluatorAdmission.Claimed) admitted).claim().slot().requestRef(),
					"assignedEvaluatorRequest").isEqualTo(requestRef);
				EvaluatorTransportAttemptService attempts = new EvaluatorTransportAttemptService(locator, reopened);
				EvidenceRef attemptRef = attempts.publishAttempt(successor, assignmentRef);
				evaluatorAttemptRef = attemptRef;
				EvidenceRef preStartRef = attempts.publishPreStart(successor, attemptRef, manager, artifact);
				CallPreStartRecord preStart = reopened.readCallPreStartRecord(binding,
					request.logicalCallId()).orElseThrow();
				requireThat(preStartRef.collection(), "evaluatorPreStartCollection").
					isEqualTo("call-pre-starts");
				requireThat(preStart.producerAttemptKind(), "evaluatorAttemptKind").
					isEqualTo(ProducerAttemptKind.EVALUATOR);
				requireThat(preStart.producerAttemptRef(), "indexedEvaluatorAttempt").isEqualTo(attemptRef);
				if (testsRequestAndRecovery(evaluatorFails, malformedResponse, forgedRunner, countedLabel))
				{
					Path trialCompletionIndex;
					try (Stream<Path> indexes = Files.list(store.directory().resolve("native-completion-index")))
					{
						trialCompletionIndex = indexes.findFirst().orElseThrow();
					}
					byte[] trialCompletionIndexBytes = Files.readAllBytes(trialCompletionIndex);
					Files.delete(trialCompletionIndex);
					try
					{
						Assert.expectThrows(IOException.class, () ->
							attempts.publishPreStart(successor, attemptRef, manager, artifact));
					}
					finally
					{
						Files.write(trialCompletionIndex, trialCompletionIndexBytes);
					}
					Path evaluatorIndexes = store.directory().resolve("evaluator-execution-attempts");
					try (Stream<Path> indexes = Files.list(evaluatorIndexes))
					{
						Files.delete(indexes.findFirst().orElseThrow());
					}
					try
					{
						reopened.readCallPreStartRecord(binding, request.logicalCallId());
						throw new AssertionError("Evaluator pre-start accepted a missing attempt index");
					}
					catch (IOException expected)
					{
						requireThat(expected.getMessage(), "missingEvaluatorAttempt").contains("attempt index");
					}
				}
				CampaignStore cold = CampaignStore.open(store.directory());
				if (testsRequestAndRecovery(evaluatorFails, malformedResponse, forgedRunner, countedLabel))
				{
					EvaluatorTransportAttemptService recovered = new EvaluatorTransportAttemptService(locator, cold);
					requireThat(recovered.publishAttempt(successor, assignmentRef), "sameEvaluatorAttempt").
						isEqualTo(attemptRef);
					requireThat(recovered.publishPreStart(successor, attemptRef, manager, artifact),
						"sameEvaluatorPreStart").isEqualTo(preStartRef);
				}
				boolean[] reconcileUnknown = {true};
				AdapterCall[] nativeHandle = {null};
				evaluatorAgent = new NestedAgent(new Adapter()
				{
					@Override
					public AdapterCall start(NestedRunRequest nativeRequest, String runId) throws IOException
					{
						requireThat(cold.readCallSlotClaim(binding, nativeRequest.logicalCallId()).slot().state(),
							"evaluatorStartingAtDispatch").isEqualTo(CallSlot.State.STARTING);
						requireThat(nativeRequest.context() instanceof SterileWorkspaceContext,
							"sterileEvaluatorContext").isTrue();
						requireThat(nativeRequest.modelProfile(), "frozenEvaluatorProfile").
							isEqualTo(request.modelProfile());
						requireThat(runId, "retainedEvaluatorRunId").isEqualTo(preStart.runId());
						++nativeStarts[0];
						AdapterCall handle = new AdapterCall()
						{
							@Override
							public String runId()
							{
								return runId;
							}

							@Override
							public CompletionStage<NestedRunCompletion> completion()
							{
								return new CompletableFuture<>();
							}

							@Override
							public void interrupt()
							{
							}

							@Override
							public void close()
							{
							}
						};
						nativeHandle[0] = handle;
						return handle;
					}

					@Override
					public AdapterCallReconciliation reconcile(NestedRunRequest nativeRequest,
						Optional<String> knownRunId)
					{
						++nativeReconciliations[0];
						if (nativeSettlement[0] != null)
							return new AdapterCallReconciliation.Settled(nativeSettlement[0]);
						if (reconcileUnknown[0])
							return new AdapterCallReconciliation.Unknown(new NestedRunFailure(
								NestedRunFailureKind.NATIVE_STATE_UNKNOWN, nativeRequest.logicalCallId(),
								knownRunId, "Controlled evaluator state is unknown", List.of(),
								Optional.empty(), Optional.empty()));
						return new AdapterCallReconciliation.Active(nativeHandle[0]);
					}
				}, cold::readImmutableEvidence);
				try (ActiveCall evaluatorCall = new InitialEvaluatorCallCoordinator(locator, cold).
					startClaimed(successor, assignmentRef, evaluatorAgent, manager, artifact))
				{
					requireThat(evaluatorCall.runId(), "startedEvaluatorRunId").isEqualTo(preStart.runId());
					requireThat(cold.readCallSlotClaim(binding, request.logicalCallId()).slot().state(),
						"activeEvaluator").isEqualTo(CallSlot.State.ACTIVE);
					requireThat(nativeStarts[0], "oneEvaluatorStart").isEqualTo(1);
				}
				if (testsRequestAndRecovery(evaluatorFails, malformedResponse, forgedRunner, countedLabel))
				{
					InitialEvaluatorCallCoordinator coordinator = new InitialEvaluatorCallCoordinator(locator,
						cold);
					InitialEvaluatorCallReconciliation uncertain = coordinator.reconcile(successor,
						assignmentRef, evaluatorAgent);
					requireThat(uncertain instanceof InitialEvaluatorCallReconciliation.Unknown,
						"uncertainEvaluator").isTrue();
					requireThat(cold.readCallSlotClaim(binding, request.logicalCallId()).slot().state(),
						"stillActiveEvaluator").isEqualTo(CallSlot.State.ACTIVE);
					reconcileUnknown[0] = false;
					InitialEvaluatorCallReconciliation recoveredEvaluator = coordinator.reconcile(successor,
						assignmentRef, evaluatorAgent);
					requireThat(recoveredEvaluator instanceof InitialEvaluatorCallReconciliation.Active,
						"recoveredEvaluator").isTrue();
					try (ActiveCall recoveredCall =
						((InitialEvaluatorCallReconciliation.Active) recoveredEvaluator).call())
					{
						requireThat(recoveredCall.runId(), "recoveredEvaluatorRunId").isEqualTo(preStart.runId());
					}
					requireThat(nativeStarts[0], "singleEvaluatorDispatch").isEqualTo(1);
					requireThat(nativeReconciliations[0], "twoEvaluatorReconciliations").isEqualTo(2);
				}
				InitialEvaluatorCompletionService evaluatorCompletions =
					new InitialEvaluatorCompletionService(locator, cold);
				if (testsRequestAndRecovery(evaluatorFails, malformedResponse, forgedRunner, countedLabel))
				{
					try
					{
						evaluatorCompletions.adoptIndexed(successor, assignmentRef);
						throw new AssertionError("Evaluator adoption accepted a missing completion index");
					}
					catch (IOException expected)
					{
						requireThat(expected.getMessage(), "missingEvaluatorCompletion").contains("completion");
					}
				}
				EvidenceRef evaluatorEventRef = cold.appendImmutableBytes("native-events",
					"evaluator response".getBytes(StandardCharsets.UTF_8));
				NativeEventEvidence evaluatorEvent = new NativeEventEvidence("conversation", 1,
					evaluatorEventRef);
				String responseText;
				if (malformedResponse)
					responseText = "{\"label\":\"COMPLIANT\",\"reason\":\"observed\",\"extra\":1}";
				else
					responseText = "{\"label\":\"" + nativeLabel +
						"\",\"reason\":\"expected behavior observed\"}";
				NestedRunResult evaluatorResult = new NestedRunResult("test", preStart.runId(),
					root.resolve("evaluator-native-project"),
					List.of(new TurnResult(1, "conversation", responseText, evaluatorEvent)),
					List.of(evaluatorEvent), new RunnerIdentity("test", "1",
						RunnerRuntimeManifest.fingerprint(artifact), Optional.of("test-provider"), Map.of(),
						List.of()), request.modelProfile(), List.of(), List.of(), List.of(),
					List.of(new ObservationCoverage(ObservationDomain.PROMPT_SOURCE,
						ObservationCoverageStatus.LIMITED, Optional.of("No source path events"), List.of()),
						new ObservationCoverage(ObservationDomain.DIRECT_TOOL,
							ObservationCoverageStatus.COMPLETE, Optional.empty(), List.of()),
						new ObservationCoverage(ObservationDomain.EXTERNAL_SOURCE,
							ObservationCoverageStatus.COMPLETE, Optional.empty(), List.of())));
				NestedRunResult changedRunnerResult = new NestedRunResult(evaluatorResult.harness(),
					evaluatorResult.runId(), evaluatorResult.executionProject(),
					evaluatorResult.turnResults(), evaluatorResult.nativeEventEvidence(),
					new RunnerIdentity("test", "1", "0".repeat(64), Optional.of("test-provider"),
						Map.of(), List.of()), evaluatorResult.observedModelProfile(),
					evaluatorResult.promptSources(), evaluatorResult.directToolExecutions(),
					evaluatorResult.externalSourceExecutions(), evaluatorResult.coverage());
				if (testsRequestAndRecovery(evaluatorFails, malformedResponse, forgedRunner, countedLabel))
				{
					try
					{
						evaluatorCompletions.publishCompletion(successor, assignmentRef,
							new NestedRunCompletion.Succeeded(changedRunnerResult));
						throw new AssertionError("Evaluator completion accepted a changed runner");
					}
					catch (IOException expected)
					{
						requireThat(expected.getMessage(), "changedEvaluatorRunner").contains("runner identity");
					}
				}
				NestedRunCompletion evaluatorCompletion;
				if (evaluatorFails)
					evaluatorCompletion = new NestedRunCompletion.Failed(new NestedRunFailure(
						NestedRunFailureKind.TRANSPORT_FAILED, request.logicalCallId(),
						Optional.of(preStart.runId()), "Controlled evaluator transport failed",
						List.of(evaluatorEventRef), Optional.empty(), Optional.empty()));
				else
					evaluatorCompletion = new NestedRunCompletion.Succeeded(evaluatorResult);
				if (forgedRunner)
				{
					ClaimedSlot evaluatorClaim = cold.readCallSlotClaim(binding, request.logicalCallId());
					NativeRunCompletionEvidence forged = new NativeRunCompletionEvidence(binding.campaignId(),
						request.logicalCallId(), evaluatorClaim.slot().requestRef(),
						evaluatorClaim.slotClaimRef(), Optional.of(preStart.runId()),
						NativeRunCompletionEvidence.Outcome.SUCCEEDED, Optional.of(changedRunnerResult),
						Optional.empty(), List.of(evaluatorEventRef), Optional.empty(), Optional.empty());
					EvidenceRef forgedRef = cold.appendImmutableJson("native-run-completions",
						NativeRunCompletionCodec.encode(forged, cold::readImmutableEvidence));
					cold.publishNativeCompletionIndex(binding, request.logicalCallId(), forgedRef);
					evaluatorCompletionRef = forgedRef;
				}
				else if (evaluatorFails)
				{
					nativeSettlement[0] = evaluatorCompletion;
					evaluatorCompletionRef = null;
				}
				else
					evaluatorCompletionRef = evaluatorCompletions.publishCompletion(successor,
						assignmentRef, evaluatorCompletion);
			}
			try (SchedulerLease adopter = acquire(locator, binding, "adopt-evaluator"))
			{
				CampaignStore settled = CampaignStore.open(store.directory());
				if (forgedRunner)
				{
					try
					{
						new InitialEvaluatorCallCoordinator(locator, settled).
							reconcile(adopter, assignmentRef, evaluatorAgent);
						throw new AssertionError("Indexed result from another runner was adopted");
					}
					catch (IOException expected)
					{
						requireThat(expected.getMessage(), "forgedEvaluatorRunner").
							contains("runner identity");
					}
					return;
				}
				if (evaluatorFails)
				{
					InitialEvaluatorCallReconciliation recoveredSettlement =
						new InitialEvaluatorCallCoordinator(locator, settled).
							reconcile(adopter, assignmentRef, evaluatorAgent);
					requireThat(recoveredSettlement instanceof InitialEvaluatorCallReconciliation.Settled,
						"coldEvaluatorSettlement").isTrue();
					evaluatorCompletionRef = settled.readNativeCompletionIndex(binding,
						request.logicalCallId()).orElseThrow();
					requireThat(nativeStarts[0], "noSecondEvaluatorDispatch").isEqualTo(1);
					requireThat(nativeReconciliations[0], "oneColdEvaluatorReconciliation").isEqualTo(1);
				}
				if (testsRequestAndRecovery(evaluatorFails, malformedResponse, forgedRunner, countedLabel))
				{
					Path evaluatorAttemptIndex;
					try (Stream<Path> indexes = Files.list(store.directory().resolve("evaluator-execution-attempts")))
					{
						evaluatorAttemptIndex = indexes.findFirst().orElseThrow();
					}
					byte[] attemptIndexBytes = Files.readAllBytes(evaluatorAttemptIndex);
					Files.delete(evaluatorAttemptIndex);
					try
					{
						new InitialEvaluatorCompletionService(locator, settled).adoptIndexed(adopter,
							assignmentRef);
						throw new AssertionError("Evaluator completion accepted a missing attempt index");
					}
					catch (IOException expected)
					{
						requireThat(expected.getMessage(), "missingEvaluatorCompletionAttempt").
							contains("attempt index");
					}
					Files.write(evaluatorAttemptIndex, attemptIndexBytes);
					Path evaluatorPreStartIndex = null;
					try (Stream<Path> indexes = Files.list(store.directory().resolve("call-pre-start-index")))
					{
						for (Path index : indexes.toList())
						{
							CallPreStartIndexEntry entry = JSON.readValue(Files.readAllBytes(index),
								CallPreStartIndexEntry.class);
							if (entry.logicalCallId().equals(request.logicalCallId()))
								evaluatorPreStartIndex = index;
						}
					}
					if (evaluatorPreStartIndex == null)
						throw new AssertionError("Evaluator pre-start index was absent before the recovery check");
					byte[] preStartIndexBytes = Files.readAllBytes(evaluatorPreStartIndex);
					Files.delete(evaluatorPreStartIndex);
					try
					{
						new InitialEvaluatorCompletionService(locator, settled).adoptIndexed(adopter,
							assignmentRef);
						throw new AssertionError("Evaluator completion accepted a missing pre-start index");
					}
					catch (IOException expected)
					{
						requireThat(expected.getMessage(), "missingEvaluatorCompletionPreStart").
							contains("pre-start");
					}
					Files.write(evaluatorPreStartIndex, preStartIndexBytes);
				}
				InitialEvaluatorCallReconciliation reconciliation =
					new InitialEvaluatorCallCoordinator(locator, settled).
						reconcile(adopter, assignmentRef, evaluatorAgent);
				requireThat(reconciliation instanceof InitialEvaluatorCallReconciliation.Settled,
					"indexedEvaluatorReconciliation").isTrue();
				EvidenceRef evaluatorExecutionRef =
					((InitialEvaluatorCallReconciliation.Settled) reconciliation).executionRef();
				InitialEvaluatorExecutionRecord evaluatorExecution = JSON.readValue(
					settled.readImmutableJson(evaluatorExecutionRef), InitialEvaluatorExecutionRecord.class);
				requireThat(evaluatorExecution.completionRef(), "evaluatorCompletion").
					isEqualTo(evaluatorCompletionRef);
				NativeRunCompletionEvidence retainedCompletion = NativeRunCompletionCodec.decode(
					evaluatorCompletionRef, settled::readImmutableEvidence);
				NativeRunCompletionEvidence.Outcome expectedOutcome;
				if (evaluatorFails)
					expectedOutcome = NativeRunCompletionEvidence.Outcome.FAILED;
				else
					expectedOutcome = NativeRunCompletionEvidence.Outcome.SUCCEEDED;
				requireThat(retainedCompletion.outcome(), "nativeEvaluatorOutcome").isEqualTo(expectedOutcome);
				requireThat(evaluatorExecution.attemptRef(), "evaluatorExecutionAttempt").
					isEqualTo(evaluatorAttemptRef);
				requireThat(settled.readCallSlotClaim(binding, request.logicalCallId()).slot().state(),
					"adoptedEvaluator").isEqualTo(CallSlot.State.ADOPTED);
				MeasurementObservation evaluatorAfterCall = settled.readMeasurementObservation(
					settled.load().measurementState().baselineRootRef().orElseThrow());
				requireThat(evaluatorAfterCall.stage(), "evaluatorAfterCallStage").
					isEqualTo(MeasurementObservation.Stage.AFTER_CALL);
				requireThat(evaluatorAfterCall.logicalCallId(), "evaluatorAfterCallIdentity").
					isEqualTo(request.logicalCallId());
				if (evaluatorFails)
					requireThat(evaluatorAfterCall.conditions().values().get(
						MeasurementConditions.Kind.HARNESS_RUNNER) instanceof MeasurementConditions.Value.Unknown,
						"failedEvaluatorRunnerUnknown").isTrue();
				requireThat(new InitialEvaluatorCompletionService(locator, CampaignStore.open(store.directory())).
					adoptIndexed(adopter, assignmentRef), "equalEvaluatorAdoption").
					isEqualTo(evaluatorExecutionRef);
				InitialTrialOutcomeService outcomes = new InitialTrialOutcomeService(locator, settled);
				if (evaluatorFails || malformedResponse)
				{
					try
					{
						outcomes.publishOutcome(adopter, assignmentRef);
						throw new AssertionError("Invalid evaluator produced a semantic outcome");
					}
					catch (IOException expected)
					{
						requireThat(expected.getMessage().toLowerCase(Locale.ROOT), "failedEvaluatorHasNoOutcome").
							contains("evaluator");
					}
					requireThat(Files.exists(store.directory().resolve("trial-outcome-index")),
						"noFailureOutcomeIndex").isFalse();
				}
				else
				{
					EvidenceRef outcomeRef = outcomes.publishOutcome(adopter, assignmentRef);
					TrialOutcomeRecord outcome = JSON.readValue(settled.readImmutableJson(outcomeRef),
						TrialOutcomeRecord.class);
					requireThat(outcome.assignmentRef(), "outcomeAssignment").isEqualTo(assignmentRef);
					requireThat(outcome.evaluatorExecutionRef(), "outcomeEvaluator").
						isEqualTo(evaluatorExecutionRef);
					requireThat(outcome.response().label(), "complianceLabel").
						isEqualTo(nativeLabel);
					requireThat(new InitialTrialOutcomeService(locator, CampaignStore.open(store.directory())).
						publishOutcome(adopter, assignmentRef), "sameOutcome").isEqualTo(outcomeRef);
					try (Stream<Path> indexes = Files.list(store.directory().resolve("trial-outcome-index")))
					{
						Files.delete(indexes.findFirst().orElseThrow());
					}
					requireThat(new InitialTrialOutcomeService(locator, CampaignStore.open(store.directory())).
						publishOutcome(adopter, assignmentRef), "repairedOutcomeIndex").isEqualTo(outcomeRef);
					if (countedLabel != null)
					{
						Path outcomeIndex;
						try (Stream<Path> indexes = Files.list(store.directory().resolve("trial-outcome-index")))
						{
							outcomeIndex = indexes.findFirst().orElseThrow();
						}
						byte[] outcomeIndexBytes = Files.readAllBytes(outcomeIndex);
						EvaluatorLabel forgedLabel = switch (countedLabel)
						{
							case COMPLIANT -> EvaluatorLabel.NONCOMPLIANT;
							case NONCOMPLIANT -> EvaluatorLabel.COMPLIANT;
						};
						TrialOutcomeRecord forged = new TrialOutcomeRecord(TrialOutcomeRecord.SCHEMA_VERSION,
							outcome.campaignId(), outcome.lineage(), assignmentRef, outcome.trialExecutionRef(),
							outcome.evaluatorExecutionRef(), outcome.resultSchema(),
							new EvaluatorResponse(forgedLabel, outcome.response().reason()));
						EvidenceRef forgedRef = settled.appendImmutableJson("trial-outcomes",
							JSON.writeValueAsBytes(forged));
						Files.delete(outcomeIndex);
						settled.appendCreateOnceIndex("trial-outcome-index", assignmentRef.evidenceId(),
							new LineageEvidenceRef(assignment.lineage(), forgedRef));
						try
						{
							new CampaignTransitions(CampaignStore.open(store.directory())).apply(
								new TransitionRequest(binding, settled.load().transitionCounter(),
									TransitionKind.SEMANTIC_OUTCOME, forgedRef));
							throw new AssertionError("A changed evaluator label passed outcome accounting");
						}
						catch (IOException expected)
						{
							requireThat(expected.getMessage(), "changedOutcomeLabel").contains("label");
						}
						Files.delete(outcomeIndex);
						Files.write(outcomeIndex, outcomeIndexBytes);

						CampaignStore outcomeStore = CampaignStore.open(store.directory());
						long expectedCounter = outcomeStore.load().transitionCounter();
						TransitionRequest adoption = new TransitionRequest(binding, expectedCounter,
							TransitionKind.SEMANTIC_OUTCOME, outcomeRef);
						TransitionResult first = new CampaignTransitions(outcomeStore).apply(adoption);
						requireThat(first.phase(), "countedOutcomePhase").isEqualTo(CampaignStatus.DEVELOPING);
						CampaignStore reopenedAfterOutcome = CampaignStore.open(store.directory());
						TrialState trial = reopenedAfterOutcome.load().trials().getFirst();
						TrialStatus expectedStatus = switch (countedLabel)
						{
							case COMPLIANT -> TrialStatus.COMPLIANT;
							case NONCOMPLIANT -> TrialStatus.NONCOMPLIANT;
						};
						requireThat(trial.status(), "semanticTrialStatus").isEqualTo(expectedStatus);
						requireThat(trial.outcomeEvidence(), "semanticTrialOutcome").isEqualTo(outcomeRef);
						CategoryCounts expectedCounts = switch (countedLabel)
						{
							case COMPLIANT -> new CategoryCounts(1, 1);
							case NONCOMPLIANT -> new CategoryCounts(0, 1);
						};
						requireThat(new DevelopmentEvidenceService(reopenedAfterOutcome).readCurrent().
							categorySeries(50).get("first").total(), "countedDevelopmentEvidence").
							isEqualTo(expectedCounts);
						requireThat(new CampaignTransitions(reopenedAfterOutcome).apply(adoption),
							"equalOutcomeTransition").
							isEqualTo(first);
						requireThat(reopenedAfterOutcome.load().transitionCounter(), "oneOutcomeTransition").
							isEqualTo(first.resultingCounter());
						Files.delete(outcomeIndex);
						try
						{
							new CampaignTransitions(CampaignStore.open(store.directory())).apply(adoption);
							throw new AssertionError("A missing outcome index passed cold transition retry");
						}
						catch (IOException expected)
						{
							requireThat(expected.getMessage(), "missingOutcomeIndexOnRetry").contains("outcome");
						}
						Files.write(outcomeIndex, outcomeIndexBytes);
					}
				}
			}
			if (countedLabel == null)
			{
				try (Stream<Path> indexes = Files.list(store.directory().resolve("native-completion-index")))
				{
					for (Path index : indexes.toList())
					{
						NativeCompletionIndexEntry entry = JSON.readValue(Files.readAllBytes(index),
							NativeCompletionIndexEntry.class);
						if (entry.logicalCallId().equals(assignment.logicalTrialId()))
							Files.delete(index);
					}
				}
				try
				{
					new InitialEvaluatorRequestService(locator, CampaignStore.open(store.directory())).
						prepare(assignmentRef);
					throw new AssertionError("missing native completion index was accepted for evaluation");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "missingIndex").contains("completion");
				}
			}
		}
	}

	/**
	 * Acquires an owner after publishing a test-owned host observation.
	 *
	 * @param locator registered campaign locator
	 * @param binding exact campaign identity
	 * @param invocationId one test invocation identity
	 * @return current scheduler owner
	 * @throws IOException if registration or acquisition fails
	 */
	private static SchedulerLease acquire(CampaignLocator locator, CampaignBinding binding,
		String invocationId) throws IOException
	{
		CampaignStopInbox inbox = locator.openStopInbox(binding);
		EvidenceRef registration = inbox.beginInvocation(binding, invocationId);
		inbox.publishHostObservation(binding, invocationId, registration,
			JSON.writeValueAsBytes(new HostObservation(1,
				String.format(Locale.ROOT, "%08x", invocationId.hashCode()).repeat(8),
				"session-" + invocationId, "turn-" + invocationId)));
		return locator.openSchedulerLease(binding).acquire(binding, invocationId);
	}

	/**
	 * Represents a host observation for the controlled scheduler owner.
	 *
	 * @param schemaVersion supported observation schema
	 * @param tokenDigest test-owned host token identity
	 * @param hostSessionId test-owned host session identity
	 * @param hostTurnId test-owned host turn identity
	 */
	private record HostObservation(int schemaVersion, String tokenDigest, String hostSessionId,
		String hostTurnId)
	{
	}

	/**
	 * Selects the successful scenario that owns request construction and recovery checks shared by the variants.
	 *
	 * @param evaluatorFails whether native evaluation fails
	 * @param malformedResponse whether its response is malformed
	 * @param forgedRunner whether retained runner identity is forged
	 * @param countedLabel label whose counting is under test, or null
	 * @return whether this scenario checks the shared preparation and recovery flow
	 */
	private static boolean testsRequestAndRecovery(boolean evaluatorFails, boolean malformedResponse,
		boolean forgedRunner, EvaluatorLabel countedLabel)
	{
		return !evaluatorFails && !malformedResponse && !forgedRunner && countedLabel == null;
	}
}
