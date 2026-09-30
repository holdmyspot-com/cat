package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.contract.ContractTestFixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the closed transition boundary for unavailable producers.
 */
public final class CampaignTransitionsTest
{
	/**
	 * Creates the test fixture.
	 */
	public CampaignTransitionsTest()
	{
	}

	/**
	 * Every unintegrated kind fails closed without changing durable campaign state.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void allUnintegratedKindsFailClosed() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-transitions-")).toRealPath();
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			Campaign before = store.load();
			CampaignTransitions transitions = new CampaignTransitions(store);
			EvidenceRef producer = store.appendImmutableJson("producer-records", new byte[]{1});
			for (TransitionKind kind : TransitionKind.values())
			{
				if (kind == TransitionKind.INTERRUPTION || kind == TransitionKind.CANCELLATION ||
					kind == TransitionKind.SEMANTIC_OUTCOME)
					continue;
				try
				{
					transitions.apply(new TransitionRequest(store.binding(), 0, kind, producer));
					throw new AssertionError("Unintegrated kind must fail: " + kind);
				}
				catch (UnavailableTransitionException expected)
				{
					requireThat(expected.getMessage(), "unavailableProducer").contains(kind.name());
				}
				requireThat(store.load(), "campaignAfterRejectedKind").isEqualTo(before);
			}
		}
	}

	/**
	 * Dormant producer kinds retain nonempty result state without publishing a transition.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void allUnintegratedKindsPreserveResultFields() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-dormant-results-")).toRealPath();
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			Campaign current = ProspectiveCampaignState.addLineage(store, "second-contract");
			EvidenceRef trigger = store.appendImmutableJson("formal-failure-round-triggers", "{}".getBytes(
				StandardCharsets.UTF_8));
			EvidenceRef epoch = store.appendImmutableJson("measurement-epochs", "{}".getBytes(
				StandardCharsets.UTF_8));
			EvidenceRef root = store.appendImmutableJson("terminal-results", "{}".getBytes(
				StandardCharsets.UTF_8));
			MeasurementState measurement = new MeasurementState(epoch, Optional.empty(), Optional.empty());
			TerminalResultRef terminal = new TerminalResultRef(TerminalResultRef.Kind.PLATEAU,
				current.lineages().getFirst().lineageId(), Optional.empty(), root, Optional.empty(),
				current.transitionCounter());
			Campaign withResults = new Campaign(Campaign.SCHEMA_VERSION, current.campaignId(), current.status(),
				current.activeLineageId(), current.currentCandidateId(), current.appliedCandidateId(),
				current.activeDevelopmentRoot(), current.transitionCounter(), current.closedPhase(), current.lineages(),
				current.trials(), current.history(), current.optimizationProfile(), current.transitionReceipts(),
				current.scheduler(), current.preparationScope(), trigger, measurement, List.of(terminal), null);
			ProspectiveCampaignState.write(store, withResults);
			byte[] before = Files.readAllBytes(directory.resolve("campaign.json"));
			CampaignTransitions transitions = new CampaignTransitions(store);
			EvidenceRef producer = store.appendImmutableJson("producer-records", new byte[]{1});
			for (TransitionKind kind : TransitionKind.values())
			{
				if (kind == TransitionKind.INTERRUPTION || kind == TransitionKind.CANCELLATION ||
					kind == TransitionKind.SEMANTIC_OUTCOME)
					continue;
				try
				{
					transitions.apply(new TransitionRequest(store.binding(), current.transitionCounter(), kind,
						producer));
					throw new AssertionError("Dormant kind must fail: " + kind);
				}
				catch (UnavailableTransitionException expected)
				{
					requireThat(expected.getMessage(), "unavailable" + kind).contains(kind.name());
				}
				requireThat(Files.readAllBytes(directory.resolve("campaign.json")), "unchanged" + kind).
					isEqualTo(before);
				requireThat(new CampaignStore(directory).load(), "retained" + kind).isEqualTo(withResults);
			}
		}
	}

	/**
	 * The producer-availability check recognizes the integrated semantic outcome transition.
	 *
	 * @throws IOException if the transition is incorrectly treated as unavailable
	 */
	@Test
	public void acceptsIntegratedSemanticOutcomeKind() throws IOException
	{
		CampaignTransitions.requireIntegratedProducer(TransitionKind.SEMANTIC_OUTCOME);
	}

