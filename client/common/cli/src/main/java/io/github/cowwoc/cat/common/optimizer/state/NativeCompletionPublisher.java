package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.runner.NativeEventEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionCodec;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunCompletion;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailure;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunRequest;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunResult;
import io.github.cowwoc.cat.common.optimizer.runner.PrivateGenerationContext;
import io.github.cowwoc.cat.common.optimizer.runner.ProjectRunContext;
import io.github.cowwoc.cat.common.optimizer.runner.SterileWorkspaceContext;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Publishes and revalidates native settlements against their original call authority. */
final class NativeCompletionPublisher
{
	private static final String COMPLETIONS = "native-run-completions";
	private final CampaignStore store;

	/**
	 * Binds publication to the owning store.
	 *
	 * @param store pinned campaign store
	 */
	NativeCompletionPublisher(CampaignStore store)
	{
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Retains a validated native settlement in the call's create-once completion index.
	 *
	 * @param binding pinned campaign
	 * @param claimed original call claim and current slot
	 * @param preStart indexed native run and runner identity
	 * @param request source-backed native request
	 * @param completion native settlement
	 * @return indexed completion
	 * @throws IOException if native identity, workspace, or evidence conflicts
	 */
	EvidenceRef publish(CampaignBinding binding, ClaimedSlot claimed, CallPreStartRecord preStart,
		NestedRunRequest request, NestedRunCompletion completion) throws IOException
	{
		NativeRunCompletionEvidence evidence = completionEvidence(binding, claimed, preStart, request, completion);
		EvidenceRef reference = store.appendImmutableJson(COMPLETIONS,
			NativeRunCompletionCodec.encode(evidence, store::readImmutableJson));
		return store.publishNativeCompletionIndex(binding, request.logicalCallId(), reference);
	}

	/**
	 * Revalidates indexed completion before a producer adopts it.
	 *
	 * @param binding pinned campaign
	 * @param claimed original call claim and current slot
	 * @param preStart indexed native run and runner identity
	 * @param request source-backed native request
	 * @return exact indexed settlement
	 * @throws IOException if the index or settlement conflicts with retained authority
	 */
	IndexedCompletion readIndexed(CampaignBinding binding, ClaimedSlot claimed, CallPreStartRecord preStart,
		NestedRunRequest request) throws IOException
	{
		EvidenceRef reference = store.readNativeCompletionIndex(binding, request.logicalCallId()).
			orElseThrow(() -> new IOException("Native completion index is missing"));
		NativeRunCompletionEvidence evidence = NativeRunCompletionCodec.decode(reference, store::readImmutableEvidence);
		NestedRunCompletion settlement = switch (evidence.outcome())
		{
			case SUCCEEDED -> new NestedRunCompletion.Succeeded(evidence.result().orElseThrow());
			case FAILED -> new NestedRunCompletion.Failed(evidence.failure().orElseThrow());
		};
		completionEvidence(binding, claimed, preStart, request, settlement);
		return new IndexedCompletion(reference, evidence, settlement);
	}

	/**
	 * Checks native observations and referenced event bytes against the original request and pre-start.
	 *
	 * @param binding pinned campaign
	 * @param claimed original call authority
	 * @param preStart indexed native identity
	 * @param request source-backed request
	 * @param completion native settlement
	 * @return validated completion evidence
	 * @throws IOException if the settlement or its referenced evidence conflicts
	 */
	private NativeRunCompletionEvidence completionEvidence(CampaignBinding binding, ClaimedSlot claimed,
		CallPreStartRecord preStart, NestedRunRequest request, NestedRunCompletion completion) throws IOException
	{
		String callId = request.logicalCallId();
		EvidenceRef requestRef = claimed.slot().requestRef();
		EvidenceRef claimRef = claimed.slotClaimRef();
		String runId = preStart.runId();
		if (!callId.equals(claimed.slot().logicalCallId()) || !callId.equals(preStart.logicalCallId()) ||
			!requestRef.equals(preStart.requestRef()) || !claimRef.equals(preStart.slotClaimRef()))
			throw new IOException("Native completion sources conflict with the original call claim");
		if (completion instanceof NestedRunCompletion.Succeeded succeeded)
		{
			NestedRunResult result = succeeded.result();
			if (!result.runId().equals(runId) || !request.modelProfile().acceptsObserved(result.observedModelProfile()) ||
				result.turnResults().size() != request.turns().size() ||
				!result.runnerIdentity().runtimeManifestSha256().equals(preStart.runtimeManifestSha256()))
				throw new IOException("Native result conflicts with its request or runner identity");
			requireExecutionProject(request, result);
			List<EvidenceRef> events = result.nativeEventEvidence().stream().map(NativeEventEvidence::evidence).toList();
			for (EvidenceRef event : events)
				store.readImmutableBytes(event);
			return new NativeRunCompletionEvidence(binding.campaignId(), callId, requestRef, claimRef,
				Optional.of(runId), NativeRunCompletionEvidence.Outcome.SUCCEEDED, Optional.of(result),
				Optional.empty(), events, Optional.empty(), Optional.empty());
		}
		NestedRunFailure failure = ((NestedRunCompletion.Failed) completion).failure();
		if (!failure.logicalCallId().equals(callId) || !failure.runId().equals(Optional.of(runId)))
			throw new IOException("Native failure conflicts with its request or runner identity");
		for (EvidenceRef event : failure.nativeEvidence())
			store.readImmutableBytes(event);
		if (failure.terminalResponseEvidence().isPresent())
			store.readImmutableJson(failure.terminalResponseEvidence().orElseThrow());
		return new NativeRunCompletionEvidence(binding.campaignId(), callId, requestRef, claimRef,
			Optional.of(runId), NativeRunCompletionEvidence.Outcome.FAILED, Optional.empty(), Optional.of(failure),
			failure.nativeEvidence(), failure.terminalResponseEvidence(), failure.noNativeTurnDispatchProof());
	}

	/**
	 * Checks the workspace relationship selected by the native request.
	 *
	 * @param request retained native request
	 * @param result reported native result
	 * @throws IOException if the result names a forbidden execution project
	 */
	private static void requireExecutionProject(NestedRunRequest request, NestedRunResult result) throws IOException
	{
		boolean matches = switch (request.context())
		{
			case PrivateGenerationContext generation -> result.executionProject().equals(generation.privateProject());
			case ProjectRunContext project -> switch (project.mode())
			{
				case ACTIVE_WORKTREE -> result.executionProject().equals(project.snapshot().projectRoot());
				case ISOLATED_COPY -> !result.executionProject().equals(project.snapshot().projectRoot());
			};
			case SterileWorkspaceContext ignored -> true;
		};
		if (!matches)
			throw new IOException("Native result execution project conflicts with its request");
	}

	/**
	 * One validated create-once native completion.
	 *
	 * @param reference indexed completion reference
	 * @param evidence typed durable evidence
	 * @param settlement native result or failure
	 */
	record IndexedCompletion(EvidenceRef reference, NativeRunCompletionEvidence evidence, NestedRunCompletion settlement)
	{
	}
}
