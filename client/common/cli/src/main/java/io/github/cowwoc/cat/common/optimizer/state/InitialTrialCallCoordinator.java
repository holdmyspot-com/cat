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
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/** Starts an assigned initial trial through its indexed native call authority. */
public final class InitialTrialCallCoordinator
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final CampaignLocator locator;
	private final CampaignStore store;

	/**
	 * Binds trial execution to a registered campaign.
	 *
	 * @param locator owning campaign locator
	 * @param store pinned campaign store
	 */
	public InitialTrialCallCoordinator(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Starts one admitted and claimed initial trial under its retained runner identity.
	 *
	 * @param lease exclusive scheduler owner
	 * @param assignmentRef committed initial trial assignment
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
			throw new IOException("Initial trial start belongs to another campaign store");
		TrialAssignment assignment = store.readTrialAssignment(assignmentRef);
		TrialTransportAttemptService attempts = new TrialTransportAttemptService(locator, store);
		TrialTransportAttemptService.PreparedStart prepared =
			attempts.prepareStart(lease, assignmentRef, manager, artifact);
		EvidenceRef requestRef = prepared.requestRef();
		EvidenceRef attemptRef = prepared.attemptRef();
		CallPreStartRecord preStart = store.readCallPreStartRecord(lease.binding(),
			assignment.logicalTrialId()).orElseThrow(() -> new IOException("Trial pre-start is missing"));
		CallSlot starting = locator.openSchedulerLease(lease.binding()).prepareCallStart(lease,
			assignment.logicalTrialId(), ProducerAttemptKind.TRIAL, attemptRef,
			preStart.ownerRequestRef(), manager, artifact);
		if (starting.cancellationRequested())
			throw new IOException("Trial start was cancelled before native dispatch");
		byte[] requestBytes = store.readImmutableJson(requestRef);
		InitialTrialRunRequest request;
		try
		{
			request = JSON.readValue(requestBytes, InitialTrialRunRequest.class);
			if (request == null || !Arrays.equals(requestBytes, JSON.writeValueAsBytes(request)) ||
				!request.assignmentRef().equals(assignmentRef) ||
				!request.logicalCallId().equals(assignment.logicalTrialId()))
				throw new IOException("Trial native request conflicts with its assignment");
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Trial native request is malformed", exception);
		}
		locator.verifyInitialFork(lease.binding());
		ActiveCall call = agent.start(request.asNested(), preStart.runId());
		try
		{
			lease.activate(assignment.logicalTrialId(), call.runId());
			return call;
		}
		catch (IOException | RuntimeException failure)
		{
			NativeCallHandleCleanup.interruptAndClose(call, failure);
			throw failure;
		}
	}

	/**
	 * Recovers the original native trial without dispatching another call.
	 *
	 * @param lease current scheduler owner
	 * @param assignmentRef committed trial assignment
	 * @param agent native reconciliation boundary
	 * @return settled, active, or unknown native state
	 * @throws IOException if retained trial authority is invalid
	 */
	public InitialTrialCallReconciliation reconcile(SchedulerLease lease, EvidenceRef assignmentRef,
		NestedAgent agent) throws IOException
	{
		Objects.requireNonNull(agent, "agent");
		InitialTrialCompletionService completions = new InitialTrialCompletionService(locator, store);
		InitialTrialCompletionService.RetainedTrialCall call = completions.load(lease, assignmentRef);
		String callId = call.assignment().logicalTrialId();
		Optional<EvidenceRef> indexed = store.readNativeCompletionIndex(lease.binding(), callId);
		if (indexed.isPresent())
			return settled(lease, assignmentRef, indexed.orElseThrow(), completions);
		CallSlot slot = call.claimed().slot();
		if (slot.state() != CallSlot.State.STARTING && slot.state() != CallSlot.State.ACTIVE)
			throw new IOException("Trial has no started call or indexed native completion");
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
			RetainedNativeCallRecovery.acceptActiveCall(lease, store, call.claimed(), call.preStart(), recovered);
			return new InitialTrialCallReconciliation.Active(recovered);
		}
		NativeCallReconciliation.Unknown unknown = (NativeCallReconciliation.Unknown) nativeState;
		return new InitialTrialCallReconciliation.Unknown(unknown.failure());
	}

	/**
	 * Adopts one indexed native completion and returns its typed result.
	 *
	 * @param lease current scheduler owner
	 * @param assignmentRef original trial assignment
	 * @param completionRef indexed native completion
	 * @param completions trial completion producer
	 * @return adopted native settlement
	 * @throws IOException if the completion is missing or conflicts
	 */
	private InitialTrialCallReconciliation.Settled settled(SchedulerLease lease,
		EvidenceRef assignmentRef, EvidenceRef completionRef, InitialTrialCompletionService completions)
		throws IOException
	{
		EvidenceRef executionRef = completions.adoptIndexed(lease, assignmentRef);
		NativeRunCompletionEvidence evidence = NativeRunCompletionCodec.decode(completionRef,
			store::readImmutableJson);
		NestedRunCompletion completion;
		if (evidence.outcome() == NativeRunCompletionEvidence.Outcome.SUCCEEDED)
			completion = new NestedRunCompletion.Succeeded(evidence.result().orElseThrow());
		else
			completion = new NestedRunCompletion.Failed(evidence.failure().orElseThrow());
		return new InitialTrialCallReconciliation.Settled(executionRef, completion);
	}
}
