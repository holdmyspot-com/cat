package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionCodec;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.NativeEventEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailure;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailureKind;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunResult;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverage;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverageStatus;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationDomain;
import io.github.cowwoc.cat.common.optimizer.runner.RunnerIdentity;
import io.github.cowwoc.cat.common.optimizer.runner.TurnResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/** Focused C1A index and settlement recovery tests. */
public final class NativeCompletionIndexTest
{
	private static final String RUN_ID = ProspectiveCampaignState.nativeRunId("call");

	/** Creates the test fixture. */
	public NativeCompletionIndexTest()
	{
	}

	/**
	 * Adoption rejects a producer record changed after its preliminary read but before the state lock.
	 *
	 * @throws IOException if fixture I/O fails
	 * @throws InterruptedException if the test thread is interrupted
	 * @throws TimeoutException if adoption does not finish
	 */
	@Test
	public void adoptionRechecksProducerUnderStateLock() throws IOException, InterruptedException, TimeoutException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("native-index-lock-")).toRealPath().
				resolve("campaign-campaign");
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				("{\"schemaVersion\":1,\"tokenDigest\":\"" + "c".repeat(64) +
					"\",\"hostSessionId\":\"session\",\"hostTurnId\":\"turn\"}").
					getBytes(StandardCharsets.UTF_8));
			EvidenceRef request = store.appendImmutableJson("run-requests",
				"{\"logicalCallId\":\"call\"}".getBytes(StandardCharsets.UTF_8));
			try (SchedulerLease lease = ProspectiveCampaignState.lease(store).acquire(binding, "invocation"))
			{
				CallSlot claim = ProspectiveCampaignState.claim(lease, "call", request);
				ProspectiveCampaignState.prepareStart(store, lease, "call");
				lease.activate("call", RUN_ID);
				NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.TRANSPORT_FAILED,
					"call", Optional.of(RUN_ID), "transport failed", List.of(), Optional.empty(), Optional.empty());
				NativeRunCompletionEvidence completion = new NativeRunCompletionEvidence("campaign", "call",
					request, claim.slotClaimRef(), Optional.of(RUN_ID), NativeRunCompletionEvidence.Outcome.FAILED,
					Optional.empty(), Optional.of(failure), List.of(), Optional.empty(), Optional.empty());
				EvidenceRef completionRef = store.appendImmutableJson("native-run-completions",
					NativeRunCompletionCodec.encode(completion, store.evidenceReader(binding)));
				store.publishNativeCompletionIndex(binding, "call", completionRef);
				lease.settle("call", completionRef);
				EvidenceRef producer = store.appendImmutableJson("generation-outcomes",
					"{\"outcome\":\"failed\"}".getBytes(StandardCharsets.UTF_8));
				FutureTask<CallSlot> adoption = new FutureTask<>(() -> lease.adopt("call", producer));
				Thread adoptionThread;
				synchronized (CampaignFileLocks.class)
				{
					adoptionThread = Thread.ofPlatform().start(adoption);
					long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
					while (adoptionThread.getState() != Thread.State.BLOCKED && !adoption.isDone() &&
						System.nanoTime() < deadline)
						Thread.onSpinWait();
					requireThat(adoptionThread.getState(), "waitingForCampaignStateLock").
						isEqualTo(Thread.State.BLOCKED);
					Files.writeString(directory.resolve("generation-outcomes").
						resolve(producer.evidenceId() + ".json"), "{}");
				}
				try
				{
					adoption.get(5, TimeUnit.SECONDS);
					throw new AssertionError("Changed producer evidence must not be adopted");
				}
				catch (ExecutionException expected)
				{
					requireThat(expected.getCause().getMessage(), "invalidProducer").contains("content address");
				}
				requireThat(store.load().scheduler().slots().getFirst().state(), "slotAfterRejectedAdoption").
					isEqualTo(CallSlot.State.SETTLED);
			}
		}
	}

	/**
	 * An indexed failure without a native run ID settles a starting call and replays after restart.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void startingFailureWithoutRunIdSettles() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("native-index-starting-failure-")).toRealPath().
				resolve("campaign-campaign");
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				("{\"schemaVersion\":1,\"tokenDigest\":\"" + "c".repeat(64) +
					"\",\"hostSessionId\":\"session\",\"hostTurnId\":\"turn\"}").
					getBytes(StandardCharsets.UTF_8));
			EvidenceRef request = store.appendImmutableJson("run-requests",
				"{\"logicalCallId\":\"call\"}".getBytes(StandardCharsets.UTF_8));
			EvidenceRef completionRef;
			try (SchedulerLease lease = ProspectiveCampaignState.lease(store).acquire(binding, "invocation"))
			{
				CallSlot claim = ProspectiveCampaignState.claim(lease, "call", request);
				ProspectiveCampaignState.prepareStart(store, lease, "call");
				NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.TRANSPORT_FAILED,
					"call", Optional.empty(), "transport failed before launch", List.of(), Optional.empty(),
					Optional.empty());
				NativeRunCompletionEvidence completion = new NativeRunCompletionEvidence("campaign", "call",
					request, claim.slotClaimRef(), Optional.empty(), NativeRunCompletionEvidence.Outcome.FAILED,
					Optional.empty(), Optional.of(failure), List.of(), Optional.empty(), Optional.empty());
				completionRef = store.appendImmutableJson("native-run-completions",
					NativeRunCompletionCodec.encode(completion, store.evidenceReader(binding)));
				store.publishNativeCompletionIndex(binding, "call", completionRef);
				requireThat(store.load().scheduler().slots().getFirst().state(), "beforeFailureSettlement").
					isEqualTo(CallSlot.State.STARTING);
				requireThat(lease.settle("call", completionRef).state(), "startingFailureSettlement").
					isEqualTo(CallSlot.State.SETTLED);
			}
			CampaignStore restarted = new CampaignStore(directory);
			try (SchedulerLease lease = ProspectiveCampaignState.lease(restarted).acquire(binding, "invocation"))
			{
				CallSlot settled = restarted.load().scheduler().slots().getFirst();
				requireThat(settled.runId(), "failedBeforeLaunchRunId").isEqualTo(Optional.empty());
				requireThat(lease.settle("call", completionRef), "replayedStartingFailure").isEqualTo(settled);
			}
		}
	}

	/**
	 * Corrupt indexed completion bytes cannot settle a call or authorize equal settlement replay.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void corruptCompletionBlocksSettleAndReplay() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("native-index-corrupt-payload-")).toRealPath().
				resolve("campaign-campaign");
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				("{\"schemaVersion\":1,\"tokenDigest\":\"" + "c".repeat(64) +
					"\",\"hostSessionId\":\"session\",\"hostTurnId\":\"turn\"}").
					getBytes(StandardCharsets.UTF_8));
			EvidenceRef request = store.appendImmutableJson("run-requests",
				"{\"logicalCallId\":\"call\"}".getBytes(StandardCharsets.UTF_8));
			try (SchedulerLease lease = ProspectiveCampaignState.lease(store).acquire(binding, "invocation"))
			{
				CallSlot claim = ProspectiveCampaignState.claim(lease, "call", request);
				ProspectiveCampaignState.prepareStart(store, lease, "call");
				lease.activate("call", RUN_ID);
				NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.TRANSPORT_FAILED,
					"call", Optional.of(RUN_ID), "transport failed", List.of(), Optional.empty(),
					Optional.empty());
				NativeRunCompletionEvidence completion = new NativeRunCompletionEvidence("campaign", "call",
					request, claim.slotClaimRef(), Optional.of(RUN_ID), NativeRunCompletionEvidence.Outcome.FAILED,
					Optional.empty(), Optional.of(failure), List.of(), Optional.empty(), Optional.empty());
				EvidenceRef completionRef = store.appendImmutableJson("native-run-completions",
					NativeRunCompletionCodec.encode(completion, store.evidenceReader(binding)));
				store.publishNativeCompletionIndex(binding, "call", completionRef);
				Path completionFile = directory.resolve("native-run-completions").
					resolve(completionRef.evidenceId() + ".json");
				byte[] original = Files.readAllBytes(completionFile);
				CallSlot active = store.load().scheduler().slots().getFirst();
				Files.writeString(completionFile, "{}");
				try
				{
				lease.settle("call", completionRef);
					throw new AssertionError("Corrupt completion bytes must not settle a call");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "corruptCompletionAtSettlement").contains("content address");
				}
				requireThat(store.load().scheduler().slots().getFirst(), "slotAfterRejectedSettlement").
					isEqualTo(active);
				Files.write(completionFile, original);
				CallSlot settled = lease.settle("call", completionRef);
				Files.writeString(completionFile, "{}");
				try
				{
					lease.settle("call", completionRef);
					throw new AssertionError("Corrupt completion bytes must not authorize replay");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "corruptCompletionAtReplay").contains("content address");
				}
				requireThat(store.load().scheduler().slots().getFirst(), "slotAfterRejectedReplay").
					isEqualTo(settled);
				Files.write(completionFile, original);
				requireThat(lease.settle("call", completionRef), "replayAfterEvidenceRepair").isEqualTo(settled);
			}
		}
	}

	/**
	 * A typed failure settles and replays, while invalid indexes block slot mutations.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void indexedFailureSettlesAndReplays() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("native-index-")).toRealPath().
				resolve("campaign-campaign");
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				("{\"schemaVersion\":1,\"tokenDigest\":\"" + "c".repeat(64) +
					"\",\"hostSessionId\":\"session\",\"hostTurnId\":\"turn\"}").
					getBytes(StandardCharsets.UTF_8));
			EvidenceRef request = store.appendImmutableJson("run-requests",
				"{\"logicalCallId\":\"call\"}".getBytes(StandardCharsets.UTF_8));
			try (SchedulerLease lease = ProspectiveCampaignState.lease(store).acquire(binding, "invocation"))
			{
				CallSlot claim = ProspectiveCampaignState.claim(lease, "call", request);
				ProspectiveCampaignState.prepareStart(store, lease, "call");
				lease.activate("call", RUN_ID);
				NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.TRANSPORT_FAILED,
					"call", Optional.of(RUN_ID), "transport failed", List.of(), Optional.empty(), Optional.empty());
				NativeRunCompletionEvidence completion = new NativeRunCompletionEvidence("campaign", "call",
					request, claim.slotClaimRef(), Optional.of(RUN_ID), NativeRunCompletionEvidence.Outcome.FAILED,
					Optional.empty(), Optional.of(failure), List.of(), Optional.empty(), Optional.empty());
				EvidenceRef ref = store.appendImmutableJson("native-run-completions",
					NativeRunCompletionCodec.encode(completion, store.evidenceReader(binding)));
				EvidenceRef opaque = store.appendImmutableJson("native-run-completions",
					"{}".getBytes(StandardCharsets.UTF_8));
				try
				{
					store.publishNativeCompletionIndex(binding, "call", opaque);
					throw new AssertionError("Opaque completion must not be indexed");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "opaqueCompletion").contains("completion");
				}
				String forgedSuccess = new String(store.readImmutableJson(ref), StandardCharsets.UTF_8).
					replace("\"outcome\":\"FAILED\"", "\"outcome\":\"SUCCEEDED\"");
				requireThat(forgedSuccess, "forgedOutcome").contains("\"outcome\":\"SUCCEEDED\"");
				EvidenceRef forged = store.appendImmutableJson("native-run-completions",
					forgedSuccess.getBytes(StandardCharsets.UTF_8));
				try
				{
					store.publishNativeCompletionIndex(binding, "call", forged);
					throw new AssertionError("Failed payload labeled as success must not be indexed");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "failedAsSuccess").contains("completion");
				}
				NativeRunCompletionEvidence foreignCompletion = new NativeRunCompletionEvidence("foreign",
					"call", request, claim.slotClaimRef(), Optional.of(RUN_ID),
					NativeRunCompletionEvidence.Outcome.FAILED, Optional.empty(), Optional.of(failure),
					List.of(), Optional.empty(), Optional.empty());
				EvidenceRef foreignRef = store.appendImmutableJson("native-run-completions",
					NativeRunCompletionCodec.encode(foreignCompletion, store.evidenceReader(binding)));
				try
				{
					store.publishNativeCompletionIndex(binding, "call", foreignRef);
					throw new AssertionError("Foreign completion must not be indexed");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "foreignCompletion").contains("conflicts");
				}
				requireThat(store.readNativeCompletionIndex(binding, "call"), "absentIndex").
					isEqualTo(Optional.empty());
				try
				{
					lease.settle("call", ref);
					throw new AssertionError("Unindexed completion must not settle");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "unindexed").contains("indexed");
				}
				requireThat(store.publishNativeCompletionIndex(binding, "call", ref), "published").isEqualTo(ref);
				requireThat(store.publishNativeCompletionIndex(binding, "call", ref), "equalPublication").
					isEqualTo(ref);
				Path indexFile;
				try (Stream<Path> entries = Files.list(directory.resolve("native-completion-index")))
				{
					indexFile = entries.findFirst().orElseThrow();
				}
				byte[] original = Files.readAllBytes(indexFile);
				Files.writeString(indexFile, "{}");
				try
				{
					lease.settle("call", ref);
					throw new AssertionError("Malformed index must not settle a call");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "malformedSettlementIndex").contains("index");
				}
				requireThat(store.load().scheduler().slots().getFirst().state(), "slotAfterRejectedSettlement").
					isEqualTo(CallSlot.State.ACTIVE);
				Files.write(indexFile, original);
				NestedRunFailure differentFailure = new NestedRunFailure(NestedRunFailureKind.TRANSPORT_FAILED,
					"call", Optional.of(RUN_ID), "different failure", List.of(), Optional.empty(), Optional.empty());
				NativeRunCompletionEvidence differentCompletion = new NativeRunCompletionEvidence("campaign",
					"call", request, claim.slotClaimRef(), Optional.of(RUN_ID),
					NativeRunCompletionEvidence.Outcome.FAILED, Optional.empty(), Optional.of(differentFailure),
					List.of(), Optional.empty(), Optional.empty());
				EvidenceRef differentRef = store.appendImmutableJson("native-run-completions",
					NativeRunCompletionCodec.encode(differentCompletion, store.evidenceReader(binding)));
				try
				{
					store.publishNativeCompletionIndex(binding, "call", differentRef);
					throw new AssertionError("Conflicting completion must not replace the index");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "indexCollision").contains("collision");
				}
				requireThat(new CampaignStore(directory).readNativeCompletionIndex(binding, "call"), "restartIndex").
					isEqualTo(Optional.of(ref));
				requireThat(lease.settle("call", ref).state(), "settled").isEqualTo(CallSlot.State.SETTLED);
				requireThat(lease.settle("call", ref).state(), "replayed").isEqualTo(CallSlot.State.SETTLED);
				EvidenceRef producer = store.appendImmutableJson("generation-outcomes",
					"{\"outcome\":\"failed\"}".getBytes(StandardCharsets.UTF_8));
				Files.writeString(indexFile, "{}");
				try
				{
					lease.adopt("call", producer);
					throw new AssertionError("Malformed index must not authorize adoption");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "malformedAdoptionIndex").contains("index");
				}
				requireThat(store.load().scheduler().slots().getFirst().state(), "slotAfterRejectedIndexAdoption").
					isEqualTo(CallSlot.State.SETTLED);
				Files.write(indexFile, original);
				CallSlot adopted = lease.adopt("call", producer);
				requireThat(adopted.state(), "adopted").isEqualTo(CallSlot.State.ADOPTED);
				requireThat(adopted.adoptionRef().orElseThrow().collection(), "envelopeCollection").
					isEqualTo("call-adoptions");
				requireThat(lease.adopt("call", producer), "equalAdoption").isEqualTo(adopted);
				try
				{
					lease.adopt("call", differentRef);
					throw new AssertionError("A different producer must not replace adoption");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "adoptionCollision").contains("Conflicting");
				}
				Files.writeString(indexFile, "{}");
				try
				{
					store.readNativeCompletionIndex(binding, "call");
					throw new AssertionError("Malformed index must not be read");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "malformedIndex").contains("index");
				}
				NativeCompletionIndexEntry foreignEntry = new NativeCompletionIndexEntry(1, "foreign",
					"call", request, claim.slotClaimRef(), ref);
				Files.write(indexFile, JsonMapper.builder().build().writeValueAsBytes(foreignEntry));
				try
				{
					store.readNativeCompletionIndex(binding, "call");
					throw new AssertionError("Foreign index entry must not be read");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "foreignIndex").contains("foreign");
				}
				Files.delete(indexFile);
				Files.createSymbolicLink(indexFile, directory.resolve("campaign.json"));
				try
				{
					store.readNativeCompletionIndex(binding, "call");
					throw new AssertionError("Symlinked index must not be read");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "symlinkedIndex").contains("redirected");
				}
				Files.delete(indexFile);
				Files.write(indexFile, original);
				Path indexDirectory = indexFile.getParent();
				if (Files.getFileStore(indexDirectory).supportsFileAttributeView("posix"))
				{
					Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(indexDirectory);
					try
					{
						Files.setPosixFilePermissions(indexDirectory, Set.of());
						try
						{
							store.readNativeCompletionIndex(binding, "call");
							throw new AssertionError("Unreadable index must not look absent");
						}
						catch (IOException expected)
						{
							requireThat(expected.getMessage(), "unreadableIndex").contains(indexDirectory.toString());
						}
					}
					finally
					{
						Files.setPosixFilePermissions(indexDirectory, originalPermissions);
					}
				}
				requireThat(store.load().scheduler().slots().getFirst(), "unchangedAfterIndexAttacks").
					isEqualTo(adopted);
			}
		}
	}

	/**
	 * A successful typed completion and an orphan adoption envelope are recovered after separate restarts.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void successAndOrphanAdoptionRecover() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("success-index-")).toRealPath().
				resolve("campaign-campaign");
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				("{\"schemaVersion\":1,\"tokenDigest\":\"" + "a".repeat(64) +
					"\",\"hostSessionId\":\"session\",\"hostTurnId\":\"turn\"}").
					getBytes(StandardCharsets.UTF_8));
			EvidenceRef request = store.appendImmutableJson("run-requests",
				"{\"logicalCallId\":\"call\"}".getBytes(StandardCharsets.UTF_8));
			EvidenceRef eventRef = store.appendImmutableJson("native-events", "{}".getBytes(StandardCharsets.UTF_8));
			NativeEventEvidence event = new NativeEventEvidence("conversation", 1, eventRef);
			NestedRunResult result = new NestedRunResult("codex", RUN_ID, directory.toAbsolutePath(),
				List.of(new TurnResult(1, "conversation", "response", event)), List.of(event),
				new RunnerIdentity("codex", "version", "b".repeat(64), Optional.of("provider"), Map.of(),
					List.of()), new ModelProfile("model", Optional.empty(), Map.of()), List.of(), List.of(),
				List.of(), List.of(new ObservationCoverage(ObservationDomain.PROMPT_SOURCE,
					ObservationCoverageStatus.LIMITED, Optional.of("no path events"), List.of()),
					new ObservationCoverage(ObservationDomain.DIRECT_TOOL,
						ObservationCoverageStatus.LIMITED, Optional.of("no tool events"), List.of()),
					new ObservationCoverage(ObservationDomain.EXTERNAL_SOURCE,
						ObservationCoverageStatus.LIMITED, Optional.of("no external events"), List.of())));
			EvidenceRef completionRef;
			EvidenceRef claimRef;
			try (SchedulerLease first = ProspectiveCampaignState.lease(store).acquire(binding, "invocation"))
			{
				CallSlot claimed = ProspectiveCampaignState.claim(first, "call", request);
				claimRef = claimed.slotClaimRef();
				try
				{
					first.settle("call", eventRef);
					throw new AssertionError("Claimed slot without completion cannot settle");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "unstartedSlot").contains("not started");
				}
				requireThat(store.load().scheduler().slots().getFirst(), "unchangedClaim").isEqualTo(claimed);
				ProspectiveCampaignState.prepareStart(store, first, "call");
				first.activate("call", RUN_ID);
				NativeRunCompletionEvidence completion = new NativeRunCompletionEvidence("campaign", "call",
					request, claimRef, Optional.of(RUN_ID), NativeRunCompletionEvidence.Outcome.SUCCEEDED,
					Optional.of(result), Optional.empty(), List.of(eventRef), Optional.of(eventRef),
					Optional.empty());
				completionRef = store.appendImmutableJson("native-run-completions",
					NativeRunCompletionCodec.encode(completion, store.evidenceReader(binding)));
				store.publishNativeCompletionIndex(binding, "call", completionRef);
			}
			CampaignStore restarted = new CampaignStore(directory);
			try (SchedulerLease second = ProspectiveCampaignState.lease(restarted).acquire(binding,
				"invocation"))
			{
				requireThat(restarted.load().scheduler().slots().getFirst().state(), "beforeSettlement").
					isEqualTo(CallSlot.State.ACTIVE);
				requireThat(second.settle("call", completionRef).state(), "successSettlement").
					isEqualTo(CallSlot.State.SETTLED);
			}
			EvidenceRef producer = restarted.appendImmutableJson("generation-outcomes",
				"{\"outcome\":\"succeeded\"}".getBytes(StandardCharsets.UTF_8));
			CallAdoptionRecord envelope = new CallAdoptionRecord(1, binding.campaignId(), "call", request,
				claimRef, completionRef, producer);
			EvidenceRef orphan = restarted.appendImmutableJson("call-adoptions",
				JsonMapper.builder().build().writeValueAsBytes(envelope));
			requireThat(restarted.load().scheduler().slots().getFirst().state(), "orphanHasNoAuthority").
				isEqualTo(CallSlot.State.SETTLED);
			CampaignStore resumed = new CampaignStore(directory);
			try (SchedulerLease third = ProspectiveCampaignState.lease(resumed).acquire(binding, "invocation"))
			{
				CallSlot adopted = third.adopt("call", producer);
				requireThat(adopted.state(), "recoveredAdoption").isEqualTo(CallSlot.State.ADOPTED);
				requireThat(adopted.adoptionRef(), "reusedOrphan").isEqualTo(Optional.of(orphan));
			}
		}
	}
}
