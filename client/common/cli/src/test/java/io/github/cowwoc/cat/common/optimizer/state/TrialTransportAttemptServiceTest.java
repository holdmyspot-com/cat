package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.candidate.InitialCandidateService;
import io.github.cowwoc.cat.common.optimizer.contract.ContractTestFixtures;
import io.github.cowwoc.cat.common.optimizer.runner.ActiveCall;
import io.github.cowwoc.cat.common.optimizer.runner.Adapter;
import io.github.cowwoc.cat.common.optimizer.runner.AdapterCall;
import io.github.cowwoc.cat.common.optimizer.runner.AdapterCallReconciliation;
import io.github.cowwoc.cat.common.optimizer.runner.NestedAgent;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunCompletion;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunException;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailure;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailureKind;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunRequest;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunResult;
import io.github.cowwoc.cat.common.optimizer.runner.NativeEventEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverage;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverageStatus;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationDomain;
import io.github.cowwoc.cat.common.optimizer.runner.RunnerIdentity;
import io.github.cowwoc.cat.common.optimizer.runner.TurnResult;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import io.github.cowwoc.cat.common.runner.RunnerRuntimeManifest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;
import org.testng.Assert;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/** Exercises an initial trial's indexed attempt and pre-start recovery. */
public final class TrialTransportAttemptServiceTest
{
	/** Creates the trial attempt fixture. */
	public TrialTransportAttemptServiceTest()
	{
	}