	/**
	 * Verifies a stale counter is rejected before a dormant producer can change campaign bytes.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rejectsStaleCounterBeforeMutation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-stale-counter-")).toRealPath();
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			Path campaignFile = directory.resolve("campaign.json");
			byte[] before = Files.readAllBytes(campaignFile);
			EvidenceRef producer = new EvidenceRef("producer-records", "a".repeat(64));
			try
			{
				new CampaignTransitions(store).apply(new TransitionRequest(store.binding(), 1,
					TransitionKind.TRIAL_ASSIGNMENT, producer));
				throw new AssertionError("A stale counter must fail before mutation");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "staleFailure").contains("Stale");
				requireThat(Files.readAllBytes(campaignFile), "unchangedCampaign").isEqualTo(before);
			}
		}
	}

	/**
	 * Dormant resumption rejects even a malformed request before any key intent or state mutation.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void dormantResumptionPreflight() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("dormant-key-transitions-")).toRealPath();
			CampaignStore store = new CampaignStore(root.resolve("campaign-campaign"));
			ProspectiveCampaignState.initialize(store, "campaign");
			CampaignLocator locator = ProspectiveCampaignState.register(store);
			Campaign before = store.load();
			EvidenceRef malformedProducer = new EvidenceRef("unavailable", "a".repeat(64));
			for (TransitionKind kind : List.of(TransitionKind.RESUMPTION))
			{
				TransitionRequest valid = new TransitionRequest(store.binding(), 0, kind, malformedProducer);
				TransitionRequest foreign = new TransitionRequest(
					new CampaignBinding("foreign", store.directory()), 900, kind, malformedProducer);
				for (TransitionRequest request : List.of(valid, foreign))
				{
					try
					{
						locator.resumeCancelled(request);
						throw new AssertionError("Dormant key transition must fail before mutation");
					}
					catch (UnavailableTransitionException expected)
					{
						requireThat(expected.getMessage(), "keyProducer").contains(kind.name());
					}
					requireThat(store.load(), "unchangedCampaign").isEqualTo(before);
					requireThat(Files.notExists(root.resolve("campaign-key-transition-intents")),
						"noKeyIntent").isTrue();
				}
			}
		}
	}

	/**
	 * A confirmed claim comes from one pinned state snapshot.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void readsCurrentConfirmedClaim() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-claim-")).toRealPath();
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			CurrentClaim claim = new CampaignTransitions(store).readCurrentClaim(store.binding());
			requireThat(claim.campaignId(), "campaignId").isEqualTo("campaign");
			requireThat(claim.activeContractId(), "contractId").isEqualTo("d".repeat(64));
			requireThat(claim.transitionCounter(), "counter").isEqualTo(1L);
		}
	}

	/**
	 * A pristine unconfirmed campaign exposes generation-one INITIAL preparation only for itself.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void readsInitialPreparationAuthority() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("initial-preparation-")).toRealPath();
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			Campaign before = store.load();
			PreparationAuthority authority = new CampaignTransitions(store).
				readPreparationAuthority(store.binding());
			requireThat(authority.campaignId(), "campaignId").isEqualTo("campaign");
			requireThat(authority.kind(), "kind").isEqualTo(PreparationAuthority.Kind.INITIAL);
			requireThat(authority.priorLineage().isEmpty(), "noPriorLineage").isTrue();
			requireThat(authority.quiescenceProofRef().isEmpty(), "noRevisionProof").isTrue();
			requireThat(authority.preparationGeneration(), "generation").isEqualTo(1L);
			requireThat(authority.sourceChangeRef().isEmpty(), "noSourceChange").isTrue();
			requireThat(authority.transitionCounter(), "counter").isEqualTo(0L);
			requireThat(store.load(), "readOnlyCampaign").isEqualTo(before);
		}
	}

	/**
	 * A revision read requires the active lineage and its retained proven CONTRACT_REVISION barrier.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void readsProvenRevisionPreparation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("revision-preparation-")).toRealPath();
			CampaignStore store = new CampaignStore(root.resolve("campaign-campaign"));
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				JsonMapper.builder().build().writeValueAsBytes(new Observation(1, "e".repeat(64),
					"session-e", "turn-e")));
			EvidenceRef proof;
			try (SchedulerLease lease = ProspectiveCampaignState.lease(store).acquire(binding, "invocation"))
			{
				lease.beginQuiescence(QuiescencePurpose.CONTRACT_REVISION, 1);
				proof = lease.proveQuiescent();
			}
			PreparationScope staleScope = new PreparationScope(
				Optional.of(new EvidenceRef("scheduler-quiescence-proofs", "a".repeat(64))), 2,
				Optional.of(new EvidenceRef("contract-preparation-source-changes", "b".repeat(64))));
			ProspectiveCampaignState.write(store, store.load().withPreparationScope(staleScope));
			Campaign before = store.load();
			PreparationAuthority authority = new CampaignTransitions(store).readPreparationAuthority(binding);
			requireThat(authority.kind(), "kind").isEqualTo(PreparationAuthority.Kind.REVISION);
			requireThat(authority.priorLineage(), "priorLineage").isEqualTo(Optional.of(
				new LineageKey("lineage-1-" + "d".repeat(64), "d".repeat(64))));
			requireThat(authority.quiescenceProofRef(), "proof").isEqualTo(Optional.of(proof));
			requireThat(authority.preparationGeneration(), "generation").isEqualTo(1L);
			requireThat(authority.sourceChangeRef().isEmpty(), "noSourceChange").isTrue();
			requireThat(authority.transitionCounter(), "counter").isEqualTo(1L);
			requireThat(store.load(), "readOnlyCampaign").isEqualTo(before);
			Files.write(store.directory().resolve(proof.collection()).resolve(proof.evidenceId() + ".json"),
				"{}".getBytes(StandardCharsets.UTF_8));
			try
			{
				new CampaignTransitions(store).readPreparationAuthority(binding);
				throw new AssertionError("Corrupt revision proof must not authorize preparation");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "corruptProof").contains("evidence");
			}
		}
	}

	/**
	 * An active confirmed lineage alone, or an unproven barrier, cannot authorize revision.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void refusesUnprovenRevisionPreparation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("unproven-revision-")).toRealPath();
			CampaignStore store = new CampaignStore(root.resolve("campaign-campaign"));
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			CampaignBinding binding = store.binding();
			CampaignTransitions transitions = new CampaignTransitions(store);
			assertNoPreparation(transitions, binding, "barrier");
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				JsonMapper.builder().build().writeValueAsBytes(new Observation(1, "f".repeat(64),
					"session-f", "turn-f")));
			try (SchedulerLease lease = ProspectiveCampaignState.lease(store).acquire(binding, "invocation"))
			{
				lease.beginQuiescence(QuiescencePurpose.CONTRACT_REVISION, 1);
				assertNoPreparation(transitions, binding, "proven");
			}
		}
	}

	/**
	 * Requires a failed preparation read without changing any campaign state.
	 *
	 * @param transitions pinned transition service
	 * @param binding exact campaign binding
	 * @param expectedMessage expected failure fragment
	 * @throws IOException if a fixture read fails
	 */
	private static void assertNoPreparation(CampaignTransitions transitions, CampaignBinding binding,
		String expectedMessage) throws IOException
	{
		try
		{
			transitions.readPreparationAuthority(binding);
			throw new AssertionError("Missing revision proof must not authorize preparation");
		}
		catch (IOException expected)
		{
			requireThat(expected.getMessage(), "missingPreparationProof").contains(expectedMessage);
		}
	}

