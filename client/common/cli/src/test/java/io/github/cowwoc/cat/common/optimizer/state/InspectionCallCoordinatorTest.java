package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.candidate.EditableRoot;
import io.github.cowwoc.cat.common.optimizer.candidate.ConfirmedEditableRoots;
import io.github.cowwoc.cat.common.optimizer.candidate.ConfirmedSourceAuthorityService;
import io.github.cowwoc.cat.common.optimizer.candidate.ConfirmedSourceBaseline;
import io.github.cowwoc.cat.common.optimizer.candidate.ConfirmedSourceBaselineRepository;
import io.github.cowwoc.cat.common.optimizer.candidate.KnownSourcePresence;
import io.github.cowwoc.cat.common.optimizer.candidate.KnownSourceState;
import io.github.cowwoc.cat.common.optimizer.candidate.InitialCandidate;
import io.github.cowwoc.cat.common.optimizer.candidate.InitialCandidateService;
import io.github.cowwoc.cat.common.optimizer.candidate.PromptSnapshot;
import io.github.cowwoc.cat.common.optimizer.candidate.PromptSnapshotRepository;
import io.github.cowwoc.cat.common.optimizer.candidate.SnapshotJson;
import io.github.cowwoc.cat.common.optimizer.contract.PromptSource;
import io.github.cowwoc.cat.common.optimizer.contract.ContractProposal;
import io.github.cowwoc.cat.common.optimizer.contract.ContractService;
import io.github.cowwoc.cat.common.optimizer.contract.ContractTestFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.LineageActivation;
import io.github.cowwoc.cat.common.optimizer.contract.StoredProposal;
import io.github.cowwoc.cat.common.optimizer.loading.InspectedSourceEvidence;
import io.github.cowwoc.cat.common.optimizer.loading.InspectedSourceEvidenceService;
import io.github.cowwoc.cat.common.optimizer.runner.ActiveCall;
import io.github.cowwoc.cat.common.optimizer.runner.Adapter;
import io.github.cowwoc.cat.common.optimizer.runner.AdapterCall;
import io.github.cowwoc.cat.common.optimizer.runner.AdapterCallReconciliation;
import io.github.cowwoc.cat.common.optimizer.runner.NestedAgent;
import io.github.cowwoc.cat.common.optimizer.runner.NativeEventEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionCodec;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunCompletion;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunException;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailure;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailureKind;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunRequest;
import io.github.cowwoc.cat.common.optimizer.runner.ProjectRunContext;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunResult;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverage;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverageStatus;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationDomain;
import io.github.cowwoc.cat.common.optimizer.runner.PromptLoadMode;
import io.github.cowwoc.cat.common.optimizer.runner.PromptSourceOrigin;
import io.github.cowwoc.cat.common.optimizer.runner.RunnerIdentity;
import io.github.cowwoc.cat.common.optimizer.runner.RuntimePromptSource;
import io.github.cowwoc.cat.common.optimizer.runner.TurnResult;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import io.github.cowwoc.cat.common.runner.RunnerRuntimeManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks the production inspection start sequence through a caller-owned native adapter boundary.
 */
public final class InspectionCallCoordinatorTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** Creates an isolated coordinator test. */
	public InspectionCallCoordinatorTest()
	{
	}

	/**
	 * The first native start sees its indexed claim and pre-start identity before becoming active.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native start fails
	 */
	@Test
	public void startsIndexedInspectionThroughAgent() throws IOException, NestedRunException
	{
		runScenario(false, false, false, false, false, false);
	}

	/**
	 * A controlled native inspection retains a link and limited coverage through review, confirmation, and first
	 * candidate selection after reopening the campaign.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native start fails
	 */
	@Test
	public void selectsLinkedCandidateAfterConfirmation() throws IOException, NestedRunException
	{
		runScenario(false, false, false, false, false, false, SourceFixture.LINKED, true);
	}

	/**
	 * A controlled native inspection retains a regular source through review, confirmation, and first candidate
	 * selection after reopening the campaign.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native start fails
	 */
	@Test
	public void selectsRegularCandidateAfterConfirmation() throws IOException, NestedRunException
	{
		runScenario(false, false, false, false, false, false, SourceFixture.REGULAR, true);
	}

	/**
	 * A selected file reached through a directory link retains its source identity through candidate capture.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native start fails
	 */
	@Test
	public void selectsDirectoryLinkedCandidate() throws IOException, NestedRunException
	{
		runScenario(false, false, false, false, false, false, SourceFixture.DIRECTORY_LINKED_TARGET, true);
	}

	/**
	 * An observed in-project rule joins the reviewed source graph and the first candidate as read-only source bytes.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native start fails
	 */
	@Test
	public void reviewsObservedRuleBeforeCapture() throws IOException, NestedRunException
	{
		runScenario(false, false, false, false, false, false, SourceFixture.REGULAR_WITH_RULE, true);
	}

	/**
	 * An observed in-project rule link retains its destination identity and bytes through first candidate capture.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native start fails
	 */
	@Test
	public void reviewsObservedLinkedRuleBeforeCapture() throws IOException, NestedRunException
	{
		runScenario(false, false, false, false, false, false, SourceFixture.REGULAR_WITH_LINKED_RULE, true);
	}

	/**
	 * An observed rule reached through a directory link keeps its resolved bytes through confirmation and capture.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native start fails
	 */
	@Test
	public void reviewsObservedRuleThroughDirectoryLink() throws IOException, NestedRunException
	{
		runScenario(false, false, false, false, false, false,
			SourceFixture.REGULAR_WITH_DIRECTORY_LINKED_RULE, true);
	}

	/**
	 * A cold confirmed campaign refuses the missing adoption envelope behind its reviewed source.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native start fails
	 */
	@Test
	public void rejectsMissingAdoptionOnColdLoad() throws IOException, NestedRunException
	{
		runScenario(false, false, false, false, false, false, SourceFixture.LINKED, true,
			OwnerCloseFault.MISSING_ADOPTION);
	}

	/**
	 * After the scheduler lease closes, a new campaign store reads the adopted inspection before source review.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native start fails
	 */
	@Test
	public void recoversAdoptionForSourceReview() throws IOException, NestedRunException
	{
		runScenario(false, false, false, false, false, false, SourceFixture.LINKED, true,
			OwnerCloseFault.NONE, true);
	}

	/**
	 * After the scheduler lease closes, a cold review retains an observed rule link through candidate capture.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native start fails
	 */
	@Test
	public void recoversLinkedRuleAfterOwnerClose() throws IOException, NestedRunException
	{
		runScenario(false, false, false, false, false, false, SourceFixture.REGULAR_WITH_LINKED_RULE, true,
			OwnerCloseFault.NONE, true);
	}

	/**
	 * A cold campaign rejects a missing adopted-call envelope before source evidence is published.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native start fails
	 */
	@Test
	public void rejectsMissingAdoptionBeforeSourceReview() throws IOException, NestedRunException
	{
		runScenario(false, false, false, false, false, false, SourceFixture.LINKED, true,
			OwnerCloseFault.MISSING_ADOPTION, true);
	}

	/**
	 * A new campaign store rejects a corrupt adoption envelope before source evidence is published.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native start fails
	 */
	@Test
	public void rejectsCorruptAdoptionBeforeSourceReview() throws IOException, NestedRunException
	{
		runScenario(false, false, false, false, false, false, SourceFixture.LINKED, true,
			OwnerCloseFault.CORRUPT_ADOPTION, true);
	}

	/**
	 * A new campaign store rejects a missing completion index before publishing inspected source evidence.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native start fails
	 */
	@Test
	public void rejectsMissingIndexBeforeSourceReview() throws IOException, NestedRunException
	{
		runScenario(false, false, false, false, false, false, SourceFixture.LINKED, true,
			OwnerCloseFault.MISSING_INDEX, true);
	}

	/**
	 * A new campaign store rejects a corrupt completion index before publishing inspected source evidence.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native start fails
	 */
	@Test
	public void rejectsCorruptIndexBeforeSourceReview() throws IOException, NestedRunException
	{
		runScenario(false, false, false, false, false, false, SourceFixture.LINKED, true,
			OwnerCloseFault.CORRUPT_INDEX, true);
	}

	/**
	 * Cancellation during native handle creation prevents activation and closes the handle.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native start fails
	 */
	@Test
	public void interruptsCancelledStart() throws IOException, NestedRunException
	{
		runScenario(true, false, false, false, false, false);
	}

	/**
	 * A durable host stop during handle creation blocks activation and permits later result recovery.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native start fails
	 */
	@Test
	public void interruptsStartAfterHostStop() throws IOException, NestedRunException
	{
		runScenario(false, false, true, false, false, false);
	}

	/**
	 * A host stop is committed when native start fails after it submitted the stop.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native start fails unexpectedly
	 */
	@Test
	public void commitsStopAfterNativeStartFailure() throws IOException, NestedRunException
	{
		runScenario(false, false, true, false, true, false);
	}

	/**
	 * A stop on an active inspection commits interruption before retaining its later native result.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native start fails
	 */
	@Test
	public void transfersStopToActiveInspection() throws IOException, NestedRunException
	{
		runScenario(false, false, false, true, false, false);
	}

	/**
	 * A successor adopts a completed native inspection and the host stop left pending by its former owner.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native start fails
	 */
	@Test
	public void recoversResultAndPendingStop() throws IOException, NestedRunException
	{
		runScenario(false, true, false, false, false, true);
	}

	/**
	 * A successor adopts an indexed native result and the original pending host stop.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native start fails
	 */
	@Test
	public void recoversIndexedResultAndPendingStop() throws IOException, NestedRunException
	{
		runScenario(false, false, false, false, false, true);
	}

	/**
	 * A successor transfers a predecessor's pending stop to its recovered active native handle.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native handle cannot close
	 */
	@Test
	public void transfersPendingStopAfterRecovery() throws IOException, NestedRunException
	{
		runActiveRecovery(RecoveryMode.ACTIVE);
	}

	/**
	 * A recovered active native handle is closed when retained attempt evidence becomes invalid.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native handle cannot close
	 */
	@Test
	public void closesInvalidRecoveredHandle() throws IOException, NestedRunException
	{
		runActiveRecovery(RecoveryMode.INVALID_ATTEMPT);
	}

	/**
	 * A pending stop closes scheduling even when the retained native state cannot be determined.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native handle cannot close
	 */
	@Test
	public void commitsStopWithUnknownNativeState() throws IOException, NestedRunException
	{
		runActiveRecovery(RecoveryMode.UNKNOWN);
	}

	/**
	 * Runs active native reconciliation with an optional retained-attempt fault.
	 *
	 * @param mode the retained native state presented after owner recovery
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native handle cannot close
	 */
	private static void runActiveRecovery(RecoveryMode mode) throws IOException, NestedRunException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("active-inspection-recovery-")).toRealPath();
			Path project = Files.createDirectory(root.resolve("project"));
			git(project, "init");
			git(project, "config", "user.email", "test@example.invalid");
			git(project, "config", "user.name", "Inspection Test");
			Files.writeString(project.resolve("target.md"), "inspect this\n");
			git(project, "add", ".");
			git(project, "commit", "-m", "initial");

			Path workflow = Files.createDirectory(root.resolve("workflow"));
			CampaignLocator locator = new CampaignLocator(workflow);
			locator.createOrFind(new CampaignLookupKey(project, Path.of("target.md")),
				new CampaignCreationRequest("inspection", ProspectiveCampaignState.profile()));
			CampaignStore store = CampaignStore.open(workflow.resolve("campaign-inspection"));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = locator.openStopInbox(binding);
			EvidenceRef firstRegistration = inbox.beginInvocation(binding, "first");
			inbox.publishHostObservation(binding, "first", firstRegistration,
				JSON.writeValueAsBytes(new Observation(1, "a".repeat(64),
					"first-session", "first-turn")));
			Path artifact = Files.createDirectory(root.resolve("artifact"));
			RunnerRuntimeManifest.write(artifact);
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			ObservingAdapter adapter = new ObservingAdapter(store, binding);
			NestedAgent agent = new NestedAgent(adapter, store::readImmutableJson);
			StartedInspectionCall started;
			StopRequest pending;
			try (SchedulerLease first = locator.openSchedulerLease(binding).acquire(binding, "first"))
			{
				started = new InspectionCallCoordinator(locator, store).startFirst(first,
					agent, manager, artifact);
				pending = inbox.submit(binding, "first", "ESC");
			}

			try (ActiveCall original = started.call())
			{
				EvidenceRef successorRegistration = inbox.beginInvocation(binding, "successor");
				inbox.publishHostObservation(binding, "successor", successorRegistration,
					JSON.writeValueAsBytes(new Observation(1, "b".repeat(64),
						"successor-session", "successor-turn")));
				adapter.reconcileAsActive = mode != RecoveryMode.UNKNOWN;
				adapter.reconcileAsUnknown = mode == RecoveryMode.UNKNOWN;
				adapter.damageAttemptOnReconcile = mode == RecoveryMode.INVALID_ATTEMPT;
				try (SchedulerLease successor = locator.openSchedulerLease(binding).acquire(binding, "successor"))
				{
				if (mode == RecoveryMode.INVALID_ATTEMPT)
				{
					try
					{
						new InspectionCallCoordinator(locator, CampaignStore.open(store.directory())).
							reconcileFirst(successor, agent);
						throw new AssertionError("Damaged attempt must prevent active reconciliation");
					}
					catch (IOException expected)
					{
						requireThat(expected.getMessage(), "damagedAttempt").contains("Immutable evidence");
					}
					requireThat(adapter.interrupts, "cleanupInterrupts").isEqualTo(1);
					requireThat(adapter.closes, "cleanupCloses").isEqualTo(1);
					adapter.nativeCompletion.complete(new NestedRunCompletion.Failed(new NestedRunFailure(
						NestedRunFailureKind.INTERRUPTED, started.logicalCallId(), Optional.of(original.runId()),
						"native turn interrupted", List.of(), Optional.empty(), Optional.empty())));
					return;
				}
				if (mode == RecoveryMode.UNKNOWN)
				{
					InspectionCallReconciliation observed = new InspectionCallCoordinator(locator,
						CampaignStore.open(store.directory())).reconcileFirst(successor, agent);
					requireThat(observed instanceof InspectionCallReconciliation.Unknown,
						"unknownNativeState").isTrue();
					requireThat(store.load().status(), "interruptedCampaign").
						isEqualTo(CampaignStatus.INTERRUPTED);
					requireThat(store.load().acknowledgedInterruptionStopRef(), "acknowledgedStop").
						isEqualTo(pending.stopRef());
					requireThat(store.readCallSlotClaim(binding, started.logicalCallId()).slot().
						cancellationRequested(), "retainedCancellation").isTrue();
					requireThat(adapter.interrupts, "nativeInterruptsWithoutHandle").isEqualTo(0);
					adapter.nativeCompletion.complete(new NestedRunCompletion.Failed(new NestedRunFailure(
						NestedRunFailureKind.INTERRUPTED, started.logicalCallId(), Optional.of(original.runId()),
						"native turn interrupted", List.of(), Optional.empty(), Optional.empty())));
					return;
				}
					InspectionCallReconciliation observed = new InspectionCallCoordinator(locator,
						CampaignStore.open(store.directory())).reconcileFirst(successor, agent);
					requireThat(observed instanceof InspectionCallReconciliation.Active,
						"recoveredActiveCall").isTrue();
					StartedInspectionCall retained = ((InspectionCallReconciliation.Active) observed).started();
					requireThat(retained.ownerRef(), "retainedOwner").isEqualTo(started.ownerRef());
					requireThat(retained.attemptRef(), "retainedAttempt").isEqualTo(started.attemptRef());
					requireThat(retained.logicalCallId(), "retainedCallId").isEqualTo(started.logicalCallId());
					try (ActiveCall recovered = retained.call())
					{
						requireThat(recovered.runId(), "recoveredRunId").isEqualTo(original.runId());
						requireThat(store.load().status(), "interruptedCampaign").
							isEqualTo(CampaignStatus.INTERRUPTED);
						requireThat(store.load().acknowledgedInterruptionStopRef(), "acknowledgedStop").
							isEqualTo(pending.stopRef());
						requireThat(store.readCallSlotClaim(binding, started.logicalCallId()).slot().
							cancellationRequested(), "retainedCancellation").isTrue();
						requireThat(adapter.interrupts, "nativeInterrupts").isEqualTo(1);
						adapter.nativeCompletion.complete(new NestedRunCompletion.Failed(new NestedRunFailure(
							NestedRunFailureKind.INTERRUPTED, started.logicalCallId(), Optional.of(original.runId()),
							"native turn interrupted", List.of(), Optional.empty(), Optional.empty())));
					}
				}
			}
		}
	}

	/**
	 * A successor retrieves an active native handle after the original owner exits before activation.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native handle cannot close
	 */
	@Test
	public void recoversActiveStartAfterOwnerClose() throws IOException, NestedRunException
	{
		runStartingRecovery(false);
	}

	/**
	 * A successor interrupts a native handle whose original starting slot retained cancellation.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native handle cannot close
	 */
	@Test
	public void interruptsRecoveredCancelledStart() throws IOException, NestedRunException
	{
		runStartingRecovery(true);
	}

	/**
	 * Recovers a native handle from an older starting fence and optionally applies retained cancellation.
	 *
	 * @param cancelled whether the original owner retained cancellation before exiting
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native handle cannot close
	 */
	private static void runStartingRecovery(boolean cancelled) throws IOException, NestedRunException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("starting-inspection-recovery-")).toRealPath();
			Path project = Files.createDirectory(root.resolve("project"));
			git(project, "init");
			git(project, "config", "user.email", "test@example.invalid");
			git(project, "config", "user.name", "Inspection Test");
			Files.writeString(project.resolve("target.md"), "inspect this\n");
			git(project, "add", ".");
			git(project, "commit", "-m", "initial");
			Path workflow = Files.createDirectory(root.resolve("workflow"));
			CampaignLocator locator = new CampaignLocator(workflow);
			locator.createOrFind(new CampaignLookupKey(project, Path.of("target.md")),
				new CampaignCreationRequest("inspection", ProspectiveCampaignState.profile()));
			CampaignStore store = CampaignStore.open(workflow.resolve("campaign-inspection"));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = locator.openStopInbox(binding);
			EvidenceRef firstRegistration = inbox.beginInvocation(binding, "first");
			inbox.publishHostObservation(binding, "first", firstRegistration,
				JSON.writeValueAsBytes(new Observation(1, "a".repeat(64),
					"first-session", "first-turn")));
			Path artifact = Files.createDirectory(root.resolve("artifact"));
			RunnerRuntimeManifest.write(artifact);
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			ObservingAdapter adapter = new ObservingAdapter(store, binding);
			NestedAgent agent = new NestedAgent(adapter, store::readImmutableJson);
			PreparedInspectionCall prepared = new InspectionOwnerService(locator, store).prepare(binding);
			String callId = prepared.owner().logicalCallId();
			ActiveCall nativeCall;
			try (SchedulerLease first = locator.openSchedulerLease(binding).acquire(binding, "first"))
			{
				locator.openMeasurementObserver(binding).beforeInspectionCall(first, prepared);
				CampaignSchedulerLease scheduler = locator.openSchedulerLease(binding);
				scheduler.claimOrResumeCall(first, callId, prepared.owner().runRequestRef(),
					Optional.of(prepared.ownerRef()), Optional.of(prepared.owner().preparationCycleRef()));
				EvidenceRef attemptRef = new InspectionAttemptService(locator, store).
					publish(binding, prepared.ownerRef());
				scheduler.prepareCallStart(first, callId, ProducerAttemptKind.INSPECTION,
					attemptRef, prepared.ownerRef(), manager, artifact);
				String runId = store.readCallPreStartRecord(binding, callId).orElseThrow().runId();
				nativeCall = agent.start(prepared.request().asNested(), runId);
				if (cancelled)
					first.requestCancellation(callId);
			}
			try (ActiveCall original = nativeCall)
			{
				EvidenceRef successorRegistration = inbox.beginInvocation(binding, "successor");
				inbox.publishHostObservation(binding, "successor", successorRegistration,
					JSON.writeValueAsBytes(new Observation(1, "b".repeat(64),
						"successor-session", "successor-turn")));
				adapter.reconcileAsActive = true;
				try (SchedulerLease successor = locator.openSchedulerLease(binding).acquire(binding, "successor"))
				{
					InspectionCallReconciliation recovered = new InspectionCallCoordinator(locator,
						CampaignStore.open(store.directory())).reconcileFirst(successor, agent);
					requireThat(recovered instanceof InspectionCallReconciliation.Active, "activeRecovery").
						isTrue();
					try (ActiveCall adopted = ((InspectionCallReconciliation.Active) recovered).started().call())
					{
						requireThat(adopted.runId(), "sameRunId").isEqualTo(original.runId());
							requireThat(store.readCallSlotClaim(binding, callId).slot().state(), "retainedStart").
								isEqualTo(CallSlot.State.STARTING);
							requireThat(adapter.starts, "noSecondDispatch").isEqualTo(1);
							if (cancelled)
								requireThat(adapter.interrupts, "retainedCancellationInterrupt").isEqualTo(1);
							adapter.nativeCompletion.complete(new NestedRunCompletion.Failed(new NestedRunFailure(
								NestedRunFailureKind.TRANSPORT_FAILED, callId, Optional.of(original.runId()),
								"controlled native failure", List.of(), Optional.empty(), Optional.empty())));
							InspectionCompletionService completions = new InspectionCompletionService(locator,
								CampaignStore.open(store.directory()));
							completions.publishRecovered(successor, prepared.ownerRef(),
								adopted.completion().toCompletableFuture().join());
							completions.adoptIndexed(successor, prepared.ownerRef());
							requireThat(store.readCallSlotClaim(binding, callId).slot().state(), "adoptedCall").
								isEqualTo(CallSlot.State.ADOPTED);
					}
				}
			}
		}
	}

	/** Retained native states exercised after the original scheduler owner closes. */
	private enum RecoveryMode
	{
		ACTIVE,
		INVALID_ATTEMPT,
		UNKNOWN
	}

	/**
	 * A retained native result is indexed and adopted after the first scheduler lease closes before publication.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native start fails
	 */
	@Test
	public void recoversCompletionBeforeIndex() throws IOException, NestedRunException
	{
		runScenario(false, true, false, false, false, false);
	}

	/**
	 * Indicates whether the scenario tests native lifecycle behavior without source review or candidate capture.
	 *
	 * @param recoverBeforeIndex whether completion recovery precedes its durable index
	 * @param stopAfterStart whether the scenario transfers a stop to an active call
	 * @param stopBeforeRecovery whether recovery must also commit a pending stop
	 * @param confirmCandidate whether the scenario must confirm and capture its reviewed source
	 * @param sourceFixture source form and observed rules whose review must remain covered
	 * @param ownerCloseFault retained-evidence fault whose rejection must remain covered
	 * @param reopenAfterAdoption whether the scenario must review the source after owner closure
	 * @return true when only the regular-source lifecycle claim needs verification
	 */
	private static boolean testsOnlyLifecycle(boolean recoverBeforeIndex, boolean stopAfterStart,
		boolean stopBeforeRecovery, boolean confirmCandidate, SourceFixture sourceFixture,
		OwnerCloseFault ownerCloseFault, boolean reopenAfterAdoption)
	{
		return (recoverBeforeIndex || stopAfterStart || stopBeforeRecovery) && !confirmCandidate &&
			sourceFixture == SourceFixture.REGULAR && ownerCloseFault == OwnerCloseFault.NONE && !reopenAfterAdoption;
	}

	/**
	 * Runs one complete preparation and start scenario with an optional cancellation race.
	 *
	 * @param cancelDuringStart whether cancellation arrives while the adapter creates its handle
	 * @param recoverBeforeIndex whether native completion precedes its durable index
	 * @param stopDuringStart whether the host submits a durable stop while the adapter creates its handle
	 * @param stopAfterStart whether the host submits a durable stop after activation
	 * @param failNativeStart whether the adapter throws after submitting the stop
	 * @param stopBeforeRecovery whether a stop remains pending when the native result is recovered
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native start fails
	 */
	private static void runScenario(boolean cancelDuringStart, boolean recoverBeforeIndex,
		boolean stopDuringStart, boolean stopAfterStart, boolean failNativeStart, boolean stopBeforeRecovery)
		throws IOException, NestedRunException
	{
		runScenario(cancelDuringStart, recoverBeforeIndex, stopDuringStart, stopAfterStart, failNativeStart,
			stopBeforeRecovery, SourceFixture.REGULAR, false);
	}

	/**
	 * Runs preparation and start with the selected target represented as a regular file or an in-project link.
	 *
	 * @param cancelDuringStart whether cancellation arrives while the adapter creates its handle
	 * @param recoverBeforeIndex whether native completion precedes its durable index
	 * @param stopDuringStart whether the host submits a durable stop while the adapter creates its handle
	 * @param stopAfterStart whether the host submits a durable stop after activation
	 * @param failNativeStart whether the adapter throws after submitting the stop
	 * @param stopBeforeRecovery whether a stop remains pending when the native result is recovered
	 * @param sourceFixture selected target form and optional observed rule
	 * @param confirmCandidate whether to confirm the reviewed source and capture the first candidate
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native start fails
	 */
	private static void runScenario(boolean cancelDuringStart, boolean recoverBeforeIndex,
		boolean stopDuringStart, boolean stopAfterStart, boolean failNativeStart, boolean stopBeforeRecovery,
		SourceFixture sourceFixture, boolean confirmCandidate) throws IOException, NestedRunException
	{
		runScenario(cancelDuringStart, recoverBeforeIndex, stopDuringStart, stopAfterStart, failNativeStart,
			stopBeforeRecovery, sourceFixture, confirmCandidate, OwnerCloseFault.NONE);
	}

	/**
	 * Runs the selected-source scenario with an optional retained-evidence fault after the scheduler lease closes.
	 *
	 * @param cancelDuringStart whether cancellation arrives while the adapter creates its handle
	 * @param recoverBeforeIndex whether native completion precedes its durable index
	 * @param stopDuringStart whether the host submits a durable stop while the adapter creates its handle
	 * @param stopAfterStart whether the host submits a durable stop after activation
	 * @param failNativeStart whether the adapter throws after submitting the stop
	 * @param stopBeforeRecovery whether a stop remains pending when the native result is recovered
	 * @param sourceFixture selected target form and optional observed rule
	 * @param confirmCandidate whether to confirm the reviewed source and capture the first candidate
	 * @param ownerCloseFault retained evidence removed or corrupted after the scheduler lease closes
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native start fails
	 */
	private static void runScenario(boolean cancelDuringStart, boolean recoverBeforeIndex,
		boolean stopDuringStart, boolean stopAfterStart, boolean failNativeStart, boolean stopBeforeRecovery,
		SourceFixture sourceFixture, boolean confirmCandidate, OwnerCloseFault ownerCloseFault)
		throws IOException, NestedRunException
	{
		runScenario(cancelDuringStart, recoverBeforeIndex, stopDuringStart, stopAfterStart, failNativeStart,
			stopBeforeRecovery, sourceFixture, confirmCandidate, ownerCloseFault, false);
	}

	/**
	 * Runs the selected-source scenario with optional scheduler-lease closure before source review.
	 *
	 * @param cancelDuringStart whether cancellation arrives while the adapter creates its handle
	 * @param recoverBeforeIndex whether native completion precedes its durable index
	 * @param stopDuringStart whether the host submits a durable stop while the adapter creates its handle
	 * @param stopAfterStart whether the host submits a durable stop after activation
	 * @param failNativeStart whether the adapter throws after submitting the stop
	 * @param stopBeforeRecovery whether a stop remains pending when the native result is recovered
	 * @param sourceFixture selected target form and optional observed rule
	 * @param confirmCandidate whether to confirm the reviewed source and capture the first candidate
	 * @param ownerCloseFault retained evidence removed or corrupted after the scheduler lease closes
	 * @param reopenAfterAdoption whether to unwind the scheduler lease and reopen the store before source review
	 * @throws IOException if fixture Git or campaign I/O fails
	 * @throws NestedRunException if the controlled native start fails
	 */
	private static void runScenario(boolean cancelDuringStart, boolean recoverBeforeIndex,
		boolean stopDuringStart, boolean stopAfterStart, boolean failNativeStart, boolean stopBeforeRecovery,
		SourceFixture sourceFixture, boolean confirmCandidate, OwnerCloseFault ownerCloseFault,
		boolean reopenAfterAdoption)
		throws IOException, NestedRunException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			int expectedPathCount = 0;
			String promptCoverageLimit = "not observed";
			if (sourceFixture.hasObservedRule())
			{
				expectedPathCount = 1;
				promptCoverageLimit = "partial path visibility";
			}
			Path root = cleanup.add(Files.createTempDirectory("inspection-coordinator-")).toRealPath();
			Path project = Files.createDirectory(root.resolve("project"));
			git(project, "init");
			git(project, "config", "user.email", "test@example.invalid");
			git(project, "config", "user.name", "Inspection Test");
			switch (sourceFixture)
			{
				case REGULAR -> Files.writeString(project.resolve("target.md"), "inspect this\n");
				case REGULAR_WITH_RULE ->
				{
					Files.writeString(project.resolve("target.md"), "inspect this\n");
					Files.writeString(project.resolve("rules.md"), "review this rule\n");
				}
				case REGULAR_WITH_LINKED_RULE ->
				{
					Files.writeString(project.resolve("target.md"), "inspect this\n");
					Files.writeString(project.resolve("actual-rule.md"), "review this rule\n");
					Files.createSymbolicLink(project.resolve("rules.md"), Path.of("actual-rule.md"));
				}
				case REGULAR_WITH_DIRECTORY_LINKED_RULE ->
				{
					Files.writeString(project.resolve("target.md"), "inspect this\n");
					Path actualRules = Files.createDirectory(project.resolve("actual-rules"));
					Files.writeString(actualRules.resolve("rules.md"), "review this rule\n");
					Files.createSymbolicLink(project.resolve("rules"), Path.of("actual-rules"));
				}
				case LINKED ->
				{
					Files.writeString(project.resolve("actual.md"), "inspect this\n");
					Files.createSymbolicLink(project.resolve("target.md"), Path.of("actual.md"));
				}
				case DIRECTORY_LINKED_TARGET ->
				{
					Path actual = Files.createDirectory(project.resolve("actual-rules"));
					Files.writeString(actual.resolve("target.md"), "inspect this\n");
					Files.createSymbolicLink(project.resolve("rules"), Path.of("actual-rules"));
				}
			}
			git(project, "add", ".");
			git(project, "commit", "-m", "initial");

			Path workflow = Files.createDirectory(root.resolve("workflow"));
			CampaignLocator locator = new CampaignLocator(workflow);
			locator.createOrFind(new CampaignLookupKey(project, sourceFixture.selectedPath()),
				new CampaignCreationRequest("inspection", ProspectiveCampaignState.profile()));
			CampaignStore store = CampaignStore.open(workflow.resolve("campaign-inspection"));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = locator.openStopInbox(binding);
			EvidenceRef registration = inbox.beginInvocation(binding, "inspection-invocation");
			inbox.publishHostObservation(binding, "inspection-invocation", registration,
				JSON.writeValueAsBytes(new Observation(1, "7".repeat(64),
					"session-7", "turn-7")));
			Path artifact = Files.createDirectory(root.resolve("artifact"));
			RunnerRuntimeManifest.write(artifact);
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			ObservingAdapter adapter = new ObservingAdapter(store, binding);
			NestedAgent agent = new NestedAgent(adapter, store::readImmutableJson);
			StartedInspectionCall started = null;
			StopRequest pendingBeforeRecovery = null;
			String stoppedCallId = null;
			String stoppedRunId = null;
			try (SchedulerLease lease = locator.openSchedulerLease(binding).acquire(binding,
				"inspection-invocation"))
			{
				if (cancelDuringStart)
					adapter.cancelDuringStart(lease);
				if (stopDuringStart)
					adapter.stopDuringStart(inbox, lease.invocationId());
				adapter.failNativeStart = failNativeStart;
				InspectionCallCoordinator coordinator = new InspectionCallCoordinator(locator, store);
				if (cancelDuringStart || stopDuringStart)
				{
					if (failNativeStart)
					{
						try
						{
							coordinator.startFirst(lease, agent, manager, artifact);
							throw new AssertionError("Failed native start must not return an active call");
						}
						catch (NestedRunException expected)
						{
							requireThat(expected.failure().kind(), "nativeStartFailure").
								isEqualTo(NestedRunFailureKind.START_FAILED);
						}
					}
					else
					{
						try
						{
							coordinator.startFirst(lease, agent, manager, artifact);
							throw new AssertionError("A cancelled start must not return an active call");
						}
						catch (IOException expected)
						{
							String expectedMessage = "Cancelled";
							if (stopDuringStart)
								expectedMessage = "Pending stop";
							requireThat(expected.getMessage(), "blockedStart").contains(expectedMessage);
						}
					}
					requireThat(adapter.starts, "cancelledStarts").isEqualTo(1);
					int expectedCleanups = 1;
					if (failNativeStart)
						expectedCleanups = 0;
					requireThat(adapter.interrupts, "nativeInterrupts").isEqualTo(expectedCleanups);
					requireThat(adapter.closes, "nativeCloses").isEqualTo(expectedCleanups);
					if (stopDuringStart)
					{
						Campaign interrupted = store.load();
						requireThat(interrupted.status(), "interruptedCampaign").
							isEqualTo(CampaignStatus.INTERRUPTED);
						requireThat(interrupted.acknowledgedInterruptionStopRef(), "acknowledgedStop").
							isNotNull();
						stoppedCallId = interrupted.scheduler().slots().getFirst().logicalCallId();
						requireThat(store.readCallSlotClaim(binding, stoppedCallId).slot().cancellationRequested(),
							"durableCancellation").isTrue();
						stoppedRunId = store.readCallPreStartRecord(binding, stoppedCallId).orElseThrow().runId();
						EvidenceRef nativeEvent = store.appendImmutableBytes("native-events",
							"interrupted".getBytes(StandardCharsets.UTF_8));
						adapter.nativeCompletion.complete(new NestedRunCompletion.Failed(new NestedRunFailure(
							NestedRunFailureKind.INTERRUPTED, stoppedCallId, Optional.of(stoppedRunId),
							"native turn interrupted", List.of(nativeEvent), Optional.empty(), Optional.empty())));
					}
					if (cancelDuringStart)
						return;
				}
				else
				{
					started = coordinator.startFirst(lease, agent, manager, artifact);
					try (ActiveCall active = started.call())
					{
						requireThat(active.runId(), "liveRunId").isNotBlank();
						requireThat(adapter.starts, "nativeStarts").isEqualTo(1);
						requireThat(store.readCallSlotClaim(binding, started.logicalCallId()).slot().state(),
							"activeSlot").isEqualTo(CallSlot.State.ACTIVE);
						if (!testsOnlyLifecycle(recoverBeforeIndex, stopAfterStart, stopBeforeRecovery, confirmCandidate,
							sourceFixture, ownerCloseFault, reopenAfterAdoption))
						{
							requireThat(new InspectionAttemptService(locator, store).load(binding,
								started.attemptRef()).header().ownerRequestRef(), "indexedOwner").
								isEqualTo(started.ownerRef());
							PreparedInspectionCall prepared = new InspectionOwnerService(locator, store).load(binding,
								started.ownerRef());
							EvidenceRef observationRef = store.readPreconfirmationIndex(binding,
								PreconfirmationIndexKind.CONTRACT_INSPECTION_BEFORE_OBSERVATION,
								PreparationMeasurementObservation.indexKey(
									prepared.owner().preparationCycleRef(), started.logicalCallId())).orElseThrow();
							PreparationMeasurementObservation observation = JSON.readValue(
								store.readImmutableJson(observationRef), PreparationMeasurementObservation.class);
							requireThat(observation.ownerRef(), "observedOwner").isEqualTo(started.ownerRef());
							requireThat(observation.conditions().values().get(MeasurementConditions.Kind.MODEL_PROFILE),
								"observedModel").isInstanceOf(MeasurementConditions.Value.Known.class);
							requireThat(observation.conditions().values().get(MeasurementConditions.Kind.PROMPT_SOURCES),
								"observedSnapshot").isEqualTo(new MeasurementConditions.Value.Known(
									prepared.request().context().snapshot().snapshotManifestRef()));
						}
						if (stopAfterStart)
						{
							StartedInspectionCall foreignAttempt = new StartedInspectionCall(started.ownerRef(),
								new EvidenceRef("contract-inspection-attempts", "0".repeat(64)),
								started.logicalCallId(), active);
							try
							{
								coordinator.transferPendingStop(lease, foreignAttempt);
								throw new AssertionError("An unindexed attempt must not transfer a stop");
							}
							catch (IOException _)
							{
								requireThat(adapter.interrupts, "rejectedAttemptInterrupts").isEqualTo(0);
							}

							if (!(adapter.startedRequest.context() instanceof ProjectRunContext context))
								throw new AssertionError("Inspection must use a project context");
							Path inspectionTarget = context.snapshot().projectRoot().resolve("target.md");
							byte[] retainedTarget = Files.readAllBytes(inspectionTarget);
							Files.writeString(inspectionTarget, "changed between operations\n");
							try
							{
								try
								{
									coordinator.transferPendingStop(lease, started);
									throw new AssertionError("A later stop transfer must reject the changed original worktree");
								}
								catch (IOException expected)
								{
									requireThat(expected.getMessage(), "freshWorktreeValidation").contains("clean fork");
									requireThat(adapter.interrupts, "changedWorktreeInterrupts").isEqualTo(0);
								}
							}
							finally
							{
								Files.write(inspectionTarget, retainedTarget);
							}

							StopRequest submitted = inbox.submit(binding, lease.invocationId(), "ESC");
							StopRequest adoptedStop = coordinator.transferPendingStop(lease, started).orElseThrow();
							requireThat(adoptedStop.stopRef(),
								"transferredStop").isEqualTo(submitted.stopRef());
							requireThat(store.readCallSlotClaim(binding, started.logicalCallId()).slot().
								cancellationRequested(), "durableCancellation").isTrue();
							requireThat(adapter.interrupts, "nativeInterrupts").isEqualTo(1);
							requireThat(coordinator.transferPendingStop(lease, started).orElseThrow().stopRef(),
								"sameStop").isEqualTo(submitted.stopRef());
							requireThat(adapter.interrupts, "oneNativeInterrupt").isEqualTo(1);
							TransitionResult interruption = new CampaignInterruptionService(locator, store).
								commitHostStop(lease, adoptedStop, Optional.of(started.logicalCallId()));
							requireThat(interruption.phase(), "interruptedCampaign").
								isEqualTo(CampaignStatus.INTERRUPTED);
							requireThat(store.load().acknowledgedInterruptionStopRef(), "acknowledgedStop").
								isEqualTo(adoptedStop.stopRef());
						}
						if (stopBeforeRecovery)
							pendingBeforeRecovery = inbox.submit(binding, lease.invocationId(), "ESC");
						EvidenceRef nativeEvent = store.appendImmutableBytes("native-events",
							"{}".getBytes(StandardCharsets.UTF_8));
						NativeEventEvidence event = new NativeEventEvidence("conversation", 1, nativeEvent);
						List<NativeEventEvidence> nativeEvents = new ArrayList<>(List.of(event));
						List<RuntimePromptSource> promptSources = List.of();
						List<EvidenceRef> promptEvidence = List.of();
						if (sourceFixture.hasObservedRule())
						{
							EvidenceRef ruleEvent = store.appendImmutableBytes("native-events",
								("{\"source\":\"" + sourceFixture.observedRulePath() + "\"}").
									getBytes(StandardCharsets.UTF_8));
							nativeEvents.add(new NativeEventEvidence("conversation", 2, ruleEvent));
							promptSources = List.of(new RuntimePromptSource("conversation", 2, "rule",
								sourceFixture.observedRulePath(), PromptSourceOrigin.PROJECT_RULE,
								Optional.of(PromptLoadMode.CONDITIONAL), Optional.of(Path.of("target.md")),
								Optional.of("fixture observed rule path"), Optional.of(Path.of("target.md")), ruleEvent));
							promptEvidence = List.of(ruleEvent);
						}
						NestedRunResult result = new NestedRunResult("test", active.runId(),
							root.resolve("native-project"), List.of(new TurnResult(1, "conversation", "sources",
								event)), nativeEvents, new RunnerIdentity("test", "1",
								RunnerRuntimeManifest.fingerprint(artifact), Optional.of("test-provider"),
								java.util.Map.of(), List.of()),
								new ModelProfile(adapter.startedRequest.modelProfile().modelId(), Optional.of("high"),
									java.util.Map.of()), promptSources, List.of(), List.of(), List.of(
								new ObservationCoverage(ObservationDomain.PROMPT_SOURCE,
									ObservationCoverageStatus.LIMITED, Optional.of(promptCoverageLimit),
									promptEvidence),
								new ObservationCoverage(ObservationDomain.DIRECT_TOOL,
									ObservationCoverageStatus.COMPLETE, Optional.empty(), List.of()),
								new ObservationCoverage(ObservationDomain.EXTERNAL_SOURCE,
									ObservationCoverageStatus.COMPLETE, Optional.empty(), List.of())));
						adapter.nativeCompletion.complete(new NestedRunCompletion.Succeeded(result));
						NestedRunCompletion settled = active.completion().toCompletableFuture().join();
						if (recoverBeforeIndex || stopAfterStart)
							requireThat(store.readNativeCompletionIndex(binding, started.logicalCallId()),
								"unindexedCompletion").isEmpty();
						else
						{
							EvidenceRef indexed = new InspectionCompletionService(locator, store).
								publish(lease, started, settled);
							requireThat(store.readNativeCompletionIndex(binding, started.logicalCallId()),
								"indexedCompletion").isEqualTo(Optional.of(indexed));
						}
						requireThat(store.readCallSlotClaim(binding, started.logicalCallId()).slot().state(),
							"preAdoptionSlot").isEqualTo(CallSlot.State.ACTIVE);
					}
				}
			}
			if (stopDuringStart)
			{
				EvidenceRef resumedRegistration = inbox.beginInvocation(binding, "stopped-resumption");
				inbox.publishHostObservation(binding, "stopped-resumption", resumedRegistration,
					JSON.writeValueAsBytes(new Observation(1, "8".repeat(64),
						"session-8", "turn-8")));
				adapter.reconcileAsSettled = true;
				CampaignStore fresh = CampaignStore.open(store.directory());
				try (SchedulerLease resumed = locator.openSchedulerLease(binding).acquire(binding,
					"stopped-resumption"))
				{
					InspectionCallReconciliation reconciled = new InspectionCallCoordinator(
						new CampaignLocator(workflow), fresh).reconcileFirst(resumed,
						new NestedAgent(adapter, fresh::readImmutableJson));
					requireThat(reconciled instanceof InspectionCallReconciliation.Settled,
						"recoveredInterruptedResult").isTrue();
					requireThat(fresh.readCallSlotClaim(binding, stoppedCallId).slot().state(),
						"adoptedAfterInterruption").isEqualTo(CallSlot.State.ADOPTED);
					requireThat(fresh.readCallSlotClaim(binding, stoppedCallId).slot().runId(),
						"retainedPreStartRunId").isEqualTo(Optional.of(stoppedRunId));
				}
				requireThat(adapter.starts, "oneNativeStart").isEqualTo(1);
				return;
			}
			EvidenceRef resumedRegistration = inbox.beginInvocation(binding, "resumed-invocation");
			inbox.publishHostObservation(binding, "resumed-invocation", resumedRegistration,
				JSON.writeValueAsBytes(new Observation(1, "9".repeat(64),
					"session-9", "turn-9")));
			InspectionCompletionService recovered = new InspectionCompletionService(new CampaignLocator(workflow),
				CampaignStore.open(store.directory()));
			ContractProposal selectedReviewed;
			StoredProposal selectedRecorded;
			EvidenceRef adoptedBeforeOwnerClose = null;
			try (SchedulerLease resumed = locator.openSchedulerLease(binding).acquire(binding,
				"resumed-invocation"))
			{
				if (recoverBeforeIndex || stopAfterStart || stopBeforeRecovery)
				{
					adapter.reconcileAsSettled = true;
					CampaignStore fresh = CampaignStore.open(store.directory());
					InspectionCallReconciliation observed = new InspectionCallCoordinator(new CampaignLocator(workflow),
						fresh).reconcileFirst(resumed, new NestedAgent(adapter, fresh::readImmutableJson));
					requireThat(observed instanceof InspectionCallReconciliation.Settled,
						"recoveredNativeCompletion").isTrue();
					if (stopBeforeRecovery)
					{
						requireThat(fresh.load().status(), "recoveredInterruption").
							isEqualTo(CampaignStatus.INTERRUPTED);
						requireThat(fresh.load().acknowledgedInterruptionStopRef(), "recoveredStop").
							isEqualTo(pendingBeforeRecovery.stopRef());
					}
				}
				EvidenceRef adopted = recovered.adoptIndexed(resumed, started.ownerRef());
				if (reopenAfterAdoption)
				{
					requireThat(Files.notExists(store.directory().resolve("prompt-loading")),
						"noSourceEvidenceBeforeOwnerClose").isTrue();
					adoptedBeforeOwnerClose = adopted;
					throw new SimulatedOwnerInterruption();
				}
				if (testsOnlyLifecycle(recoverBeforeIndex, stopAfterStart, stopBeforeRecovery, confirmCandidate,
					sourceFixture, ownerCloseFault, reopenAfterAdoption))
				{
					ContractInspectionResult retained = JSON.readValue(
						store.readImmutableJson(adopted), ContractInspectionResult.class);
					requireThat(retained.ownerRef(), "recoveredResultOwner").isEqualTo(started.ownerRef());
					requireThat(retained.attemptRef(), "recoveredResultAttempt").isEqualTo(started.attemptRef());
					requireThat(recovered.adoptIndexed(resumed, started.ownerRef()), "sameAdoption").
						isEqualTo(adopted);
					requireThat(recovered.adopt(resumed, started,
						adapter.nativeCompletion.join()), "liveRetryAdoption").isEqualTo(adopted);
					requireThat(store.readCallSlotClaim(binding, started.logicalCallId()).slot().state(),
						"adoptedSlot").isEqualTo(CallSlot.State.ADOPTED);
					return;
				}
				EvidenceRef sourceCapture = new InspectionOwnerService(locator, store).load(binding,
					started.ownerRef()).owner().sourceCaptureRef();
				JsonNode adoptedJson = JSON.readTree(store.readImmutableJson(adopted));
				requireThat(adoptedJson.path("sourceCaptureRef").isObject(), "adoptedSourcePresent").isTrue();
				requireThat(adoptedJson.path("sourceCaptureRef").path("collection").stringValue(),
					"adoptedSourceCollection").isEqualTo(sourceCapture.collection());
				requireThat(adoptedJson.path("sourceCaptureRef").path("evidenceId").stringValue(),
					"adoptedSourceIdentity").isEqualTo(sourceCapture.evidenceId());
				JsonNode proposalReference = adoptedJson.path("selectedSourceProposalRef");
				requireThat(proposalReference.isObject(), "selectedSourceProposalPresent").isTrue();
				EvidenceRef proposalRef = new EvidenceRef(proposalReference.path("collection").stringValue(),
					proposalReference.path("evidenceId").stringValue());
				JsonNode sourceProposal = JSON.readTree(store.readImmutableJson(proposalRef));
				requireThat(sourceProposal.path("sourceCaptureRef").path("evidenceId").stringValue(),
					"proposedSourceCapture").isEqualTo(sourceCapture.evidenceId());
				requireThat(sourceProposal.path("nativeCompletionRef").path("evidenceId").stringValue(),
					"proposedNativeCompletion").isEqualTo(adoptedJson.path("completionRef").path("evidenceId").
						stringValue());
				Path expectedEditablePath = switch (sourceFixture)
				{
					case REGULAR, REGULAR_WITH_RULE, REGULAR_WITH_LINKED_RULE,
						REGULAR_WITH_DIRECTORY_LINKED_RULE -> Path.of("target.md");
					case LINKED -> Path.of("actual.md");
					case DIRECTORY_LINKED_TARGET -> Path.of("actual-rules", "target.md");
				};
				requireThat(sourceProposal.path("editableRoot").path("path").stringValue(),
					"proposedEditablePath").isEqualTo(expectedEditablePath.toString());
				requireThat(sourceProposal.path("editableRoot").path("kind").stringValue(),
					"proposedEditKind").isEqualTo("FILE");
				InitialTargetCapture selectedCapture = new InitialTargetCaptureService(locator, store).load(binding,
					sourceCapture);
				requireThat(sourceProposal.path("selectedPath").stringValue(), "reviewedSelectedPath").isEqualTo(
					selectedCapture.targetPath().toString());
				requireThat(sourceProposal.path("resolvedSourcePath").stringValue(), "reviewedResolvedPath").
					isEqualTo(selectedCapture.resolvedSourcePath().toString());
				requireThat(sourceProposal.path("selectedLinkTarget").stringValue(), "reviewedLinkDestination").
					isEqualTo(selectedCapture.selectedLinkTarget());
				requireThat(sourceProposal.path("selectedDirectoryLinks").isArray(), "reviewedDirectoryLinks").
					isTrue();
				requireThat(sourceProposal.path("retainedSourceSha256").stringValue(), "reviewedRetainedHash").
					isEqualTo(selectedCapture.sourceSha256());
				requireThat(sourceProposal.path("promptPathObservations").isArray(), "reviewedPathObservations").
					isTrue();
				requireThat(sourceProposal.path("promptPathObservations").size(), "reviewedPathEventCount").
					isEqualTo(expectedPathCount);
				requireThat(sourceProposal.path("promptSourceCoverage").path("status").stringValue(),
					"reviewedPromptCoverage").isEqualTo("LIMITED");
				CampaignStore reopenedBeforeSourceReview = CampaignStore.open(store.directory());
				EvidenceRef adoptionEnvelopeRef = reopenedBeforeSourceReview.readCallSlotClaim(binding,
					started.logicalCallId()).slot().adoptionRef().orElseThrow();
				CallAdoptionRecord adoptionEnvelope = JSON.readValue(
					reopenedBeforeSourceReview.readImmutableJson(adoptionEnvelopeRef), CallAdoptionRecord.class);
				EvidenceRef indexedAdoption = adoptionEnvelope.producerRecordRef();
				requireThat(indexedAdoption, "recoveredAdoptionForSourceReview").isEqualTo(adopted);
				InspectedSourceEvidenceService loading = new InspectedSourceEvidenceService(
					new CampaignLocator(workflow), reopenedBeforeSourceReview);
				EvidenceRef loadingRef = loading.publish(binding, indexedAdoption);
				InspectedSourceEvidence coldLoaded = new InspectedSourceEvidenceService(
					new CampaignLocator(workflow), CampaignStore.open(store.directory())).load(binding, loadingRef);
				requireThat(loadingRef.collection(), "inspectedLoadingCollection").isEqualTo("prompt-loading");
				requireThat(coldLoaded.inspectionResultRef(), "loadingProducer").isEqualTo(adopted);
				requireThat(coldLoaded.selectedSource().path(), "loadingSelectedPath").isEqualTo(
					selectedCapture.targetPath());
				requireThat(coldLoaded.selectedSource().resolvedSourcePath(), "loadingResolvedPath").isEqualTo(
					selectedCapture.resolvedSourcePath());
				requireThat(coldLoaded.selectedSource().linkTarget(), "loadingLinkTarget").isEqualTo(
					selectedCapture.selectedLinkTarget());
				requireThat(coldLoaded.selectedSource().directoryLinks(), "loadingDirectoryLinks").isEqualTo(
					selectedCapture.selectedDirectoryLinks());
				requireThat(coldLoaded.selectedSource().sha256(), "loadingRetainedHash").isEqualTo(
					selectedCapture.sourceSha256());
				if (sourceFixture == SourceFixture.DIRECTORY_LINKED_TARGET)
					requireThat(coldLoaded.selectedSource().directoryLinks(), "selectedDirectoryLink").isEqualTo(
						List.of(new PromptSource.DirectoryLink(Path.of("rules"), "actual-rules",
							Path.of("actual-rules"))));
				requireThat(coldLoaded.pathObservations().size(), "loadingNativePathCount").isEqualTo(
					expectedPathCount);
				if (sourceFixture.hasObservedRule())
				{
					RuntimePromptSource observedRule = coldLoaded.pathObservations().getFirst();
					requireThat(observedRule.normalizedRelativePath(), "observedRulePath").isEqualTo(
						sourceFixture.observedRulePath());
					requireThat(observedRule.origin(), "observedRuleOrigin").isEqualTo(
						PromptSourceOrigin.PROJECT_RULE);
				requireThat(observedRule.loadMode(), "observedRuleLoadMode").isEqualTo(
					Optional.of(PromptLoadMode.CONDITIONAL));
				PromptSnapshot retained = new PromptSnapshotRepository(store.directory()).load(
					selectedCapture.snapshotId());
				requireThat(coldLoaded.sources().size(), "retainedSourceCount").isEqualTo(2);
				requireThat(coldLoaded.sources().getFirst(), "retainedSelectedSource").isEqualTo(
					coldLoaded.selectedSource());
				Path resolvedRulePath = sourceFixture.ruleContentPath();
				String ruleLinkTarget = "";
				if (sourceFixture == SourceFixture.REGULAR_WITH_LINKED_RULE)
					ruleLinkTarget = "actual-rule.md";
				List<PromptSource.DirectoryLink> directoryLinks = List.of();
				if (sourceFixture == SourceFixture.REGULAR_WITH_DIRECTORY_LINKED_RULE)
					directoryLinks = List.of(new PromptSource.DirectoryLink(Path.of("rules"), "actual-rules",
						Path.of("actual-rules")));
				requireThat(coldLoaded.sources().get(1), "retainedObservedRule").isEqualTo(
					new PromptSource(sourceFixture.observedRulePath(), resolvedRulePath, ruleLinkTarget,
						directoryLinks, retained.entries().get(resolvedRulePath).hash()));
			}
				requireThat(coldLoaded.coverage().status(), "loadingNativeCoverage").isEqualTo(
					ObservationCoverageStatus.LIMITED);
				requireThat(coldLoaded.proposedEditableRoot().path(), "loadingProposedRoot").isEqualTo(
					expectedEditablePath);
				requireThat(loading.publish(binding, adopted), "loadingPublicationRetry").isEqualTo(loadingRef);
				ContractProposal reviewed;
			if (sourceFixture.hasObservedRule())
			{
				reviewed = ContractTestFixtures.inspectedProposalWithRule(loadingRef.evidenceId(),
					coldLoaded.selectedSource(), coldLoaded.sources().get(1), coldLoaded.proposedEditableRoot());
			}
			else
				reviewed = ContractTestFixtures.inspectedProposal(loadingRef.evidenceId(),
					coldLoaded.selectedSource(), coldLoaded.proposedEditableRoot());
			if (sourceFixture.hasObservedRule())
			{
				ContractProposal missingRule = ContractTestFixtures.inspectedProposal(loadingRef.evidenceId(),
					coldLoaded.selectedSource(), coldLoaded.proposedEditableRoot());
				try
				{
					new ContractService(store).recordInspectedProposal(ContractTestFixtures.bytes(missingRule),
						loadingRef);
					throw new AssertionError("Review must include the observed rule source");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "missingObservedRule").contains("do not match");
				}
				PromptSource changedRule = new PromptSource(sourceFixture.observedRulePath(), "0".repeat(64));
				ContractProposal wrongRuleBytes = ContractTestFixtures.withSources(reviewed,
					List.of(coldLoaded.selectedSource(), changedRule));
				try
				{
					new ContractService(store).recordInspectedProposal(ContractTestFixtures.bytes(wrongRuleBytes),
						loadingRef);
					throw new AssertionError("Review must retain the observed rule's captured byte identity");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "changedObservedRule").contains("do not match");
				}
				if (sourceFixture == SourceFixture.REGULAR_WITH_LINKED_RULE)
				{
					PromptSource changedLink = new PromptSource(Path.of("rules.md"),
						Path.of("actual-rule.md"), "other-rule.md", coldLoaded.sources().get(1).sha256());
					ContractProposal wrongRuleLink = ContractTestFixtures.withSources(reviewed,
						List.of(coldLoaded.selectedSource(), changedLink));
					try
					{
						new ContractService(store).recordInspectedProposal(ContractTestFixtures.bytes(wrongRuleLink),
							loadingRef);
						throw new AssertionError("Review must retain the observed rule's exact link destination");
					}
					catch (IOException expected)
					{
						requireThat(expected.getMessage(), "changedObservedRuleLink").contains("do not match");
					}
				}
				if (sourceFixture == SourceFixture.REGULAR_WITH_DIRECTORY_LINKED_RULE)
				{
					PromptSource observedRule = coldLoaded.sources().get(1);
					PromptSource changedLink = new PromptSource(observedRule.path(),
						observedRule.resolvedSourcePath(), observedRule.linkTarget(),
						List.of(new PromptSource.DirectoryLink(Path.of("rules"), "other-rules",
							Path.of("actual-rules"))), observedRule.sha256());
					ContractProposal wrongRuleLink = ContractTestFixtures.withSources(reviewed,
						List.of(coldLoaded.selectedSource(), changedLink));
					try
					{
						new ContractService(store).recordInspectedProposal(ContractTestFixtures.bytes(wrongRuleLink),
							loadingRef);
						throw new AssertionError("Review must retain the directory link's exact destination");
					}
					catch (IOException expected)
					{
						requireThat(expected.getMessage(), "changedDirectoryLink").contains("do not match");
					}
				}
			}
			if (sourceFixture == SourceFixture.DIRECTORY_LINKED_TARGET)
			{
				PromptSource source = coldLoaded.selectedSource();
				PromptSource changedLink = new PromptSource(source.path(), source.resolvedSourcePath(),
					source.linkTarget(), List.of(new PromptSource.DirectoryLink(Path.of("rules"),
						"other-rules", Path.of("actual-rules"))), source.sha256());
				ContractProposal wrongSelectedLink = ContractTestFixtures.withSources(reviewed,
					List.of(changedLink));
				try
				{
					new ContractService(store).recordInspectedProposal(ContractTestFixtures.bytes(
						wrongSelectedLink), loadingRef);
					throw new AssertionError("Review must retain the selected directory link's destination");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "changedSelectedDirectoryLink").contains("do not match");
				}
			}
			StoredProposal recorded = new ContractService(store).recordInspectedProposal(
					ContractTestFixtures.bytes(reviewed), loadingRef);
				selectedReviewed = reviewed;
				selectedRecorded = recorded;
				StoredProposal restored = new ContractService(CampaignStore.open(store.directory())).loadProposal(
					recorded.evidenceId());
				requireThat(restored.proposal(), "coldInspectedProposal").isEqualTo(reviewed);
				if (sourceFixture == SourceFixture.LINKED)
				{
					InitialTargetCapture captured = new InitialTargetCaptureService(locator, store).load(binding,
						sourceCapture);
					requireThat(captured.targetPath(), "selectedLinkPath").isEqualTo(Path.of("target.md"));
					requireThat(captured.resolvedSourcePath(), "resolvedSourcePath").isEqualTo(Path.of("actual.md"));
					requireThat(captured.selectedLinkTarget(), "selectedLinkText").isEqualTo("actual.md");
					byte[] original = new PromptSnapshotRepository(store.directory()).load(captured.snapshotId()).
						entries().get(captured.resolvedSourcePath()).bytes();
					requireThat(new String(original, StandardCharsets.UTF_8), "retainedSourceBytes").
						isEqualTo("inspect this\n");
					ContractInspectionResult retainedResult = JSON.readValue(
						store.readImmutableJson(adopted), ContractInspectionResult.class);
					SelectedSourceEditProposal loadedProposal = recovered.loadSelectedSourceProposal(binding, adopted);
					requireThat(loadedProposal.editableRoot().path(), "loadedResolvedEditRoot").isEqualTo(
						Path.of("actual.md"));
					PromptSource wrongSelected = new PromptSource(Path.of("target.md"), Path.of("actual.md"),
						"actual.md", "0".repeat(64));
					InspectedSourceEvidence wrongSource = new InspectedSourceEvidence(
						InspectedSourceEvidence.SCHEMA_VERSION, binding.campaignId(), adopted,
						wrongSelected, List.of(wrongSelected), coldLoaded.pathObservations(), coldLoaded.coverage(),
						coldLoaded.proposedEditableRoot());
					EvidenceRef wrongSourceRef = store.appendImmutableJson("prompt-loading",
						SnapshotJson.mapper().writeValueAsBytes(wrongSource));
					try
					{
						loading.load(binding, wrongSourceRef);
						throw new AssertionError("Inspected source bytes must match the adopted initial source capture");
					}
					catch (IOException expected)
					{
						requireThat(expected.getMessage(), "wrongRetainedSource").contains("differs");
					}
					ContractProposal changedSource = ContractTestFixtures.inspectedProposal(
						loadingRef.evidenceId(), wrongSource.selectedSource(), coldLoaded.proposedEditableRoot());
					try
					{
						new ContractService(store).recordInspectedProposal(
							ContractTestFixtures.bytes(changedSource), loadingRef);
						throw new AssertionError("Contract review cannot replace the retained source hash");
					}
					catch (IOException expected)
					{
						requireThat(expected.getMessage(), "changedReviewedSource").contains("do not match");
					}
					SelectedSourceEditProposal wrongRoot = new SelectedSourceEditProposal(
						SelectedSourceEditProposal.SCHEMA_VERSION, binding.campaignId(), sourceCapture,
						retainedResult.completionRef(), Path.of("target.md"), Path.of("target.md"), "", List.of(),
						loadedProposal.retainedSourceSha256(), loadedProposal.promptPathObservations(),
						loadedProposal.promptSourceCoverage(),
						new EditableRoot(Path.of("target.md"), EditableRoot.Kind.FILE));
					EvidenceRef wrongRootRef = store.appendImmutableJson("selected-source-edit-proposals",
						SnapshotJson.mapper().writeValueAsBytes(wrongRoot));
					ContractInspectionResult unadoptedResult = new ContractInspectionResult(
						retainedResult.schemaVersion(), retainedResult.campaignId(), retainedResult.ownerRef(),
						retainedResult.attemptRef(), retainedResult.sourceCaptureRef(), retainedResult.completionRef(),
						Optional.of(wrongRootRef));
					EvidenceRef unadoptedRef = store.appendImmutableJson("contract-inspection-results",
						JSON.writeValueAsBytes(unadoptedResult));
					try
					{
						recovered.loadSelectedSourceProposal(binding, unadoptedRef);
						throw new AssertionError("An unadopted source proposal must not be reviewable");
					}
					catch (IOException expected)
					{
						requireThat(expected.getMessage(), "unadoptedProposal").contains("not adopted");
					}
					try
					{
						loading.publish(binding, unadoptedRef);
						throw new AssertionError("Unadopted inspection cannot publish prompt loading evidence");
					}
					catch (IOException expected)
					{
						requireThat(expected.getMessage(), "unadoptedLoading").contains("not adopted");
					}
					NativeRunCompletionEvidence nativeCompletion = NativeRunCompletionCodec.decode(
						retainedResult.completionRef(), store::readImmutableJson);
					requireThat(nativeCompletion.result().orElseThrow().promptSources(), "nativePromptPaths").isEmpty();
					ObservationCoverage promptCoverage = nativeCompletion.result().orElseThrow().coverage().stream().
						filter(coverage -> coverage.domain() == ObservationDomain.PROMPT_SOURCE).findFirst().orElseThrow();
					requireThat(promptCoverage.status(), "nativePromptCoverage").isEqualTo(
						ObservationCoverageStatus.LIMITED);
				}
				requireThat(recovered.adoptIndexed(resumed, started.ownerRef()), "sameAdoption").
					isEqualTo(adopted);
				requireThat(recovered.adopt(resumed, started,
					adapter.nativeCompletion.join()), "liveRetryAdoption").isEqualTo(adopted);
				requireThat(store.readCallSlotClaim(binding, started.logicalCallId()).slot().state(),
					"adoptedSlot").isEqualTo(CallSlot.State.ADOPTED);
			}
			catch (SimulatedOwnerInterruption _)
			{
				inbox.finishInvocation(binding, "inspection-invocation");
				inbox.finishInvocation(binding, "resumed-invocation");
				if (ownerCloseFault == OwnerCloseFault.MISSING_ADOPTION ||
					ownerCloseFault == OwnerCloseFault.CORRUPT_ADOPTION)
				{
					EvidenceRef envelopeRef = store.readCallSlotClaim(binding, started.logicalCallId()).slot().
						adoptionRef().orElseThrow();
					Path envelopeFile = store.directory().resolve(envelopeRef.collection()).resolve(
						envelopeRef.evidenceId() + ".json");
					if (ownerCloseFault == OwnerCloseFault.MISSING_ADOPTION)
						Files.delete(envelopeFile);
					else
						Files.writeString(envelopeFile, "{}");
					try
					{
						CampaignStore.open(store.directory());
						throw new AssertionError("Cold campaign loading must reject invalid adoption before source review");
					}
					catch (IOException expected)
					{
						requireThat(expected.getMessage(), "invalidAdoptionBeforeSourceReview").contains(
							"Adopted call envelope");
					}
					requireThat(Files.notExists(store.directory().resolve("prompt-loading")),
						"noSourceEvidenceAfterMissingAdoption").isTrue();
					return;
				}
				if (ownerCloseFault == OwnerCloseFault.MISSING_INDEX ||
					ownerCloseFault == OwnerCloseFault.CORRUPT_INDEX)
				{
					rejectSourceReviewAfterIndexFault(workflow, store.directory(), binding,
						adoptedBeforeOwnerClose, ownerCloseFault);
					return;
				}
				recoverSourceReviewAfterAdoption(workflow, store.directory(), binding, started.logicalCallId(),
					adoptedBeforeOwnerClose, sourceFixture);
				return;
			}
			if (confirmCandidate)
			{
				Path expectedSourcePath = switch (sourceFixture)
				{
					case REGULAR, REGULAR_WITH_RULE, REGULAR_WITH_LINKED_RULE,
						REGULAR_WITH_DIRECTORY_LINKED_RULE -> Path.of("target.md");
					case LINKED -> Path.of("actual.md");
					case DIRECTORY_LINKED_TARGET -> Path.of("actual-rules", "target.md");
				};
				inbox.finishInvocation(binding, "inspection-invocation");
				inbox.finishInvocation(binding, "resumed-invocation");
				LineageActivation activated = new ContractService(CampaignStore.open(store.directory())).confirm(
					store.load().transitionCounter(), selectedRecorded.evidenceId(),
					ContractTestFixtures.bytes(selectedReviewed));
				ConfirmedEditableRoots roots = SnapshotJson.mapper().readValue(store.readImmutableJson(
					activated.sourceAuthorityRef()), ConfirmedEditableRoots.class);
				ConfirmedSourceBaseline baseline = new ConfirmedSourceBaselineRepository(store.directory()).load(
					roots.confirmedSourceBaselineRef());
				requireThat(roots.roots().getFirst().path(), "confirmedResolvedRoot").isEqualTo(expectedSourcePath);
				requireThat(roots.roots().size(), "confirmedEditRootCount").isEqualTo(1);
			int expectedKnownSourceCount = 1;
			if (sourceFixture.hasObservedRule())
				expectedKnownSourceCount = 2;
			List<KnownSourceState> knownSources = baseline.orderedKnownSourceStates();
			requireThat(knownSources.size(), "resolvedKnownSourceCount").isEqualTo(expectedKnownSourceCount);
			requireThat(knownSources.stream().anyMatch(state -> state.path().equals(expectedSourcePath) &&
				state.presence() == KnownSourcePresence.REGULAR), "resolvedKnownSource").isTrue();
			if (sourceFixture.hasObservedRule())
			{
				Path resolvedRulePath = sourceFixture.ruleContentPath();
				requireThat(knownSources.stream().anyMatch(state -> state.path().equals(resolvedRulePath) &&
					state.presence() == KnownSourcePresence.REGULAR), "ruleKnownSource").isTrue();
			}
				requireThat(new ConfirmedSourceAuthorityService(new CampaignLocator(workflow),
					CampaignStore.open(store.directory())).load(activated.contract().contractId()),
					"coldConfirmedAuthority").isEqualTo(activated.sourceAuthorityRef());
				InitialCandidate initialCandidate = new InitialCandidateService(new CampaignLocator(workflow),
					CampaignStore.open(store.directory())).capture();
				PromptSnapshot selected = new PromptSnapshotRepository(store.directory()).load(
					initialCandidate.candidateSnapshotId());
				requireThat(selected.entries().containsKey(expectedSourcePath), "candidateResolvedFile").isTrue();
				if (sourceFixture == SourceFixture.LINKED ||
					sourceFixture == SourceFixture.DIRECTORY_LINKED_TARGET)
					requireThat(selected.entries().containsKey(sourceFixture.selectedPath()),
						"candidateLinkAsRegularFile").
						isFalse();
			requireThat(new String(selected.entries().get(expectedSourcePath).bytes(), StandardCharsets.UTF_8),
				"candidateRetainedBytes").isEqualTo("inspect this\n");
			if (sourceFixture.hasObservedRule())
			{
				Path resolvedRulePath = sourceFixture.ruleContentPath();
				requireThat(new String(selected.entries().get(resolvedRulePath).bytes(),
					StandardCharsets.UTF_8), "candidateRuleBytes").isEqualTo("review this rule\n");
				if (sourceFixture == SourceFixture.REGULAR_WITH_LINKED_RULE)
					requireThat(selected.entries().containsKey(Path.of("rules.md")), "candidateRuleLinkAsFile").
						isFalse();
			}
				requireThat(new InitialCandidateService(new CampaignLocator(workflow),
					CampaignStore.open(store.directory())).load(), "coldInitialCandidate").isEqualTo(initialCandidate);
				if (ownerCloseFault == OwnerCloseFault.MISSING_ADOPTION)
				{
					EvidenceRef envelopeRef = store.readCallSlotClaim(binding, started.logicalCallId()).slot().
						adoptionRef().orElseThrow();
					Files.delete(store.directory().resolve(envelopeRef.collection()).resolve(
						envelopeRef.evidenceId() + ".json"));
					try
					{
						CampaignStore.open(store.directory()).load();
						throw new AssertionError("Confirmed campaign must retain its adopted source envelope");
					}
					catch (IOException expected)
					{
						requireThat(expected.getMessage(), "missingAdoptionEnvelope").contains(
							"Adopted call envelope");
					}
				}
			}
		}
	}

	/**
	 * Recovers the adopted result from a cold campaign and carries it through confirmation and candidate capture.
	 *
	 * @param workflow campaign locator root
	 * @param campaignDirectory pinned campaign directory
	 * @param binding retained campaign identity
	 * @param logicalCallId adopted inspection identity
	 * @param adoptedBeforeOwnerClose producer reference observed before the scheduler lease closed
	 * @param sourceFixture target and observed-rule paths retained by the fixture
	 * @throws IOException if retained evidence or confirmation is invalid
	 */
	private static void recoverSourceReviewAfterAdoption(Path workflow, Path campaignDirectory, CampaignBinding binding,
		String logicalCallId, EvidenceRef adoptedBeforeOwnerClose, SourceFixture sourceFixture) throws IOException
	{
		CampaignStore cold = CampaignStore.open(campaignDirectory);
		EvidenceRef envelopeRef = cold.readCallSlotClaim(binding, logicalCallId).slot().adoptionRef().orElseThrow();
		CallAdoptionRecord envelope = JSON.readValue(cold.readImmutableJson(envelopeRef),
			CallAdoptionRecord.class);
		requireThat(envelope.producerRecordRef(), "adoptedAfterOwnerClose").isEqualTo(
			adoptedBeforeOwnerClose);
		InspectedSourceEvidenceService loading = new InspectedSourceEvidenceService(new CampaignLocator(workflow),
			cold);
		EvidenceRef loadingRef = loading.publish(binding, envelope.producerRecordRef());
		InspectedSourceEvidence reviewedSource = new InspectedSourceEvidenceService(new CampaignLocator(workflow),
			CampaignStore.open(campaignDirectory)).load(binding, loadingRef);
		Path selectedContentPath = Path.of("target.md");
		if (sourceFixture == SourceFixture.LINKED)
			selectedContentPath = Path.of("actual.md");
		requireThat(reviewedSource.selectedSource().resolvedSourcePath(), "recoveredSourceReviewPath").isEqualTo(
			selectedContentPath);
		ContractProposal reviewed;
		if (sourceFixture.hasObservedRule())
		{
			requireThat(reviewedSource.sources().size(), "recoveredObservedSourceCount").isEqualTo(2);
			reviewed = ContractTestFixtures.inspectedProposalWithRule(loadingRef.evidenceId(),
				reviewedSource.selectedSource(), reviewedSource.sources().get(1),
				reviewedSource.proposedEditableRoot());
		}
		else
			reviewed = ContractTestFixtures.inspectedProposal(loadingRef.evidenceId(),
				reviewedSource.selectedSource(), reviewedSource.proposedEditableRoot());
		StoredProposal recorded = new ContractService(cold).recordInspectedProposal(
			ContractTestFixtures.bytes(reviewed), loadingRef);
		new ContractService(CampaignStore.open(campaignDirectory)).confirm(cold.load().transitionCounter(),
			recorded.evidenceId(), ContractTestFixtures.bytes(reviewed));
		InitialCandidate candidate = new InitialCandidateService(new CampaignLocator(workflow),
			CampaignStore.open(campaignDirectory)).capture();
		PromptSnapshot selected = new PromptSnapshotRepository(campaignDirectory).load(candidate.candidateSnapshotId());
		requireThat(new String(selected.entries().get(selectedContentPath).bytes(), StandardCharsets.UTF_8),
			"recoveredCandidateBytes").isEqualTo("inspect this\n");
		if (sourceFixture.hasObservedRule())
			requireThat(new String(selected.entries().get(sourceFixture.ruleContentPath()).bytes(),
				StandardCharsets.UTF_8), "recoveredRuleBytes").isEqualTo("review this rule\n");
	}

	/**
	 * Removes or corrupts the native completion index and checks that source publication refuses the adopted result.
	 *
	 * @param workflow registered campaign locator directory
	 * @param campaignDirectory retained campaign directory
	 * @param binding retained campaign identity
	 * @param adoptedInspection retained inspection result
	 * @param fault change applied to the single native completion index
	 * @throws IOException if fixture evidence cannot be read or changed
	 */
	private static void rejectSourceReviewAfterIndexFault(Path workflow, Path campaignDirectory,
		CampaignBinding binding, EvidenceRef adoptedInspection, OwnerCloseFault fault) throws IOException
	{
		List<Path> indexFiles;
		try (Stream<Path> files = Files.list(campaignDirectory.resolve("native-completion-index")))
		{
			indexFiles = files.toList();
		}
		requireThat(indexFiles.size(), "completionIndexFiles").isEqualTo(1);
		Path indexFile = indexFiles.getFirst();
		switch (fault)
		{
			case MISSING_INDEX -> Files.delete(indexFile);
			case CORRUPT_INDEX -> Files.writeString(indexFile, "{}");
			default -> throw new IllegalArgumentException("Expected a native completion index fault");
		}
		try
		{
			CampaignStore cold = CampaignStore.open(campaignDirectory);
			new InspectedSourceEvidenceService(new CampaignLocator(workflow), cold).publish(binding,
				adoptedInspection);
			throw new AssertionError("Source review must reject a missing or corrupt native completion index");
		}
		catch (IOException expected)
		{
			String failure = expected.getMessage();
			if (fault == OwnerCloseFault.MISSING_INDEX)
				requireThat(failure, "missingIndexBeforeSourceReview").contains("not adopted");
			else
				requireThat(failure, "corruptIndexBeforeSourceReview").contains("completion index");
		}
		requireThat(Files.notExists(campaignDirectory.resolve("prompt-loading")),
			"noSourceEvidenceAfterIndexFault").isTrue();
	}

	/**
	 * Selects the captured target and any additional rule observed by the inspection fixture.
	 */
	private enum SourceFixture
	{
		REGULAR,
		LINKED,
		DIRECTORY_LINKED_TARGET,
		REGULAR_WITH_RULE,
		REGULAR_WITH_LINKED_RULE,
		REGULAR_WITH_DIRECTORY_LINKED_RULE;

		/**
		 * Indicates whether the fixture emits an additional rule-path observation.
		 *
		 * @return true when the native result names a rule path
		 */
		private boolean hasObservedRule()
		{
			return switch (this)
			{
				case REGULAR_WITH_RULE, REGULAR_WITH_LINKED_RULE, REGULAR_WITH_DIRECTORY_LINKED_RULE -> true;
				default -> false;
			};
		}

		/**
		 * Returns the regular file supplying an observed rule's retained bytes.
		 *
		 * @return observed rule's project-relative regular-file path
		 * @throws IllegalStateException if the fixture has no observed rule
		 */
		private Path ruleContentPath()
		{
			return switch (this)
			{
				case REGULAR_WITH_RULE -> Path.of("rules.md");
				case REGULAR_WITH_LINKED_RULE -> Path.of("actual-rule.md");
				case REGULAR_WITH_DIRECTORY_LINKED_RULE -> Path.of("actual-rules", "rules.md");
				default -> throw new IllegalStateException("Fixture has no observed rule");
			};
		}

		/**
		 * Returns the logical path reported by the inspection fixture.
		 *
		 * @return observed project-relative rule path
		 */
		private Path observedRulePath()
		{
			if (this == REGULAR_WITH_DIRECTORY_LINKED_RULE)
				return Path.of("rules", "rules.md");
			return Path.of("rules.md");
		}

		/**
		 * Returns the path selected when the fixture registers a campaign.
		 *
		 * @return selected project-relative prompt path
		 */
		private Path selectedPath()
		{
			if (this == DIRECTORY_LINKED_TARGET)
				return Path.of("rules", "target.md");
			return Path.of("target.md");
		}
	}

	/**
	 * Selects a retained-evidence fault after the scheduler lease closes.
	 */
	private enum OwnerCloseFault
	{
		NONE,
		MISSING_ADOPTION,
		CORRUPT_ADOPTION,
		MISSING_INDEX,
		CORRUPT_INDEX
	}

	/**
	 * Unwinds the fixture's scheduler lease before a newly opened store reads source evidence in the same JVM.
	 */
	private static final class SimulatedOwnerInterruption extends RuntimeException
	{
		private static final long serialVersionUID = 1L;
	}

	/**
	 * Runs setup Git commands in the isolated fixture project.
	 *
	 * @param project fixture Git root
	 * @param arguments Git arguments
	 * @throws IOException if Git fails
	 */
	private static void git(Path project, String... arguments) throws IOException
	{
		List<String> command = new ArrayList<>(List.of("git"));
		command.addAll(List.of(arguments));
		try (Process process = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).start())
		{
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			try
			{
				if (process.waitFor() != 0)
					throw new IOException("Fixture Git command failed: " + command + ": " + output);
			}
			catch (InterruptedException exception)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Fixture Git command was interrupted: " + command, exception);
			}
		}
	}

	/**
	 * Checks that native start observes the scheduler's authorized STARTING state.
	 */
	private static final class ObservingAdapter implements Adapter
	{
		private final CampaignStore store;
		private final CampaignBinding binding;
		private int starts;
		private int interrupts;
		private int closes;
		private SchedulerLease cancellationLease;
		private CampaignStopInbox stopInbox;
		private String stopInvocationId;
		private boolean reconcileAsSettled;
		private boolean reconcileAsActive;
		private boolean reconcileAsUnknown;
		private boolean damageAttemptOnReconcile;
		private boolean failNativeStart;
		private AdapterCall nativeHandle;
		private NestedRunRequest startedRequest;
		private final CompletableFuture<NestedRunCompletion> nativeCompletion = new CompletableFuture<>();

		/**
		 * Observes native start through one pinned campaign store.
		 *
		 * @param store pinned campaign evidence
		 * @param binding exact campaign identity
		 */
		private ObservingAdapter(CampaignStore store, CampaignBinding binding)
		{
			this.store = store;
			this.binding = binding;
		}

		/**
		 * Schedules a cancellation before this adapter returns its native handle.
		 *
		 * @param lease the current scheduler owner
		 */
		private void cancelDuringStart(SchedulerLease lease)
		{
			cancellationLease = lease;
		}

		/**
		 * Schedules a durable host stop before this adapter returns its native handle.
		 *
		 * @param inbox host stop inbox
		 * @param invocationId observed command invocation
		 */
		private void stopDuringStart(CampaignStopInbox inbox, String invocationId)
		{
			stopInbox = inbox;
			stopInvocationId = invocationId;
		}

		@Override
		public AdapterCall start(NestedRunRequest request, String runId) throws IOException
		{
			startedRequest = request;
			requireThat(store.readCallSlotClaim(binding, request.logicalCallId()).slot().state(),
				"nativeStartState").isEqualTo(CallSlot.State.STARTING);
			requireThat(store.readCallPreStartRecord(binding, request.logicalCallId()).orElseThrow().runId(),
				"nativeStartRunId").isEqualTo(runId);
			++starts;
			if (cancellationLease != null)
				cancellationLease.requestCancellation(request.logicalCallId());
			if (stopInbox != null)
				stopInbox.submit(binding, stopInvocationId, "ESC");
			if (failNativeStart)
				throw new IOException("Controlled native start failure after host stop");
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
			throws IOException
		{
			if (reconcileAsSettled)
				return new AdapterCallReconciliation.Settled(nativeCompletion.join());
			if (reconcileAsActive)
			{
				if (damageAttemptOnReconcile)
				{
					EvidenceRef attempt = store.readCallPreStartRecord(binding,
						request.logicalCallId()).orElseThrow().producerAttemptRef();
					Files.writeString(store.directory().resolve("contract-inspection-attempts").resolve(
						attempt.evidenceId() + ".json"), "{}", StandardCharsets.UTF_8);
				}
				return new AdapterCallReconciliation.Active(nativeHandle);
			}
			if (reconcileAsUnknown)
				return new AdapterCallReconciliation.Unknown(new NestedRunFailure(
					NestedRunFailureKind.NATIVE_STATE_UNKNOWN, request.logicalCallId(), knownRunId,
					"retained native process cannot be observed", List.of(), Optional.empty(), Optional.empty()));
			throw new UnsupportedOperationException("Native reconciliation is outside this start check");
		}
	}

	/**
	 * Host observation fixture for the scheduler invocation.
	 *
	 * @param schemaVersion observation schema
	 * @param tokenDigest host token digest
	 * @param hostSessionId host session identity
	 * @param hostTurnId host turn identity
	 */
	private record Observation(int schemaVersion, String tokenDigest, String hostSessionId, String hostTurnId)
	{
	}
}
