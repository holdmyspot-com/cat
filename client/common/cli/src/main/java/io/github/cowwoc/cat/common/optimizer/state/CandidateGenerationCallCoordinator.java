package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.runner.ActiveCall;
import io.github.cowwoc.cat.common.optimizer.runner.NestedAgent;
import io.github.cowwoc.cat.common.optimizer.runner.NativeCallReconciliation;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunException;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Starts an admitted candidate generation call in its reserved private worktree.
 */
public final class CandidateGenerationCallCoordinator
{
	private final CampaignLocator locator;
	private final CampaignStore store;

	/**
	 * Binds candidate execution to a registered campaign.
	 *
	 * @param locator owning campaign locator
	 * @param store pinned campaign store
	 */
	public CandidateGenerationCallCoordinator(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Starts one claimed generation request under its indexed pre-start identity.
	 *
	 * @param lease exclusive scheduler owner
	 * @param semanticRequestRef indexed private generation request
	 * @param agent native agent boundary receiving the private-worktree context
	 * @param manager native run identity source
	 * @param artifact selected packaged runner
	 * @return caller-owned native call
	 * @throws IOException if campaign, request, workspace, or scheduler authority is invalid
	 * @throws NestedRunException if native start fails
	 */
	public ActiveCall startClaimed(SchedulerLease lease, EvidenceRef semanticRequestRef, NestedAgent agent,
		HarnessRunManager manager, Path artifact) throws IOException, NestedRunException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(semanticRequestRef, "semanticRequestRef");
		Objects.requireNonNull(agent, "agent");
		Objects.requireNonNull(manager, "manager");
		Objects.requireNonNull(artifact, "artifact");
		lease.requireLive();
		if (!locator.requireRegisteredBinding(lease.binding()).directory().equals(store.directory()))
			throw new IOException("Candidate generation start belongs to another campaign store");
		CandidateTransportAttemptService attempts = new CandidateTransportAttemptService(locator, store);
		CandidateTransportAttemptService.PreparedStart prepared = attempts.prepareStart(lease,
			semanticRequestRef, manager, artifact);
		EvidenceRef attemptRef = prepared.attemptRef();
		CandidateGenerationRunRequest request = prepared.request();
		CallPreStartRecord preStart = store.readCallPreStartRecord(lease.binding(), request.logicalCallId()).
			orElseThrow(() -> new IOException("Candidate generation pre-start is missing"));
		CallSlot starting = locator.openSchedulerLease(lease.binding()).prepareCallStart(lease,
			request.logicalCallId(), ProducerAttemptKind.CANDIDATE, attemptRef,
			preStart.ownerRequestRef(), manager, artifact);
		if (starting.cancellationRequested())
			throw new IOException("Candidate generation start was cancelled before native dispatch");

		ActiveCall call = agent.start(request.asNested(), preStart.runId());
		try
		{
			lease.activate(request.logicalCallId(), call.runId());
			return call;
		}
		catch (IOException | RuntimeException failure)
		{
			NativeCallHandleCleanup.interruptAndClose(call, failure);
			throw failure;
		}
	}

	/**
	 * Recovers the retained native generation call without dispatching another call.
	 * Authored private files are preserved. An active result transfers handle ownership to the caller.
	 *
	 * @param lease current scheduler owner
	 * @param semanticRequestRef original indexed generation request
	 * @param agent native reconciliation boundary
	 * @return caller-owned active handle, adopted native settlement, or unresolved native state
	 * @throws IOException if retained campaign, request, claim, or start authority is invalid
	 */
	public CandidateGenerationCallReconciliation reconcile(SchedulerLease lease, EvidenceRef semanticRequestRef,
		NestedAgent agent) throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(semanticRequestRef, "semanticRequestRef");
		Objects.requireNonNull(agent, "agent");
		CandidateGenerationCompletionService completions = new CandidateGenerationCompletionService(locator, store);
		CandidateGenerationCompletionService.RetainedGenerationCall call = completions.load(lease, semanticRequestRef);
		String callId = call.semantic().logicalCallId();
		if (store.readNativeCompletionIndex(lease.binding(), callId).isPresent())
			return completions.adoptRetainedCompletion(lease, call);
		boolean started = switch (call.claimed().slot().state())
		{
			case STARTING, ACTIVE -> true;
			default -> false;
		};
		if (!started)
			throw new IOException("Candidate generation has no starting or active native call");

		NativeCallReconciliation nativeState = agent.reconcile(call.request().asNested(),
			Optional.of(call.preStart().runId()));
		if (nativeState instanceof NativeCallReconciliation.Settled settled)
		{
			completions.publishRetainedCompletion(lease, call, settled.completion());
			return completions.adoptRetainedCompletion(lease, call);
		}
		if (nativeState instanceof NativeCallReconciliation.Active active)
		{
			RetainedNativeCallRecovery.acceptActiveCall(lease, store, call.claimed(), call.preStart(), active.call());
			return new CandidateGenerationCallReconciliation.Active(active.call());
		}
		NativeCallReconciliation.Unknown unknown = (NativeCallReconciliation.Unknown) nativeState;
		return new CandidateGenerationCallReconciliation.Unknown(unknown.failure());
	}
}
