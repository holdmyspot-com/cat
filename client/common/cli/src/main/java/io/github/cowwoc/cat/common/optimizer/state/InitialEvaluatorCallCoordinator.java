package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.runner.ActiveCall;
import io.github.cowwoc.cat.common.optimizer.runner.NativeCallReconciliation;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionCodec;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.NestedAgent;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunCompletion;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunException;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Starts one claimed evaluator through its indexed native call authority. */
public final class InitialEvaluatorCallCoordinator
{
	private final CampaignLocator locator;
	private final CampaignStore store;

	/**
	 * Binds evaluator execution to a registered campaign.
	 *
	 * @param locator owning campaign locator
	 * @param store pinned campaign store
	 */
	public InitialEvaluatorCallCoordinator(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Starts one admitted evaluator under its retained run identity.
	 *
	 * @param lease exclusive scheduler owner
	 * @param assignmentRef adopted trial assignment
	 * @param agent native agent boundary
	 * @param manager native run identity source
	 * @param artifact selected packaged runner
	 * @return caller-owned native call
	 * @throws IOException if campaign or claim authority is invalid
	 * @throws NestedRunException if native start fails
	 */
	public ActiveCall startClaimed(SchedulerLease lease, EvidenceRef assignmentRef, NestedAgent agent,
		HarnessRunManager manager, Path artifact) throws IOException, NestedRunException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(assignmentRef, "assignmentRef");
		Objects.requireNonNull(agent, "agent");
		Objects.requireNonNull(manager, "manager");
		Objects.requireNonNull(artifact, "artifact");
		lease.requireLive();
		if (!locator.requireRegisteredBinding(lease.binding()).directory().equals(store.directory()))
			throw new IOException("Evaluator start belongs to another campaign store");
		EvaluatorTransportAttemptService attempts = new EvaluatorTransportAttemptService(locator, store);
		EvaluatorTransportAttemptService.PreparedStart prepared =
			attempts.prepareStart(lease, assignmentRef, manager, artifact);
		InitialEvaluatorRunRequest request = prepared.request();
		EvidenceRef attemptRef = prepared.attemptRef();
		CallPreStartRecord preStart = store.readCallPreStartRecord(lease.binding(),
			request.logicalCallId()).orElseThrow(() -> new IOException("Evaluator pre-start is missing"));
		CallSlot starting = locator.openSchedulerLease(lease.binding()).prepareCallStart(lease,
			request.logicalCallId(), ProducerAttemptKind.EVALUATOR, attemptRef,
			preStart.ownerRequestRef(), manager, artifact);
		if (starting.cancellationRequested())
			throw new IOException("Evaluator start was cancelled before native dispatch");
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
	 * Recovers the original native evaluator without dispatching another call.
	 *
	 * @param lease current scheduler owner
	 * @param assignmentRef adopted trial assignment
	 * @param agent native reconciliation boundary
	 * @return settled, active, or unknown native state
	 * @throws IOException if retained evaluator authority is invalid
	 */
	public InitialEvaluatorCallReconciliation reconcile(SchedulerLease lease, EvidenceRef assignmentRef,
		NestedAgent agent) throws IOException
	{
		Objects.requireNonNull(agent, "agent");
		InitialEvaluatorCompletionService completions = new InitialEvaluatorCompletionService(locator, store);
		InitialEvaluatorCompletionService.RetainedEvaluatorCall call = completions.load(lease, assignmentRef);
		String callId = call.request().logicalCallId();
		Optional<EvidenceRef> indexed = store.readNativeCompletionIndex(lease.binding(), callId);
		if (indexed.isPresent())
			return settled(lease, assignmentRef, indexed.orElseThrow(), completions);
		CallSlot slot = call.claimed().slot();
		if (slot.state() != CallSlot.State.STARTING && slot.state() != CallSlot.State.ACTIVE)
			throw new IOException("Evaluator has no started call or indexed native completion");
		NativeCallReconciliation nativeState = agent.reconcile(call.request().asNested(),
			Optional.of(call.preStart().runId()));
		if (nativeState instanceof NativeCallReconciliation.Settled nativeSettled)
		{
			EvidenceRef completionRef = completions.publishCompletion(lease, assignmentRef,
				nativeSettled.completion());
			return settled(lease, assignmentRef, completionRef, completions);
		}
		if (nativeState instanceof NativeCallReconciliation.Active nativeActive)
		{
			ActiveCall recovered = nativeActive.call();
			try
			{
				if (!recovered.runId().equals(call.preStart().runId()))
					throw new IOException("Recovered evaluator run differs from the retained pre-start");
				if (slot.state() == CallSlot.State.STARTING)
				{
					if (slot.fence() == lease.fence())
						lease.activate(callId, recovered.runId());
					else if (slot.fence() > lease.fence())
						throw new IOException("Retained evaluator start belongs to a newer scheduler owner");
				}
				else if (!slot.runId().equals(Optional.of(recovered.runId())))
					throw new IOException("Retained evaluator active slot differs from the native run");
				if (store.readCallSlotClaim(lease.binding(), callId).slot().cancellationRequested())
					recovered.interrupt();
				return new InitialEvaluatorCallReconciliation.Active(recovered);
			}
			catch (IOException | RuntimeException failure)
			{
				NativeCallHandleCleanup.interruptAndClose(recovered, failure);
				throw failure;
			}
		}
		NativeCallReconciliation.Unknown unknown = (NativeCallReconciliation.Unknown) nativeState;
		return new InitialEvaluatorCallReconciliation.Unknown(unknown.failure());
	}

	/**
	 * Adopts an indexed evaluator completion and returns its typed native result.
	 *
	 * @param lease current scheduler owner
	 * @param assignmentRef original trial assignment
	 * @param completionRef indexed native completion
	 * @param completions evaluator completion producer
	 * @return adopted native settlement
	 * @throws IOException if the completion is missing or conflicts
	 */
	private InitialEvaluatorCallReconciliation.Settled settled(SchedulerLease lease,
		EvidenceRef assignmentRef, EvidenceRef completionRef, InitialEvaluatorCompletionService completions)
		throws IOException
	{
		EvidenceRef executionRef = completions.adoptIndexed(lease, assignmentRef);
		NativeRunCompletionEvidence evidence = NativeRunCompletionCodec.decode(completionRef,
			store::readImmutableEvidence);
		NestedRunCompletion completion;
		if (evidence.outcome() == NativeRunCompletionEvidence.Outcome.SUCCEEDED)
			completion = new NestedRunCompletion.Succeeded(evidence.result().orElseThrow());
		else
			completion = new NestedRunCompletion.Failed(evidence.failure().orElseThrow());
		return new InitialEvaluatorCallReconciliation.Settled(executionRef, completion);
	}
}
