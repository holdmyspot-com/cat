package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.runner.ActiveCall;
import io.github.cowwoc.cat.common.optimizer.runner.NestedAgent;
import io.github.cowwoc.cat.common.optimizer.runner.NativeCallReconciliation;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionCodec;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunCompletion;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunException;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Moves the first contract inspection from indexed preparation through native start.
 */
public final class InspectionCallCoordinator
{
	private final CampaignLocator locator;
	private final CampaignStore store;

	/**
	 * Binds the inspection start sequence to one registered campaign.
	 *
	 * @param locator durable campaign locator
	 * @param store pinned campaign evidence store
	 */
	public InspectionCallCoordinator(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Starts the first inspection from its indexed source, owner, claim, and pre-start identity.
	 *
	 * @param lease exclusive campaign scheduler owner
	 * @param agent harness-neutral native agent boundary
	 * @param manager runner identity source
	 * @param artifact selected packaged runner artifact
	 * @return the retained inspection identity and caller-owned native handle
	 * @throws IOException if preparation or scheduler authority is invalid
	 * @throws NestedRunException if native start fails
	 */
	public StartedInspectionCall startFirst(SchedulerLease lease, NestedAgent agent, HarnessRunManager manager,
		Path artifact) throws IOException, NestedRunException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(agent, "agent");
		Objects.requireNonNull(manager, "manager");
		Objects.requireNonNull(artifact, "artifact");
		locator.requireRegisteredBinding(lease.binding());
		if (!store.directory().equals(lease.binding().realDirectory()))
			throw new IOException("Inspection lease belongs to another campaign store");
		PreparedInspectionCall prepared = new InspectionOwnerService(locator, store).prepare(lease.binding());
		ContractInspectionOwner owner = prepared.owner();
		locator.openMeasurementObserver(lease.binding()).recordInspectionObservation(lease, prepared);
		CampaignSchedulerLease scheduler = locator.openSchedulerLease(lease.binding());
		scheduler.claimOrResumeCall(lease, owner.logicalCallId(), owner.runRequestRef(),
			Optional.of(prepared.ownerRef()), Optional.of(owner.preparationCycleRef()));
		EvidenceRef attemptRef = new InspectionAttemptService(locator, store).publish(lease.binding(),
			prepared);
		CallSlot starting = scheduler.prepareCallStart(lease, owner.logicalCallId(),
			ProducerAttemptKind.INSPECTION, attemptRef, prepared.ownerRef(), manager, artifact);
		if (starting.cancellationRequested())
			throw new IOException("Inspection start was cancelled before native dispatch");
		CallPreStartRecord preStart = store.readCallPreStartRecord(lease.binding(), owner.logicalCallId()).
			orElseThrow(() -> new IOException("Inspection pre-start record is missing"));
		ActiveCall call;
		try
		{
			call = agent.start(prepared.request().asNested(), preStart.runId());
		}
		catch (NestedRunException failure)
		{
			commitStopAfterUnsuccessfulStart(lease, owner.logicalCallId(), failure);
			throw failure;
		}
		try
		{
			lease.activate(owner.logicalCallId(), call.runId());
			return new StartedInspectionCall(prepared.ownerRef(), attemptRef, owner.logicalCallId(), call);
		}
		catch (IOException | RuntimeException failure)
		{
			NativeCallHandleCleanup.interruptAndClose(call, failure);
			commitStopAfterUnsuccessfulStart(lease, owner.logicalCallId(), failure);
			throw failure;
		}
	}

	/**
	 * Commits a host stop when native start or scheduler activation failed.
	 *
	 * @param lease original scheduler owner
	 * @param callId starting inspection call
	 * @param failure original start or activation failure
	 */
	private void commitStopAfterUnsuccessfulStart(SchedulerLease lease, String callId, Throwable failure)
	{
		try
		{
			Optional<StopRequest> stop = locator.openStopInbox(lease.binding()).adoptPending(lease);
			if (stop.isEmpty())
				return;
			lease.requestCancellation(callId);
			new CampaignInterruptionService(locator, store).commitHostStop(lease, stop.orElseThrow(),
				Optional.of(callId));
		}
		catch (IOException | RuntimeException stopFailure)
		{
			failure.addSuppressed(stopFailure);
		}
	}

