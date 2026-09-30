package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Tests that a stop can be acknowledged only by its embedded committed interruption. */
public final class CampaignStopAcknowledgmentTest
{
	private final JsonMapper json = JsonMapper.builder().build();

	/** Creates the test fixture. */
	public CampaignStopAcknowledgmentTest()
	{
	}

	/**
	 * A missing journal mirror cannot hide the embedded commit, and equal replay writes nothing.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void adoptsEmbeddedCommitWithoutMirror() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.commit("HOST_OR_TOKEN_STOP", true);
			Files.deleteIfExists(fixture.directory.resolve("transition-journal.json"));
			CampaignStopInbox restarted = ProspectiveCampaignState.inbox(new CampaignStore(fixture.directory));
			StopRequest acknowledged = restarted.acknowledge(fixture.lease, fixture.stop.stopRef(),
				"receipt-1", Optional.empty());
			requireThat(acknowledged.status(), "acknowledged").isEqualTo(StopRequest.Status.ACKNOWLEDGED);
			requireThat(acknowledged.interruptionReceiptId().orElseThrow(), "receiptId").isEqualTo("receipt-1");
			requireThat(new CampaignStore(fixture.directory).load().acknowledgedInterruptionStopRef(),
				"durableAcknowledgedStop").isEqualTo(fixture.stop.stopRef());
			byte[] committed = Files.readAllBytes(fixture.directory.resolve("campaign.json"));
			requireThat(restarted.acknowledge(fixture.lease, fixture.stop.stopRef(), "receipt-1",
				Optional.empty()), "equalReplay").isEqualTo(acknowledged);
			requireThat(Files.readAllBytes(fixture.directory.resolve("campaign.json")), "noReplayMutation").
				isEqualTo(committed);
			requireThat(fixture.store.load().transitionCounter(), "noExtraTransition").isEqualTo(1L);
		}
	}

	/**
	 * A retained host stop commits one interruption, survives a missing mirror, and acknowledges once.
	 *
	 * @throws IOException if the production transition or fixture I/O fails
	 */
	@Test
	public void commitsHostStopTransitionAndAcknowledges() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			EvidenceRef producer = fixture.store.appendImmutableJson("campaign-interruption-records",
				json.writeValueAsBytes(new InterruptionEvidence(1, fixture.binding, 0,
					"HOST_OR_TOKEN_STOP", Optional.of(fixture.stop.stopRef()), Optional.empty(),
					fixture.lease.fence(), Optional.empty())));
			TransitionRequest request = new TransitionRequest(fixture.binding, 0,
				TransitionKind.INTERRUPTION, producer);
			TransitionResult committed = new CampaignTransitions(fixture.store).apply(request);
			requireThat(committed.phase(), "interruptedPhase").isEqualTo(CampaignStatus.INTERRUPTED);
			requireThat(committed.resultingCounter(), "counter").isEqualTo(1L);
			Files.deleteIfExists(fixture.directory.resolve("transition-journal.json"));
			requireThat(new CampaignTransitions(new CampaignStore(fixture.directory)).apply(request),
				"sameInterruption").isEqualTo(committed);
			requireThat(new CampaignInterruptionService(ProspectiveCampaignState.register(fixture.store),
				fixture.store).commitHostStop(fixture.lease, fixture.stop, Optional.empty()),
				"recoveredProducerBeforeAck").isEqualTo(committed);
			StopRequest acknowledged = fixture.inbox.acknowledge(fixture.lease,
				fixture.stop.stopRef(), committed.receiptId(), Optional.empty());
			requireThat(acknowledged.status(), "acknowledged").isEqualTo(StopRequest.Status.ACKNOWLEDGED);
			requireThat(new CampaignStore(fixture.directory).load().acknowledgedInterruptionStopRef(),
				"retainedStop").isEqualTo(fixture.stop.stopRef());
			requireThat(fixture.directory.resolve("transition-journal.json"), "recoveredMirror").
				isRegularFile();
		}
	}

	/**
	 * The production stop owner publishes the exact producer, commits it, and acknowledges the same stop on retry.
	 *
	 * @throws IOException if campaign or fixture I/O fails
	 */
	@Test
	public void publishesAndCommitsAdoptedHostStop() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			CampaignInterruptionService interruptions = new CampaignInterruptionService(
				ProspectiveCampaignState.register(fixture.store), fixture.store);
			TransitionResult committed = interruptions.commitHostStop(fixture.lease,
				fixture.stop, Optional.empty());
			requireThat(committed.phase(), "interrupted").isEqualTo(CampaignStatus.INTERRUPTED);
			requireThat(fixture.store.load().acknowledgedInterruptionStopRef(), "acknowledgedStop").
				isEqualTo(fixture.stop.stopRef());
			byte[] first = Files.readAllBytes(fixture.directory.resolve("campaign.json"));
			requireThat(interruptions.commitHostStop(fixture.lease, fixture.stop, Optional.empty()),
				"equalRetry").isEqualTo(committed);
			requireThat(Files.readAllBytes(fixture.directory.resolve("campaign.json")), "sameCampaignBytes").
				isEqualTo(first);
		}
	}

	/**
	 * An unrelated interruption cause cannot use the host-stop transition before its producer exists.
	 *
	 * @throws IOException if fixture evidence cannot be retained
	 */
	@Test
	public void rejectsUnsupportedInterruptionCause() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			EvidenceRef sourceReview = new EvidenceRef("source-reviews", "a".repeat(64));
			EvidenceRef producer = fixture.store.appendImmutableJson("campaign-interruption-records",
				json.writeValueAsBytes(new InterruptionEvidence(1, fixture.binding, 0,
					"SOURCE_REVIEW", Optional.empty(), Optional.of(sourceReview), fixture.lease.fence(),
					Optional.empty())));
			byte[] before = Files.readAllBytes(fixture.directory.resolve("campaign.json"));
			try
			{
				new CampaignTransitions(fixture.store).apply(new TransitionRequest(fixture.binding, 0,
					TransitionKind.INTERRUPTION, producer));
				throw new AssertionError("An unimplemented interruption cause must not commit");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "excludedCause").contains("conflicts");
			}
			requireThat(Files.readAllBytes(fixture.directory.resolve("campaign.json")),
				"unchangedCampaign").isEqualTo(before);
		}
	}

	/**
	 * An equal transition retry rechecks the stop evidence before returning its earlier result.
	 *
	 * @throws IOException when the retained stop evidence has been damaged
	 */
	@Test
	public void rejectsDamagedStopOnInterruptionReplay() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			EvidenceRef producer = fixture.store.appendImmutableJson("campaign-interruption-records",
				json.writeValueAsBytes(new InterruptionEvidence(1, fixture.binding, 0,
					"HOST_OR_TOKEN_STOP", Optional.of(fixture.stop.stopRef()), Optional.empty(),
					fixture.lease.fence(), Optional.empty())));
			TransitionRequest request = new TransitionRequest(fixture.binding, 0,
				TransitionKind.INTERRUPTION, producer);
			new CampaignTransitions(fixture.store).apply(request);
			Path stopFile = fixture.directory.resolve("stop-requests").resolve(
				fixture.stop.stopRef().evidenceId() + ".json");
			Files.writeString(stopFile, "{}", StandardCharsets.UTF_8);
			try
			{
				new CampaignTransitions(new CampaignStore(fixture.directory)).apply(request);
				throw new AssertionError("A damaged stop must not authenticate an interruption retry");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "damagedStop").contains("Immutable evidence");
			}
		}
	}

	/**
	 * A fresh campaign reader rejects a damaged stop before resumption can consume interrupted state.
	 *
	 * @throws IOException when retained interruption evidence is damaged
	 */
	@Test
	public void coldLoadRejectsDamagedStop() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			EvidenceRef producer = fixture.store.appendImmutableJson("campaign-interruption-records",
				json.writeValueAsBytes(new InterruptionEvidence(1, fixture.binding, 0,
					"HOST_OR_TOKEN_STOP", Optional.of(fixture.stop.stopRef()), Optional.empty(),
					fixture.lease.fence(), Optional.empty())));
			new CampaignTransitions(fixture.store).apply(new TransitionRequest(fixture.binding, 0,
				TransitionKind.INTERRUPTION, producer));
			Path stopFile = fixture.directory.resolve("stop-requests").resolve(
				fixture.stop.stopRef().evidenceId() + ".json");
			Files.writeString(stopFile, "{}", StandardCharsets.UTF_8);
			try
			{
				new CampaignStore(fixture.directory).load();
				throw new AssertionError("Cold load must reject damaged interruption evidence");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "damagedStop").contains("Immutable evidence");
			}
		}
	}

	/**
	 * A successor owner retains the committed stop fence and acknowledges a transition left unacknowledged.
	 *
	 * @throws IOException if the durable interruption or resumed lease cannot be read
	 */
	@Test
	public void successorAcknowledgesCommittedStop() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			EvidenceRef producer = fixture.store.appendImmutableJson("campaign-interruption-records",
				json.writeValueAsBytes(new InterruptionEvidence(1, fixture.binding, 0,
					"HOST_OR_TOKEN_STOP", Optional.of(fixture.stop.stopRef()), Optional.empty(),
					fixture.lease.fence(), Optional.empty())));
			TransitionResult committed = new CampaignTransitions(fixture.store).apply(new TransitionRequest(
				fixture.binding, 0, TransitionKind.INTERRUPTION, producer));
			long originalFence = fixture.lease.fence();
			fixture.lease.close();
			EvidenceRef successorRegistration = fixture.inbox.beginInvocation(fixture.binding,
				"successor");
			fixture.inbox.publishHostObservation(fixture.binding, "successor", successorRegistration,
				json.writeValueAsBytes(new Observation(1, "b".repeat(64), "successor-session",
					"successor-turn")));
			try (SchedulerLease successor = ProspectiveCampaignState.lease(fixture.store).acquire(
				fixture.binding, "successor"))
			{
				StopRequest retained = fixture.inbox.adoptPending(successor).orElseThrow();
				requireThat(retained.adoptingFence(), "originalAdoptingFence").
					isEqualTo(Optional.of(originalFence));
				TransitionResult resumed = new CampaignInterruptionService(ProspectiveCampaignState.register(
					fixture.store), fixture.store).commitHostStop(successor, retained, Optional.empty());
				requireThat(resumed, "originalInterruption").isEqualTo(committed);
				requireThat(fixture.store.load().acknowledgedInterruptionStopRef(), "acknowledgedStop").
					isEqualTo(fixture.stop.stopRef());
			}
		}
	}

	/**
	 * A cold read rejects an active acknowledged interruption whose pointer was lost.
	 *
	 * @throws IOException when the campaign state is inconsistent
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Acknowledged scheduler stop has no active pointer.*")
	public void rejectsMissingActiveAckPointer() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.commit("HOST_OR_TOKEN_STOP", true);
			fixture.inbox.acknowledge(fixture.lease, fixture.stop.stopRef(), "receipt-1", Optional.empty());
			Campaign current = fixture.store.load();
			Campaign withoutPointer = new Campaign(3, current.campaignId(), current.status(),
				current.activeLineageId(), current.currentCandidateId(), current.appliedCandidateId(),
				current.activeDevelopmentRoot(), current.transitionCounter(), current.closedPhase(), current.lineages(),
				current.trials(), current.history(), current.optimizationProfile(), current.transitionReceipts(),
				current.scheduler(), current.preparationScope(), current.activeFormalFailureTriggerRef(),
				current.measurementState(), current.terminalResults(), null);
			Files.write(fixture.directory.resolve("campaign.json"), json.writeValueAsBytes(withoutPointer));
			new CampaignStore(fixture.directory).load();
		}
	}

	/**
	 * A cold read rejects an acknowledged pointer to a different existing stop record.
	 *
	 * @throws IOException when the pointer does not name the acknowledged scheduler stop
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Acknowledged stop pointer has no matching acknowledged scheduler stop.*")
	public void rejectsForeignAckPointer() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.commit("HOST_OR_TOKEN_STOP", true);
			fixture.inbox.acknowledge(fixture.lease, fixture.stop.stopRef(), "receipt-1", Optional.empty());
			EvidenceRef foreign = fixture.store.appendImmutableJson("stop-requests", "{}".getBytes(
				StandardCharsets.UTF_8));
			Path campaignFile = fixture.directory.resolve("campaign.json");
			ObjectNode document = (ObjectNode) json.readTree(Files.readAllBytes(campaignFile));
			((ObjectNode) document.get("acknowledgedInterruptionStopRef")).put("evidenceId",
				foreign.evidenceId());
			Files.write(campaignFile, json.writeValueAsBytes(document));
			new CampaignStore(fixture.directory).load();
		}
	}

	/**
	 * A cold read rejects a scheduler acknowledgment whose receipt differs from the embedded commit.
	 *
	 * @throws IOException when the acknowledgment is inconsistent with committed evidence
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Matching committed interruption receipt is missing.*")
	public void rejectsMismatchedCommittedAckOnColdLoad() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.commit("HOST_OR_TOKEN_STOP", true);
			fixture.inbox.acknowledge(fixture.lease, fixture.stop.stopRef(), "receipt-1", Optional.empty());
			Path campaignFile = fixture.directory.resolve("campaign.json");
			ObjectNode document = (ObjectNode) json.readTree(Files.readAllBytes(campaignFile));
			((ObjectNode) document.get("scheduler").get("stops").get(0)).put("interruptionReceiptId",
				"foreign-receipt");
			Files.write(campaignFile, json.writeValueAsBytes(document));
			new CampaignStore(fixture.directory).load();
		}
	}

	/**
	 * Wrong, source-review, and uncommitted identities cannot acknowledge the retained stop.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rejectsUncommittedAndWrongCause() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.commit("SOURCE_REVIEW", true);
			fixture.reject("receipt-1");
			fixture.reject("foreign-receipt");
		}
		try (Fixture fixture = new Fixture())
		{
			fixture.commit("HOST_OR_TOKEN_STOP", false);
			fixture.reject("receipt-1");
		}
		try (Fixture fixture = new Fixture())
		{
			fixture.reject("journal-only-receipt");
		}
	}

	/** Owns one real pinned campaign and live scheduler lease. */
	private final class Fixture implements AutoCloseable
	{
		private final TemporaryDirectories cleanup = new TemporaryDirectories();
		private final Path directory;
		private final CampaignStore store;
		private final CampaignBinding binding;
		private final CampaignStopInbox inbox;
		private final SchedulerLease lease;
		private final StopRequest stop;

		/**
		 * Creates a pinned campaign with an adopted no-call stop.
		 *
		 * @throws IOException if campaign setup fails
		 */
		private Fixture() throws IOException
		{
			directory = cleanup.add(Files.createTempDirectory("stop-ack-")).resolve("campaign-campaign");
			store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			binding = store.binding();
			inbox = ProspectiveCampaignState.inbox(store);
			inbox.beginInvocation(binding, "invocation");
			inbox.submit(binding, "invocation", "token");
			lease = ProspectiveCampaignState.lease(store).acquire(binding, "invocation");
			stop = inbox.adoptPending(lease).orElseThrow();
		}

		/**
		 * Installs a test-only prospective transition fixture, optionally embedding its receipt.
		 *
		 * @param reason interruption cause
		 * @param embed whether the receipt was committed in campaign state
		 * @throws IOException if evidence or fixture writes fail
		 */
		private void commit(String reason, boolean embed) throws IOException
		{
			EvidenceRef record = store.appendImmutableJson("campaign-interruption-records",
				json.writeValueAsBytes(new InterruptionEvidence(1, binding, 0, reason,
					Optional.of(stop.stopRef()), Optional.empty(), lease.fence(), Optional.empty())));
			TransitionResult result = new TransitionResult(1, CampaignStatus.INTERRUPTED, null, null, "receipt-1");
			TransitionReceipt receipt = new TransitionReceipt(binding, TransitionKind.INTERRUPTION, 0, record, 1,
				result);
			if (embed)
			{
				Campaign current = store.load();
				Campaign committed = new Campaign(3, current.campaignId(), CampaignStatus.INTERRUPTED, null, null,
					null, null, 1, CampaignStatus.AWAITING_CONTRACT_CONFIRMATION, List.of(), List.of(), List.of(),
					current.optimizationProfile(), List.of(receipt), current.scheduler(), current.preparationScope(),
					current.activeFormalFailureTriggerRef(), current.measurementState(), current.terminalResults(),
					current.acknowledgedInterruptionStopRef());
				Files.write(directory.resolve("campaign.json"), json.writeValueAsBytes(committed));
			}
			else
				Files.write(directory.resolve("transition-journal.json"),
					json.writeValueAsBytes(new JournalFixture(1, List.of(receipt))));
		}

		/**
		 * Requires a rejected identity to leave campaign bytes unchanged.
		 *
		 * @param receiptId untrusted receipt identity
		 * @throws IOException if fixture I/O fails
		 */
		private void reject(String receiptId) throws IOException
		{
			byte[] before = Files.readAllBytes(directory.resolve("campaign.json"));
			try
			{
				inbox.acknowledge(lease, stop.stopRef(), receiptId, Optional.empty());
				throw new AssertionError("Uncommitted or wrong-cause receipt must fail");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage() == null, "hasFailureReason").isFalse();
			}
			requireThat(Files.readAllBytes(directory.resolve("campaign.json")), "unchangedBytes").isEqualTo(before);
		}

		/** Releases the owner before removing test directories. */
		@Override
		public void close() throws IOException
		{
			lease.close();
			cleanup.close();
		}
	}

	private record InterruptionEvidence(int schemaVersion, CampaignBinding binding, long expectedCounter,
		String reason, Optional<EvidenceRef> stopRef, Optional<EvidenceRef> sourceReviewRef, long adoptingFence,
		Optional<String> logicalCallId)
	{
	}

	private record JournalFixture(int schemaVersion, List<TransitionReceipt> receipts)
	{
	}

	/**
	 * One canonical host observation used to bind the successor invocation.
	 *
	 * @param schemaVersion host observation schema
	 * @param tokenDigest host token digest
	 * @param hostSessionId host session identity
	 * @param hostTurnId host turn identity
	 */
	private record Observation(int schemaVersion, String tokenDigest, String hostSessionId,
		String hostTurnId)
	{
	}
}
