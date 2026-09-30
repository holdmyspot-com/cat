package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.contract.ConfirmedContract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractService;
import io.github.cowwoc.cat.common.optimizer.contract.EvaluatorResponse;
import io.github.cowwoc.cat.common.optimizer.contract.EvaluatorResponseParser;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionCodec;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunCompletion;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunResult;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/** Publishes one semantic outcome from an adopted initial trial and evaluator. */
public final class InitialTrialOutcomeService
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final CampaignLocator locator;
	private final CampaignStore store;

	/**
	 * Binds outcome publication to one registered campaign.
	 *
	 * @param locator owning campaign locator
	 * @param store pinned campaign store
	 */
	public InitialTrialOutcomeService(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Publishes or recovers one validated semantic outcome for the assigned trial.
	 *
	 * @param lease current scheduler owner
	 * @param assignmentRef adopted trial assignment
	 * @return indexed semantic outcome
	 * @throws IOException if an adopted successful evaluator or valid result is unavailable
	 */
	public EvidenceRef publishOutcome(SchedulerLease lease, EvidenceRef assignmentRef) throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(assignmentRef, "assignmentRef");
		lease.requireLive();
		if (!lease.binding().realDirectory().equals(store.directory()) ||
			!locator.requireRegisteredBinding(lease.binding()).directory().equals(store.directory()))
			throw new IOException("Trial outcome store is not the registered campaign");
		InitialEvaluatorRequestService requests = new InitialEvaluatorRequestService(locator, store);
		EvidenceRef requestRef = requests.prepare(assignmentRef);
		InitialEvaluatorRunRequest request = requests.readRunRequest(requestRef);
		InitialEvaluatorRequest semantic = requests.readSemanticRequest(request.semanticRequestRef());
		TrialAssignment assignment = store.readTrialAssignment(assignmentRef);
		ClaimedSlot claimed = store.readCallSlotClaim(lease.binding(), request.logicalCallId());
		if (claimed.slot().state() != CallSlot.State.ADOPTED)
			throw new IOException("Trial outcome requires an adopted native evaluator");
		EvidenceRef evaluatorExecutionRef = new InitialEvaluatorCompletionService(locator, store).
			adoptIndexed(lease, assignmentRef);
		InitialEvaluatorExecutionRecord execution = readEvaluatorExecution(evaluatorExecutionRef);
		EvidenceRef completionRef = execution.completionRef();
		if (!semantic.campaignId().equals(lease.binding().campaignId()) ||
			!semantic.assignmentRef().equals(assignmentRef) ||
			!semantic.lineage().equals(assignment.lineage()) ||
			!execution.campaignId().equals(lease.binding().campaignId()) ||
			!execution.semanticRequestRef().equals(request.semanticRequestRef()) ||
			!claimed.slot().completionRef().equals(Optional.of(completionRef)) ||
			!store.readNativeCompletionIndex(lease.binding(), request.logicalCallId()).equals(
				Optional.of(completionRef)))
			throw new IOException("Trial outcome conflicts with adopted evaluator evidence");
		NativeRunCompletionEvidence completion = NativeRunCompletionCodec.decode(completionRef,
			store::readImmutableEvidence);
		if (completion.outcome() != NativeRunCompletionEvidence.Outcome.SUCCEEDED)
			throw new IOException("Native evaluator failure has no semantic trial outcome");
		NestedRunResult result = completion.result().orElseThrow();
		if (result.turnResults().size() != 1)
			throw new IOException("Evaluator result must have exactly one turn");
		ConfirmedContract contract = new ContractService(store).
			loadConfirmedContract(assignment.lineage().contractId());
		EvaluatorResponse response = new EvaluatorResponseParser().parse(
			result.turnResults().getFirst().responseText(), contract.evaluator().resultSchema());
		TrialOutcomeRecord outcome = new TrialOutcomeRecord(TrialOutcomeRecord.SCHEMA_VERSION,
			lease.binding().campaignId(), assignment.lineage(), assignmentRef, semantic.executionRef(),
			evaluatorExecutionRef, contract.evaluator().resultSchema(), response);
		EvidenceRef outcomeRef = store.appendImmutableJson("trial-outcomes", JSON.writeValueAsBytes(outcome));
		store.appendCreateOnceIndex("trial-outcome-index", assignmentRef.evidenceId(),
			new LineageEvidenceRef(assignment.lineage(), outcomeRef));
		return outcomeRef;
	}

	/**
	 * Revalidates a published outcome and its adopted native evaluator before campaign accounting.
	 *
	 * @param outcomeRef indexed semantic outcome
	 * @return the validated outcome and assignment identity
	 * @throws IOException if the index, native evidence, or semantic label conflicts
	 */
	TrialOutcomeRecord requireIndexedOutcome(EvidenceRef outcomeRef) throws IOException
	{
		TrialOutcomeRecord outcome = store.readTrialOutcome(outcomeRef);
		CampaignBinding binding = store.binding();
		if (!locator.requireRegisteredBinding(binding).directory().equals(store.directory()) ||
			!outcome.campaignId().equals(binding.campaignId()))
			throw new IOException("Trial outcome belongs to another campaign");
		TrialAssignment assignment = store.readTrialAssignment(outcome.assignmentRef());
		if (!assignment.campaignId().equals(binding.campaignId()) ||
			!assignment.lineage().equals(outcome.lineage()) ||
			!store.readCreateOnceIndex("trial-outcome-index", outcome.assignmentRef().evidenceId(),
				outcome.lineage()).evidence().equals(outcomeRef))
			throw new IOException("Trial outcome conflicts with its assignment or index");

		InitialEvaluatorRequestService requests = new InitialEvaluatorRequestService(locator, store);
		EvidenceRef requestRef = requests.prepare(outcome.assignmentRef());
		InitialEvaluatorRunRequest request = requests.readRunRequest(requestRef);
		InitialEvaluatorRequest semantic = requests.readSemanticRequest(request.semanticRequestRef());
		ClaimedSlot claimed = store.readCallSlotClaim(binding, request.logicalCallId());
		CallPreStartRecord preStart = store.readCallPreStartRecord(binding, request.logicalCallId()).
			orElseThrow(() -> new IOException("Evaluator pre-start is missing"));
		CallAdoptionRecord adoption = requests.readAdoption(claimed.slot().adoptionRef().orElseThrow(() ->
			new IOException("Evaluator adoption is missing")));
		InitialEvaluatorExecutionRecord execution = readEvaluatorExecution(outcome.evaluatorExecutionRef());
		EvidenceRef completionRef = execution.completionRef();
		if (claimed.slot().state() != CallSlot.State.ADOPTED ||
			!claimed.slot().requestRef().equals(requestRef) ||
			!adoption.producerRecordRef().equals(outcome.evaluatorExecutionRef()) ||
			!adoption.completionRef().equals(completionRef) ||
			!claimed.slot().completionRef().equals(Optional.of(completionRef)) ||
			!store.readNativeCompletionIndex(binding, request.logicalCallId()).equals(Optional.of(completionRef)) ||
			!preStart.requestRef().equals(requestRef) ||
			!preStart.producerAttemptRef().equals(execution.attemptRef()) ||
			!execution.campaignId().equals(binding.campaignId()) ||
			!execution.semanticRequestRef().equals(request.semanticRequestRef()) ||
			!semantic.assignmentRef().equals(outcome.assignmentRef()) ||
			!semantic.executionRef().equals(outcome.trialExecutionRef()))
			throw new IOException("Trial outcome conflicts with adopted evaluator evidence");

		NativeRunCompletionEvidence completion = NativeRunCompletionCodec.decode(completionRef,
			store::readImmutableEvidence);
		if (completion.outcome() != NativeRunCompletionEvidence.Outcome.SUCCEEDED)
			throw new IOException("Native evaluator failure has no semantic trial outcome");
		NestedRunResult result = completion.result().orElseThrow();
		InitialEvaluatorCompletionService nativeEvaluator = new InitialEvaluatorCompletionService(locator, store);
		var retainedCall = new InitialEvaluatorCompletionService.RetainedEvaluatorCall(claimed, preStart, request);
		if (!nativeEvaluator.completionEvidence(binding, retainedCall,
			new NestedRunCompletion.Succeeded(result)).equals(completion))
			throw new IOException("Trial outcome native completion conflicts with retained evaluator call");
		ConfirmedContract contract = new ContractService(store).
			loadConfirmedContract(assignment.lineage().contractId());
		if (result.turnResults().size() != 1 ||
			outcome.resultSchema() != contract.evaluator().resultSchema() ||
			!new EvaluatorResponseParser().parse(result.turnResults().getFirst().responseText(),
				outcome.resultSchema()).equals(outcome.response()))
			throw new IOException("Trial outcome label conflicts with frozen evaluator response");
		return outcome;
	}

	/**
	 * Restores the canonical evaluator execution linked by adoption.
	 *
	 * @param reference adopted evaluator execution
	 * @return validated execution record
	 * @throws IOException if evidence is missing or noncanonical
	 */
	private InitialEvaluatorExecutionRecord readEvaluatorExecution(EvidenceRef reference) throws IOException
	{
		if (!reference.collection().equals("evaluator-execution-records"))
			throw new IOException("Evaluator adoption has the wrong execution collection");
		byte[] bytes = store.readImmutableJson(reference);
		try
		{
			InitialEvaluatorExecutionRecord record = JSON.readValue(bytes,
				InitialEvaluatorExecutionRecord.class);
			if (record == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(record)))
				throw new IOException("Evaluator execution record is noncanonical");
			return record;
		}
		catch (RuntimeException failure)
		{
			throw new IOException("Evaluator execution record is malformed", failure);
		}
	}
}
