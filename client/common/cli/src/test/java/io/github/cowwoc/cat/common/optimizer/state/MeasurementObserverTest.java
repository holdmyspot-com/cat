package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.candidate.InitialCandidateService;
import io.github.cowwoc.cat.common.optimizer.contract.ContractTestFixtures;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionCodec;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailure;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailureKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/** Checks confirmed-call observations against the actual scheduler admission boundary. */
public final class MeasurementObserverTest
{
	/** Creates the test fixture. */
	public MeasurementObserverTest()
	{
	}

	/**
	 * An unconfirmed inspection needs its exact indexed condition observation before it can claim a call.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void inspectionRequiresConditionObservation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("inspection-observation-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			CampaignStore store = fixture.store();
			CampaignBinding binding = store.binding();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			PreparedInspectionCall prepared = new InspectionOwnerService(locator, store).prepare(binding);
			CampaignSchedulerLease scheduler = locator.openSchedulerLease(binding);
			try (SchedulerLease lease = acquire(locator, scheduler, binding, "inspection-observation"))
			{
				try
				{
					scheduler.claimOrResumeCall(lease, prepared.owner().logicalCallId(),
						prepared.owner().runRequestRef(), Optional.of(prepared.ownerRef()),
						Optional.of(prepared.owner().preparationCycleRef()));
					throw new AssertionError("Inspection must require its condition observation");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "missingInspectionObservation").contains(
						"measurement observation");
				}
				InspectionRunRequest request = prepared.request();
				InspectionRunRequest changedRequest = new InspectionRunRequest(request.schemaVersion(),
					request.campaignId(), request.logicalCallId(), request.context(), List.of("Different instruction"),
					request.modelProfile());
				PreparedInspectionCall changed = new PreparedInspectionCall(prepared.ownerRef(), prepared.owner(),
					prepared.cycle(), changedRequest);
				try
				{
					locator.openMeasurementObserver(binding).beforeInspectionCall(lease, changed);
					throw new AssertionError("Observation must reject a request that differs from its indexed owner");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "changedPreparedRequest").contains("another prepared call");
				}

				EvidenceRef observed = locator.openMeasurementObserver(binding).beforeInspectionCall(lease,
					prepared);
				requireThat(observed.collection(), "observationCollection").isEqualTo(
					"preparation-measurement-observations");
				requireThat(scheduler.claimOrResumeCall(lease, prepared.owner().logicalCallId(),
					prepared.owner().runRequestRef(), Optional.of(prepared.ownerRef()),
					Optional.of(prepared.owner().preparationCycleRef())).slot().state(),
					"admittedInspection").isEqualTo(CallSlot.State.CLAIMED);
				requireThat(store.load().measurementState(), "unconfirmedEpoch").isNull();
			}
		}
	}

	/**
	 * An index with the right call key cannot borrow another inspection owner's observation.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void rejectsForeignInspectionObservation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("inspection-foreign-observation-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			CampaignStore store = fixture.store();
			CampaignBinding binding = store.binding();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			PreparedInspectionCall prepared = new InspectionOwnerService(locator, store).prepare(binding);
			EvidenceRef foreignOwner = new EvidenceRef("contract-preparation-owners", "f".repeat(64));
			PreparationMeasurementObservation foreign = PreparationMeasurementObservation.from(
				binding.campaignId(), prepared.owner().preparationCycleRef(), foreignOwner,
				prepared.owner().logicalCallId(), MeasurementObservation.Stage.BEFORE_CALL, conditions("a"));
			EvidenceRef foreignRef = store.appendImmutableJson("preparation-measurement-observations",
				JsonMapper.builder().build().writeValueAsBytes(foreign));
			store.appendPreconfirmationIndex(binding,
				PreconfirmationIndexKind.CONTRACT_INSPECTION_BEFORE_OBSERVATION,
				PreparationMeasurementObservation.indexKey(foreign.cycleRef(), foreign.logicalCallId()),
				foreignRef);
			CampaignSchedulerLease scheduler = locator.openSchedulerLease(binding);
			try (SchedulerLease lease = acquire(locator, scheduler, binding, "foreign-inspection"))
			{
				try
				{
					scheduler.claimOrResumeCall(lease, prepared.owner().logicalCallId(),
						prepared.owner().runRequestRef(), Optional.of(prepared.ownerRef()),
						Optional.of(prepared.owner().preparationCycleRef()));
					throw new AssertionError("Foreign condition authority must deny inspection admission");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "foreignInspectionObservation").contains(
						"foreign authority");
				}
				requireThat(store.load().scheduler().slots().isEmpty(), "foreignObservationSlots").isTrue();
			}
		}
	}

	/**
	 * A confirmed call needs its current indexed before-call observation.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void acceptedObservationAdmitsExactCall() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("measurement-admission-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignBinding binding = store.binding();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			CampaignSchedulerLease scheduler = locator.openSchedulerLease(binding);
			try (SchedulerLease lease = acquire(locator, scheduler, binding, "first"))
			{
				EvidenceRef request = request(store, "call-1");
				try
				{
					scheduler.claimOrResumeCall(lease, "call-1", request, Optional.empty(), Optional.empty());
					throw new AssertionError("Call without observation must be refused");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "missingObservation").contains("measurement observation");
				}
				MeasurementConditions observedConditions = conditions("a");
				Campaign beforeIndex = store.load();
				MeasurementObservation pending = MeasurementObservation.from(beforeIndex.campaignId(),
					beforeIndex.activeLineageId(), beforeIndex.measurementState().epochRef(), "call-1",
					MeasurementObservation.Stage.BEFORE_CALL, observedConditions);
				EvidenceRef pendingRef = store.appendImmutableJson("dynamic-baseline-roots",
					JsonMapper.builder().build().writeValueAsBytes(pending));
				store.commitBeforeCallObservation(binding, pendingRef);
				try
				{
					scheduler.claimOrResumeCall(lease, "call-1", request, Optional.empty(), Optional.empty());
					throw new AssertionError("A baseline without its call index must not admit a call");
				}
				catch (IOException _)
				{
					requireThat(store.load().scheduler().slots().size(), "unindexedSlots").isEqualTo(0);
				}
				MeasurementObservationDecision decision = locator.openMeasurementObserver(binding).beforeCall(
					lease, "call-1", observedConditions);
				requireThat(decision.status(), "decision").isEqualTo(
					MeasurementObservationDecision.Status.ACCEPTED);
				requireThat(decision.observationRef(), "recoveredObservation").isEqualTo(pendingRef);
				requireThat(store.load().measurementState().baselineRootRef().orElseThrow(), "baseline").
					isEqualTo(decision.observationRef());
				requireThat(scheduler.claimOrResumeCall(lease, "call-1", request, Optional.empty(),
					Optional.empty()).slot().state(), "admittedCall").isEqualTo(CallSlot.State.CLAIMED);
				String contractId = store.load().lineages().getFirst().confirmedContractId();
				Files.delete(store.directory().resolve("confirmed-source-authority-index").resolve(
					contractId + ".json"));
				try
				{
					lease.prepareStart("call-1", request);
					throw new AssertionError("Losing source authority before start must deny native work");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "missingStartAuthority").contains(
						"source authority index is missing");
				}
				requireThat(store.load().scheduler().slots().getFirst().state(), "unstartedCall").isEqualTo(
					CallSlot.State.CLAIMED);
			}
		}
	}

	/**
	 * A committed claim without its indexed source authority cannot acquire a model-call lease.
	 *
	 * @throws IOException if fixture persistence or authority recovery fails
	 */
	@Test
	public void rejectsClaimMissingSourceAuthority() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("measurement-missing-authority-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			CampaignStore store = fixture.store();
			CampaignBinding binding = store.binding();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			byte[] reviewed = ContractTestFixtures.bytes(fixture.proposal());
			String contractId = fixture.service().prepareConfirmation(fixture.proposal().proposalId(), reviewed).
				contractId();
			locator.confirm(new TransitionRequest(binding, 0, TransitionKind.CONFIRMED_CONTRACT,
				new EvidenceRef("contracts", contractId)));
			CampaignStopInbox inbox = locator.openStopInbox(binding);
			EvidenceRef registration = inbox.beginInvocation(binding, "missing-authority");
			inbox.publishHostObservation(binding, "missing-authority", registration,
				JsonMapper.builder().build().writeValueAsBytes(new HostObservation(1, "8".repeat(64),
					"session-missing-authority", "turn-missing-authority")));
			CampaignSchedulerLease scheduler = locator.openSchedulerLease(binding);
			try
			{
				scheduler.acquire(binding, "missing-authority").close();
				throw new AssertionError("A claim without source authority must not acquire a model-call lease");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "missingAuthority").contains("source authority index is missing");
			}
			requireThat(store.load().scheduler().fence(), "unchangedFence").isEqualTo(0L);
			fixture.service().confirm(0, fixture.proposal().proposalId(), reviewed);
			try (SchedulerLease lease = scheduler.acquire(binding, "missing-authority"))
			{
				requireThat(lease.binding(), "recoveredBinding").isEqualTo(binding);
				Files.delete(store.directory().resolve("confirmed-source-authority-index").resolve(
					contractId + ".json"));
				try
				{
					scheduler.claimOrResumeCall(lease, "call-1", request(store, "call-1"),
						Optional.empty(), Optional.empty());
					throw new AssertionError("Removing source authority after acquisition must deny a call");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "removedAuthority").contains(
						"source authority index is missing");
				}
				requireThat(store.load().scheduler().slots().size(), "unadmittedSlots").isEqualTo(0);
			}
		}
	}

	/**
	 * A changed known model pauses before the next call and an equal retry adopts the pause.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void knownChangePausesBeforeNextCall() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("measurement-change-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignBinding binding = store.binding();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			CampaignSchedulerLease scheduler = locator.openSchedulerLease(binding);
			try (SchedulerLease lease = acquire(locator, scheduler, binding, "second"))
			{
				MeasurementObserver observer = locator.openMeasurementObserver(binding);
				observer.beforeCall(lease, "call-1", conditions("a"));
				MeasurementObservationDecision changed = observer.beforeCall(lease, "call-2",
					conditions("b"));
				requireThat(changed.status(), "changedDecision").isEqualTo(
					MeasurementObservationDecision.Status.PAUSED);
				requireThat(changed.changeRef().isPresent(), "changeEvidence").isTrue();
				requireThat(observer.beforeCall(lease, "call-2", conditions("b")), "equalRetry").
					isEqualTo(changed);
				requireThat(CampaignStore.open(store.directory()).load().transitionReceipts().getLast().kind(),
					"changeReceipt").isEqualTo(TransitionKind.MEASUREMENT_CHANGED);
				try
				{
					scheduler.claimOrResumeCall(lease, "call-2", request(store, "call-2"),
						Optional.empty(), Optional.empty());
					throw new AssertionError("Changed conditions must pause admission");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "pausedAdmission").contains("measurement change");
				}
			}
		}
	}

	/**
	 * Restoring the original known conditions retains the epoch and admits the paused call once.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void restoresOriginalMeasurementEpoch() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("measurement-restore-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignBinding binding = store.binding();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			CampaignSchedulerLease scheduler = locator.openSchedulerLease(binding);
			try (SchedulerLease lease = acquire(locator, scheduler, binding, "restore"))
			{
				MeasurementObserver observer = locator.openMeasurementObserver(binding);
				observer.beforeCall(lease, "call-1", conditions("a"));
				observer.beforeCall(lease, "call-2", conditions("b"));
				EvidenceRef epoch = store.load().measurementState().epochRef();
				MeasurementObservationDecision restored = observer.restoreBeforeCall(lease, "call-2",
					conditions("a"));
				requireThat(restored.status(), "restoration").isEqualTo(
					MeasurementObservationDecision.Status.ACCEPTED);
				requireThat(observer.restoreBeforeCall(lease, "call-2", conditions("a")),
					"restorationRetry").isEqualTo(restored);
				Campaign recovered = CampaignStore.open(store.directory()).load();
				requireThat(recovered.measurementState().epochRef(), "sameEpoch").isEqualTo(epoch);
				requireThat(recovered.measurementState().pause().isEmpty(), "unpaused").isTrue();
				requireThat(recovered.transitionReceipts().getLast().kind(), "restorationReceipt").
					isEqualTo(TransitionKind.MEASUREMENT_RESTORED);
				requireThat(scheduler.claimOrResumeCall(lease, "call-2", request(store, "call-2"),
					Optional.empty(), Optional.empty()).slot().state(), "admittedCall").
					isEqualTo(CallSlot.State.CLAIMED);
				EvidenceRef restorationRef = recovered.transitionReceipts().getLast().producerRecordRef();
				Files.delete(store.directory().resolve("measurement-restorations").resolve(
					restorationRef.evidenceId() + ".json"));
				try
				{
					CampaignStore.open(store.directory()).load();
					throw new AssertionError("Cold recovery must refuse missing restoration evidence");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "missingRestoration").contains("restoration");
				}
			}
		}
	}

	/**
	 * A changed condition can start a new epoch without reusing the earlier trial assignment.
	 *
	 * @throws IOException if fixture or retained campaign evidence cannot be written
	 */
	@Test
	public void startsNewMeasurementAfterBeforeCallPause() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("measurement-new-epoch-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignBinding binding = store.binding();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			new InitialCandidateService(locator, store).capture();
			new InitialCategoryDecisionService(locator, store).publish();
			EvidenceRef previousAssignment = new TrialAssignmentService(locator, store).
				assignInitialDevelopment("first");
			try (SchedulerLease lease = acquire(locator, locator.openSchedulerLease(binding), binding,
				"new-epoch"))
			{
				MeasurementObserver observer = locator.openMeasurementObserver(binding);
				observer.beforeCall(lease, "call-1", conditions("a"));
				observer.beforeCall(lease, "call-2", conditions("b"));
				Campaign paused = store.load();
				EvidenceRef previousEpoch = paused.measurementState().epochRef();
				String lineageId = paused.activeLineageId();

				MeasurementObservationDecision started = observer.startNewMeasurement(lease, "call-2",
					conditions("b"));

				Campaign active = CampaignStore.open(store.directory()).load();
				requireThat(started.status(), "newIdentityDecision").isEqualTo(
					MeasurementObservationDecision.Status.ACCEPTED);
				requireThat(active.measurementState().epochRef(), "distinctEpoch").isNotEqualTo(previousEpoch);
				requireThat(active.measurementState().baselineRootRef().orElseThrow(), "newBaseline").
					isEqualTo(started.observationRef());
				requireThat(active.measurementState().pause().isEmpty(), "clearedNewEpochPause").isTrue();
				requireThat(active.activeLineageId(), "sameLineage").isEqualTo(lineageId);
				requireThat(active.trials().getFirst().selectionEvidence(), "previousTrialRetained").
					isEqualTo(previousAssignment);
				requireThat(observer.startNewMeasurement(lease, "call-2", conditions("b")),
					"equalNewIdentityRetry").isEqualTo(started);
				try
				{
					observer.startNewMeasurement(lease, "call-2", conditions("c"));
					throw new AssertionError("A retry cannot change the new identity's conditions");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "changedNewIdentityRetry").contains(
						"retained conditions");
				}
				try
				{
					new TrialAssignmentService(locator, store).assignInitialDevelopment("first");
					throw new AssertionError("An earlier measurement choice cannot authorize a new draw");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "newEpochNeedsDecision").contains("category decision");
				}
				new InitialCategoryDecisionService(locator, store).publish();
				EvidenceRef nextAssignment = new TrialAssignmentService(locator, store).
					assignInitialDevelopment("first");
				requireThat(nextAssignment, "newEpochAssignment").isNotEqualTo(previousAssignment);
				requireThat(CampaignStore.open(store.directory()).load().trials().size(), "separateTrials").
					isEqualTo(2);
				requireThat(locator.openSchedulerLease(binding).claimOrResumeCall(lease, "call-2",
					request(store, "call-2"), Optional.empty(), Optional.empty()).slot().state(),
					"admittedNewEpochCall").isEqualTo(CallSlot.State.CLAIMED);
			}
		}
	}

	/**
	 * Cold recovery still requires the activation observation after the active baseline advances.
	 *
	 * @throws IOException if fixture or retained campaign evidence cannot be written
	 */
	@Test
	public void newMeasurementRetainsActivationProof() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("measurement-new-proof-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignBinding binding = store.binding();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			try (SchedulerLease lease = acquire(locator, locator.openSchedulerLease(binding), binding,
				"new-proof"))
			{
				MeasurementObserver observer = locator.openMeasurementObserver(binding);
				observer.beforeCall(lease, "call-1", conditions("a"));
				observer.beforeCall(lease, "call-2", conditions("b"));
				MeasurementObservationDecision started = observer.startNewMeasurement(lease, "call-2",
					conditions("b"));
				observer.beforeCall(lease, "call-3", conditions("b"));
				Files.delete(store.directory().resolve("dynamic-baseline-roots").resolve(
					started.observationRef().evidenceId() + ".json"));

				try
				{
					CampaignStore.open(store.directory()).load();
					throw new AssertionError("Cold recovery must require the new measurement proof");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "missingNewMeasurementProof").contains(
						"measurement");
				}
			}
		}
	}

	/**
	 * A changed adopted call starts another epoch only for a distinct pending call.
	 *
	 * @throws IOException if fixture or retained call evidence cannot be written
	 */
	@Test
	public void startsNewMeasurementAfterAdoptedCall() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("measurement-new-after-call-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignBinding binding = store.binding();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			try (SchedulerLease lease = acquire(locator, locator.openSchedulerLease(binding), binding,
				"new-after-call"))
			{
				MeasurementObserver observer = locator.openMeasurementObserver(binding);
				adoptConfirmedCall(store, locator, lease, observer, "call-1");
				observer.afterCall(lease, "call-1", conditions("b"));
				EvidenceRef previousEpoch = store.load().measurementState().epochRef();
				try
				{
					observer.startNewMeasurement(lease, "call-1", conditions("b"));
					throw new AssertionError("An adopted call cannot be the pending new-epoch call");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "adoptedNewEpochCall").contains(
						"pending logical call");
				}
				MeasurementObservationDecision started = observer.startNewMeasurement(lease, "call-2",
					conditions("b"));
				Campaign resumed = CampaignStore.open(store.directory()).load();
				requireThat(resumed.measurementState().epochRef(), "afterCallNewEpoch").
					isNotEqualTo(previousEpoch);
				requireThat(resumed.measurementState().baselineRootRef().orElseThrow(), "afterCallNewBaseline").
					isEqualTo(started.observationRef());
				requireThat(locator.openSchedulerLease(binding).claimOrResumeCall(lease, "call-2",
					request(store, "call-2"), Optional.empty(), Optional.empty()).slot().state(),
					"admittedAfterCallNewEpoch").isEqualTo(CallSlot.State.CLAIMED);
			}
		}
	}

	/**
	 * Cold recovery rejects an active epoch that rolls back behind its committed new-identity receipt.
	 *
	 * @throws IOException if fixture or retained campaign evidence cannot be written
	 */
	@Test
	public void newMeasurementRejectsActiveEpochRollback() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("measurement-new-rollback-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignBinding binding = store.binding();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			try (SchedulerLease lease = acquire(locator, locator.openSchedulerLease(binding), binding,
				"new-rollback"))
			{
				MeasurementObserver observer = locator.openMeasurementObserver(binding);
				EvidenceRef previousBaseline = observer.beforeCall(lease, "call-1", conditions("a")).
					observationRef();
				observer.beforeCall(lease, "call-2", conditions("b"));
				EvidenceRef previousEpoch = store.load().measurementState().epochRef();
				observer.startNewMeasurement(lease, "call-2", conditions("b"));
				ProspectiveCampaignState.withMeasurementState(store,
					new MeasurementState(previousEpoch, Optional.of(previousBaseline), Optional.empty()));
				try
				{
					CampaignStore.open(store.directory()).load();
					throw new AssertionError("Cold recovery must reject a rolled-back active epoch");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "rolledBackEpoch").contains(
						"measurement epoch differs");
				}
			}
		}
	}

	/**
	 * An equal retry repairs the new epoch's call index after state publication and restart.
	 *
	 * @throws IOException if fixture or retained campaign evidence cannot be written
	 * @throws NoSuchAlgorithmException if the platform does not provide SHA-256
	 */
	@Test
	public void newMeasurementRepairsMissingCallIndex() throws IOException, NoSuchAlgorithmException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("measurement-new-index-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignBinding binding = store.binding();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			try (SchedulerLease lease = acquire(locator, locator.openSchedulerLease(binding), binding,
				"new-index"))
			{
				MeasurementObserver observer = locator.openMeasurementObserver(binding);
				observer.beforeCall(lease, "call-1", conditions("a"));
				observer.beforeCall(lease, "call-2", conditions("b"));
				MeasurementObservationDecision started = observer.startNewMeasurement(lease, "call-2",
					conditions("b"));
				Campaign committed = store.load();
				String key = MeasurementObservation.callIndexKey(committed.measurementState().epochRef(),
					"call-2");
				String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
					key.getBytes(StandardCharsets.UTF_8)));
				Path indexFile = store.directory().resolve("measurement-before-call-index").resolve(
					digest + ".json");
				Files.delete(indexFile);
				requireThat(Files.notExists(indexFile), "missingNewCallIndex").isTrue();

				CampaignStore reopened = CampaignStore.open(store.directory());
				requireThat(locator.openMeasurementObserver(binding).startNewMeasurement(lease, "call-2",
					conditions("b")), "replayedNewIdentity").isEqualTo(started);
				requireThat(Files.isRegularFile(indexFile), "repairedNewCallIndex").isTrue();
				requireThat(reopened.load().transitionCounter(), "sameNewIdentityCounter").isEqualTo(
					committed.transitionCounter());
				requireThat(locator.openSchedulerLease(binding).claimOrResumeCall(lease, "call-2",
					request(store, "call-2"), Optional.empty(), Optional.empty()).slot().state(),
					"admittedRepairedCall").isEqualTo(CallSlot.State.CLAIMED);
			}
		}
	}

	/**
	 * A failed cancellation intent rolls back before an accepted pause cancellation releases the project key.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void cancelsPausedMeasurementAndReleasesKey() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("measurement-cancel-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignBinding binding = store.binding();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			CampaignSchedulerLease scheduler = locator.openSchedulerLease(binding);
			try (SchedulerLease lease = acquire(locator, scheduler, binding, "cancel-pause"))
			{
				MeasurementObserver observer = locator.openMeasurementObserver(binding);
				observer.beforeCall(lease, "call-1", conditions("a"));
				MeasurementObservationDecision changed = observer.beforeCall(lease, "call-2",
					conditions("b"));
				EvidenceRef epochRef = store.load().measurementState().epochRef();
				long pausedCounter = store.load().transitionCounter();
				CampaignLookupKey key = new CampaignLookupKey(root.resolve("project"), Path.of("prompt.md"));
				EvidenceRef invalid = store.appendImmutableJson("measurement-pause-cancellations",
					"{}".getBytes(StandardCharsets.UTF_8));
				try
				{
					locator.cancel(new TransitionRequest(binding, pausedCounter, TransitionKind.CANCELLATION,
						invalid));
					throw new AssertionError("Malformed cancellation evidence must not close the campaign");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "invalidCancellation").contains("malformed");
				}
				requireThat(locator.find(key).getFirst().status(), "recoveredFailedIntent").
					isEqualTo(CampaignStatus.DEVELOPING);
				try
				{
					locator.cancelPaused(binding, pausedCounter - 1);
					throw new AssertionError("A stale cancellation choice must not close the campaign");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "staleCancellation").contains("conflicts");
				}
				TransitionResult cancelled = locator.cancelPaused(binding, pausedCounter);
				requireThat(cancelled.phase(), "cancelledPhase").isEqualTo(CampaignStatus.CANCELLED);
				requireThat(locator.cancelPaused(binding, pausedCounter), "cancelRetry").isEqualTo(cancelled);
				Campaign retained = CampaignStore.open(store.directory()).load();
				requireThat(retained.closedPhase(), "resumablePhase").isEqualTo(CampaignStatus.DEVELOPING);
				requireThat(retained.measurementState().epochRef(), "retainedEpoch").isEqualTo(epochRef);
				requireThat(retained.measurementState().pause().orElseThrow().changeRef(), "retainedChange").
					isEqualTo(changed.changeRef().orElseThrow());
				requireThat(retained.activeLineageId(), "retainedLineage").isNotBlank();
				requireThat(retained.transitionReceipts().getLast().kind(), "cancellationReceipt").
					isEqualTo(TransitionKind.CANCELLATION);
				try
				{
					scheduler.claimOrResumeCall(lease, "call-3", request(store, "call-3"),
						Optional.empty(), Optional.empty());
					throw new AssertionError("Cancelled campaign must not schedule another call");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "cancelledAdmission").contains("phase cannot start");
				}
				MeasurementPauseCancellation conflicting = new MeasurementPauseCancellation(
					MeasurementPauseCancellation.SCHEMA_VERSION, binding, pausedCounter,
					CampaignStatus.DEVELOPING, retained.activeLineageId(),
					new EvidenceRef("measurement-epochs", "0".repeat(64)),
					changed.changeRef().orElseThrow());
				EvidenceRef conflictingRef = store.appendImmutableJson("measurement-pause-cancellations",
					JsonMapper.builder().build().writeValueAsBytes(conflicting));
				try
				{
					locator.cancel(new TransitionRequest(binding, pausedCounter, TransitionKind.CANCELLATION,
						conflictingRef));
					throw new AssertionError("Changed evidence cannot replay one cancellation identity");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "conflictingCancellation").contains("Conflicting");
				}
				requireThat(store.load().transitionCounter(), "oneCancellationCounter").
					isEqualTo(cancelled.resultingCounter());
				requireThat(new CampaignLocator(root.resolve("workflow")).createOrFind(key,
					new CampaignCreationRequest("next", ProspectiveCampaignState.profile())).status(),
					"releasedKey").isEqualTo(CampaignRegistration.Status.CREATED);
				EvidenceRef cancellationRef = retained.transitionReceipts().getLast().producerRecordRef();
				Files.delete(store.directory().resolve(cancellationRef.collection()).resolve(
					cancellationRef.evidenceId() + ".json"));
				try
				{
					CampaignStore.open(store.directory()).load();
					throw new AssertionError("Missing cancellation evidence must fail a cold campaign read");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "missingCancellationProof").contains(
						"Immutable evidence is missing");
				}
			}
		}
	}

	/**
	 * Lookup reopens a measurement pause whose store transition has advanced beyond the locator summary.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void findsPausedCampaignAfterStoreTransition() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("measurement-lookup-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignBinding binding = store.binding();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			try (SchedulerLease lease = acquire(locator, locator.openSchedulerLease(binding), binding,
				"lookup-pause"))
			{
				MeasurementObserver observer = locator.openMeasurementObserver(binding);
				observer.beforeCall(lease, "call-1", conditions("a"));
				observer.beforeCall(lease, "call-2", conditions("b"));
				CampaignLookupKey key = new CampaignLookupKey(root.resolve("project"), Path.of("prompt.md"));
				requireThat(new CampaignLocator(root.resolve("workflow")).find(key).getFirst().transitionCounter(),
					"observedRevision").isEqualTo(store.load().transitionCounter());
			}
		}
	}

	/**
	 * Restart completes key release after the cancellation state commits but before the locator index changes.
	 *
	 * @throws IOException if fixture persistence fails
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	@Test
	public void recoversPauseCancellationAfterCommit() throws IOException, NoSuchAlgorithmException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("measurement-cancel-restart-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignBinding binding = store.binding();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			try (SchedulerLease lease = acquire(locator, locator.openSchedulerLease(binding), binding,
				"restart-cancel"))
			{
				MeasurementObserver observer = locator.openMeasurementObserver(binding);
				observer.beforeCall(lease, "call-1", conditions("a"));
				observer.beforeCall(lease, "call-2", conditions("b"));
			}
			Campaign paused = store.load();
			MeasurementState measurement = paused.measurementState();
			CampaignLookupKey key = new CampaignLookupKey(root.resolve("project"), Path.of("prompt.md"));
			MeasurementPauseCancellation producer = new MeasurementPauseCancellation(
				MeasurementPauseCancellation.SCHEMA_VERSION, binding, paused.transitionCounter(), paused.status(),
				paused.activeLineageId(), measurement.epochRef(),
				measurement.pause().orElseThrow().changeRef());
			EvidenceRef producerRef = store.appendImmutableJson("measurement-pause-cancellations",
				JsonMapper.builder().build().writeValueAsBytes(producer));
			TransitionRequest request = new TransitionRequest(binding, paused.transitionCounter(),
				TransitionKind.CANCELLATION, producerRef);
			KeyTransitionIntent intent = new KeyTransitionIntent(KeyTransitionIntent.Operation.CANCEL, key,
				binding, paused.transitionCounter(), producerRef, KeyTransitionIntent.requestDigest(request),
				Optional.of(binding.campaignId()), paused.status(), paused.status(),
				Optional.of(locator.readLatestConfirmedClaim(binding)));
			Path intentDirectory = Files.createDirectories(root.resolve(
				"workflow/campaign-key-transition-intents"));
			byte[] keyBytes = JsonMapper.builder().build().writeValueAsBytes(new ClaimKeyDocument(
				key.canonicalProjectRoot().toString(), key.normalizedTargetRelativePath().toString()));
			String keyDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(keyBytes));
			Path intentPath = intentDirectory.resolve(keyDigest + ".json");
			Files.write(intentPath, intent.encode());
			TransitionResult committed = store.commitMeasurementPauseCancellation(request);
			requireThat(committed.phase(), "committedPhase").isEqualTo(CampaignStatus.CANCELLED);
			requireThat(Files.exists(intentPath), "unfinishedKeyRelease").isTrue();
			CampaignLocator restarted = new CampaignLocator(root.resolve("workflow"));
			requireThat(restarted.find(key).getFirst().status(), "recoveredCancellation").
				isEqualTo(CampaignStatus.CANCELLED);
			requireThat(Files.notExists(intentPath), "completedKeyRelease").isTrue();
			requireThat(restarted.cancel(request), "replayedCancellation").isEqualTo(committed);
			requireThat(restarted.createOrFind(key,
				new CampaignCreationRequest("next", ProspectiveCampaignState.profile())).status(),
				"recoveredReleasedKey").isEqualTo(CampaignRegistration.Status.CREATED);
		}
	}

	/**
	 * A known changed model condition cannot be presented as restoration of the original epoch.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void rejectsConditionsStillChanged() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("measurement-not-restored-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignBinding binding = store.binding();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			try (SchedulerLease lease = acquire(locator, locator.openSchedulerLease(binding), binding,
				"still-changed"))
			{
				MeasurementObserver observer = locator.openMeasurementObserver(binding);
				observer.beforeCall(lease, "call-1", conditions("a"));
				observer.beforeCall(lease, "call-2", conditions("b"));
				long counter = store.load().transitionCounter();
				try
				{
					observer.restoreBeforeCall(lease, "call-2", conditions("b"));
					throw new AssertionError("Changed conditions must not restore the original epoch");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "notRestored").contains("not restored");
				}
				requireThat(store.load().measurementState().pause().isPresent(), "stillPaused").isTrue();
				requireThat(store.load().transitionCounter(), "unchangedCounter").isEqualTo(counter);
			}
		}
	}

	/**
	 * An adopted call's changed conditions pause scheduling before the next call.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void adoptedCallChangePausesBeforeNextCall() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("measurement-after-call-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignBinding binding = store.binding();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			try (SchedulerLease lease = acquire(locator, locator.openSchedulerLease(binding), binding, "after"))
			{
				MeasurementObserver observer = locator.openMeasurementObserver(binding);
				adoptConfirmedCall(store, locator, lease, observer, "call-1");
				try
				{
					observer.beforeCall(lease, "call-2", conditions("a"));
					throw new AssertionError("Another call must await the first call's after-call observation");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "missingAfterCall").contains("after-call observation");
				}
				MeasurementObservationDecision changed = observer.afterCall(lease, "call-1", conditions("b"));
				requireThat(changed.status(), "afterCallDecision").isEqualTo(
					MeasurementObservationDecision.Status.PAUSED);
				requireThat(observer.afterCall(lease, "call-1", conditions("b")), "replayedDecision").
					isEqualTo(changed);
				requireThat(CampaignStore.open(store.directory()).load().measurementState().pause().isPresent(),
					"retainedPause").isTrue();
				try
				{
					locator.openSchedulerLease(binding).claimOrResumeCall(lease, "call-2",
						request(store, "call-2"), Optional.empty(), Optional.empty());
					throw new AssertionError("A changed after-call observation must block another call");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "pausedAdmission").contains("measurement change");
				}
			}
		}
	}

	/**
	 * Restoring conditions after an adopted call retains its epoch and admits a different next call.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void restoresAfterAdoptedCallChange() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("measurement-after-restore-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignBinding binding = store.binding();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			try (SchedulerLease lease = acquire(locator, locator.openSchedulerLease(binding), binding,
				"after-restore"))
			{
				MeasurementObserver observer = locator.openMeasurementObserver(binding);
				adoptConfirmedCall(store, locator, lease, observer, "call-1");
				MeasurementObservationDecision changed = observer.afterCall(lease, "call-1",
					conditions("b"));
				EvidenceRef epoch = store.load().measurementState().epochRef();
				try
				{
					observer.restoreBeforeCall(lease, "call-1", conditions("a"));
					throw new AssertionError("An adopted call cannot be its own pending restoration call");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "sameCallRestoration").contains("pending logical call");
				}
				MeasurementObservationDecision restored = observer.restoreBeforeCall(lease, "call-2",
					conditions("a"));
				requireThat(restored.status(), "afterCallRestoration").isEqualTo(
					MeasurementObservationDecision.Status.ACCEPTED);
				Campaign retained = CampaignStore.open(store.directory()).load();
				requireThat(retained.measurementState().epochRef(), "originalEpoch").isEqualTo(epoch);
				requireThat(retained.measurementState().pause().isEmpty(), "clearedPause").isTrue();
				requireThat(changed.changeRef().isPresent(), "retainedChange").isTrue();
				requireThat(locator.openSchedulerLease(binding).claimOrResumeCall(lease, "call-2",
					request(store, "call-2"), Optional.empty(), Optional.empty()).slot().state(),
					"admittedNextCall").isEqualTo(CallSlot.State.CLAIMED);
			}
		}
	}

	/**
	 * An unchanged after-call observation survives index loss and an equal retry repairs its admission proof.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void unchangedAfterCallRepairsMissingIndex() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("measurement-after-index-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignBinding binding = store.binding();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			try (SchedulerLease lease = acquire(locator, locator.openSchedulerLease(binding), binding, "after-index"))
			{
				MeasurementObserver observer = locator.openMeasurementObserver(binding);
				adoptConfirmedCall(store, locator, lease, observer, "call-1");
				MeasurementObservationDecision accepted = observer.afterCall(lease, "call-1",
					conditions("a"));
				requireThat(accepted.status(), "unchangedDecision").isEqualTo(
					MeasurementObservationDecision.Status.ACCEPTED);
				requireThat(store.load().measurementState().baselineRootRef().orElseThrow(), "afterBaseline").
					isEqualTo(accepted.observationRef());
				Path indexDirectory = store.directory().resolve("measurement-after-call-index");
				Path indexFile;
				try (Stream<Path> files = Files.list(indexDirectory))
				{
					indexFile = files.toList().getFirst();
				}
				Files.delete(indexFile);
				try
				{
					observer.beforeCall(lease, "call-2", conditions("a"));
					throw new AssertionError("Missing after-call index must refuse the next observation");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "missingAfterIndex").contains(
						"after-call observation");
				}
				requireThat(observer.afterCall(lease, "call-1", conditions("a")), "equalAfterRetry").
					isEqualTo(accepted);
				requireThat(Files.isRegularFile(indexFile), "repairedIndex").isTrue();
				requireThat(observer.beforeCall(lease, "call-2", conditions("a")).status(),
					"nextAdmission").isEqualTo(MeasurementObservationDecision.Status.ACCEPTED);
			}
		}
	}

	/**
	 * The after-call index accepts only immutable condition observations.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*dynamic-baseline-roots.*")
	public void afterCallIndexRejectsForeignEvidence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("measurement-after-index-kind-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			LineageSummary lineage = store.load().lineages().getFirst();
			EvidenceRef foreign = store.appendImmutableJson("run-requests", "{}".getBytes(
				StandardCharsets.UTF_8));
			store.appendCreateOnceIndex("measurement-after-call-index", "foreign",
				new LineageEvidenceRef(new LineageKey(lineage.lineageId(), lineage.confirmedContractId()),
					foreign));
		}
	}

	/**
	 * Simulates a finished native call with typed completion evidence, then adopts its retained slot.
	 *
	 * @param store test-owned confirmed campaign store
	 * @param locator registered campaign locator
	 * @param lease live scheduler owner
	 * @param observer registered measurement observer
	 * @param logicalCallId call represented by the local completion evidence
	 * @throws IOException if the retained lifecycle cannot complete
	 */
	private static void adoptConfirmedCall(CampaignStore store, CampaignLocator locator, SchedulerLease lease,
		MeasurementObserver observer, String logicalCallId) throws IOException
	{
		CampaignBinding binding = lease.binding();
		observer.beforeCall(lease, logicalCallId, conditions("a"));
		EvidenceRef request = request(store, logicalCallId);
		CallSlot claim = locator.openSchedulerLease(binding).claimOrResumeCall(lease, logicalCallId, request,
			Optional.empty(), Optional.empty()).slot();
		ProspectiveCampaignState.prepareStart(store, lease, logicalCallId);
		String runId = ProspectiveCampaignState.nativeRunId(logicalCallId);
		lease.activate(logicalCallId, runId);
		NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.TRANSPORT_FAILED,
			logicalCallId, Optional.of(runId), "transport failed", List.of(), Optional.empty(),
			Optional.empty());
		NativeRunCompletionEvidence completion = new NativeRunCompletionEvidence(binding.campaignId(),
			logicalCallId, request, claim.slotClaimRef(), Optional.of(runId),
			NativeRunCompletionEvidence.Outcome.FAILED, Optional.empty(), Optional.of(failure),
			List.of(), Optional.empty(), Optional.empty());
		EvidenceRef completionRef = store.appendImmutableJson("native-run-completions",
			NativeRunCompletionCodec.encode(completion, store.evidenceReader(binding)));
		store.publishNativeCompletionIndex(binding, logicalCallId, completionRef);
		lease.settle(logicalCallId, completionRef);
		EvidenceRef producer = store.appendImmutableJson("generation-outcomes", "{}".getBytes(
			StandardCharsets.UTF_8));
		lease.adopt(logicalCallId, producer);
	}

	/**
	 * Acquires a real scheduler owner with an indexed host observation.
	 *
	 * @param locator registered campaign locator
	 * @param scheduler locator-owned lease service
	 * @param binding exact campaign
	 * @param invocationId new host invocation
	 * @return live scheduler lease
	 * @throws IOException if registration or ownership fails
	 */
	private static SchedulerLease acquire(CampaignLocator locator, CampaignSchedulerLease scheduler,
		CampaignBinding binding, String invocationId) throws IOException
	{
		CampaignStopInbox inbox = locator.openStopInbox(binding);
		EvidenceRef registration = inbox.beginInvocation(binding, invocationId);
		byte[] observation = JsonMapper.builder().build().writeValueAsBytes(new HostObservation(1, "8".repeat(64),
			"session-" + invocationId, "turn-" + invocationId));
		inbox.publishHostObservation(binding, invocationId, registration, observation);
		return scheduler.acquire(binding, invocationId);
	}

	/**
	 * Persists one C0R request for a logical call.
	 *
	 * @param store campaign evidence store
	 * @param logicalCallId exact call
	 * @return immutable request evidence
	 * @throws IOException if persistence fails
	 */
	private static EvidenceRef request(CampaignStore store, String logicalCallId) throws IOException
	{
		return store.appendImmutableJson("run-requests", ("{\"logicalCallId\":\"" + logicalCallId + "\"}").
			getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Provides complete conditions with one known model identity.
	 *
	 * @param modelHash known model condition identity
	 * @return complete observation
	 */
	private static MeasurementConditions conditions(String modelHash)
	{
		Map<MeasurementConditions.Kind, MeasurementConditions.Value> values =
			new EnumMap<>(MeasurementConditions.Kind.class);
		for (MeasurementConditions.Kind kind : MeasurementConditions.Kind.values())
			values.put(kind, new MeasurementConditions.Value.Unknown("not observed"));
		values.put(MeasurementConditions.Kind.MODEL_PROFILE, new MeasurementConditions.Value.Known(
			new EvidenceRef("measurement-values", modelHash.repeat(64))));
		return new MeasurementConditions(values);
	}

	private record HostObservation(int schemaVersion, String tokenDigest, String hostSessionId,
		String hostTurnId)
	{
	}

	private record ClaimKeyDocument(String projectRoot, String targetPath)
	{
	}
}
