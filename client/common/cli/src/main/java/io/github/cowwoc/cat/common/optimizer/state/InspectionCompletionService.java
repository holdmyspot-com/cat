package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.candidate.EditableRoot;
import io.github.cowwoc.cat.common.optimizer.candidate.SnapshotJson;
import io.github.cowwoc.cat.common.optimizer.runner.NativeEventEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionCodec;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunCompletion;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailure;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunResult;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverage;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationDomain;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Retains and adopts the first inspection's native completion under its original call claim.
 */
public final class InspectionCompletionService
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final JsonMapper SOURCE_JSON = SnapshotJson.mapper();

	private final CampaignLocator locator;
	private final CampaignStore store;

	/**
	 * Binds native completion adoption to a registered campaign.
	 *
	 * @param locator durable campaign locator
	 * @param store pinned campaign evidence store
	 */
	public InspectionCompletionService(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Indexes a terminal native completion, settles its slot, and adopts the inspection result.
	 *
	 * @param lease current scheduler owner
	 * @param started retained inspection and live handle identity
	 * @param completion validated native settlement
	 * @return adopted inspection result reference
	 * @throws IOException if the completion or retained call identity conflicts
	 */
	public EvidenceRef adopt(SchedulerLease lease, StartedInspectionCall started,
		NestedRunCompletion completion) throws IOException
	{
		publish(lease, started, completion);
		return adoptIndexed(lease, started.ownerRef());
	}

	/**
	 * Publishes the native completion index before scheduler settlement.
	 *
	 * @param lease current scheduler owner
	 * @param started retained inspection and live handle identity
	 * @param completion validated native settlement
	 * @return indexed native completion reference
	 * @throws IOException if the completion or retained call identity conflicts
	 */
	public EvidenceRef publish(SchedulerLease lease, StartedInspectionCall started,
		NestedRunCompletion completion) throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(started, "started");
		Objects.requireNonNull(completion, "completion");
		CallPreStartRecord preStart = store.readCallPreStartRecord(lease.binding(),
			started.logicalCallId()).orElseThrow(() -> new IOException("Inspection pre-start is missing"));
		if (!preStart.runId().equals(started.call().runId()) ||
			!preStart.producerAttemptRef().equals(started.attemptRef()) ||
			!preStart.ownerRequestRef().equals(started.ownerRef()))
			throw new IOException("Inspection completion conflicts with retained native start");
		return publishRecovered(lease, started.ownerRef(), completion);
	}

	/**
	 * Indexes a native settlement recovered from the retained runner after the original handle is gone.
	 *
	 * @param lease current scheduler owner
	 * @param ownerRef indexed inspection owner
	 * @param completion validated retained native settlement
	 * @return indexed native completion reference
	 * @throws IOException if the recovered settlement conflicts with the original call
	 */
	public EvidenceRef publishRecovered(SchedulerLease lease, EvidenceRef ownerRef,
		NestedRunCompletion completion) throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(ownerRef, "ownerRef");
		Objects.requireNonNull(completion, "completion");
		locator.requireRegisteredBinding(lease.binding());
		if (!store.directory().equals(lease.binding().realDirectory()))
			throw new IOException("Inspection completion store belongs to another campaign");
		PreparedInspectionCall prepared = new InspectionOwnerService(locator, store).load(lease.binding(), ownerRef);
		String callId = prepared.owner().logicalCallId();
		EvidenceRef attemptRef = new InspectionAttemptService(locator, store).publish(lease.binding(), prepared);
		CallPreStartRecord preStart = store.readCallPreStartRecord(lease.binding(), callId).
			orElseThrow(() -> new IOException("Inspection pre-start is missing"));
		if (!preStart.producerAttemptRef().equals(attemptRef) ||
			!preStart.ownerRequestRef().equals(ownerRef))
			throw new IOException("Inspection completion conflicts with retained native start");
		ClaimedSlot claimed = store.readCallSlotClaim(lease.binding(), callId);
		NativeRunCompletionEvidence nativeEvidence = completionEvidence(lease.binding(), prepared,
			claimed, preStart, completion);
		EvidenceRef completionRef = store.appendImmutableJson("native-run-completions",
			NativeRunCompletionCodec.encode(nativeEvidence, store::readImmutableJson));
		store.publishNativeCompletionIndex(lease.binding(), callId, completionRef);
		return completionRef;
	}

	/**
	 * Settles and adopts a retained indexed completion without the original native handle.
	 *
	 * @param lease current scheduler owner
	 * @param ownerRef indexed inspection owner
	 * @return adopted inspection result reference
	 * @throws IOException if the original call or completion index is unavailable
	 */
	public EvidenceRef adoptIndexed(SchedulerLease lease, EvidenceRef ownerRef) throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(ownerRef, "ownerRef");
		locator.requireRegisteredBinding(lease.binding());
		if (!store.directory().equals(lease.binding().realDirectory()))
			throw new IOException("Inspection completion store belongs to another campaign");
		PreparedInspectionCall prepared = new InspectionOwnerService(locator, store).load(lease.binding(), ownerRef);
		String callId = prepared.owner().logicalCallId();
		EvidenceRef attemptRef = new InspectionAttemptService(locator, store).publish(lease.binding(), prepared);
		CallPreStartRecord preStart = store.readCallPreStartRecord(lease.binding(), callId).
			orElseThrow(() -> new IOException("Inspection pre-start is missing"));
		if (!preStart.producerAttemptRef().equals(attemptRef) ||
			!preStart.ownerRequestRef().equals(ownerRef))
			throw new IOException("Inspection pre-start conflicts with its indexed attempt");
		EvidenceRef completionRef = store.readNativeCompletionIndex(lease.binding(), callId).
			orElseThrow(() -> new IOException("Inspection native completion index is missing"));
		ClaimedSlot claimed = store.readCallSlotClaim(lease.binding(), callId);
		NativeRunCompletionEvidence nativeCompletion = NativeRunCompletionCodec.decode(completionRef,
			store::readImmutableJson);
		if (!nativeCompletion.campaignId().equals(lease.binding().campaignId()) ||
			!nativeCompletion.logicalCallId().equals(callId) ||
			!nativeCompletion.runRequestRef().equals(prepared.owner().runRequestRef()) ||
			!nativeCompletion.slotClaimRef().equals(claimed.slotClaimRef()))
			throw new IOException("Inspection native completion differs from its retained request or claim");
		Optional<EvidenceRef> selectedSourceProposalRef = Optional.empty();
		if (nativeCompletion.outcome() == NativeRunCompletionEvidence.Outcome.SUCCEEDED)
		{
			InitialTargetCapture capture = new InitialTargetCaptureService(locator, store).load(lease.binding(),
				prepared.owner().sourceCaptureRef());
			NestedRunResult nativeResult = nativeCompletion.result().orElseThrow();
			SelectedSourceEditProposal proposal = new SelectedSourceEditProposal(
				SelectedSourceEditProposal.SCHEMA_VERSION, lease.binding().campaignId(),
				prepared.owner().sourceCaptureRef(), completionRef, capture.targetPath(),
				capture.resolvedSourcePath(), capture.selectedLinkTarget(), capture.selectedDirectoryLinks(),
				capture.sourceSha256(),
				nativeResult.promptSources(), promptSourceCoverage(nativeResult),
				new EditableRoot(capture.resolvedSourcePath(), EditableRoot.Kind.FILE));
			selectedSourceProposalRef = Optional.of(store.appendImmutableJson("selected-source-edit-proposals",
				SnapshotJson.mapper().writeValueAsBytes(proposal)));
		}
		if (claimed.slot().state() != CallSlot.State.ADOPTED)
			lease.settle(callId, completionRef);
		ContractInspectionResult result = new ContractInspectionResult(3, lease.binding().campaignId(),
			ownerRef, attemptRef, prepared.owner().sourceCaptureRef(), completionRef, selectedSourceProposalRef);
		EvidenceRef resultRef = store.appendImmutableJson("contract-inspection-results",
			JSON.writeValueAsBytes(result));
		lease.adopt(callId, resultRef);
		return resultRef;
	}

	/**
	 * Reads the selected source's proposed edit root from an adopted inspection result.
	 *
	 * @param binding the registered campaign
	 * @param resultRef the adopted inspection result
	 * @return the selected source's proposed edit root and evidence references
	 * @throws IOException if the result was not adopted or its source, completion, or proposal differs
	 */
	public SelectedSourceEditProposal loadSelectedSourceProposal(CampaignBinding binding, EvidenceRef resultRef)
		throws IOException
	{
		Objects.requireNonNull(binding, "binding");
		Objects.requireNonNull(resultRef, "resultRef");
		locator.requireRegisteredBinding(binding);
		if (!store.directory().equals(binding.realDirectory()) ||
			!resultRef.collection().equals("contract-inspection-results"))
			throw new IOException("Inspection result does not belong to this campaign store");
		ContractInspectionResult result = readInspectionResult(resultRef);
		if (!result.campaignId().equals(binding.campaignId()))
			throw new IOException("Inspection result belongs to another campaign");
		PreparedInspectionCall prepared = new InspectionOwnerService(locator, store).load(binding,
			result.ownerRef());
		ContractInspectionAttempt attempt = new InspectionAttemptService(locator, store).load(binding,
			result.attemptRef(), prepared);
		ContractInspectionOwner owner = prepared.owner();
		ClaimedSlot claimed = store.readCallSlotClaim(binding, owner.logicalCallId());
		if (!attempt.header().ownerRequestRef().equals(result.ownerRef()) ||
			!attempt.requestRef().equals(result.sourceCaptureRef()) ||
			!owner.sourceCaptureRef().equals(result.sourceCaptureRef()))
			throw new IOException("Inspection result source differs from its indexed owner or attempt");
		requireAdoptedResult(binding, resultRef, result, owner, claimed);
		NativeRunCompletionEvidence nativeCompletion = NativeRunCompletionCodec.decode(result.completionRef(),
			store::readImmutableJson);
		if (!nativeCompletion.campaignId().equals(binding.campaignId()) ||
			!nativeCompletion.logicalCallId().equals(owner.logicalCallId()) ||
			!nativeCompletion.runRequestRef().equals(owner.runRequestRef()) ||
			!nativeCompletion.slotClaimRef().equals(claimed.slotClaimRef()))
			throw new IOException("Inspection native completion differs from the adopted request");
		if (nativeCompletion.outcome() != NativeRunCompletionEvidence.Outcome.SUCCEEDED ||
			result.selectedSourceProposalRef().isEmpty())
			throw new IOException("Inspection has no selected source edit proposal");
		InitialTargetCapture capture = new InitialTargetCaptureService(locator, store).load(binding,
			result.sourceCaptureRef());
		return readSelectedSourceProposal(binding, result, capture, nativeCompletion.result().orElseThrow());
	}

	/**
	 * Reads one canonical inspection result from its immutable evidence reference.
	 *
	 * @param resultRef the retained inspection result
	 * @return the validated result shape
	 * @throws IOException if result JSON is missing or noncanonical
	 */
	private ContractInspectionResult readInspectionResult(EvidenceRef resultRef) throws IOException
	{
		byte[] bytes = store.readImmutableJson(resultRef);
		try
		{
			ContractInspectionResult result = JSON.readValue(bytes, ContractInspectionResult.class);
			if (result == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(result)))
				throw new IOException("Inspection result is noncanonical");
			return result;
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Inspection result is malformed", exception);
		}
	}

	/**
	 * Checks that the scheduler adopted exactly this inspection result for the indexed native call.
	 *
	 * @param binding the registered campaign
	 * @param resultRef requested result reference
	 * @param result parsed inspection result
	 * @param owner indexed inspection owner
	 * @param claimed retained scheduler slot and claim
	 * @throws IOException if the adoption envelope or completion index identifies different evidence
	 */
	private void requireAdoptedResult(CampaignBinding binding, EvidenceRef resultRef,
		ContractInspectionResult result, ContractInspectionOwner owner, ClaimedSlot claimed) throws IOException
	{
		CallSlot slot = claimed.slot();
		if (slot.state() != CallSlot.State.ADOPTED ||
			!slot.completionRef().equals(Optional.of(result.completionRef())) ||
			!store.readNativeCompletionIndex(binding, owner.logicalCallId()).equals(
				Optional.of(result.completionRef())))
			throw new IOException("Inspection result is not adopted for its call");
		CallAdoptionRecord adoption = store.readAdoptedCall(binding, slot);
		if (!adoption.runRequestRef().equals(owner.runRequestRef()) ||
			!adoption.producerRecordRef().equals(resultRef))
			throw new IOException("Inspection result is not adopted for its call");
	}

	/**
	 * Revalidates the proposed root against the original selected source and native completion references.
	 *
	 * @param binding the registered campaign
	 * @param result adopted inspection result
	 * @param capture retained source relationship and bytes
	 * @param nativeResult the exact indexed native observation
	 * @return the validated selected-source proposal
	 * @throws IOException if the proposal differs from the adopted evidence
	 */
	private SelectedSourceEditProposal readSelectedSourceProposal(CampaignBinding binding,
		ContractInspectionResult result, InitialTargetCapture capture, NestedRunResult nativeResult) throws IOException
	{
		byte[] bytes = store.readImmutableJson(result.selectedSourceProposalRef().orElseThrow());
		try
		{
			SelectedSourceEditProposal proposal = SOURCE_JSON.readValue(bytes, SelectedSourceEditProposal.class);
			if (proposal == null || !Arrays.equals(bytes, SOURCE_JSON.writeValueAsBytes(proposal)) ||
				!proposal.campaignId().equals(binding.campaignId()) ||
				!proposal.sourceCaptureRef().equals(result.sourceCaptureRef()) ||
				!proposal.nativeCompletionRef().equals(result.completionRef()) ||
				!proposal.source().equals(capture.source()) ||
				!proposal.promptPathObservations().equals(nativeResult.promptSources()) ||
				!proposal.promptSourceCoverage().equals(promptSourceCoverage(nativeResult)) ||
				!proposal.editableRoot().path().equals(capture.resolvedSourcePath()))
				throw new IOException("Selected source edit proposal differs from adopted source evidence");
			return proposal;
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Selected source edit proposal is malformed", exception);
		}
	}

	/**
	 * Finds the native host's exact prompt-path observation limit.
	 *
	 * @param nativeResult indexed successful native result
	 * @return its prompt-source coverage
	 */
	private static ObservationCoverage promptSourceCoverage(NestedRunResult nativeResult)
	{
		return nativeResult.coverage().stream().filter(coverage ->
			coverage.domain() == ObservationDomain.PROMPT_SOURCE).findFirst().orElseThrow();
	}

	/**
	 * Binds a validated native settlement to the original claimed request and runner identity.
	 *
	 * @param binding pinned campaign
	 * @param prepared inspection owner and native request
	 * @param claimed retained slot claim
	 * @param preStart retained native start identity
	 * @param completion native settlement
	 * @return typed completion evidence
	 * @throws IOException if native identity or referenced evidence differs
	 */
	private NativeRunCompletionEvidence completionEvidence(CampaignBinding binding,
		PreparedInspectionCall prepared, ClaimedSlot claimed, CallPreStartRecord preStart,
		NestedRunCompletion completion) throws IOException
	{
		String callId = prepared.owner().logicalCallId();
		if (completion instanceof NestedRunCompletion.Succeeded succeeded)
		{
			NestedRunResult result = succeeded.result();
			if (!result.runId().equals(preStart.runId()) ||
				!prepared.request().modelProfile().acceptsObserved(result.observedModelProfile()) ||
				result.turnResults().size() != prepared.request().turns().size() ||
				result.executionProject().equals(prepared.request().context().snapshot().projectRoot()) ||
				!result.runnerIdentity().runtimeManifestSha256().equals(preStart.runtimeManifestSha256()))
				throw new IOException("Inspection native result conflicts with its request or runner identity");
			List<EvidenceRef> nativeEvents = result.nativeEventEvidence().stream().
				map(NativeEventEvidence::evidence).toList();
			for (EvidenceRef event : nativeEvents)
				store.readImmutableBytes(event);
			return new NativeRunCompletionEvidence(binding.campaignId(), callId,
				prepared.owner().runRequestRef(), claimed.slotClaimRef(), Optional.of(preStart.runId()),
				NativeRunCompletionEvidence.Outcome.SUCCEEDED, Optional.of(result), Optional.empty(),
				nativeEvents, Optional.empty(), Optional.empty());
		}
		NestedRunFailure failure = ((NestedRunCompletion.Failed) completion).failure();
		if (!failure.logicalCallId().equals(callId) ||
			!failure.runId().equals(Optional.of(preStart.runId())))
			throw new IOException("Inspection native failure conflicts with its request or runner identity");
		for (EvidenceRef event : failure.nativeEvidence())
			store.readImmutableBytes(event);
		if (failure.terminalResponseEvidence().isPresent())
			store.readImmutableJson(failure.terminalResponseEvidence().orElseThrow());
		return new NativeRunCompletionEvidence(binding.campaignId(), callId,
			prepared.owner().runRequestRef(), claimed.slotClaimRef(), Optional.of(preStart.runId()),
			NativeRunCompletionEvidence.Outcome.FAILED, Optional.empty(), Optional.of(failure),
			failure.nativeEvidence(), failure.terminalResponseEvidence(), failure.noNativeTurnDispatchProof());
	}
}
