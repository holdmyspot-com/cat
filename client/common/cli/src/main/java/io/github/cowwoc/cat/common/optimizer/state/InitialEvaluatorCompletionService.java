package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.runner.NativeEventEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionCodec;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunCompletion;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailure;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunResult;
import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/** Retains a native evaluator completion before semantic trial judgment is adopted. */
public final class InitialEvaluatorCompletionService
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final CampaignLocator locator;
	private final CampaignStore store;

	/**
	 * Binds native evaluator completion to a registered campaign.
	 *
	 * @param locator owning campaign locator
	 * @param store pinned campaign store
	 */
	public InitialEvaluatorCompletionService(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Publishes one typed native completion against the evaluator's original claim.
	 *
	 * @param lease current scheduler owner
	 * @param assignmentRef adopted trial assignment
	 * @param completion validated native settlement
	 * @return indexed native completion
	 * @throws IOException if completion evidence conflicts with its retained call
	 */
	public EvidenceRef publishCompletion(SchedulerLease lease, EvidenceRef assignmentRef,
		NestedRunCompletion completion) throws IOException
	{
		Objects.requireNonNull(completion, "completion");
		RetainedEvaluatorCall call = load(lease, assignmentRef);
		NativeRunCompletionEvidence evidence = completionEvidence(lease.binding(), call, completion);
		EvidenceRef reference = store.appendImmutableJson("native-run-completions",
			NativeRunCompletionCodec.encode(evidence, store::readImmutableJson));
		return store.publishNativeCompletionIndex(lease.binding(), call.request().logicalCallId(), reference);
	}

	/**
	 * Adopts one indexed native evaluator completion without a semantic trial label.
	 *
	 * @param lease current scheduler owner
	 * @param assignmentRef adopted trial assignment
	 * @return retained evaluator execution record
	 * @throws IOException if the original call or completion index is invalid
	 */
	public EvidenceRef adoptIndexed(SchedulerLease lease, EvidenceRef assignmentRef) throws IOException
	{
		RetainedEvaluatorCall call = load(lease, assignmentRef);
		String callId = call.request().logicalCallId();
		EvidenceRef completionRef = store.readNativeCompletionIndex(lease.binding(), callId).
			orElseThrow(() -> new IOException("Evaluator native completion index is missing"));
		NativeRunCompletionEvidence completion = NativeRunCompletionCodec.decode(completionRef,
			store::readImmutableEvidence);
		NestedRunCompletion nativeCompletion = switch (completion.outcome())
		{
			case SUCCEEDED -> new NestedRunCompletion.Succeeded(completion.result().orElseThrow());
			case FAILED -> new NestedRunCompletion.Failed(completion.failure().orElseThrow());
		};
		completionEvidence(lease.binding(), call, nativeCompletion);
		MeasurementConditions afterConditions = afterConditions(call, completion);
		InitialEvaluatorExecutionRecord record = new InitialEvaluatorExecutionRecord(
			InitialEvaluatorExecutionRecord.SCHEMA_VERSION, lease.binding().campaignId(),
			call.request().semanticRequestRef(), call.preStart().producerAttemptRef(), completionRef);
		EvidenceRef recordRef = store.appendImmutableJson("evaluator-execution-records",
			JSON.writeValueAsBytes(record));
		if (call.claimed().slot().state() != CallSlot.State.ADOPTED)
			lease.settle(callId, completionRef);
		lease.adopt(callId, recordRef);
		locator.openMeasurementObserver(lease.binding()).afterCall(lease, callId, afterConditions);
		return recordRef;
	}

	/**
	 * Restores the frozen evaluator, claim, and indexed native pre-start.
	 *
	 * @param lease current scheduler owner
	 * @param assignmentRef adopted trial assignment
	 * @return retained evaluator call
	 * @throws IOException if authority or an attempt index is missing or conflicts
	 */
	RetainedEvaluatorCall load(SchedulerLease lease, EvidenceRef assignmentRef) throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(assignmentRef, "assignmentRef");
		lease.requireLive();
		if (!lease.binding().realDirectory().equals(store.directory()) ||
			!locator.requireRegisteredBinding(lease.binding()).directory().equals(store.directory()))
			throw new IOException("Evaluator completion store is not the registered campaign");
		InitialEvaluatorRequestService requests = new InitialEvaluatorRequestService(locator, store);
		EvidenceRef requestRef = requests.prepare(assignmentRef);
		InitialEvaluatorRunRequest request = requests.readRunRequest(requestRef);
		InitialEvaluatorRequest semantic = requests.readSemanticRequest(request.semanticRequestRef());
		ClaimedSlot claimed = store.readCallSlotClaim(lease.binding(), request.logicalCallId());
		CallPreStartRecord preStart = store.readCallPreStartRecord(lease.binding(),
			request.logicalCallId()).orElseThrow(() -> new IOException("Evaluator pre-start is missing"));
		if (!semantic.campaignId().equals(lease.binding().campaignId()) ||
			!semantic.assignmentRef().equals(assignmentRef) ||
			!request.campaignId().equals(lease.binding().campaignId()) ||
			!claimed.slot().requestRef().equals(requestRef) ||
			!preStart.requestRef().equals(requestRef) ||
			preStart.producerAttemptKind() != ProducerAttemptKind.EVALUATOR ||
			claimed.slot().state() != CallSlot.State.STARTING &&
			claimed.slot().state() != CallSlot.State.ACTIVE &&
			claimed.slot().state() != CallSlot.State.SETTLED &&
			claimed.slot().state() != CallSlot.State.ADOPTED)
			throw new IOException("Evaluator completion conflicts with its request or native start");
		return new RetainedEvaluatorCall(claimed, preStart, request);
	}

	/**
	 * Validates native completion against the retained evaluator and runner.
	 *
	 * @param binding pinned campaign
	 * @param call retained evaluator call
	 * @param completion native settlement
	 * @return typed completion evidence
	 * @throws IOException if native identity or referenced evidence conflicts
	 */
	NativeRunCompletionEvidence completionEvidence(CampaignBinding binding,
		RetainedEvaluatorCall call, NestedRunCompletion completion) throws IOException
	{
		String callId = call.request().logicalCallId();
		EvidenceRef requestRef = call.claimed().slot().requestRef();
		EvidenceRef claimRef = call.claimed().slotClaimRef();
		String runId = call.preStart().runId();
		if (completion instanceof NestedRunCompletion.Succeeded succeeded)
		{
			NestedRunResult result = succeeded.result();
			if (!result.runId().equals(runId) ||
				!call.request().modelProfile().acceptsObserved(result.observedModelProfile()) ||
				result.turnResults().size() != 1 ||
				!result.runnerIdentity().runtimeManifestSha256().equals(
					call.preStart().runtimeManifestSha256()))
				throw new IOException("Evaluator native result conflicts with its request or runner identity");
			List<EvidenceRef> events = result.nativeEventEvidence().stream().map(NativeEventEvidence::evidence).
				toList();
			for (EvidenceRef event : events)
				store.readImmutableBytes(event);
			return new NativeRunCompletionEvidence(binding.campaignId(), callId, requestRef, claimRef,
				Optional.of(runId), NativeRunCompletionEvidence.Outcome.SUCCEEDED, Optional.of(result),
				Optional.empty(), events, Optional.empty(), Optional.empty());
		}
		NestedRunFailure failure = ((NestedRunCompletion.Failed) completion).failure();
		if (!failure.logicalCallId().equals(callId) ||
			!failure.runId().equals(Optional.of(runId)))
			throw new IOException("Evaluator native failure conflicts with its request or runner identity");
		for (EvidenceRef event : failure.nativeEvidence())
			store.readImmutableBytes(event);
		if (failure.terminalResponseEvidence().isPresent())
			store.readImmutableJson(failure.terminalResponseEvidence().orElseThrow());
		return new NativeRunCompletionEvidence(binding.campaignId(), callId, requestRef, claimRef,
			Optional.of(runId), NativeRunCompletionEvidence.Outcome.FAILED, Optional.empty(),
			Optional.of(failure), failure.nativeEvidence(), failure.terminalResponseEvidence(),
			failure.noNativeTurnDispatchProof());
	}

	/**
	 * Retains trial measurement identity while recording the evaluator's observed runner.
	 *
	 * @param call evaluator call and indexed before-call observation
	 * @param completion indexed native settlement
	 * @return post-call measurement conditions
	 * @throws IOException if retained observation or settlement conflicts
	 */
	private MeasurementConditions afterConditions(RetainedEvaluatorCall call,
		NativeRunCompletionEvidence completion) throws IOException
	{
		EvidenceRef beforeRef = call.preStart().beforeCallObservationRef().orElseThrow(() ->
			new IOException("Evaluator completion has no retained before-call observation"));
		MeasurementObservation before = store.readMeasurementObservation(beforeRef);
		if (before.stage() != MeasurementObservation.Stage.BEFORE_CALL ||
			!before.logicalCallId().equals(call.request().logicalCallId()) ||
			!before.campaignId().equals(call.request().campaignId()) ||
			!completion.logicalCallId().equals(call.request().logicalCallId()) ||
			!completion.runRequestRef().equals(call.claimed().slot().requestRef()) ||
			!completion.slotClaimRef().equals(call.claimed().slotClaimRef()))
			throw new IOException("Evaluator after-call observation conflicts with retained authority");
		Map<MeasurementConditions.Kind, MeasurementConditions.Value> values =
			new EnumMap<>(before.conditions().values());
		if (completion.outcome() == NativeRunCompletionEvidence.Outcome.SUCCEEDED)
		{
			NestedRunResult result = completion.result().orElseThrow();
			values.put(MeasurementConditions.Kind.HARNESS_RUNNER,
				new MeasurementConditions.Value.Known(store.appendImmutableJson("measurement-values",
					JSON.writeValueAsBytes(result.runnerIdentity().runtimeManifestSha256()))));
		}
		else
			values.put(MeasurementConditions.Kind.HARNESS_RUNNER,
				new MeasurementConditions.Value.Unknown("Native evaluator did not report a completed runner identity"));
		return new MeasurementConditions(values);
	}

	/**
	 * Original evaluator authority retained across scheduler owners.
	 *
	 * @param claimed original scheduler claim
	 * @param preStart indexed native run identity
	 * @param request frozen native evaluator request
	 */
	record RetainedEvaluatorCall(ClaimedSlot claimed, CallPreStartRecord preStart,
		InitialEvaluatorRunRequest request)
	{
	}
}
