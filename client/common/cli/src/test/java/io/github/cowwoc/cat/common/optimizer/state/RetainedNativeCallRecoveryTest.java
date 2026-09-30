package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.SessionMode;
import io.github.cowwoc.cat.common.optimizer.runner.ActiveCall;
import io.github.cowwoc.cat.common.optimizer.runner.Adapter;
import io.github.cowwoc.cat.common.optimizer.runner.AdapterCall;
import io.github.cowwoc.cat.common.optimizer.runner.AdapterCallReconciliation;
import io.github.cowwoc.cat.common.optimizer.runner.NativeCallReconciliation;
import io.github.cowwoc.cat.common.optimizer.runner.NestedAgent;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunCompletion;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunException;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunRequest;
import io.github.cowwoc.cat.common.optimizer.runner.ProjectExecutionSnapshot;
import io.github.cowwoc.cat.common.optimizer.runner.ProjectRunContext;
import io.github.cowwoc.cat.common.optimizer.runner.ProjectWorkspaceMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link RetainedNativeCallRecovery} ownership and cancellation without semantic outcome processing.
 */
public final class RetainedNativeCallRecoveryTest
{
	/**
	 * Creates the test suite.
	 */
	public RetainedNativeCallRecoveryTest()
	{
	}

	/**
	 * The original scheduler owner records activation when its retained native call is still running.
	 *
	 * @throws IOException if fixture persistence or recovery fails
	 * @throws NestedRunException if handle cleanup fails
	 */
	@Test
	public void originalOwnerActivatesRecoveredCall() throws IOException, NestedRunException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CampaignStore store = createStore(cleanup.add(Files.createTempDirectory("native-recovery-")));
			try (SchedulerLease owner = acquire(store, "first", "a"))
			{
				prepare(store, owner);
				ClaimedSlot claimed = store.readCallSlotClaim(store.binding(), "call");
				CallPreStartRecord preStart = store.readCallPreStartRecord(store.binding(), "call").orElseThrow();
				PendingCall nativeCall = new PendingCall(preStart.runId());
				try (ActiveCall recovered = recover(store, nativeCall))
				{
					RetainedNativeCallRecovery.acceptActiveCall(owner, store, claimed, preStart, recovered);
					CallSlot active = store.readCallSlotClaim(store.binding(), "call").slot();
					requireThat(active.state(), "state").isEqualTo(CallSlot.State.ACTIVE);
					requireThat(active.fence(), "originalFence").isEqualTo(owner.fence());
					requireThat(active.runId(), "originalRun").isEqualTo(Optional.of(preStart.runId()));
					requireThat(nativeCall.interrupts, "interruptsBeforeRelease").isEqualTo(0);
					requireThat(nativeCall.closes, "closesBeforeRelease").isEqualTo(0);
				}
			}
		}
	}

	/**
	 * A successor recovers the original run without rewriting the predecessor's starting slot or claim.
	 *
	 * @throws IOException if fixture persistence or recovery fails
	 * @throws NestedRunException if handle cleanup fails
	 */
	@Test
	public void successorPreservesStartingCall() throws IOException, NestedRunException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CampaignStore store = createStore(cleanup.add(Files.createTempDirectory("native-recovery-")));
			try (SchedulerLease owner = acquire(store, "first", "a"))
			{
				prepare(store, owner);
			}
			CampaignStore reopened = CampaignStore.open(store.directory());
			ClaimedSlot original = reopened.readCallSlotClaim(reopened.binding(), "call");
			CallPreStartRecord preStart = reopened.readCallPreStartRecord(reopened.binding(), "call").orElseThrow();
			PendingCall nativeCall = new PendingCall(preStart.runId());
			try (SchedulerLease successor = acquire(reopened, "second", "b");
				ActiveCall recovered = recover(reopened, nativeCall))
			{
				requireThat(successor.fence(), "successorFence").isGreaterThan(original.slot().fence());
				RetainedNativeCallRecovery.acceptActiveCall(successor, reopened, original, preStart, recovered);
				requireThat(reopened.readCallSlotClaim(reopened.binding(), "call"), "unchangedClaim").
					isEqualTo(original);
				requireThat(recovered.runId(), "originalRun").isEqualTo(preStart.runId());
				requireThat(nativeCall.interrupts, "interruptsBeforeRelease").isEqualTo(0);
				requireThat(nativeCall.closes, "closesBeforeRelease").isEqualTo(0);
			}
		}
	}

	/**
	 * A successor interrupts a cancelled predecessor call while retaining its starting slot for settlement.
	 *
	 * @throws IOException if fixture persistence or recovery fails
	 * @throws NestedRunException if handle cleanup fails
	 */
	@Test
	public void successorHonorsStartingCancellation() throws IOException, NestedRunException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CampaignStore store = createStore(cleanup.add(Files.createTempDirectory("native-recovery-")));
			try (SchedulerLease owner = acquire(store, "first", "a"))
			{
				prepare(store, owner);
				owner.requestCancellation("call");
			}
			CampaignStore reopened = CampaignStore.open(store.directory());
			ClaimedSlot original = reopened.readCallSlotClaim(reopened.binding(), "call");
			CallPreStartRecord preStart = reopened.readCallPreStartRecord(reopened.binding(), "call").orElseThrow();
			PendingCall nativeCall = new PendingCall(preStart.runId());
			try (SchedulerLease successor = acquire(reopened, "second", "b");
				ActiveCall recovered = recover(reopened, nativeCall))
			{
				RetainedNativeCallRecovery.acceptActiveCall(successor, reopened, original, preStart, recovered);
				requireThat(reopened.readCallSlotClaim(reopened.binding(), "call"), "unchangedClaim").
					isEqualTo(original);
				requireThat(nativeCall.interrupts, "cancelledNativeCall").isEqualTo(1);
				requireThat(nativeCall.closes, "retainedHandle").isEqualTo(0);
			}
			requireThat(nativeCall.interrupts, "idempotentInterrupt").isEqualTo(1);
			requireThat(nativeCall.closes, "releasedHandle").isEqualTo(1);
		}
	}

	/**
	 * A foreign native run is interrupted and closed without changing the retained scheduler claim.
	 *
	 * @throws IOException if fixture persistence fails or the foreign run is rejected
	 * @throws NestedRunException if handle cleanup fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Recovered native run differs from the retained pre-start")
	public void rejectsAndReleasesForeignRun() throws IOException, NestedRunException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CampaignStore store = createStore(cleanup.add(Files.createTempDirectory("native-recovery-")));
			try (SchedulerLease owner = acquire(store, "first", "a"))
			{
				prepare(store, owner);
				ClaimedSlot original = store.readCallSlotClaim(store.binding(), "call");
				CallPreStartRecord preStart = store.readCallPreStartRecord(store.binding(), "call").orElseThrow();
				PendingCall nativeCall = new PendingCall("foreign-001");
				try (ActiveCall recovered = recover(store, nativeCall))
				{
					try
					{
						RetainedNativeCallRecovery.acceptActiveCall(owner, store, original, preStart, recovered);
					}
					finally
					{
						requireThat(nativeCall.interrupts, "rejectedInterrupt").isEqualTo(1);
						requireThat(nativeCall.closes, "rejectedRelease").isEqualTo(1);
						requireThat(store.readCallSlotClaim(store.binding(), "call"), "unchangedClaim").
							isEqualTo(original);
					}
				}
			}
		}
	}

	/**
	 * Seeds only the campaign and lineage required by scheduler mechanics.
	 *
	 * @param root owned temporary workflow directory
	 * @return prospective campaign store
	 * @throws IOException if fixture persistence fails
	 */
	private static CampaignStore createStore(Path root) throws IOException
	{
		CampaignStore store = new CampaignStore(root.resolve("campaign-campaign"));
		ProspectiveCampaignState.initialize(store, "campaign");
		ProspectiveCampaignState.addLineage(store, "d".repeat(64));
		return store;
	}

	/**
	 * Acquires a scheduler owner with a distinct observed host turn.
	 *
	 * @param store prospective campaign
	 * @param invocation invocation identity
	 * @param token host observation token seed
	 * @return caller-owned scheduler lease
	 * @throws IOException if registration or ownership fails
	 */
	private static SchedulerLease acquire(CampaignStore store, String invocation, String token) throws IOException
	{
		CampaignBinding binding = store.binding();
		CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
		EvidenceRef registration = inbox.beginInvocation(binding, invocation);
		inbox.publishHostObservation(binding, invocation, registration, JsonMapper.builder().build().writeValueAsBytes(
			new Observation(1, token.repeat(64), "session-" + token, "turn-" + token)));
		return ProspectiveCampaignState.lease(store).acquire(binding, invocation);
	}

	/**
	 * Retains one request, claim and pre-start before any native dispatch.
	 *
	 * @param store prospective campaign
	 * @param owner current scheduler lease
	 * @throws IOException if fixture persistence fails
	 */
	private static void prepare(CampaignStore store, SchedulerLease owner) throws IOException
	{
		EvidenceRef request = store.appendImmutableJson("run-requests",
			"{\"logicalCallId\":\"call\"}".getBytes(StandardCharsets.UTF_8));
		ProspectiveCampaignState.claim(owner, "call", request);
		ProspectiveCampaignState.prepareStart(store, owner, "call");
	}

	/**
	 * Wraps a pending adapter handle through the public native reconciliation boundary.
	 * The isolated project context supplies the wrapper's required shape; no project is copied or executed.
	 *
	 * @param store fixture project location
	 * @param nativeCall pending adapter handle
	 * @return caller-owned validated handle
	 */
	private static ActiveCall recover(CampaignStore store, PendingCall nativeCall)
	{
		ProjectExecutionSnapshot snapshot = new ProjectExecutionSnapshot(store.directory(),
			new EvidenceRef("snapshots", "a".repeat(64)));
		ProjectRunContext context = new ProjectRunContext(snapshot, ProjectWorkspaceMode.ISOLATED_COPY,
			Optional.empty(), Optional.empty());
		NestedRunRequest request = new NestedRunRequest("call", EvidencePurpose.DEVELOPMENT, context, List.of("turn"),
			SessionMode.SINGLE_TURN, ProspectiveCampaignState.profile(), List.of());
		NestedAgent agent = new NestedAgent(new RecoveringAdapter(nativeCall), ignored ->
		{
			throw new AssertionError("Handle recovery must not load semantic evidence");
		});
		return ((NativeCallReconciliation.Active) agent.reconcile(request, Optional.of(nativeCall.runId()))).call();
	}

	/**
	 * Supplies a distinct host observation for each scheduler invocation.
	 *
	 * @param schemaVersion observation schema
	 * @param tokenDigest one-use host token digest
	 * @param hostSessionId host session identity
	 * @param hostTurnId host turn identity
	 */
	private record Observation(int schemaVersion, String tokenDigest, String hostSessionId, String hostTurnId)
	{
	}

	/**
	 * Returns an existing handle and rejects attempts to dispatch another call.
	 *
	 * @param call existing adapter handle
	 */
	private record RecoveringAdapter(PendingCall call) implements Adapter
	{
		@Override
		public AdapterCall start(NestedRunRequest request, String runId)
		{
			throw new AssertionError("Recovery must not dispatch another native call");
		}

		@Override
		public AdapterCallReconciliation reconcile(NestedRunRequest request, Optional<String> knownRunId)
		{
			return new AdapterCallReconciliation.Active(call);
		}
	}

	/**
	 * Observes interruption and release of an unsettled native call.
	 */
	private static final class PendingCall implements AdapterCall
	{
		private final CompletableFuture<NestedRunCompletion> completion = new CompletableFuture<>();
		private final String runId;
		private int interrupts;
		private int closes;

		/**
		 * Creates a pending native call with the retained identity.
		 *
		 * @param runId native run identity
		 */
		private PendingCall(String runId)
		{
			this.runId = runId;
		}

		@Override
		public String runId()
		{
			return runId;
		}

		@Override
		public CompletionStage<NestedRunCompletion> completion()
		{
			return completion;
		}

		@Override
		public void interrupt()
		{
			interrupts = interrupts + 1;
		}

		@Override
		public void close()
		{
			closes = closes + 1;
		}
	}
}