	/**
	 * Publication survives a cold reopen between the attempt and pre-start, retaining one native run identity.
	 *
	 * @throws IOException if campaign or runner evidence cannot be written
	 * @throws NestedRunException if the recovered native handle cannot be closed
	 */
	@Test
	public void recoversPreStartAfterColdReopen() throws IOException, NestedRunException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("trial-attempt-")).toRealPath();
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
			Path artifact = Files.createDirectory(root.resolve("artifact"));
			RunnerRuntimeManifest.write(artifact);
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			CampaignSchedulerLease scheduler = locator.openSchedulerLease(binding);
			EvidenceRef attemptRef;
			try (SchedulerLease lease = acquire(locator, scheduler, binding, "first-trial"))
			{
				InitialTrialAdmission admitted = new InitialTrialAdmissionService(locator, store).
					admit(lease, assignmentRef, artifact);
				requireThat(admitted instanceof InitialTrialAdmission.Claimed, "claimed").isTrue();
				attemptRef = new TrialTransportAttemptService(locator, store).
					publishAttempt(lease, assignmentRef);
			}

			CampaignStore reopened = CampaignStore.open(store.directory());
			EvidenceRef preStartRef;
			try (SchedulerLease successor = acquire(locator, locator.openSchedulerLease(binding), binding,
				"retry-trial"))
			{
				TrialTransportAttemptService service = new TrialTransportAttemptService(locator, reopened);
				requireThat(service.publishAttempt(successor, assignmentRef), "sameAttempt").isEqualTo(attemptRef);
				Path selectedFile = locator.optimizationRoot(binding).resolve("prompt.md");
				byte[] selectedBytes = Files.readAllBytes(selectedFile);
				Files.writeString(selectedFile, "unowned edit between independent preparation calls\n");
				try
				{
					service.publishPreStart(successor, attemptRef, manager, artifact);
					throw new AssertionError("Independent pre-start publication accepted a changed selected worktree");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "changedSelectedBeforePublicPreStart").contains("clean fork");
				}
				finally
				{
					Files.write(selectedFile, selectedBytes);
				}
				Path changedArtifact = Files.createDirectory(root.resolve("changed-artifact"));
				Files.createDirectory(changedArtifact.resolve("hooks"));
				Files.writeString(changedArtifact.resolve("hooks/changed-runner.txt"), "changed");
				RunnerRuntimeManifest.write(changedArtifact);
				try
				{
					service.publishPreStart(successor, attemptRef, manager, changedArtifact);
					throw new AssertionError("A changed runner must not acquire trial pre-start authority");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "changedRunner").contains("observed runner");
				}
				preStartRef = service.publishPreStart(successor, attemptRef, manager, artifact);
				requireThat(service.publishPreStart(successor, attemptRef, manager, artifact), "samePreStart").
					isEqualTo(preStartRef);
				String callId = reopened.readTrialAssignment(assignmentRef).logicalTrialId();
				CallPreStartRecord record = reopened.readCallPreStartRecord(binding, callId).orElseThrow();
				requireThat(record.producerAttemptRef(), "indexedAttempt").isEqualTo(attemptRef);
				requireThat(record.runId(), "runId").isNotBlank();
				requireThat(reopened.readCallPreStartIndex(binding, callId), "indexedPreStart").
					isEqualTo(Optional.of(preStartRef));
			}

			Path attemptIndex;
			try (Stream<Path> entries = Files.list(store.directory().resolve("trial-execution-attempts")))
			{
				attemptIndex = entries.findFirst().orElseThrow();
			}
			byte[] attemptIndexBytes = Files.readAllBytes(attemptIndex);
			Files.delete(attemptIndex);
			try
			{
				String callId = reopened.readTrialAssignment(assignmentRef).logicalTrialId();
				IOException missing = Assert.expectThrows(IOException.class, () ->
					reopened.readCallPreStartRecord(binding, callId));
				requireThat(missing.getMessage(), "missingAttemptOnPublicPreStartRead").contains("attempt index is missing");
			}
			finally
			{
				Files.write(attemptIndex, attemptIndexBytes);
			}

			CampaignStore restarted = CampaignStore.open(store.directory());
			CountingAdapter adapter = new CountingAdapter(restarted, binding);
			NestedAgent agent = new NestedAgent(adapter, restarted::readImmutableJson);
			try (SchedulerLease retry = acquire(locator, locator.openSchedulerLease(binding), binding,
				"retry-start"))
			{
				new InitialTrialAdmissionService(locator, restarted).admit(retry, assignmentRef, artifact);
				try (ActiveCall call = new InitialTrialCallCoordinator(locator, restarted).
					startClaimed(retry, assignmentRef, agent, manager, artifact))
				{
					String callId = restarted.readTrialAssignment(assignmentRef).logicalTrialId();
					requireThat(call.runId(), "recoveredRunId").isEqualTo(restarted.
						readCallPreStartRecord(binding, callId).orElseThrow().runId());
					requireThat(restarted.readCallPreStartIndex(binding, callId), "recoveredPreStart").
						isEqualTo(Optional.of(preStartRef));
				}
			}
		}
	}

	/**
	 * A trial pre-start requires an index pointing at its exact retained attempt.
	 *
	 * @throws IOException if fixture evidence cannot be retained or read
	 */
	@Test
	public void requiresTrialAttemptIndex() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("trial-attempt-index-")).toRealPath();
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			String digest = "a".repeat(64);
			Campaign campaign = ProspectiveCampaignState.addLineage(store, digest);
			LineageKey lineage = new LineageKey(campaign.activeLineageId(), digest);
			CampaignBinding binding = store.binding();
			EvidenceRef requestRef = new EvidenceRef("run-requests", digest);
			EvidenceRef claimRef = new EvidenceRef("call-slot-claims", digest);
			EvidenceRef ownerRef = new EvidenceRef("prepared-lineage-calls", digest);
			CallSlot slot = new CallSlot("trial", 1, CallSlot.State.CLAIMED, requestRef, claimRef,
				Optional.empty(), Optional.empty(), false, Optional.empty(), Optional.empty());
			ProducerAttemptHeader header = new ProducerAttemptHeader(ProducerAttemptKind.TRIAL, "trial",
				requestRef, claimRef, ownerRef, Optional.empty());
			TrialTransportAttempt attempt = new TrialTransportAttempt(header,
				new EvidenceRef("trial-assignments", digest), 0);
			EvidenceRef attemptRef = store.appendImmutableJson("trial-transport-attempts",
				JsonMapper.builder().build().writeValueAsBytes(attempt));
			CallPreStartRecord record = new CallPreStartRecord(3, "trial", requestRef, claimRef,
				Optional.empty(), Optional.empty(), Optional.empty(), ProducerAttemptKind.TRIAL,
				attemptRef, ownerRef, ProspectiveCampaignState.nativeRunId("trial"), digest);
			IOException missing = Assert.expectThrows(IOException.class, () ->
				store.requireMatchingLineageAttemptIndex(binding, lineage, slot, record, 0));
			requireThat(missing.getMessage(), "missingAttemptIndex").contains("attempt index is missing");

			String key = OwnerAttemptIndexKey.deriveLineage(ProducerAttemptKind.TRIAL, ownerRef,
				requestRef, claimRef, 0);
			store.appendCreateOnceIndex("trial-execution-attempts", key,
				new LineageEvidenceRef(lineage, attemptRef));
			store.requireMatchingLineageAttemptIndex(binding, lineage, slot, record, 0);
		}
	}

	/**
	 * Native start sees an indexed pre-start before dispatch, and a second start cannot run the trial again.
	 *
	 * @throws IOException if campaign or runner evidence cannot be written
	 * @throws NestedRunException if the controlled native handle cannot be closed
	 */
	@Test
	public void startsClaimedTrialOnlyOnce() throws IOException, NestedRunException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("start-initial-trial-")).toRealPath();
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
			Path artifact = Files.createDirectory(root.resolve("artifact"));
			RunnerRuntimeManifest.write(artifact);
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			CountingAdapter adapter = new CountingAdapter(store, binding);
			NestedAgent agent = new NestedAgent(adapter, store::readImmutableJson);
			try (SchedulerLease lease = acquire(locator, locator.openSchedulerLease(binding), binding,
				"first-trial"))
			{
				new InitialTrialAdmissionService(locator, store).admit(lease, assignmentRef, artifact);
				InitialTrialCallCoordinator coordinator = new InitialTrialCallCoordinator(locator, store);
				try (ActiveCall started = coordinator.startClaimed(lease, assignmentRef, agent, manager, artifact))
				{
					String callId = store.readTrialAssignment(assignmentRef).logicalTrialId();
					requireThat(store.readCallSlotClaim(binding, callId).slot().state(), "activeSlot").
						isEqualTo(CallSlot.State.ACTIVE);
					requireThat(started.runId(), "retainedRunId").isEqualTo(
						store.readCallPreStartRecord(binding, callId).orElseThrow().runId());
					try
					{
						coordinator.startClaimed(lease, assignmentRef, agent, manager, artifact);
						throw new AssertionError("A started trial must reconcile before another native start");
					}
					catch (IOException expected)
					{
						requireThat(expected.getMessage(), "reconciliationRequired").contains("claim");
					}
					requireThat(adapter.starts, "singleNativeStart").isEqualTo(1);
				}
			}
		}
	}

	/**
	 * An edit after request preparation and pre-start publication stops native trial dispatch.
	 *
	 * @throws IOException if campaign or runner evidence cannot be written
	 * @throws NestedRunException if the controlled native handle cannot be closed
	 */
	@Test
	public void rejectsSelectedEditAfterPreStart() throws IOException, NestedRunException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("trial-pre-start-drift-")).toRealPath();
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
			Path selectedFile = locator.optimizationRoot(binding).resolve("prompt.md");
			Path artifact = Files.createDirectory(root.resolve("artifact"));
			RunnerRuntimeManifest.write(artifact);
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"),
				new SelectedEditClock(selectedFile));
			CountingAdapter adapter = new CountingAdapter(store, binding);
			NestedAgent agent = new NestedAgent(adapter, store::readImmutableJson);
			try (SchedulerLease lease = acquire(locator, locator.openSchedulerLease(binding), binding,
				"first-trial"))
			{
				new InitialTrialAdmissionService(locator, store).admit(lease, assignmentRef, artifact);
				try (ActiveCall unexpected = new InitialTrialCallCoordinator(locator, store).
					startClaimed(lease, assignmentRef, agent, manager, artifact))
				{
					throw new AssertionError("A changed selected worktree started native run " + unexpected.runId());
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "selectedEditRejection").contains("clean fork");
				}
				requireThat(adapter.starts, "noNativeStartAfterSelectedEdit").isEqualTo(0);
			}
		}
	}

	/**
	 * Cancellation during native handle creation prevents trial activation and releases the handle.
	 *
	 * @throws IOException if campaign or runner evidence cannot be written
	 * @throws NestedRunException if the controlled native start fails unexpectedly
	 */
	@Test
	public void closesTrialHandleOnCancelledStart() throws IOException, NestedRunException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cancel-started-trial-")).toRealPath();
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
			Path artifact = Files.createDirectory(root.resolve("artifact"));
			RunnerRuntimeManifest.write(artifact);
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			CountingAdapter adapter = new CountingAdapter(store, binding);
			NestedAgent agent = new NestedAgent(adapter, store::readImmutableJson);
			try (SchedulerLease lease = acquire(locator, locator.openSchedulerLease(binding), binding,
				"first-trial"))
			{
				new InitialTrialAdmissionService(locator, store).admit(lease, assignmentRef, artifact);
				adapter.cancelDuringStart(lease);
				try
				{
					new InitialTrialCallCoordinator(locator, store).startClaimed(lease, assignmentRef,
						agent, manager, artifact);
					throw new AssertionError("A cancelled trial must not become active");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "cancelledStart").contains("Cancelled");
				}
				String callId = store.readTrialAssignment(assignmentRef).logicalTrialId();
				requireThat(store.readCallSlotClaim(binding, callId).slot().state(), "startingSlot").
					isEqualTo(CallSlot.State.STARTING);
				requireThat(adapter.starts, "oneNativeStart").isEqualTo(1);
				requireThat(adapter.interrupts, "nativeInterrupt").isEqualTo(1);
				requireThat(adapter.closes, "nativeClose").isEqualTo(1);
			}
		}
	}

	/**
	 * A native failure is indexed before adoption and survives a cold reopen without a semantic label.
	 *
	 * @throws IOException if campaign or runner evidence cannot be written
	 * @throws NestedRunException if the controlled native handle cannot close
	 */
	@Test
	public void adoptsNativeFailureAfterColdReopen() throws IOException, NestedRunException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("native-trial-completion-")).toRealPath();
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
			Path artifact = Files.createDirectory(root.resolve("artifact"));
			RunnerRuntimeManifest.write(artifact);
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			CountingAdapter adapter = new CountingAdapter(store, binding);
			NestedAgent agent = new NestedAgent(adapter, store::readImmutableJson);
			EvidenceRef indexedCompletion;
			try (SchedulerLease first = acquire(locator, locator.openSchedulerLease(binding), binding,
				"first-trial"))
			{
				new InitialTrialAdmissionService(locator, store).admit(first, assignmentRef, artifact);
				try (ActiveCall call = new InitialTrialCallCoordinator(locator, store).
					startClaimed(first, assignmentRef, agent, manager, artifact))
				{
					String callId = store.readTrialAssignment(assignmentRef).logicalTrialId();
					adapter.nativeCompletion.complete(new NestedRunCompletion.Failed(new NestedRunFailure(
						NestedRunFailureKind.TRANSPORT_FAILED, callId, Optional.of(call.runId()),
						"controlled transport failure", List.of(), Optional.empty(), Optional.empty())));
					indexedCompletion = new InitialTrialCompletionService(locator, store).publishCompletion(first,
						assignmentRef, call.completion().toCompletableFuture().join());
					requireThat(store.readNativeCompletionIndex(binding, callId), "indexedCompletion").
						isEqualTo(Optional.of(indexedCompletion));
				}
			}

			CampaignStore reopened = CampaignStore.open(store.directory());
			try (SchedulerLease successor = acquire(locator, locator.openSchedulerLease(binding), binding,
				"retry-trial"))
			{
				InitialTrialCompletionService completions = new InitialTrialCompletionService(locator, reopened);
				try (Stream<Path> entries = Files.list(reopened.directory().resolve("native-completion-index")))
				{
					Files.delete(entries.findFirst().orElseThrow());
				}
				try
				{
					completions.adoptIndexed(successor, assignmentRef);
					throw new AssertionError("A missing completion index must not adopt a trial");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "missingCompletionIndex").contains("index is missing");
				}
				String callId = reopened.readTrialAssignment(assignmentRef).logicalTrialId();
				reopened.publishNativeCompletionIndex(binding, callId, indexedCompletion);
				EvidenceRef adopted = completions.adoptIndexed(successor, assignmentRef);
				requireThat(completions.adoptIndexed(successor, assignmentRef), "sameAdoption").
					isEqualTo(adopted);
				requireThat(reopened.readCallSlotClaim(binding, callId).slot().state(), "adoptedSlot").
					isEqualTo(CallSlot.State.ADOPTED);
				requireThat(reopened.readNativeCompletionIndex(binding, callId), "retainedCompletion").
					isEqualTo(Optional.of(indexedCompletion));
				requireThat(adapter.starts, "oneNativeStart").isEqualTo(1);
			}
		}
	}

	/**
	 * A successful native result is bound to the observed runner before any trial outcome is adopted.
	 *
	 * @throws IOException if campaign or runner evidence cannot be written
	 * @throws NestedRunException if the controlled native handle cannot close
	 */
	@Test
	public void rejectsChangedRunnerBeforeNativeAdoption() throws IOException, NestedRunException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("successful-native-trial-")).toRealPath();
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
			Path artifact = Files.createDirectory(root.resolve("artifact"));
			RunnerRuntimeManifest.write(artifact);
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			CountingAdapter adapter = new CountingAdapter(store, binding);
			NestedAgent agent = new NestedAgent(adapter, store::readImmutableJson);
			try (SchedulerLease lease = acquire(locator, locator.openSchedulerLease(binding), binding,
				"first-trial"))
			{
				new InitialTrialAdmissionService(locator, store).admit(lease, assignmentRef, artifact);
				try (ActiveCall call = new InitialTrialCallCoordinator(locator, store).
					startClaimed(lease, assignmentRef, agent, manager, artifact))
				{
					String callId = store.readTrialAssignment(assignmentRef).logicalTrialId();
					ClaimedSlot claimed = store.readCallSlotClaim(binding, callId);
					InitialTrialRunRequest request = JsonMapper.builder().build().readValue(
						store.readImmutableJson(claimed.slot().requestRef()), InitialTrialRunRequest.class);
					List<NativeEventEvidence> events = new ArrayList<>();
					List<TurnResult> turns = new ArrayList<>();
					for (int index = 0; index < request.turns().size(); ++index)
					{
						EvidenceRef eventRef = store.appendImmutableBytes("native-events",
							("turn " + index).getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
					InitialTrialCompletionService completions = new InitialTrialCompletionService(locator, store);
					NestedRunResult wrongRunner = new NestedRunResult("test", call.runId(),
						root.resolve("native-project"), turns, events,
						new RunnerIdentity("test", "1", "a".repeat(64), Optional.of("test-provider"),
							Map.of(), List.of()), request.modelProfile(), List.of(), List.of(), List.of(), coverage);
					try
					{
						completions.publishCompletion(lease, assignmentRef,
							new NestedRunCompletion.Succeeded(wrongRunner));
						throw new AssertionError("A changed runner must not authorize a native completion");
					}
					catch (IOException expected)
					{
						requireThat(expected.getMessage(), "wrongRunner").contains("runner identity");
					}
					requireThat(store.readNativeCompletionIndex(binding, callId), "noWrongCompletion").isEmpty();
					NestedRunResult result = new NestedRunResult("test", call.runId(),
						root.resolve("native-project"), turns, events,
						new RunnerIdentity("test", "1", RunnerRuntimeManifest.fingerprint(artifact),
							Optional.of("test-provider"), Map.of(), List.of()), request.modelProfile(),
						List.of(), List.of(), List.of(), coverage);
					completions.publishCompletion(lease, assignmentRef,
						new NestedRunCompletion.Succeeded(result));
					EvidenceRef executionRef = completions.adoptIndexed(lease, assignmentRef);
					InitialTrialExecutionRecord execution = JsonMapper.builder().build().readValue(
						store.readImmutableJson(executionRef), InitialTrialExecutionRecord.class);
					requireThat(execution.assignmentRef(), "retainedAssignment").isEqualTo(assignmentRef);
					requireThat(store.readCallSlotClaim(binding, callId).slot().state(), "adoptedSlot").
						isEqualTo(CallSlot.State.ADOPTED);
				}
			}
		}
	}

	/**
	 * A successor adopts the original completed native trial without starting another call.
	 *
	 * @throws IOException if campaign or runner evidence cannot be written
	 * @throws NestedRunException if the controlled native handle cannot close
	 */
	@Test
	public void reconcilesSettledTrial() throws IOException, NestedRunException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("settled-native-trial-")).toRealPath();
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
			Path artifact = Files.createDirectory(root.resolve("artifact"));
			RunnerRuntimeManifest.write(artifact);
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			CountingAdapter adapter = new CountingAdapter(store, binding);
			NestedAgent agent = new NestedAgent(adapter, store::readImmutableJson);
			try (SchedulerLease first = acquire(locator, locator.openSchedulerLease(binding), binding,
				"first-trial"))
			{
				new InitialTrialAdmissionService(locator, store).admit(first, assignmentRef, artifact);
				try (ActiveCall call = new InitialTrialCallCoordinator(locator, store).
					startClaimed(first, assignmentRef, agent, manager, artifact))
				{
					String callId = store.readTrialAssignment(assignmentRef).logicalTrialId();
					adapter.nativeCompletion.complete(new NestedRunCompletion.Failed(new NestedRunFailure(
						NestedRunFailureKind.TRANSPORT_FAILED, callId, Optional.of(call.runId()),
						"controlled transport failure", List.of(), Optional.empty(), Optional.empty())));
				}
			}

			CampaignStore reopened = CampaignStore.open(store.directory());
			adapter.reconcileAsSettled = true;
			NestedAgent recoveredAgent = new NestedAgent(adapter, reopened::readImmutableJson);
			try (SchedulerLease successor = acquire(locator, locator.openSchedulerLease(binding), binding,
				"retry-trial"))
			{
				InitialTrialCallCoordinator coordinator = new InitialTrialCallCoordinator(locator, reopened);
				InitialTrialCallReconciliation result = coordinator.reconcile(successor, assignmentRef,
					recoveredAgent);
				requireThat(result instanceof InitialTrialCallReconciliation.Settled, "settled").isTrue();
				EvidenceRef executionRef = ((InitialTrialCallReconciliation.Settled) result).executionRef();
				requireThat(((InitialTrialCallReconciliation.Settled) coordinator.reconcile(successor,
					assignmentRef, recoveredAgent)).executionRef(), "sameExecution").isEqualTo(executionRef);
				String callId = reopened.readTrialAssignment(assignmentRef).logicalTrialId();
				requireThat(reopened.readCallSlotClaim(binding, callId).slot().state(), "adoptedSlot").
					isEqualTo(CallSlot.State.ADOPTED);
				requireThat(adapter.starts, "singleNativeStart").isEqualTo(1);
				requireThat(adapter.reconciliations, "singleNativeReconciliation").isEqualTo(1);
			}
		}
	}

	/**
	 * Unknown native state does not dispatch a replacement, and a later active reconciliation returns the original.
	 *
	 * @throws IOException if campaign or runner evidence cannot be written
	 * @throws NestedRunException if a recovered native handle cannot close
	 */
	@Test
	public void recoversActiveTrialAfterUnknown() throws IOException, NestedRunException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("active-native-trial-")).toRealPath();
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
			Path artifact = Files.createDirectory(root.resolve("artifact"));
			RunnerRuntimeManifest.write(artifact);
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			CountingAdapter adapter = new CountingAdapter(store, binding);
			NestedAgent agent = new NestedAgent(adapter, store::readImmutableJson);
			ActiveCall nativeCall;
			try (SchedulerLease first = acquire(locator, locator.openSchedulerLease(binding), binding,
				"first-trial"))
			{
				new InitialTrialAdmissionService(locator, store).admit(first, assignmentRef, artifact);
				nativeCall = new InitialTrialCallCoordinator(locator, store).
					startClaimed(first, assignmentRef, agent, manager, artifact);
			}

			try (ActiveCall original = nativeCall)
			{
				CampaignStore reopened = CampaignStore.open(store.directory());
				NestedAgent recoveredAgent = new NestedAgent(adapter, reopened::readImmutableJson);
				try (SchedulerLease successor = acquire(locator, locator.openSchedulerLease(binding), binding,
					"retry-trial"))
				{
					InitialTrialCallCoordinator coordinator = new InitialTrialCallCoordinator(locator, reopened);
					adapter.reconcileAsUnknown = true;
					InitialTrialCallReconciliation uncertain = coordinator.reconcile(successor, assignmentRef,
						recoveredAgent);
					requireThat(uncertain instanceof InitialTrialCallReconciliation.Unknown, "unknown").isTrue();
					String callId = reopened.readTrialAssignment(assignmentRef).logicalTrialId();
					requireThat(reopened.readCallSlotClaim(binding, callId).slot().state(), "stillActive").
						isEqualTo(CallSlot.State.ACTIVE);
					adapter.reconcileAsUnknown = false;
					adapter.reconcileAsActive = true;
					InitialTrialCallReconciliation recovered = coordinator.reconcile(successor, assignmentRef,
						recoveredAgent);
					requireThat(recovered instanceof InitialTrialCallReconciliation.Active, "active").isTrue();
					try (ActiveCall active = ((InitialTrialCallReconciliation.Active) recovered).call())
					{
						requireThat(active.runId(), "sameRunId").isEqualTo(original.runId());
						requireThat(adapter.starts, "singleNativeStart").isEqualTo(1);
						requireThat(adapter.reconciliations, "twoReconciliations").isEqualTo(2);
					}
				}
			}
		}
	}

	/**
	 * A successor recovers a native call started before its original owner could record activation.
	 *
	 * @throws IOException if campaign or runner evidence cannot be written
	 * @throws NestedRunException if a recovered native handle cannot close
	 */
	@Test
	public void recoversTrialStartedBeforeActivation() throws IOException, NestedRunException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("starting-native-trial-")).toRealPath();
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
			Path artifact = Files.createDirectory(root.resolve("artifact"));
			RunnerRuntimeManifest.write(artifact);
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			CountingAdapter adapter = new CountingAdapter(store, binding);
			NestedAgent agent = new NestedAgent(adapter, store::readImmutableJson);
			ActiveCall nativeCall;
			String callId = store.readTrialAssignment(assignmentRef).logicalTrialId();
			try (SchedulerLease first = acquire(locator, locator.openSchedulerLease(binding), binding,
				"first-trial"))
			{
				new InitialTrialAdmissionService(locator, store).admit(first, assignmentRef, artifact);
				TrialTransportAttemptService attempts = new TrialTransportAttemptService(locator, store);
				EvidenceRef attemptRef = attempts.publishAttempt(first, assignmentRef);
				attempts.publishPreStart(first, attemptRef, manager, artifact);
				CallPreStartRecord preStart = store.readCallPreStartRecord(binding, callId).orElseThrow();
				locator.openSchedulerLease(binding).prepareCallStart(first, callId, ProducerAttemptKind.TRIAL,
					attemptRef, preStart.ownerRequestRef(), manager, artifact);
				EvidenceRef requestRef = new InitialTrialRequestService(locator, store).prepare(assignmentRef);
				InitialTrialRunRequest request = JsonMapper.builder().build().readValue(
					store.readImmutableJson(requestRef), InitialTrialRunRequest.class);
				nativeCall = agent.start(request.asNested(), preStart.runId());
				requireThat(store.readCallSlotClaim(binding, callId).slot().state(), "startingSlot").
					isEqualTo(CallSlot.State.STARTING);
			}

			try (ActiveCall original = nativeCall)
			{
				CampaignStore reopened = CampaignStore.open(store.directory());
				NestedAgent recoveredAgent = new NestedAgent(adapter, reopened::readImmutableJson);
				adapter.reconcileAsActive = true;
				try (SchedulerLease successor = acquire(locator, locator.openSchedulerLease(binding), binding,
					"retry-trial"))
				{
					InitialTrialCallCoordinator coordinator = new InitialTrialCallCoordinator(locator, reopened);
					InitialTrialCallReconciliation recovered = coordinator.reconcile(successor, assignmentRef,
						recoveredAgent);
					requireThat(recovered instanceof InitialTrialCallReconciliation.Active, "active").isTrue();
					try (ActiveCall active = ((InitialTrialCallReconciliation.Active) recovered).call())
					{
						requireThat(active.runId(), "sameRunId").isEqualTo(original.runId());
						requireThat(reopened.readCallSlotClaim(binding, callId).slot().state(),
						"retainedStartingSlot").isEqualTo(CallSlot.State.STARTING);
						adapter.nativeCompletion.complete(new NestedRunCompletion.Failed(new NestedRunFailure(
							NestedRunFailureKind.TRANSPORT_FAILED, callId, Optional.of(active.runId()),
							"controlled transport failure", List.of(), Optional.empty(), Optional.empty())));
					}
					adapter.reconcileAsActive = false;
					adapter.reconcileAsSettled = true;
					InitialTrialCallReconciliation settled = coordinator.reconcile(successor, assignmentRef,
						recoveredAgent);
					requireThat(settled instanceof InitialTrialCallReconciliation.Settled, "settled").isTrue();
					requireThat(reopened.readCallSlotClaim(binding, callId).slot().state(), "adoptedSlot").
						isEqualTo(CallSlot.State.ADOPTED);
					requireThat(adapter.starts, "singleNativeStart").isEqualTo(1);
				}
			}
		}
	}

	/**
	 * Opens a scheduler owner with a test-owned host observation.
	 *
	 * @param locator registered locator
	 * @param scheduler registered scheduler
	 * @param binding pinned campaign
	 * @param invocationId distinct invocation
	 * @return live scheduler owner
	 * @throws IOException if registration or observation fails
	 */
	private static SchedulerLease acquire(CampaignLocator locator, CampaignSchedulerLease scheduler,
		CampaignBinding binding, String invocationId) throws IOException
	{
		CampaignStopInbox inbox = locator.openStopInbox(binding);
		EvidenceRef registration = inbox.beginInvocation(binding, invocationId);
		String digest;
		if (invocationId.equals("first-trial"))
			digest = "8".repeat(64);
		else if (invocationId.equals("retry-start"))
			digest = "7".repeat(64);
		else
			digest = "9".repeat(64);
		byte[] observation = JsonMapper.builder().build().writeValueAsBytes(new HostObservation(1,
			digest, "session-" + invocationId, "turn-" + invocationId));
		inbox.publishHostObservation(binding, invocationId, registration, observation);
		return scheduler.acquire(binding, invocationId);
	}

	/**
	 * Test-owned host identity at the scheduler boundary.
	 *
	 * @param schemaVersion supported host observation schema
	 * @param tokenDigest test-owned invocation token digest
	 * @param hostSessionId test-owned host session
	 * @param hostTurnId test-owned host turn
	 */
	private record HostObservation(int schemaVersion, String tokenDigest, String hostSessionId,
		String hostTurnId)
	{
	}

	/**
	 * Writes an unowned selected edit when the pre-start publisher allocates the native run ID.
	 */
	private static final class SelectedEditClock extends Clock
	{
		private final Path selectedFile;
		private boolean changed;

		/**
		 * Binds the controlled edit to the selected file.
		 *
		 * @param selectedFile selected worktree file to change during pre-start
		 */
		private SelectedEditClock(Path selectedFile)
		{
			this.selectedFile = selectedFile;
		}

		@Override
		public ZoneId getZone()
		{
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone)
		{
			return system(zone);
		}

		@Override
		public Instant instant()
		{
			return systemUTC().instant();
		}

		@Override
		public long millis()
		{
			if (!changed)
			{
				try
				{
					Files.writeString(selectedFile, "unowned edit after pre-start preparation\n");
				}
				catch (IOException exception)
				{
					throw new UncheckedIOException(exception);
				}
				changed = true;
			}
			return systemUTC().millis();
		}
	}

	/** Verifies that native dispatch sees the retained pre-start boundary. */
	private static final class CountingAdapter implements Adapter
	{
		private final CampaignStore store;
		private final CampaignBinding binding;
		private int starts;
		private int interrupts;
		private int closes;
		private int reconciliations;
		private boolean reconcileAsSettled;
		private boolean reconcileAsActive;
		private boolean reconcileAsUnknown;
		private SchedulerLease cancellationLease;
		private final CompletableFuture<NestedRunCompletion> nativeCompletion = new CompletableFuture<>();
		private AdapterCall nativeHandle;

		/**
		 * Watches one pinned campaign's native dispatch.
		 *
		 * @param store pinned campaign evidence
		 * @param binding exact campaign identity
		 */
		private CountingAdapter(CampaignStore store, CampaignBinding binding)
		{
			this.store = store;
			this.binding = binding;
		}

		/**
		 * Requests cancellation before the adapter returns its native handle.
		 *
		 * @param lease current scheduler owner
		 */
		private void cancelDuringStart(SchedulerLease lease)
		{
			cancellationLease = lease;
		}

		@Override
		public AdapterCall start(NestedRunRequest request, String runId) throws IOException
		{
			requireThat(store.readCallSlotClaim(binding, request.logicalCallId()).slot().state(),
				"startingAtDispatch").isEqualTo(CallSlot.State.STARTING);
			requireThat(store.readCallPreStartRecord(binding, request.logicalCallId()).orElseThrow().runId(),
				"indexedRunId").isEqualTo(runId);
			++starts;
			if (cancellationLease != null)
				cancellationLease.requestCancellation(request.logicalCallId());
			AdapterCall call = new AdapterCall()
			{
				@Override
				public String runId()
				{
					return runId;
				}

				@Override
				public CompletionStage<NestedRunCompletion> completion()
				{
					return nativeCompletion;
				}

				@Override
				public void interrupt()
				{
					++interrupts;
				}

				@Override
				public void close()
				{
					++closes;
				}
			};
			nativeHandle = call;
			return call;
		}

		@Override
		public AdapterCallReconciliation reconcile(NestedRunRequest request, Optional<String> knownRunId)
		{
			++reconciliations;
			if (reconcileAsSettled)
				return new AdapterCallReconciliation.Settled(nativeCompletion.join());
			if (reconcileAsActive)
				return new AdapterCallReconciliation.Active(nativeHandle);
			if (reconcileAsUnknown)
				return new AdapterCallReconciliation.Unknown(new NestedRunFailure(
				NestedRunFailureKind.NATIVE_STATE_UNKNOWN, request.logicalCallId(), knownRunId,
				"controlled native uncertainty", List.of(), Optional.empty(), Optional.empty()));
			throw new UnsupportedOperationException("This check does not reconcile native calls");
		}
	}
}