	/**
	 * Reconciles the first retained native inspection and a pending stop without dispatching another call.
	 *
	 * @param lease current scheduler owner after restart
	 * @param agent harness-neutral native reconciliation boundary
	 * @return settled, active, or unknown native state
	 * @throws IOException if retained call authority is invalid
	 */
	public InspectionCallReconciliation reconcileFirst(SchedulerLease lease, NestedAgent agent) throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(agent, "agent");
		lease.requireLive();
		locator.requireRegisteredBinding(lease.binding());
		if (!store.directory().equals(lease.binding().realDirectory()))
			throw new IOException("Inspection lease belongs to another campaign store");
		PreparedInspectionCall prepared = retainedFirstInspection(lease.binding());
		String callId = prepared.owner().logicalCallId();
		InspectionCompletionService completions = new InspectionCompletionService(locator, store);
		Optional<EvidenceRef> indexed = store.readNativeCompletionIndex(lease.binding(), callId);
		if (indexed.isPresent())
		{
			completions.adoptIndexed(lease, prepared.ownerRef());
			commitStopAfterRecoveredResult(lease, callId);
			NativeRunCompletionEvidence evidence = NativeRunCompletionCodec.decode(indexed.orElseThrow(),
				store::readImmutableJson);
			NestedRunCompletion completion;
			if (evidence.outcome() == NativeRunCompletionEvidence.Outcome.SUCCEEDED)
				completion = new NestedRunCompletion.Succeeded(evidence.result().orElseThrow());
			else
				completion = new NestedRunCompletion.Failed(evidence.failure().orElseThrow());
			return new InspectionCallReconciliation.Settled(completion, callId);
		}
		CallPreStartRecord preStart = store.readCallPreStartRecord(lease.binding(), callId).
			orElseThrow(() -> new IOException("Inspection pre-start is missing"));
		NativeCallReconciliation nativeState = agent.reconcile(prepared.request().asNested(),
			Optional.of(preStart.runId()));
		if (nativeState instanceof NativeCallReconciliation.Settled settled)
		{
			completions.publishRecovered(lease, prepared.ownerRef(), settled.completion());
			completions.adoptIndexed(lease, prepared.ownerRef());
			commitStopAfterRecoveredResult(lease, callId);
			return new InspectionCallReconciliation.Settled(settled.completion(), callId);
		}
		if (nativeState instanceof NativeCallReconciliation.Active active)
		{
			StartedInspectionCall started;
			try
			{
				CallSlot slot = store.readCallSlotClaim(lease.binding(), callId).slot();
				if (slot.state() == CallSlot.State.STARTING)
				{
					if (slot.fence() == lease.fence())
						lease.activate(callId, active.call().runId());
					else if (slot.fence() > lease.fence())
						throw new IOException("Retained inspection start belongs to a newer scheduler owner");
				}
				else if (slot.state() != CallSlot.State.ACTIVE ||
					!slot.runId().equals(Optional.of(active.call().runId())))
				{
					throw new IOException("Retained inspection slot conflicts with active native handle");
				}
				started = new StartedInspectionCall(prepared.ownerRef(),
					preStart.producerAttemptRef(), callId, active.call());
				Optional<StopRequest> stop = transferPendingStop(lease, started);
				if (store.readCallSlotClaim(lease.binding(), callId).slot().cancellationRequested())
					started.call().interrupt();
				if (stop.isPresent())
					new CampaignInterruptionService(locator, store).commitHostStop(lease,
						stop.orElseThrow(), Optional.of(callId));
			}
			catch (IOException | RuntimeException failure)
			{
				NativeCallHandleCleanup.interruptAndClose(active.call(), failure);
				commitStopAfterUnsuccessfulStart(lease, callId, failure);
				throw failure;
			}
			return new InspectionCallReconciliation.Active(started);
		}
		NativeCallReconciliation.Unknown unknown = (NativeCallReconciliation.Unknown) nativeState;
		Optional<StopRequest> stop = locator.openStopInbox(lease.binding()).adoptPending(lease);
		if (stop.isPresent())
		{
			lease.requestCancellation(callId);
			new CampaignInterruptionService(locator, store).commitHostStop(lease,
				stop.orElseThrow(), Optional.of(callId));
		}
		return new InspectionCallReconciliation.Unknown(unknown.failure());
	}

	/**
	 * Returns the adopted result of the first inspection after reconciliation has settled it.
	 *
	 * @param lease current scheduler owner
	 * @return the original inspection result reference
	 * @throws IOException if the first inspection has no validated indexed completion
	 */
	public EvidenceRef adoptedFirstResultRef(SchedulerLease lease) throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		PreparedInspectionCall prepared = retainedFirstInspection(lease.binding());
		return new InspectionCompletionService(locator, store).adoptIndexed(lease, prepared.ownerRef());
	}

	/**
	 * Commits a host stop after recovery has durably adopted the native result.
	 *
	 * @param lease current scheduler owner
	 * @param callId adopted inspection call
	 * @throws IOException if the pending stop or interruption authority conflicts
	 */
	private void commitStopAfterRecoveredResult(SchedulerLease lease, String callId) throws IOException
	{
		Optional<StopRequest> stop = locator.openStopInbox(lease.binding()).adoptPending(lease);
		if (stop.isEmpty())
			return;
		new CampaignInterruptionService(locator, store).commitHostStop(lease, stop.orElseThrow(),
			Optional.of(callId));
	}

	/**
	 * Transfers a pending host stop to an active inspection call.
	 *
	 * @param lease current scheduler owner
	 * @param started active inspection and its caller-owned native handle
	 * @return adopted stop, if one applies to this invocation
	 * @throws IOException if the retained call or stop authority is invalid
	 */
	public Optional<StopRequest> transferPendingStop(SchedulerLease lease, StartedInspectionCall started)
		throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(started, "started");
		CampaignBinding binding = lease.binding();
		locator.requireRegisteredBinding(binding);
		if (!store.directory().equals(binding.realDirectory()))
			throw new IOException("Inspection lease belongs to another campaign store");
		PreparedInspectionCall prepared = new InspectionOwnerService(locator, store).load(binding,
			started.ownerRef());
		if (!prepared.owner().logicalCallId().equals(started.logicalCallId()))
			throw new IOException("Active inspection identifies another owner call");
		ContractInspectionAttempt attempt = new InspectionAttemptService(locator, store).load(binding,
			started.attemptRef(), prepared);
		CallPreStartRecord preStart = store.readCallPreStartRecord(binding, started.logicalCallId()).
			orElseThrow(() -> new IOException("Inspection pre-start is missing"));
		ClaimedSlot claimed = store.readCallSlotClaim(binding, started.logicalCallId());
		if (!attempt.header().ownerRequestRef().equals(started.ownerRef()) ||
			!preStart.ownerRequestRef().equals(started.ownerRef()) ||
			!preStart.producerAttemptRef().equals(started.attemptRef()) ||
			!preStart.runId().equals(started.call().runId()) ||
			!claimed.claim().preparationOwnerRequestRef().equals(Optional.of(started.ownerRef())) ||
			claimed.slot().state() == CallSlot.State.ACTIVE &&
				!claimed.slot().runId().equals(Optional.of(preStart.runId())))
			throw new IOException("Active inspection conflicts with retained start authority");
		if (claimed.slot().state() == CallSlot.State.CLAIMED)
			throw new IOException("Inspection has not reached its native start boundary");
		Optional<StopRequest> stop = locator.openStopInbox(binding).adoptPending(lease);
		if (stop.isEmpty())
			return stop;
		CallSlot slot = store.readCallSlotClaim(binding, started.logicalCallId()).slot();
		if (slot.state() == CallSlot.State.ACTIVE || slot.state() == CallSlot.State.STARTING)
		{
			lease.requestCancellation(started.logicalCallId());
			try
			{
				started.call().interrupt();
			}
			catch (RuntimeException failure)
			{
				throw new IOException("Native inspection interruption failed after durable cancellation", failure);
			}
		}
		return stop;
	}

	/**
	 * Finds the sole first inspection through its original claimed slot after any campaign phase change.
	 *
	 * @param binding registered campaign
	 * @return indexed inspection owner and request
	 * @throws IOException if no first inspection or conflicting owners were retained
	 */
	private PreparedInspectionCall retainedFirstInspection(CampaignBinding binding) throws IOException
	{
		PreparedInspectionCall found = null;
		for (CallSlot slot : store.lockedSnapshot(binding).scheduler().slots())
		{
			ClaimedSlot claimed = store.readCallSlotClaim(binding, slot.logicalCallId());
			Optional<EvidenceRef> ownerRef = claimed.claim().preparationOwnerRequestRef();
			if (ownerRef.isEmpty() ||
				!ownerRef.orElseThrow().collection().equals("contract-preparation-owners"))
				continue;
			PreparedInspectionCall candidate = new InspectionOwnerService(locator, store).load(binding,
				ownerRef.orElseThrow());
			if (candidate.owner().inspectionOrdinal() != 0 ||
				!candidate.owner().logicalCallId().equals(slot.logicalCallId()))
				continue;
			if (found != null && !found.ownerRef().equals(candidate.ownerRef()))
				throw new IOException("Multiple first inspection owners conflict");
			found = candidate;
		}
		if (found == null)
			throw new IOException("Retained first inspection claim is missing");
		return found;
	}
}
