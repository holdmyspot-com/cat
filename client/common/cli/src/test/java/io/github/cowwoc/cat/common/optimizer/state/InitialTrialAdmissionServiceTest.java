package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.candidate.InitialCandidateService;
import io.github.cowwoc.cat.common.optimizer.contract.ContractTestFixtures;
import io.github.cowwoc.cat.common.runner.RunnerRuntimeManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.testng.Assert;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/** Checks initial development call admission through the registered observer and scheduler. */
public final class InitialTrialAdmissionServiceTest
{
	/** Creates the admission fixture. */
	public InitialTrialAdmissionServiceTest()
	{
	}

	/**
	 * An assigned trial needs observation before claim and recovers the same request and claim after restart.
	 *
	 * @throws IOException if fixture or campaign evidence cannot be read
	 */
	@Test
	public void claimsObservedInitialTrialAfterRestart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("initial-trial-admission-")).toRealPath();
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
			EvidenceRef requestRef = new InitialTrialRequestService(locator, store).prepare(assignmentRef);
			Path artifact = Files.createDirectory(root.resolve("artifact"));
			RunnerRuntimeManifest.write(artifact);
			EvidenceRef originalClaimRef;
			EvidenceRef originalObservationRef;
			CampaignSchedulerLease scheduler = locator.openSchedulerLease(binding);
			try (SchedulerLease lease = acquire(locator, scheduler, binding, "first-trial"))
			{
				try
				{
					scheduler.claimOrResumeCall(lease, assignment.logicalTrialId(), requestRef,
						Optional.empty(), Optional.empty());
					throw new AssertionError("A trial without conditions must not be claimed");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "observationRequired").contains("observation");
				}
				InitialTrialAdmission result = new InitialTrialAdmissionService(locator, store).
					admit(lease, assignmentRef, artifact);
				requireThat(result instanceof InitialTrialAdmission.Claimed, "claimedTrial").isTrue();
				InitialTrialAdmission.Claimed claimed = (InitialTrialAdmission.Claimed) result;
				requireThat(claimed.requestRef(), "retainedRequest").isEqualTo(requestRef);
				requireThat(claimed.claim().slot().state(), "claimedSlot").isEqualTo(CallSlot.State.CLAIMED);
				originalClaimRef = claimed.claim().slotClaimRef();
				originalObservationRef = claimed.observationRef();
				MeasurementObservation observation = store.readMeasurementObservation(originalObservationRef);
				EvidenceRef secondRef = new TrialAssignmentService(locator, store).
					assignInitialDevelopment("first", 1);
				String secondCallId = store.readTrialAssignment(secondRef).logicalTrialId();
				IOException blocked = Assert.expectThrows(IOException.class, () ->
					locator.openMeasurementObserver(binding).beforeCall(lease, secondCallId,
						observation.conditions()));
				requireThat(blocked.getMessage(), "sequentialObservation").contains("prior calls");
				requireThat(store.load().scheduler().slots().size(), "singleOutstandingCall").isEqualTo(1);
				requireThat(observation.logicalCallId(), "observedCall").isEqualTo(assignment.logicalTrialId());
				requireThat(observation.conditions().values().get(MeasurementConditions.Kind.CANDIDATE) instanceof
					MeasurementConditions.Value.Known, "knownCandidate").isTrue();
				requireThat(observation.conditions().values().get(MeasurementConditions.Kind.HOST_TOOLS) instanceof
					MeasurementConditions.Value.Unknown, "unobservedToolVersions").isTrue();
			}

			CampaignStore reopened = CampaignStore.open(store.directory());
			try (SchedulerLease successor = acquire(locator, locator.openSchedulerLease(binding), binding,
				"retry-trial"))
			{
				InitialTrialAdmission retried = new InitialTrialAdmissionService(locator, reopened).
					admit(successor, assignmentRef, artifact);
				requireThat(retried instanceof InitialTrialAdmission.Claimed, "recoveredClaim").isTrue();
				InitialTrialAdmission.Claimed recovered = (InitialTrialAdmission.Claimed) retried;
				requireThat(recovered.requestRef(), "sameRequest").isEqualTo(requestRef);
				requireThat(recovered.observationRef(), "sameObservation").isEqualTo(originalObservationRef);
				requireThat(recovered.claim().slotClaimRef(), "sameClaim").isEqualTo(originalClaimRef);
				requireThat(reopened.load().scheduler().slots().size(), "oneCallSlot").isEqualTo(1);
			}
		}
	}

	/**
	 * A direct scheduler claim cannot dispatch a retained trial after its category decision index disappears.
	 *
	 * @throws IOException when the trial's category authority cannot be verified
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*category decision index.*")
	public void directClaimRejectsMissingDecisionIndex() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claim-missing-decision-index-")).toRealPath();
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
			EvidenceRef requestRef = new InitialTrialRequestService(locator, store).prepare(assignmentRef);
			CampaignSchedulerLease scheduler = locator.openSchedulerLease(binding);
			try (SchedulerLease lease = acquire(locator, scheduler, binding, "missing-decision-index"))
			{
				Map<MeasurementConditions.Kind, MeasurementConditions.Value> values =
					new EnumMap<>(MeasurementConditions.Kind.class);
				for (MeasurementConditions.Kind kind : MeasurementConditions.Kind.values())
					values.put(kind, new MeasurementConditions.Value.Unknown("Test observer cannot inspect native host"));
				locator.openMeasurementObserver(binding).beforeCall(lease, assignment.logicalTrialId(),
					new MeasurementConditions(values));
				try (Stream<Path> entries = Files.list(store.directory().resolve("initial-category-decision-index")))
				{
					Files.delete(entries.findFirst().orElseThrow());
				}

				scheduler.claimOrResumeCall(lease, assignment.logicalTrialId(), requestRef,
					Optional.empty(), Optional.empty());
			}
		}
	}

	/**
	 * A complete call observation cannot authorize a request that differs from the assigned trial.
	 *
	 * @throws IOException if fixture or campaign evidence cannot be read
	 */
	@Test
	public void rejectsForgedTrialRequest() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("unassigned-trial-request-")).toRealPath();
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
			String logicalCallId = store.readTrialAssignment(assignmentRef).logicalTrialId();
			Map<MeasurementConditions.Kind, MeasurementConditions.Value> values =
				new EnumMap<>(MeasurementConditions.Kind.class);
			for (MeasurementConditions.Kind kind : MeasurementConditions.Kind.values())
				values.put(kind, new MeasurementConditions.Value.Unknown("Test observer cannot inspect native host"));
			CampaignSchedulerLease scheduler = locator.openSchedulerLease(binding);
			try (SchedulerLease lease = acquire(locator, scheduler, binding, "first-trial"))
			{
				MeasurementObservationDecision observation = locator.openMeasurementObserver(binding).
					beforeCall(lease, logicalCallId, new MeasurementConditions(values));
				requireThat(observation.status(), "indexedObservation").
					isEqualTo(MeasurementObservationDecision.Status.ACCEPTED);
				EvidenceRef unassignedRequest = store.appendImmutableJson("run-requests",
					JsonMapper.builder().build().writeValueAsBytes(Map.of("logicalCallId", logicalCallId)));
				try
				{
					scheduler.claimOrResumeCall(lease, logicalCallId, unassignedRequest,
						Optional.empty(), Optional.empty());
					throw new AssertionError("An observed trial must use its assigned request");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "assignmentRequired").contains("assignment");
				}
				requireThat(store.load().scheduler().slots().size(), "noUnassignedSlot").isEqualTo(0);
			}
		}
	}

	/**
	 * A changed known runner pauses before trial claim, and a cold retry retrieves that decision.
	 *
	 * @throws IOException if fixture or campaign evidence cannot be read
	 */
	@Test
	public void changedRunnerPausesAndRetries() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("changed-trial-runner-")).toRealPath();
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
			EvidenceRef otherAssignmentRef = new TrialAssignmentService(locator, store).
				assignInitialDevelopment("first", 1);
			Path priorArtifact = Files.createDirectory(root.resolve("prior-artifact"));
			Path selectedArtifact = Files.createDirectory(root.resolve("selected-artifact"));
			Path conflictingArtifact = Files.createDirectory(root.resolve("conflicting-artifact"));
			Files.createDirectories(priorArtifact.resolve("hooks"));
			Files.createDirectories(selectedArtifact.resolve("hooks"));
			Files.createDirectories(conflictingArtifact.resolve("hooks"));
			Files.writeString(priorArtifact.resolve("hooks/runner.txt"), "prior");
			Files.writeString(selectedArtifact.resolve("hooks/runner.txt"), "selected");
			Files.writeString(conflictingArtifact.resolve("hooks/runner.txt"), "conflicting");
			RunnerRuntimeManifest.write(priorArtifact);
			RunnerRuntimeManifest.write(selectedArtifact);
			RunnerRuntimeManifest.write(conflictingArtifact);
			EvidenceRef priorRunner = store.appendImmutableJson("measurement-values",
				JsonMapper.builder().build().writeValueAsBytes(RunnerRuntimeManifest.fingerprint(priorArtifact)));
			Map<MeasurementConditions.Kind, MeasurementConditions.Value> values =
				new EnumMap<>(MeasurementConditions.Kind.class);
			for (MeasurementConditions.Kind kind : MeasurementConditions.Kind.values())
				values.put(kind, new MeasurementConditions.Value.Unknown("Prior call did not expose this condition"));
			values.put(MeasurementConditions.Kind.HARNESS_RUNNER,
				new MeasurementConditions.Value.Known(priorRunner));

			InitialTrialAdmission.Paused original;
			CampaignSchedulerLease scheduler = locator.openSchedulerLease(binding);
			try (SchedulerLease lease = acquire(locator, scheduler, binding, "first-trial"))
			{
				MeasurementObservationDecision baseline = locator.openMeasurementObserver(binding).
					beforeCall(lease, "prior-call", new MeasurementConditions(values));
				requireThat(baseline.status(), "priorObservation").
					isEqualTo(MeasurementObservationDecision.Status.ACCEPTED);
				InitialTrialAdmission decision = new InitialTrialAdmissionService(locator, store).
					admit(lease, assignmentRef, selectedArtifact);
				requireThat(decision instanceof InitialTrialAdmission.Paused, "pausedTrial").isTrue();
				original = (InitialTrialAdmission.Paused) decision;
				MeasurementChange change = JsonMapper.builder().build().readValue(
					store.readImmutableJson(original.changeRef()), MeasurementChange.class);
				requireThat(change.comparison().fields().get(MeasurementConditions.Kind.HARNESS_RUNNER),
					"changedRunner").isEqualTo(MeasurementConditions.Status.CHANGED);
				requireThat(store.load().scheduler().slots().size(), "noTrialSlot").isEqualTo(0);
				try
				{
					new InitialTrialRequestService(locator, store).prepare(otherAssignmentRef);
					throw new AssertionError("A measurement pause cannot admit another trial");
				}
				catch (IOException _)
				{
					requireThat(store.load().scheduler().slots().size(), "otherTrialUnclaimed").isEqualTo(0);
				}
			}

			CampaignStore reopened = CampaignStore.open(store.directory());
			try (SchedulerLease successor = acquire(locator, locator.openSchedulerLease(binding), binding,
				"retry-trial"))
			{
				try
				{
					new InitialTrialAdmissionService(locator, reopened).admit(successor, assignmentRef,
						conflictingArtifact);
					throw new AssertionError("A changed retry must not replace the retained pause");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "changedRetry").contains("another measurement change");
				}
				InitialTrialAdmission retried = new InitialTrialAdmissionService(locator, reopened).
					admit(successor, assignmentRef, selectedArtifact);
				requireThat(retried, "samePause").isEqualTo(original);
				requireThat(reopened.load().scheduler().slots().size(), "stillNoTrialSlot").isEqualTo(0);
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
}
