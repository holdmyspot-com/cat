package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests durable exact-invocation registration and token stop boundaries.
 */
public final class CampaignStopInboxTest
{
	/**
	 * Creates the test fixture.
	 */
	public CampaignStopInboxTest()
	{
	}

	/**
	 * A prelease stop remains tied to the original open invocation after restart and closes with it.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void retainsPreleaseStopAcrossRestart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-stop-inbox-")).
				resolve("campaign-campaign");
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation-1");
			requireThat(inbox.listOpenInvocations(binding), "openBeforeStop").isEqualTo(List.of(registration));
			StopRequest stop = inbox.submit(binding, "invocation-1", "token");
			requireThat(stop.sourceFence().isEmpty(), "preleaseFenceAbsent").isTrue();
			requireThat(ProspectiveCampaignState.inbox(new CampaignStore(directory)).submit(binding, "invocation-1",
				"host").stopRef(), "oneLogicalStop").isEqualTo(stop.stopRef());
			requireThat(new CampaignStore(directory).load().scheduler().stops().getFirst().reason(), "firstReason").
				isEqualTo("token");
			try
			{
				inbox.finishInvocation(binding, "invocation-1");
				throw new AssertionError("Pending stop must not be hidden by invocation closure");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "pendingClosure").contains("pending stop");
			}
			EvidenceRef second = inbox.beginInvocation(binding, "invocation-2");
			inbox.finishInvocation(binding, "invocation-2");
			requireThat(ProspectiveCampaignState.inbox(new CampaignStore(directory)).listOpenInvocations(binding),
				"stillOpenFirstInvocation").isEqualTo(List.of(registration));
			try
			{
				inbox.submit(binding, "invocation-2", "late");
				throw new AssertionError("A closed invocation must reject a stale stop");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "closedStop").contains("Open invocation");
			}
			requireThat(second.equals(registration), "distinctRegistrations").isFalse();
		}
	}

	/**
	 * A registration without observation cannot lease, while a prelease token stop binds to the first fence.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void pendingStopBindsBeforeLeaseReturns() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-stop-lease-")).
				resolve("campaign-campaign");
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			inbox.beginInvocation(binding, "invocation-1");
			CampaignSchedulerLease owner = ProspectiveCampaignState.lease(store);
			try
			{
				owner.acquire(binding, "invocation-1");
				throw new AssertionError("Unobserved invocation must not lease");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "unobservedLease").contains("no host observation");
			}
			inbox.submit(binding, "invocation-1", "token");
			try (SchedulerLease lease = owner.acquire(binding, "invocation-1"))
			{
				requireThat(lease.fence(), "firstFence").isEqualTo(1L);
				requireThat(store.load().scheduler().stops().getFirst().sourceFence().orElseThrow(),
					"boundStopFence").isEqualTo(1L);
				StopRequest adopted = inbox.adoptPending(lease).orElseThrow();
				requireThat(adopted.adoptingFence().orElseThrow(), "adoptingFence").isEqualTo(1L);
				requireThat(inbox.adoptPending(lease).orElseThrow().stopRef(), "idempotentAdoption").
					isEqualTo(adopted.stopRef());
				try
				{
					ProspectiveCampaignState.claim(lease, "blocked", store.appendImmutableJson("run-requests",
						"{\"logicalCallId\":\"blocked\"}".getBytes(StandardCharsets.UTF_8)));
					throw new AssertionError("Pending stop must block a new call");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "stoppedClaim").contains("Pending stop");
				}
			}
			requireThat(new CampaignStore(directory).load().scheduler().fence(), "restartedFence").isEqualTo(1L);
		}
	}

	/**
	 * A successor fence adopts a predecessor's retained stop without accepting a stale submit.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void successorAdoptsPredecessorStop() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("successor-stop-"));
			CampaignStore store = new CampaignStore(workflowRoot.resolve("campaign-campaign"));
			ProspectiveCampaignState.initialize(store, "campaign");
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			JsonMapper json = JsonMapper.builder().build();
			EvidenceRef first = inbox.beginInvocation(binding, "first");
			inbox.publishHostObservation(binding, "first", first, json.writeValueAsBytes(
				new Observation(1, "1".repeat(64), "session-1", "turn-1")));
			StopRequest original;
			try (SchedulerLease predecessor = ProspectiveCampaignState.lease(store).acquire(binding, "first"))
			{
				requireThat(predecessor.fence(), "predecessorFence").isEqualTo(1L);
				original = inbox.submit(binding, "first", "token");
				requireThat(original.sourceFence().orElseThrow(), "sourceFence").isEqualTo(1L);
			}
			EvidenceRef second = inbox.beginInvocation(binding, "second");
			inbox.publishHostObservation(binding, "second", second, json.writeValueAsBytes(
				new Observation(1, "2".repeat(64), "session-2", "turn-2")));
			try (SchedulerLease successor = ProspectiveCampaignState.lease(new CampaignStore(store.directory())).
				acquire(binding, "second"))
			{
				StopRequest adopted = inbox.adoptPending(successor).orElseThrow();
				requireThat(adopted.stopRef(), "sameStop").isEqualTo(original.stopRef());
				requireThat(adopted.sourceFence().orElseThrow(), "retainedSourceFence").isEqualTo(1L);
				requireThat(adopted.adoptingFence().orElseThrow(), "successorFence").isEqualTo(2L);
				try
				{
					inbox.submit(binding, "first", "stale host callback");
					throw new AssertionError("Predecessor callback must not submit against successor fence");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "staleCallback").contains("stale owner fence");
				}
			}
		}
	}

	/**
	 * A second JVM commits a stop promptly while the first JVM holds the scheduler owner lock.
	 *
	 * @throws Exception if the child JVM cannot start or its stop fails
	 */
	@Test
	public void childStopBypassesOwnerLock() throws Exception
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("cross-process-stop-"));
			CampaignStore store = new CampaignStore(workflowRoot.resolve("campaign-campaign"));
			ProspectiveCampaignState.initialize(store, "campaign");
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				JsonMapper.builder().build().writeValueAsBytes(new Observation(1, "3".repeat(64),
					"session-3", "turn-3")));
			try (SchedulerLease owner = ProspectiveCampaignState.lease(store).acquire(binding, "invocation"))
			{
				ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin",
					"java").toString(), "--module-path", System.getProperty("jdk.module.path"), "--patch-module",
					"io.github.cowwoc.cat.common.cli=" + Path.of("target/test-classes").toAbsolutePath().normalize(),
					"--module", "io.github.cowwoc.cat.common.cli/" + CampaignStopProbe.class.getName(),
					store.directory().toString(), "invocation").redirectErrorStream(true);
				try (Process child = builder.start())
				{
					if (!child.waitFor(5, TimeUnit.SECONDS))
						throw new AssertionError("Child stop waited on the scheduler owner lock");
					String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
					if (child.exitValue() != 0)
						throw new AssertionError("Child stop failed: " + output);
					requireThat(output.trim(), "childStopRef").
						isEqualTo(store.load().scheduler().stops().getFirst().stopRef().evidenceId());
					requireThat(owner.fence(), "stillLiveOwner").isEqualTo(1L);
				}
			}
		}
	}

	/**
	 * A same-process waiter receives the next fence and only its own pre-lease stop after owner release.
	 *
	 * @throws Exception if the concurrent fixture fails
	 */
	@Test
	public void waitingInvocationBindsOwnStop() throws Exception
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ExecutorService executor = Executors.newSingleThreadExecutor())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("waiting-invocation-"));
			CampaignStore store = new CampaignStore(workflowRoot.resolve("campaign-campaign"));
			ProspectiveCampaignState.initialize(store, "campaign");
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			JsonMapper json = JsonMapper.builder().build();
			EvidenceRef first = inbox.beginInvocation(binding, "first");
			inbox.publishHostObservation(binding, "first", first, json.writeValueAsBytes(
				new Observation(1, "4".repeat(64), "session-4", "turn-4")));
			EvidenceRef second = inbox.beginInvocation(binding, "second");
			inbox.publishHostObservation(binding, "second", second, json.writeValueAsBytes(
				new Observation(1, "5".repeat(64), "session-5", "turn-5")));
			CountDownLatch entered = new CountDownLatch(1);
			Future<SchedulerLease> waiting;
			try (SchedulerLease owner = ProspectiveCampaignState.lease(store).acquire(binding, "first"))
			{
				waiting = executor.submit(() ->
				{
					entered.countDown();
					return ProspectiveCampaignState.lease(new CampaignStore(store.directory())).acquire(binding,
						"second");
				});
				if (!entered.await(5, TimeUnit.SECONDS))
					throw new AssertionError("Waiting invocation did not begin");
				StopRequest stopped = inbox.submit(binding, "second", "token");
				requireThat(stopped.sourceFence().isEmpty(), "waitingStopIsPrelease").isTrue();
				requireThat(waiting.isDone(), "waitsForOwnerRelease").isFalse();
				requireThat(owner.fence(), "firstOwnerUnchanged").isEqualTo(1L);
			}
			try (SchedulerLease successor = waiting.get(5, TimeUnit.SECONDS))
			{
				requireThat(successor.fence(), "successorFence").isEqualTo(2L);
				requireThat(store.load().scheduler().stops().getFirst().sourceFence().orElseThrow(),
					"boundWaitingStop").isEqualTo(2L);
			}
		}
	}

	/**
	 * A no-call stop from a prior campaign counter remains historical after later state advances.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void stalePreleaseStopIsNotAdopted() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("stale-early-stop-"));
			CampaignStore store = new CampaignStore(workflowRoot.resolve("campaign-campaign"));
			ProspectiveCampaignState.initialize(store, "campaign");
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			inbox.beginInvocation(binding, "old-invocation");
			StopRequest old = inbox.submit(binding, "old-invocation", "token");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			try (SchedulerLease lease = ProspectiveCampaignState.lease(store).acquire(binding, "old-invocation"))
			{
				requireThat(inbox.adoptPending(lease).isEmpty(), "staleStopNotAdopted").isTrue();
				requireThat(store.load().scheduler().stops().getFirst().stopRef(), "historicalStop").
					isEqualTo(old.stopRef());
			}
			EvidenceRef fresh = inbox.beginInvocation(binding, "fresh-invocation");
			inbox.publishHostObservation(binding, "fresh-invocation", fresh,
				JsonMapper.builder().build().writeValueAsBytes(new Observation(1, "6".repeat(64),
					"session-6", "turn-6")));
			try (SchedulerLease lease = ProspectiveCampaignState.lease(store).acquire(binding, "fresh-invocation"))
			{
				EvidenceRef request = store.appendImmutableJson("run-requests",
					"{\"logicalCallId\":\"fresh-call\"}".getBytes(StandardCharsets.UTF_8));
				requireThat(ProspectiveCampaignState.claim(lease, "fresh-call", request).fence(),
					"unblockedFreshClaim").isEqualTo(2L);
			}
		}
	}

	/**
	 * A cancelled campaign cannot create a new invocation or accept a late stop.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void cancelledCampaignRejectsNewRegistration() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cancelled-inbox-")).
				resolve("campaign-campaign");
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			inbox.beginInvocation(binding, "existing");
			ProspectiveCampaignState.withStatus(store, CampaignStatus.CANCELLED);
			try
			{
				inbox.beginInvocation(binding, "late");
				throw new AssertionError("Cancelled campaign must reject a new invocation");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "cancelledBegin").contains("Cancelled");
			}
			try
			{
				inbox.submit(binding, "existing", "late token");
				throw new AssertionError("Cancelled campaign must reject a late stop");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "cancelledSubmit").contains("Cancelled");
			}
			requireThat(store.load().scheduler().stops().isEmpty(), "noLateStop").isTrue();
		}
	}

	/**
	 * A registration left OPEN before terminal closure cannot reacquire scheduling authority.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void terminalCampaignRejectsLease() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("terminal-inbox-"));
			CampaignStore store = new CampaignStore(workflowRoot.resolve("campaign-campaign"));
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "e".repeat(64));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				JsonMapper.builder().build().writeValueAsBytes(new Observation(1, "a".repeat(64),
					"terminal-session", "terminal-turn")));
			ProspectiveCampaignState.withStatus(store, CampaignStatus.CERTIFIED);
			try
			{
				ProspectiveCampaignState.lease(store).acquire(binding, "invocation");
				throw new AssertionError("Terminal campaign must not lease");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "terminalLease").contains("terminal");
			}
			requireThat(store.load().scheduler().fence(), "noTerminalFence").isEqualTo(0L);
		}
	}

	private record Observation(int schemaVersion, String tokenDigest, String hostSessionId, String hostTurnId)
	{
	}
}