	/**
	 * Opaque source-change bytes cannot create a later-generation preparation authority.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void refusesUnverifiedPreparationGeneration() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("unverified-preparation-")).toRealPath();
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			EvidenceRef opaque = store.appendImmutableJson("contract-preparation-source-changes",
				"{}".getBytes(StandardCharsets.UTF_8));
			Campaign seeded = store.load().withPreparationScope(new PreparationScope(Optional.empty(), 2,
				Optional.of(opaque)));
			ProspectiveCampaignState.write(store, seeded);
			try
			{
				new CampaignTransitions(store).readPreparationAuthority(store.binding());
				throw new AssertionError("Opaque source change must not advance preparation generation");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "sourceChangeAuthority").contains("source-change");
			}
			requireThat(store.load(), "unchangedCampaign").isEqualTo(seeded);
		}
	}

	/**
	 * An interrupted unconfirmed campaign cannot read INITIAL authority before a real resumption.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void interruptedCampaignHasNoPreparation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("interrupted-preparation-")).toRealPath();
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			Campaign current = store.load();
			Campaign interrupted = new Campaign(Campaign.SCHEMA_VERSION, current.campaignId(),
				CampaignStatus.INTERRUPTED, null, null, null, null, current.transitionCounter(),
				CampaignStatus.AWAITING_CONTRACT_CONFIRMATION, current.lineages(), current.trials(),
				current.history(), current.optimizationProfile(), current.transitionReceipts(), current.scheduler(),
				current.preparationScope(), current.activeFormalFailureTriggerRef(), current.measurementState(),
				current.terminalResults(), current.acknowledgedInterruptionStopRef());
			ProspectiveCampaignState.write(store, interrupted);
			assertNoPreparation(new CampaignTransitions(store), store.binding(), "no current");
			requireThat(store.load(), "unchangedInterruption").isEqualTo(interrupted);
		}
	}

	/**
	 * A schema-3 campaign round trip retains the exact embedded committed transition receipt.
	 *
	 * @throws IOException if fixture serialization fails
	 */
	@Test
	public void preservesEmbeddedReceiptInSchemaThree() throws IOException
	{
		CampaignBinding binding = new CampaignBinding("campaign", Path.of("/tmp/receipt-campaign"));
		EvidenceRef record = new EvidenceRef("contracts", "a".repeat(64));
		TransitionResult result = new TransitionResult(1, CampaignStatus.DEVELOPING, "lineage", null,
			"receipt-1");
		TransitionReceipt receipt = new TransitionReceipt(binding, TransitionKind.CONFIRMED_CONTRACT, 0, record,
			1, result);
		Campaign campaign = new Campaign(3, "campaign", CampaignStatus.DEVELOPING, "lineage", null, null,
			null, 1, null, List.of(new LineageSummary("lineage", "contract", 1, List.of())), List.of(), List.of(),
			ProspectiveCampaignState.profile(), List.of(receipt), SchedulerState.initial(),
			PreparationScope.initial(), null, null, List.of(), null);
		JsonMapper json = JsonMapper.builder().build();
		requireThat(json.readValue(json.writeValueAsBytes(campaign), Campaign.class).transitionReceipts(),
			"restartedReceipts").isEqualTo(List.of(receipt));
	}

