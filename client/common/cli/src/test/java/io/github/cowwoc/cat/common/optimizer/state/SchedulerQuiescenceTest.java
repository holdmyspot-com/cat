package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.testng.annotations.Test;

/** Tests purpose-bound durable scheduler quiescence. */
public final class SchedulerQuiescenceTest
{
	/** Creates the test fixture. */
	public SchedulerQuiescenceTest()
	{
	}

	/**
	 * A barrier blocks new starts and retains one verified proof after restart.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void retainsProofAndBlocksNewClaim() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("scheduler-quiescence-")).
				resolve("campaign-campaign");
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation-1");
			inbox.publishHostObservation(binding, "invocation-1", registration,
				tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(new Observation(1,
					"9".repeat(64), "session-9", "turn-9")));
			try (SchedulerLease lease = ProspectiveCampaignState.lease(store).acquire(binding, "invocation-1"))
			{
				lease.beginQuiescence(QuiescencePurpose.CONTRACT_REVISION, 1);
				EvidenceRef request = store.appendImmutableJson("run-requests",
					"{\"logicalCallId\":\"blocked\"}".getBytes(StandardCharsets.UTF_8));
				try
				{
					ProspectiveCampaignState.claim(lease, "blocked", request);
					throw new AssertionError("Barrier must block an ordinary claim");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "barrierClaim").contains("Quiescence barrier");
				}
			}
			try (SchedulerLease lease = ProspectiveCampaignState.lease(new CampaignStore(directory)).acquire(binding,
				"invocation-1"))
			{
				requireThat(lease.fence(), "recoveryFence").isEqualTo(2L);
				EvidenceRef proof = lease.proveQuiescent();
				requireThat(lease.proveQuiescent(), "idempotentProof").isEqualTo(proof);
				requireThat(new CampaignStore(directory).load().scheduler().barrier().orElseThrow().proofRef().
					orElseThrow(), "restartedProof").isEqualTo(proof);
				tools.jackson.databind.json.JsonMapper json = tools.jackson.databind.json.JsonMapper.builder().build();
				SchedulerQuiescenceProof original = json.readValue(store.readImmutableJson(proof),
					SchedulerQuiescenceProof.class);
				EvidenceRef foreign = store.appendImmutableJson("scheduler-quiescence-proofs",
					json.writeValueAsBytes(new SchedulerQuiescenceProof(1, binding, original.purpose(),
						original.predecessorCounter(), original.schedulerFence(), "foreign-lineage",
						original.phase(), original.callSlotRootRef(), Instant.now())));
				store.updateScheduler(binding, campaign ->
				{
					SchedulerBarrier current = campaign.scheduler().barrier().orElseThrow();
					SchedulerBarrier retargeted = new SchedulerBarrier(current.purpose(),
						current.predecessorCounter(), current.schedulerFence(), current.lineage(), current.phase(),
						Optional.of(foreign));
					return new SchedulerState(campaign.scheduler().fence(), campaign.scheduler().invocations(),
						campaign.scheduler().stops(), campaign.scheduler().slots(), Optional.of(retargeted));
				});
				try
				{
					lease.proveQuiescent();
					throw new AssertionError("Foreign proof must not be adopted");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "foreignProof").contains("proof");
				}
			}
		}
	}

	/**
	 * Active and settled-but-unadopted slots cannot be hidden by a quiescence proof.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void requiresEverySlotAdopted() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("scheduler-slot-proof-"));
			CampaignStore store = new CampaignStore(workflowRoot.resolve("campaign-campaign"));
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "e".repeat(64));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(
					new Observation(1, "f".repeat(64), "session-f", "turn-f")));
			byte[] evidence = "{}".getBytes(StandardCharsets.UTF_8);
			EvidenceRef request = store.appendImmutableJson("run-requests",
				"{\"logicalCallId\":\"call\"}".getBytes(StandardCharsets.UTF_8));
			EvidenceRef completion = store.appendImmutableJson("native-run-completions", evidence);
			EvidenceRef producer = store.appendImmutableJson("native-adoptions", evidence);
			try (SchedulerLease lease = ProspectiveCampaignState.lease(store).acquire(binding, "invocation"))
			{
				ProspectiveCampaignState.claim(lease, "call", request);
				ProspectiveCampaignState.prepareStart(store, lease, "call");
				lease.activate("call", ProspectiveCampaignState.nativeRunId("call"));
				lease.beginQuiescence(QuiescencePurpose.CERTIFICATION_FINALIZATION, 1);
				assertNotQuiescent(lease);
				ProspectiveCampaignState.completedSlot(store, "call", completion, Optional.empty());
				assertNotQuiescent(lease);
				CallSlot settled = store.readCallSlotClaim(binding, "call").slot();
				CallAdoptionRecord envelope = new CallAdoptionRecord(1, binding.campaignId(), "call", request,
					settled.slotClaimRef(), completion, producer);
				EvidenceRef adoption = store.appendImmutableJson("call-adoptions",
					tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(envelope));
				ProspectiveCampaignState.completedSlot(store, "call", completion, Optional.of(adoption));
				requireThat(lease.proveQuiescent().collection(), "proofCollection").
					isEqualTo("scheduler-quiescence-proofs");
			}
		}
	}

	/**
	 * Requires the proof operation to reject an unreconciled slot.
	 *
	 * @param lease live scheduler lease
	 * @throws IOException if an unexpected I/O failure occurs
	 */
	private static void assertNotQuiescent(SchedulerLease lease) throws IOException
	{
		try
		{
			lease.proveQuiescent();
			throw new AssertionError("Unadopted slot must block quiescence");
		}
		catch (IOException expected)
		{
			requireThat(expected.getMessage(), "unadoptedSlot").contains("pending work");
		}
	}

	private record Observation(int schemaVersion, String tokenDigest, String hostSessionId, String hostTurnId)
	{
	}
}
