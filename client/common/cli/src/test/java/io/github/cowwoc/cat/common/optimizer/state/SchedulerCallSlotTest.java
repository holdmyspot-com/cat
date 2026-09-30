package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionCodec;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailure;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailureKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/** Tests durable fenced native-call boundaries. */
public final class SchedulerCallSlotTest
{
	private static final String RUN_ID = ProspectiveCampaignState.nativeRunId("call");

	/** Creates the test fixture. */
	public SchedulerCallSlotTest()
	{
	}

	/**
	 * A slot retains its active boundary and refuses opaque completion evidence.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void persistsSlotAcrossRestart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("scheduler-slot-")).resolve("campaign-campaign");
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation-1");
			inbox.publishHostObservation(binding, "invocation-1", registration,
				JsonMapper.builder().build().writeValueAsBytes(new Observation(1, "8".repeat(64),
					"session-8", "turn-8")));
			byte[] evidence = "{}".getBytes(StandardCharsets.UTF_8);
			EvidenceRef request = store.appendImmutableJson("run-requests",
				"{\"logicalCallId\":\"call-1\"}".getBytes(StandardCharsets.UTF_8));
			EvidenceRef completion = store.appendImmutableJson("native-completions", evidence);
			try (SchedulerLease owner = ProspectiveCampaignState.lease(store).acquire(binding, "invocation-1"))
			{
				requireThat(ProspectiveCampaignState.claim(owner, "call-1", request).state(), "claimed").
					isEqualTo(CallSlot.State.CLAIMED);
				requireThat(ProspectiveCampaignState.prepareStart(store, owner, "call-1").state(), "starting").
					isEqualTo(CallSlot.State.STARTING);
				requireThat(owner.activate("call-1", ProspectiveCampaignState.nativeRunId("call-1")).state(), "active").
					isEqualTo(CallSlot.State.ACTIVE);
				requireThat(owner.requestCancellation("call-1").cancellationRequested(), "cancelFlag").isTrue();
				requireThat(owner.requestCancellation("call-1").cancellationRequested(), "idempotentFlag").isTrue();
				try
				{
					owner.settle("call-1", completion);
					throw new AssertionError("Opaque completion must not settle the call");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "opaqueCompletion").contains("completion");
				}
			}
			requireThat(new CampaignStore(directory).load().scheduler().slots().getFirst().state(), "restartedSlot").
				isEqualTo(CallSlot.State.ACTIVE);
		}
	}

	/**
	 * A stop after pre-start publication retains cancellation intent before any native handle is available.
	 *
	 * @throws IOException if fixture publication or scheduler ownership fails
	 */
	@Test
	public void flagsStartingSlotAfterStop() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("scheduler-start-stop-")).
				resolve("campaign-campaign");
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				JsonMapper.builder().build().writeValueAsBytes(new Observation(1, "8".repeat(64),
					"session-8", "turn-8")));
			EvidenceRef request = store.appendImmutableJson("run-requests",
				"{\"logicalCallId\":\"call\"}".getBytes(StandardCharsets.UTF_8));
			try (SchedulerLease lease = ProspectiveCampaignState.lease(store).acquire(binding, "invocation"))
			{
				ProspectiveCampaignState.claim(lease, "call", request);
				ProspectiveCampaignState.prepareStart(store, lease, "call");
				inbox.submit(binding, "invocation", "ESC");
				CallSlot flagged = lease.requestCancellation("call");

				requireThat(flagged.state(), "state").isEqualTo(CallSlot.State.STARTING);
				requireThat(flagged.cancellationRequested(), "cancelFlag").isTrue();
				requireThat(lease.requestCancellation("call").cancellationRequested(), "idempotentFlag").isTrue();
			}
			CallSlot recovered = new CampaignStore(directory).load().scheduler().slots().getFirst();
			requireThat(recovered.state(), "recoveredState").isEqualTo(CallSlot.State.STARTING);
			requireThat(recovered.cancellationRequested(), "recoveredFlag").isTrue();
		}
	}

	/**
	 * A cancelled starting boundary cannot become active even if no separate pending stop remains.
	 *
	 * @throws IOException if fixture publication or scheduler ownership fails
	 */
	@Test
	public void rejectsActivationAfterCancellation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("scheduler-cancel-start-")).
				resolve("campaign-campaign");
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				JsonMapper.builder().build().writeValueAsBytes(new Observation(1, "8".repeat(64),
					"session-8", "turn-8")));
			EvidenceRef request = store.appendImmutableJson("run-requests",
				"{\"logicalCallId\":\"call\"}".getBytes(StandardCharsets.UTF_8));
			try (SchedulerLease lease = ProspectiveCampaignState.lease(store).acquire(binding, "invocation"))
			{
				ProspectiveCampaignState.claim(lease, "call", request);
				ProspectiveCampaignState.prepareStart(store, lease, "call");
				lease.requestCancellation("call");
				try
				{
					lease.activate("call", RUN_ID);
					throw new AssertionError("Cancelled start must not become active");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "cancelledStart").contains("Cancelled");
				}
			}
			requireThat(new CampaignStore(directory).load().scheduler().slots().getFirst().state(),
				"recoveredState").isEqualTo(CallSlot.State.STARTING);
		}
	}

	/**
	 * A successor cannot claim new work until a predecessor's active call has a retained adopted outcome.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void successorBlocksUnreconciledCall() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("scheduler-recovery-"));
			CampaignStore store = new CampaignStore(workflowRoot.resolve("campaign-campaign"));
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			JsonMapper json = JsonMapper.builder().build();
			EvidenceRef first = inbox.beginInvocation(binding, "first");
			inbox.publishHostObservation(binding, "first", first, json.writeValueAsBytes(
				new Observation(1, "a".repeat(64), "session-a", "turn-a")));
			byte[] evidence = "{}".getBytes(StandardCharsets.UTF_8);
			EvidenceRef request = store.appendImmutableJson("run-requests",
				"{\"logicalCallId\":\"old-call\"}".getBytes(StandardCharsets.UTF_8));
			EvidenceRef newRequest = store.appendImmutableJson("run-requests",
				"{\"logicalCallId\":\"new-call\"}".getBytes(StandardCharsets.UTF_8));
			EvidenceRef completion = store.appendImmutableJson("native-completions", evidence);
			try (SchedulerLease predecessor = ProspectiveCampaignState.lease(store).acquire(binding, "first"))
			{
				ProspectiveCampaignState.claim(predecessor, "old-call", request);
				ProspectiveCampaignState.prepareStart(store, predecessor, "old-call");
				predecessor.activate("old-call", ProspectiveCampaignState.nativeRunId("old-call"));
			}
			EvidenceRef second = inbox.beginInvocation(binding, "second");
			inbox.publishHostObservation(binding, "second", second, json.writeValueAsBytes(
				new Observation(1, "b".repeat(64), "session-b", "turn-b")));
			try (SchedulerLease successor = ProspectiveCampaignState.lease(store).acquire(binding, "second"))
			{
				try
				{
					inbox.finishInvocation(binding, "second");
					throw new AssertionError("Unadopted predecessor call must block invocation closure");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "unadoptedClosure").contains("unadopted");
				}
				try
				{
					ProspectiveCampaignState.claim(successor, "new-call", newRequest);
					throw new AssertionError("Unreconciled native call must block new claims");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "unreconciledClaim").contains("Unreconciled");
				}
				try
				{
					successor.settle("old-call", completion);
					throw new AssertionError("Opaque predecessor completion must not settle");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "opaquePredecessorCompletion").contains("completion");
				}
				requireThat(store.load().scheduler().slots().getFirst().state(), "unsettledPredecessor").
					isEqualTo(CallSlot.State.ACTIVE);
			}
		}
	}

	/**
	 * A typed completion still cannot settle before its C9B slot claim is authenticated.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void typedCompletionNeedsIndexedSlotClaim() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("typed-settlement-")).
				resolve("campaign-campaign");
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				JsonMapper.builder().build().writeValueAsBytes(new Observation(1, "c".repeat(64),
					"session-c", "turn-c")));
			EvidenceRef request = store.appendImmutableJson("run-requests",
				"{\"logicalCallId\":\"call\"}".getBytes(StandardCharsets.UTF_8));
			EvidenceRef foreignClaim = store.appendImmutableJson("slot-claims",
				"{}".getBytes(StandardCharsets.UTF_8));
			NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.TRANSPORT_FAILED, "call",
				Optional.of(RUN_ID), "transport failed", List.of(), Optional.empty(), Optional.empty());
			NativeRunCompletionEvidence completion = new NativeRunCompletionEvidence("campaign", "call", request,
				foreignClaim, Optional.of(RUN_ID), NativeRunCompletionEvidence.Outcome.FAILED, Optional.empty(),
				Optional.of(failure), List.of(), Optional.empty(), Optional.empty());
			EvidenceRef reference = store.appendImmutableJson("native-run-completions",
				NativeRunCompletionCodec.encode(completion, store.evidenceReader(binding)));
			EvidenceRef adoption = store.appendImmutableJson("native-adoptions",
				"{}".getBytes(StandardCharsets.UTF_8));
			try (SchedulerLease lease = ProspectiveCampaignState.lease(store).acquire(binding, "invocation"))
			{
				CallSlot claimed = ProspectiveCampaignState.claim(lease, "call", request);
				ProspectiveCampaignState.prepareStart(store, lease, "call");
				lease.activate("call", RUN_ID);
				try
				{
					lease.settle("call", reference);
					throw new AssertionError("Foreign slot claim must not settle");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "foreignSlotClaim").contains("slot claim");
				}
				NativeRunCompletionEvidence exactCompletion = new NativeRunCompletionEvidence("campaign", "call",
					request, claimed.slotClaimRef(), Optional.of(RUN_ID),
					NativeRunCompletionEvidence.Outcome.FAILED, Optional.empty(), Optional.of(failure), List.of(),
					Optional.empty(), Optional.empty());
				EvidenceRef exactReference = store.appendImmutableJson("native-run-completions",
					NativeRunCompletionCodec.encode(exactCompletion, store.evidenceReader(binding)));
				try
				{
					lease.settle("call", exactReference);
					throw new AssertionError("Unindexed exact slot claim must not settle");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "slotClaimFailure").contains("not indexed");
				}
				requireThat(store.load().scheduler().slots().getFirst().state(), "slotState").
					isEqualTo(CallSlot.State.ACTIVE);
				ProspectiveCampaignState.completedSlot(store, "call", exactReference, Optional.empty());
				try
				{
					lease.adopt("call", adoption);
					throw new AssertionError("Unverified slot claim must not authorize adoption");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "adoptionClaimFailure").contains("not indexed");
				}
				requireThat(store.load().scheduler().slots().getFirst().state(), "unadoptedSlot").
					isEqualTo(CallSlot.State.SETTLED);
			}
		}
	}

	/**
	 * A call claim is retained by its exact slot and can be read without scanning orphan evidence.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void retainsExactSlotClaimAcrossRestart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("durable-slot-claim-"));
			CampaignStore store = new CampaignStore(root.resolve("campaign-campaign"));
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				JsonMapper.builder().build().writeValueAsBytes(new Observation(1, "d".repeat(64),
					"session-d", "turn-d")));
			EvidenceRef request = store.appendImmutableJson("run-requests",
				"{\"logicalCallId\":\"call\"}".getBytes(StandardCharsets.UTF_8));
			CampaignSchedulerLease service = ProspectiveCampaignState.lease(store);
			try (SchedulerLease lease = service.acquire(binding, "invocation"))
			{
				ClaimedSlot claimed = service.claimOrResumeCall(lease, "call", request,
					Optional.empty(), Optional.empty());
				requireThat(claimed.slot().slotClaimRef(), "retainedClaim").isEqualTo(claimed.slotClaimRef());
				requireThat(service.readClaim(lease, "call"), "ownerRead").isEqualTo(claimed);
				requireThat(store.readCallSlotClaim(binding, "call"), "storeRead").isEqualTo(claimed);
			}
			requireThat(new CampaignStore(store.directory()).readCallSlotClaim(binding, "call").slotClaimRef(),
				"restartClaim").isEqualTo(store.load().scheduler().slots().getFirst().slotClaimRef());
			EvidenceRef retained = store.load().scheduler().slots().getFirst().slotClaimRef();
			Files.write(store.directory().resolve(retained.collection()).resolve(retained.evidenceId() + ".json"),
				"{}".getBytes(StandardCharsets.UTF_8));
			try
			{
				store.load();
				throw new AssertionError("Corrupt retained claim must not load as scheduling authority");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "corruptClaim").contains("evidence");
			}
		}
	}

	/**
	 * An equal owner restart retains the first claim, but a changed request cannot replace it.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void equalFenceRestartRetainsOriginalClaim() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("slot-claim-restart-"));
			CampaignStore store = new CampaignStore(root.resolve("campaign-campaign"));
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				JsonMapper.builder().build().writeValueAsBytes(new Observation(1, "e".repeat(64),
					"session-e", "turn-e")));
			EvidenceRef request = store.appendImmutableJson("run-requests",
				"{\"logicalCallId\":\"call\"}".getBytes(StandardCharsets.UTF_8));
			EvidenceRef otherRequest = store.appendImmutableJson("run-requests",
				"{\"logicalCallId\":\"call\",\"other\":true}".getBytes(StandardCharsets.UTF_8));
			CampaignSchedulerLease service = ProspectiveCampaignState.lease(store);
			EvidenceRef original;
			try (SchedulerLease first = service.acquire(binding, "invocation"))
			{
				original = service.claimOrResumeCall(first, "call", request, Optional.empty(),
					Optional.empty()).slotClaimRef();
			}
			try (SchedulerLease restarted = service.acquire(binding, "invocation"))
			{
				ClaimedSlot resumed = service.claimOrResumeCall(restarted, "call", request,
					Optional.empty(), Optional.empty());
				requireThat(resumed.slotClaimRef(), "originalClaim").isEqualTo(original);
				requireThat(resumed.slot().fence(), "advancedFence").isEqualTo(2L);
				try
				{
					service.claimOrResumeCall(restarted, "call", otherRequest, Optional.empty(), Optional.empty());
					throw new AssertionError("Conflicting request must not replace the original claim");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "conflictingRequest").contains("Conflicting");
				}
				requireThat(store.readCallSlotClaim(binding, "call").slotClaimRef(), "retainedAfterConflict").
					isEqualTo(original);
			}
		}
	}

	/**
	 * A retained pre-start record without C1's call index cannot move a claimed slot to STARTING.
	 *
	 * @throws IOException if the fixture or expected public guard fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*pre-start index.*")
	public void unindexedPreStartCannotStartClaimedCall() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("unindexed-pre-start-"));
			CampaignStore store = new CampaignStore(root.resolve("campaign-campaign"));
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				JsonMapper.builder().build().writeValueAsBytes(new Observation(1, "f".repeat(64),
					"session-f", "turn-f")));
			EvidenceRef request = store.appendImmutableJson("run-requests",
				"{\"logicalCallId\":\"call\"}".getBytes(StandardCharsets.UTF_8));
			EvidenceRef orphan = store.appendImmutableJson("native-prestarts",
				"{}".getBytes(StandardCharsets.UTF_8));
			try (SchedulerLease lease = ProspectiveCampaignState.lease(store).acquire(binding, "invocation"))
			{
				ProspectiveCampaignState.claim(lease, "call", request);
				lease.prepareStart("call", orphan);
			}
		}
	}

	/**
	 * An indexed pre-start remains readable from a fresh store before the claimed call starts.
	 *
	 * @throws IOException if fixture I/O or the public index check fails
	 */
	@Test
	public void indexedPreStartIsReadableBeforeStart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("indexed-pre-start-"));
			Path directory = root.resolve("campaign-campaign");
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				JsonMapper.builder().build().writeValueAsBytes(new Observation(1, "f".repeat(64),
					"session-f", "turn-f")));
			EvidenceRef request = store.appendImmutableJson("run-requests",
				"{\"logicalCallId\":\"call\"}".getBytes(StandardCharsets.UTF_8));
			try (SchedulerLease lease = ProspectiveCampaignState.lease(store).acquire(binding, "invocation"))
			{
				ProspectiveCampaignState.claim(lease, "call", request);
				EvidenceRef preStart = ProspectiveCampaignState.publishPreStart(store, lease, "call");
				requireThat(store.publishCallPreStartIndex(binding, "call", preStart), "equalPublication").
					isEqualTo(preStart);
				requireThat(store.load().scheduler().slots().getFirst().state(), "stateBeforeStart").
					isEqualTo(CallSlot.State.CLAIMED);
				requireThat(new CampaignStore(directory).readCallPreStartIndex(binding, "call"), "freshStoreIndex").
					isEqualTo(Optional.of(preStart));
				requireThat(lease.prepareStart("call", preStart).state(), "startedState").
					isEqualTo(CallSlot.State.STARTING);
			}
		}
	}

	/**
	 * A different retained record cannot replace the indexed pre-start reference at the public start boundary.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void indexedCallRejectsDifferentPreStart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("foreign-pre-start-"));
			CampaignStore store = new CampaignStore(root.resolve("campaign-campaign"));
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				JsonMapper.builder().build().writeValueAsBytes(new Observation(1, "f".repeat(64),
					"session-f", "turn-f")));
			EvidenceRef request = store.appendImmutableJson("run-requests",
				"{\"logicalCallId\":\"call\"}".getBytes(StandardCharsets.UTF_8));
			try (SchedulerLease lease = ProspectiveCampaignState.lease(store).acquire(binding, "invocation"))
			{
				ProspectiveCampaignState.claim(lease, "call", request);
				EvidenceRef indexed = ProspectiveCampaignState.publishPreStart(store, lease, "call");
				EvidenceRef other = store.appendImmutableJson("call-pre-starts",
					"{}".getBytes(StandardCharsets.UTF_8));
				try
				{
					lease.prepareStart("call", other);
					throw new AssertionError("A different pre-start record must not start the call");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "indexedReference").contains("pre-start index");
				}
				requireThat(store.load().scheduler().slots().getFirst().state(), "unstartedState").
					isEqualTo(CallSlot.State.CLAIMED);
				requireThat(lease.prepareStart("call", indexed).state(), "indexedStart").
					isEqualTo(CallSlot.State.STARTING);
			}
		}
	}

	/**
	 * A runner started under another identity cannot be adopted by this pre-start record.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rejectsMismatchedNativeRunId() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("pre-start-run-id-"));
			CampaignStore store = new CampaignStore(root.resolve("campaign-campaign"));
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				JsonMapper.builder().build().writeValueAsBytes(new Observation(1, "f".repeat(64),
					"session-f", "turn-f")));
			EvidenceRef request = store.appendImmutableJson("run-requests",
				"{\"logicalCallId\":\"call\"}".getBytes(StandardCharsets.UTF_8));
			try (SchedulerLease lease = ProspectiveCampaignState.lease(store).acquire(binding, "invocation"))
			{
				ProspectiveCampaignState.claim(lease, "call", request);
				requireThat(store.readCallPreStartRecord(binding, "call"), "beforeStart").isEmpty();
				ProspectiveCampaignState.prepareStart(store, lease, "call");
				CampaignStore restarted = new CampaignStore(store.directory());
				CallPreStartRecord preStart = restarted.readCallPreStartRecord(binding, "call").orElseThrow();
				requireThat(preStart.runId(), "retainedRunId").isEqualTo(RUN_ID);
				requireThat(preStart.runtimeManifestSha256(), "retainedRuntimeHash").isEqualTo("a".repeat(64));
				try
				{
					lease.activate("call", "foreign-1");
					throw new AssertionError("A foreign native run must not activate the claimed call");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "runIdentity").contains("run ID");
				}
				requireThat(store.load().scheduler().slots().getFirst().state(), "state").
					isEqualTo(CallSlot.State.STARTING);
			}
		}
	}

	/**
	 * A pre-start record rejects a run identity the native runner cannot own.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsInvalidPreStartRunId()
	{
		EvidenceRef request = new EvidenceRef("run-requests", "a".repeat(64));
		EvidenceRef claim = new EvidenceRef("call-slot-claims", "b".repeat(64));
		EvidenceRef attempt = new EvidenceRef("trial-transport-attempts", "c".repeat(64));
		EvidenceRef owner = new EvidenceRef("prepared-lineage-calls", "d".repeat(64));
		new CallPreStartRecord(3, "call", request, claim, Optional.empty(), Optional.empty(), Optional.empty(),
			ProducerAttemptKind.CANDIDATE, attempt, owner, "../foreign", "a".repeat(64));
	}

	/**
	 * A pre-start record requires a canonical verified packaged runner identity.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "Invalid pre-start runner runtime SHA-256")
	public void rejectsInvalidPreStartRuntimeHash()
	{
		EvidenceRef request = new EvidenceRef("run-requests", "a".repeat(64));
		EvidenceRef claim = new EvidenceRef("call-slot-claims", "b".repeat(64));
		EvidenceRef attempt = new EvidenceRef("trial-transport-attempts", "c".repeat(64));
		EvidenceRef owner = new EvidenceRef("prepared-lineage-calls", "d".repeat(64));
		new CallPreStartRecord(3, "call", request, claim, Optional.empty(), Optional.empty(), Optional.empty(),
			ProducerAttemptKind.CANDIDATE, attempt, owner, RUN_ID, "invalid");
	}

	/**
	 * Publishing a pre-start with no owner-attempt header grants no authority to start the call.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void preStartPublicationRequiresOwnerHeader() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("missing-attempt-header-"));
			CampaignStore store = new CampaignStore(root.resolve("campaign-campaign"));
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				JsonMapper.builder().build().writeValueAsBytes(new Observation(1, "f".repeat(64),
					"session-f", "turn-f")));
			EvidenceRef request = store.appendImmutableJson("run-requests",
				"{\"logicalCallId\":\"call\"}".getBytes(StandardCharsets.UTF_8));
			try (SchedulerLease lease = ProspectiveCampaignState.lease(store).acquire(binding, "invocation"))
			{
				CallSlot slot = ProspectiveCampaignState.claim(lease, "call", request);
				EvidenceRef badAttempt = store.appendImmutableJson("test-producer-attempts",
					"{}".getBytes(StandardCharsets.UTF_8));
				EvidenceRef ownerRequest = store.appendImmutableJson("prepared-lineage-calls",
					"{}".getBytes(StandardCharsets.UTF_8));
				CallPreStartRecord record = new CallPreStartRecord(3, "call", request,
					slot.slotClaimRef(), Optional.empty(), Optional.empty(), Optional.empty(),
					ProducerAttemptKind.CANDIDATE,
					badAttempt, ownerRequest, RUN_ID, "a".repeat(64));
				EvidenceRef preStart = store.appendImmutableJson("call-pre-starts",
					JsonMapper.builder().build().writeValueAsBytes(record));
				try
				{
					store.publishCallPreStartIndex(binding, "call", preStart);
					throw new AssertionError("An owner attempt without a header must not be indexed");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "missingHeader").contains("common header");
				}
				requireThat(store.readCallPreStartIndex(binding, "call"), "unpublishedIndex").
					isEqualTo(Optional.empty());
				requireThat(store.load().scheduler().slots().getFirst().state(), "unstartedState").
					isEqualTo(CallSlot.State.CLAIMED);
			}
		}
	}

	/**
	 * A claim-matching attempt without its owner index cannot acquire pre-start dispatch authority.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void orphanOwnerAttemptCannotBind() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("orphan-owner-attempt-"));
			CampaignStore store = new CampaignStore(root.resolve("campaign-campaign"));
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			JsonMapper json = JsonMapper.builder().build();
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				json.writeValueAsBytes(new Observation(1, "f".repeat(64), "session-f", "turn-f")));
			EvidenceRef request = store.appendImmutableJson("run-requests",
				"{\"logicalCallId\":\"call\"}".getBytes(StandardCharsets.UTF_8));
			try (SchedulerLease lease = ProspectiveCampaignState.lease(store).acquire(binding, "invocation"))
			{
				CallSlot slot = ProspectiveCampaignState.claim(lease, "call", request);
				Campaign campaign = store.load();
				LineageSummary active = campaign.lineages().stream().filter(lineage ->
					lineage.lineageId().equals(campaign.activeLineageId())).findFirst().orElseThrow();
				LineageKey lineage = new LineageKey(active.lineageId(), active.confirmedContractId());
				EvidenceRef semanticRequest = store.appendImmutableJson("evaluator-requests",
					"{}".getBytes(StandardCharsets.UTF_8));
				PreparedLineageCall prepared = new PreparedLineageCall(1, ProducerAttemptKind.EVALUATOR,
					binding.campaignId(), lineage, "call", semanticRequest, request);
				EvidenceRef ownerRequest = store.appendImmutableJson("prepared-lineage-calls",
					json.writeValueAsBytes(prepared));
				ProducerAttemptHeader header = new ProducerAttemptHeader(ProducerAttemptKind.EVALUATOR,
					"call", request, slot.slotClaimRef(), ownerRequest, Optional.empty());
				EvidenceRef attempt = store.appendImmutableJson("evaluator-transport-attempts",
					json.writeValueAsBytes(Map.of("header", header, "requestRef", semanticRequest,
						"attemptOrdinal", 0)));
				CallPreStartRecord record = new CallPreStartRecord(3, "call", request,
					slot.slotClaimRef(), Optional.empty(), Optional.empty(), Optional.empty(),
					ProducerAttemptKind.EVALUATOR,
					attempt, ownerRequest, RUN_ID, "a".repeat(64));
				EvidenceRef preStart = store.appendImmutableJson("call-pre-starts", json.writeValueAsBytes(record));
				try
				{
					store.publishCallPreStartIndex(binding, "call", preStart);
					throw new AssertionError("An unindexed owner attempt must not acquire pre-start authority");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "ownerIndex").contains("owner attempt index");
				}
				requireThat(store.readCallPreStartIndex(binding, "call"), "unpublishedIndex").
					isEqualTo(Optional.empty());
				requireThat(store.load().scheduler().slots().getFirst().state(), "unstartedState").
					isEqualTo(CallSlot.State.CLAIMED);
			}
		}
	}

	/**
	 * A published pre-start loses dispatch authority if its producer's index disappears before STARTING.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void missingOwnerIndexCannotStartClaimedCall() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("missing-owner-index-"));
			CampaignStore store = new CampaignStore(root.resolve("campaign-campaign"));
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				JsonMapper.builder().build().writeValueAsBytes(new Observation(1, "f".repeat(64),
					"session-f", "turn-f")));
			EvidenceRef request = store.appendImmutableJson("run-requests",
				"{\"logicalCallId\":\"call\"}".getBytes(StandardCharsets.UTF_8));
			try (SchedulerLease lease = ProspectiveCampaignState.lease(store).acquire(binding, "invocation"))
			{
				ProspectiveCampaignState.claim(lease, "call", request);
				EvidenceRef preStart = ProspectiveCampaignState.publishPreStart(store, lease, "call");
				try (Stream<Path> entries = Files.list(store.directory().resolve("evaluator-execution-attempts")))
				{
					Files.delete(entries.findFirst().orElseThrow());
				}
				try
				{
					lease.prepareStart("call", preStart);
					throw new AssertionError("A removed owner index must not authorize STARTING");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "ownerIndex").contains("owner attempt index");
				}
				requireThat(store.load().scheduler().slots().getFirst().state(), "unstartedState").
					isEqualTo(CallSlot.State.CLAIMED);
			}
		}
	}

	/**
	 * A new observed invocation retains an indexed unstarted claim, but cannot inherit it after start.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void newInvocationTransfersOnlyUnstartedClaim() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("slot-claim-transfer-"));
			CampaignStore store = new CampaignStore(root.resolve("campaign-campaign"));
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			JsonMapper json = JsonMapper.builder().build();
			EvidenceRef firstRegistration = inbox.beginInvocation(binding, "first");
			inbox.publishHostObservation(binding, "first", firstRegistration, json.writeValueAsBytes(
				new Observation(1, "a".repeat(64), "session-a", "turn-a")));
			EvidenceRef request = store.appendImmutableJson("run-requests",
				"{\"logicalCallId\":\"call\"}".getBytes(StandardCharsets.UTF_8));
			CampaignSchedulerLease service = ProspectiveCampaignState.lease(store);
			EvidenceRef original;
			EvidenceRef indexed;
			try (SchedulerLease first = service.acquire(binding, "first"))
			{
				original = service.claimOrResumeCall(first, "call", request, Optional.empty(),
					Optional.empty()).slotClaimRef();
				indexed = ProspectiveCampaignState.publishPreStart(store, first, "call");
				requireThat(store.load().scheduler().slots().getFirst().state(), "indexedUnstartedSlot").
					isEqualTo(CallSlot.State.CLAIMED);
			}
			EvidenceRef secondRegistration = inbox.beginInvocation(binding, "second");
			inbox.publishHostObservation(binding, "second", secondRegistration, json.writeValueAsBytes(
				new Observation(1, "b".repeat(64), "session-b", "turn-b")));
			try (SchedulerLease second = service.acquire(binding, "second"))
			{
				ClaimedSlot transferred = service.claimOrResumeCall(second, "call", request,
					Optional.empty(), Optional.empty());
				requireThat(transferred.slotClaimRef(), "originalClaim").isEqualTo(original);
				requireThat(transferred.slot().fence(), "newFence").isEqualTo(2L);
				requireThat(store.readCallPreStartIndex(binding, "call"), "retainedIndex").
					isEqualTo(Optional.of(indexed));
				second.prepareStart("call", indexed);
			}
			EvidenceRef thirdRegistration = inbox.beginInvocation(binding, "third");
			inbox.publishHostObservation(binding, "third", thirdRegistration, json.writeValueAsBytes(
				new Observation(1, "c".repeat(64), "session-c", "turn-c")));
			try (SchedulerLease third = service.acquire(binding, "third"))
			{
				try
				{
					service.claimOrResumeCall(third, "call", request, Optional.empty(), Optional.empty());
					throw new AssertionError("Started claim must not transfer");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "startedTransfer").contains("unstarted CLAIMED");
				}
			}
			requireThat(store.readCallSlotClaim(binding, "call").slotClaimRef(), "retainedOriginal").
				isEqualTo(original);
		}
	}

	/**
	 * An unconfirmed preparation call cannot borrow the lineage-only claim route.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void unconfirmedCallNeedsPreparationOwner() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("unconfirmed-slot-claim-"));
			CampaignStore store = new CampaignStore(root.resolve("campaign-campaign"));
			ProspectiveCampaignState.initialize(store, "campaign");
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				JsonMapper.builder().build().writeValueAsBytes(new Observation(1, "f".repeat(64),
					"session-f", "turn-f")));
			EvidenceRef request = store.appendImmutableJson("run-requests",
				"{\"logicalCallId\":\"call\"}".getBytes(StandardCharsets.UTF_8));
			CampaignSchedulerLease service = ProspectiveCampaignState.lease(store);
			try (SchedulerLease lease = service.acquire(binding, "invocation"))
			{
				try
				{
					service.claimOrResumeCall(lease, "call", request, Optional.empty(), Optional.empty());
					throw new AssertionError("Unconfirmed preparation call requires C4 owner authority");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "missingPreparationOwner").contains("preparation");
				}
				EvidenceRef owner = store.appendImmutableJson("contract-preparation-owners",
					"{}".getBytes(StandardCharsets.UTF_8));
				EvidenceRef cycle = store.appendImmutableJson("contract-preparation-cycles",
					"{}".getBytes(StandardCharsets.UTF_8));
				try
				{
					service.claimOrResumeCall(lease, "call", request, Optional.of(owner), Optional.of(cycle));
					throw new AssertionError("Opaque owner and cycle must fail preparation validation");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "unvalidatedPreparationOwner").contains("malformed");
				}
			}
			requireThat(store.load().scheduler().slots().isEmpty(), "noSlot").isTrue();
			requireThat(Files.notExists(store.directory().resolve("call-slot-claims")), "noClaim").isTrue();
		}
	}

	private record Observation(int schemaVersion, String tokenDigest, String hostSessionId, String hostTurnId)
	{
	}
}
