package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/** Tests atomic host-observation registration and one-use global association. */
public final class CampaignHostObservationTest
{
	private final JsonMapper json = JsonMapper.builder().build();

	/** Creates the test fixture. */
	public CampaignHostObservationTest()
	{
	}

	/**
	 * A nested campaign cannot select a second global host-observation root.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void refusesNestedHostObservationRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("host-nested-root-").toRealPath());
			Path nested = Files.createDirectory(workflowRoot.resolve("nested")).resolve("campaign-a");
			CampaignStore store = new CampaignStore(nested);
			ProspectiveCampaignState.initialize(store, "a");
			try
			{
				new CampaignLocator(workflowRoot).openStopInbox(store.binding());
				throw new AssertionError("Nested campaign must not open a stop inbox");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "nestedRoot").contains("direct child");
			}
			requireThat(Files.exists(workflowRoot.resolve("host-observation-index.json")), "noHostIndex").isFalse();
			requireThat(store.load().scheduler().invocations().isEmpty(), "noInvocation").isTrue();
		}
	}

	/**
	 * A correctly named but unregistered campaign cannot publish through this workflow root.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void refusesUnregisteredHostObservation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("host-unregistered-").toRealPath());
			CampaignStore store = new CampaignStore(workflowRoot.resolve("campaign-a"));
			ProspectiveCampaignState.initialize(store, "a");
			try
			{
				new CampaignLocator(workflowRoot).openStopInbox(store.binding());
				throw new AssertionError("Unregistered campaign must not open a stop inbox");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "missingRegistration").contains("registration");
			}
			requireThat(Files.exists(workflowRoot.resolve("host-observation-index.json")), "noHostIndex").isFalse();
			requireThat(store.load().scheduler().invocations().isEmpty(), "noInvocation").isTrue();
		}
	}

	/**
	 * A campaign with the wrong direct-child name cannot mint a separate stop authority.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void refusesMisnamedHostObservationRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("host-misnamed-").toRealPath());
			CampaignStore store = new CampaignStore(workflowRoot.resolve("other-a"));
			ProspectiveCampaignState.initialize(store, "a");
			try
			{
				new CampaignLocator(workflowRoot).openSchedulerLease(store.binding());
				throw new AssertionError("Misnamed campaign must not open a scheduler lease");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "misnamedRoot").contains("direct child");
			}
			requireThat(Files.exists(workflowRoot.resolve("host-observation-index.json")), "noHostIndex").isFalse();
			requireThat(store.load().scheduler().fence(), "noLease").isEqualTo(0L);
		}
	}

	/**
	 * An alias selected as the workflow root cannot retarget a registered campaign's host index.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void refusesRetargetedWorkflowRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("host-root-alias-").toRealPath());
			CampaignStore store = new CampaignStore(workflowRoot.resolve("campaign-a"));
			ProspectiveCampaignState.initialize(store, "a");
			ProspectiveCampaignState.register(store);
			Path alias = workflowRoot.resolve("alias");
			Files.createSymbolicLink(alias, workflowRoot);
			try
			{
				new CampaignLocator(alias).openStopInbox(store.binding());
				throw new AssertionError("Redirected workflow root must not open a stop inbox");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "retargetedRoot").contains("canonical");
			}
			requireThat(Files.exists(workflowRoot.resolve("host-observation-index.json")), "noHostIndex").isFalse();
			requireThat(store.load().scheduler().invocations().isEmpty(), "noInvocation").isTrue();
		}
	}

	/**
	 * A foreign binding cannot reopen a registered campaign's inbox or lease.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void refusesForeignRegisteredBinding() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("host-foreign-").toRealPath());
			CampaignStore store = new CampaignStore(workflowRoot.resolve("campaign-a"));
			ProspectiveCampaignState.initialize(store, "a");
			CampaignLocator locator = ProspectiveCampaignState.register(store);
			CampaignBinding foreign = new CampaignBinding("b", store.directory());
			try
			{
				locator.openStopInbox(foreign);
				throw new AssertionError("Foreign inbox binding must fail");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "foreignInbox").contains("direct child");
			}
			try
			{
				locator.openSchedulerLease(foreign);
				throw new AssertionError("Foreign lease binding must fail");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "foreignLease").contains("direct child");
			}
			requireThat(Files.exists(workflowRoot.resolve("host-observation-index.json")), "noHostIndex").isFalse();
		}
	}

	/**
	 * Equal publication survives restart while reused token, turn, and observation fail across campaigns.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void indexesOnlyOneOpenInvocation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("host-observation-registry-").toRealPath());
			CampaignStore first = new CampaignStore(workflowRoot.resolve("campaign-a"));
			ProspectiveCampaignState.initialize(first, "a");
			CampaignBinding firstBinding = first.binding();
			CampaignStopInbox firstInbox = ProspectiveCampaignState.inbox(first);
			EvidenceRef firstRegistration = firstInbox.beginInvocation(firstBinding, "invocation-a");
			byte[] observation = json.writeValueAsBytes(new Observation(1, "a".repeat(64), "session-1",
				"turn-1"));
			EvidenceRef reference = firstInbox.publishHostObservation(firstBinding, "invocation-a",
				firstRegistration, observation);
			requireThat(firstInbox.publishHostObservation(firstBinding, "invocation-a", firstRegistration,
				observation), "equalPublication").isEqualTo(reference);
			requireThat(ProspectiveCampaignState.inbox(new CampaignStore(first.directory())).findByHostObservation(
				firstBinding, reference).registrationRef(), "restartedAssociation").isEqualTo(firstRegistration);
			CampaignStore second = new CampaignStore(workflowRoot.resolve("campaign-b"));
			ProspectiveCampaignState.initialize(second, "b");
			CampaignBinding secondBinding = second.binding();
			CampaignStopInbox secondInbox = ProspectiveCampaignState.inbox(second);
			EvidenceRef secondRegistration = secondInbox.beginInvocation(secondBinding, "invocation-b");
			assertRejected(() -> secondInbox.publishHostObservation(secondBinding, "invocation-b",
				secondRegistration, observation), "already");
			byte[] sameTurn = json.writeValueAsBytes(new Observation(1, "b".repeat(64), "session-1",
				"turn-1"));
			assertRejected(() -> secondInbox.publishHostObservation(secondBinding, "invocation-b",
				secondRegistration, sameTurn), "already");
			requireThat(second.load().scheduler().invocations().getFirst().hostObservationRef().isEmpty(),
				"rejectedAssociationAbsent").isTrue();
			firstInbox.finishInvocation(firstBinding, "invocation-a");
			assertRejected(() -> firstInbox.findByHostObservation(firstBinding, reference), "OPEN");
			assertRejected(() -> firstInbox.publishHostObservation(firstBinding, "invocation-a",
				firstRegistration, observation), "OPEN");
		}
	}

	/**
	 * A host interrupt locates only the open invocation bound to its exact session and turn.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void findsOpenInvocationByHostTurn() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("host-turn-lookup-").toRealPath());
			CampaignStore store = new CampaignStore(workflowRoot.resolve("campaign-a"));
			ProspectiveCampaignState.initialize(store, "a");
			CampaignLocator locator = ProspectiveCampaignState.register(store);
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = locator.openStopInbox(binding);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation-a");
			inbox.publishHostObservation(binding, "invocation-a", registration,
				json.writeValueAsBytes(new Observation(1, "a".repeat(64), "session-1", "turn-1")));

			CampaignLocator restarted = new CampaignLocator(workflowRoot);
			requireThat(restarted.findOpenInvocationByHostTurn("session-1", "turn-1").orElseThrow().
				registrationRef(), "matchedRegistration").isEqualTo(registration);
			requireThat(restarted.findOpenInvocationByHostTurn("session-1", "turn-2"),
				"differentTurn").isEmpty();
			requireThat(restarted.findOpenInvocationByHostTurn("session-2", "turn-1"),
				"differentSession").isEmpty();

			inbox.finishInvocation(binding, "invocation-a");
			requireThat(restarted.findOpenInvocationByHostTurn("session-1", "turn-1"),
				"closedRegistration").isEmpty();
		}
	}

	/**
	 * A committed intent is rolled forward even when another campaign is first to inspect the global registry.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recoversAnotherCampaignsCommittedIntent() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("host-observation-intent-").toRealPath());
			CampaignStore first = new CampaignStore(workflowRoot.resolve("campaign-a"));
			ProspectiveCampaignState.initialize(first, "a");
			CampaignBinding firstBinding = first.binding();
			EvidenceRef registration = ProspectiveCampaignState.inbox(first).beginInvocation(firstBinding, "invocation-a");
			byte[] bytes = json.writeValueAsBytes(new Observation(1, "c".repeat(64), "session-a",
				"turn-a"));
			EvidenceRef observationRef = new EvidenceRef("host-interrupt-observations", digest(bytes));
			AssociationDoc association = new AssociationDoc(1, firstBinding, "invocation-a", registration,
				observationRef, "c".repeat(64), "session-a", "turn-a");
			Files.write(workflowRoot.resolve("host-observation-intent.json"), json.writeValueAsBytes(
				new IntentDoc(1, association, Base64.getEncoder().encodeToString(bytes))));
			CampaignStore second = new CampaignStore(workflowRoot.resolve("campaign-b"));
			ProspectiveCampaignState.initialize(second, "b");
			ProspectiveCampaignState.inbox(second).listOpenInvocations(second.binding());
			requireThat(ProspectiveCampaignState.inbox(first).findByHostObservation(firstBinding, observationRef).
				registrationRef(), "recoveredOriginalRegistration").isEqualTo(registration);
			requireThat(Files.notExists(workflowRoot.resolve("host-observation-intent.json")),
				"committedIntentCleared").isTrue();
		}
	}

	/**
	 * A corrupt committed intent blocks lease acquisition before any fence or registration mutation.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void malformedIntentBlocksLease() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("host-observation-corrupt-").toRealPath());
			CampaignStore store = new CampaignStore(workflowRoot.resolve("campaign-campaign"));
			ProspectiveCampaignState.initialize(store, "campaign");
			CampaignBinding binding = store.binding();
			ProspectiveCampaignState.inbox(store).beginInvocation(binding, "invocation");
			Files.write(workflowRoot.resolve("host-observation-intent.json"),
				"{\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8));
			try
			{
				ProspectiveCampaignState.lease(store).acquire(binding, "invocation");
				throw new AssertionError("Malformed committed intent must block a lease");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "malformedIntent").contains("intent");
			}
			requireThat(store.load().scheduler().fence(), "noLeaseFence").isEqualTo(0L);
			requireThat(store.load().scheduler().invocations().getFirst().hostObservationRef().isEmpty(),
				"unattachedObservation").isTrue();
		}
	}

	/**
	 * A published registration cannot lease if its global one-use index is missing.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void missingIndexBlocksLease() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("host-observation-missing-index-").toRealPath());
			CampaignStore store = new CampaignStore(workflowRoot.resolve("campaign-campaign"));
			ProspectiveCampaignState.initialize(store, "campaign");
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				json.writeValueAsBytes(new Observation(1, "7".repeat(64), "session-7", "turn-7")));
			Files.delete(workflowRoot.resolve("host-observation-index.json"));
			try
			{
				ProspectiveCampaignState.lease(store).acquire(binding, "invocation");
				throw new AssertionError("Unindexed observation must not authorize a lease");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "missingHostIndex").contains("not indexed");
			}
			requireThat(store.load().scheduler().fence(), "noFenceAfterIndexLoss").isEqualTo(0L);
		}
	}

	/**
	 * Concurrent same-JVM publishers adopt the same one-use observation instead of overlapping file locks.
	 *
	 * @throws Exception if the concurrent fixture fails
	 */
	@Test
	public void concurrentEqualPublishersConverge() throws Exception
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ExecutorService executor = Executors.newFixedThreadPool(2))
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("host-observation-race-").toRealPath());
			CampaignStore store = new CampaignStore(workflowRoot.resolve("campaign-campaign"));
			ProspectiveCampaignState.initialize(store, "campaign");
			CampaignBinding binding = store.binding();
			EvidenceRef registration = ProspectiveCampaignState.inbox(store).beginInvocation(binding, "invocation");
			byte[] observation = json.writeValueAsBytes(new Observation(1, "8".repeat(64),
				"session-8", "turn-8"));
			CountDownLatch start = new CountDownLatch(1);
			Future<EvidenceRef> first = executor.submit(() ->
			{
				start.await();
				return ProspectiveCampaignState.inbox(new CampaignStore(store.directory())).publishHostObservation(
					binding, "invocation", registration, observation);
			});
			Future<EvidenceRef> second = executor.submit(() ->
			{
				start.await();
				return ProspectiveCampaignState.inbox(new CampaignStore(store.directory())).publishHostObservation(
					binding, "invocation", registration, observation);
			});
			start.countDown();
			requireThat(first.get(5, TimeUnit.SECONDS), "convergedObservation").
				isEqualTo(second.get(5, TimeUnit.SECONDS));
			requireThat(store.load().scheduler().invocations().getFirst().hostObservationRef().isPresent(),
				"oneAttachedObservation").isTrue();
		}
	}

	/**
	 * Computes the exact content address of test observation bytes.
	 *
	 * @param bytes canonical observation bytes
	 * @return SHA-256 content identity
	 */
	private static String digest(byte[] bytes)
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError(e);
		}
	}

	/**
	 * Expects one publication to fail before it attaches an observation.
	 *
	 * @param publication candidate publication
	 * @param fragment expected rejection text
	 */
	private static void assertRejected(Publication publication, String fragment)
	{
		try
		{
			publication.run();
			throw new AssertionError("Reused host observation must fail");
		}
		catch (IOException expected)
		{
			requireThat(expected.getMessage(), "reusedObservation").contains(fragment);
		}
	}

	/** IO-capable publication action. */
	@FunctionalInterface
	private interface Publication
	{
		/**
		 * Runs the candidate publication.
		 *
		 * @throws IOException if it is rejected
		 */
		void run() throws IOException;
	}

	private record Observation(int schemaVersion, String tokenDigest, String hostSessionId, String hostTurnId)
	{
	}

	private record AssociationDoc(int schemaVersion, CampaignBinding binding, String invocationId,
		EvidenceRef registrationRef, EvidenceRef observationRef, String tokenDigest, String hostSessionId,
		String hostTurnId)
	{
	}

	private record IntentDoc(int schemaVersion, AssociationDoc association, String observationBase64)
	{
	}
}