	/**
	 * A byte-identical retry adopts the embedded result without advancing state; a conflicting retry fails.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void replaysOnlyExactEmbeddedTransition() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("campaign-receipt-replay-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			CampaignStore store = fixture.store();
			Path directory = store.directory();
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignBinding binding = store.binding();
			Campaign current = store.load();
			TransitionReceipt receipt = current.transitionReceipts().getFirst();
			TransitionResult result = receipt.result();
			EvidenceRef producer = receipt.producerRecordRef();
			CampaignTransitions transitions = new CampaignTransitions(store);
			requireThat(transitions.readCommittedConfirmation(binding, 0), "committedConfirmation").
				isEqualTo(Optional.of(receipt));
			requireThat(transitions.readCommittedConfirmation(binding, 1), "absentConfirmation").
				isEqualTo(Optional.empty());
			requireThat(transitions.apply(new TransitionRequest(binding, 0, TransitionKind.CONFIRMED_CONTRACT,
				producer)), "replayedResult").isEqualTo(result);
			requireThat(directory.resolve("transition-journal.json"), "repairedJournal").isRegularFile();
			requireThat(store.load().transitionCounter(), "unchangedCounter").isEqualTo(1L);
			try
			{
				transitions.apply(new TransitionRequest(binding, 0, TransitionKind.REVISED_CONTRACT, producer));
				throw new AssertionError("Different transition must fail");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "conflict").contains("Conflicting");
			}
			TransitionResult secondResult = new TransitionResult(2, CampaignStatus.DEVELOPING,
				current.activeLineageId(), null,
				"receipt-2");
			TransitionReceipt secondReceipt = new TransitionReceipt(binding, TransitionKind.DEVELOPMENT_ROOT, 1,
				producer, 2, secondResult);
			Campaign advanced = new Campaign(3, "campaign", CampaignStatus.DEVELOPING,
				current.activeLineageId(), null, null, null, 2, null, current.lineages(), List.of(),
				List.of(), current.optimizationProfile(), List.of(receipt, secondReceipt), current.scheduler(),
				current.preparationScope(), current.activeFormalFailureTriggerRef(), current.measurementState(),
				current.terminalResults(), current.acknowledgedInterruptionStopRef());
			Files.write(directory.resolve("campaign.json"), JsonMapper.builder().build().writeValueAsBytes(advanced));
			requireThat(transitions.readCommittedConfirmation(binding, 0), "retainedConfirmation").
				isEqualTo(Optional.of(receipt));
			requireThat(transitions.readCommittedConfirmation(binding, 1), "nonconfirmationReceipt").
				isEqualTo(Optional.empty());
			requireThat(transitions.apply(new TransitionRequest(binding, 1, TransitionKind.DEVELOPMENT_ROOT,
				producer)), "repairedSuffix").isEqualTo(secondResult);
			TransitionReceipt wrong = new TransitionReceipt(binding, TransitionKind.REVISED_CONTRACT, 0,
				producer, 1, result);
			Files.write(directory.resolve("transition-journal.json"), JsonMapper.builder().build().
				writeValueAsBytes(new JournalFixture(1, List.of(wrong))));
			try
			{
				transitions.apply(new TransitionRequest(binding, 0, TransitionKind.CONFIRMED_CONTRACT, producer));
				throw new AssertionError("A conflicting journal mirror must fail closed");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "journalConflict").contains("journal conflicts");
			}
		}
	}

	private record JournalFixture(int schemaVersion, List<TransitionReceipt> receipts)
	{
	}

	private record Observation(int schemaVersion, String tokenDigest, String hostSessionId, String hostTurnId)
	{
	}
}
