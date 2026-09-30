package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Retains the typed outcome of one native call for later settlement and candidate admission.
 *
 * @param campaignId the campaign that owns the call
 * @param logicalCallId the call identified by the scheduler
 * @param runRequestRef the immutable native request
 * @param slotClaimRef the immutable scheduler slot claim
 * @param runId the native run identity when a session was started
 * @param outcome whether the call succeeded or failed
 * @param result the native result for a successful call
 * @param failure the native failure for an unsuccessful call
 * @param orderedNativeEvidenceRefs the native events in observed order
 * @param terminalResponseEvidence the native terminal-response marker when available
 * @param noDispatchProof verified evidence that no native turn was dispatched when available
 */
public record NativeRunCompletionEvidence(String campaignId, String logicalCallId, EvidenceRef runRequestRef,
	EvidenceRef slotClaimRef, Optional<String> runId, Outcome outcome, Optional<NestedRunResult> result,
	Optional<NestedRunFailure> failure, List<EvidenceRef> orderedNativeEvidenceRefs,
	Optional<EvidenceRef> terminalResponseEvidence, Optional<NoNativeTurnDispatchProof> noDispatchProof)
{
	/**
	 * Validates the intrinsic relationship between the typed outcome and retained native evidence.
	 *
	 * @param campaignId the campaign that owns the call
	 * @param logicalCallId the call identified by the scheduler
	 * @param runRequestRef the immutable native request
	 * @param slotClaimRef the immutable scheduler slot claim
	 * @param runId the native run identity when a session was started
	 * @param outcome whether the call succeeded or failed
	 * @param result the native result for a successful call
	 * @param failure the native failure for an unsuccessful call
	 * @param orderedNativeEvidenceRefs the native events in observed order
	 * @param terminalResponseEvidence the native terminal-response marker when available
	 * @param noDispatchProof verified evidence that no native turn was dispatched when available
	 * @throws NullPointerException if a required field or container is null
	 * @throws IllegalArgumentException if an identity is blank or the outcome contradicts its payload
	 */
	public NativeRunCompletionEvidence
	{
		RunnerIdentity.ObservationRules.check(campaignId, "campaignId");
		RunnerIdentity.ObservationRules.check(logicalCallId, "logicalCallId");
		Objects.requireNonNull(runRequestRef, "runRequestRef");
		Objects.requireNonNull(slotClaimRef, "slotClaimRef");
		Objects.requireNonNull(runId, "runId");
		runId.ifPresent(value -> RunnerIdentity.ObservationRules.check(value, "runId"));
		Objects.requireNonNull(outcome, "outcome");
		Objects.requireNonNull(result, "result");
		Objects.requireNonNull(failure, "failure");
		orderedNativeEvidenceRefs = List.copyOf(orderedNativeEvidenceRefs);
		Objects.requireNonNull(terminalResponseEvidence, "terminalResponseEvidence");
		Objects.requireNonNull(noDispatchProof, "noDispatchProof");

		switch (outcome)
		{
			case SUCCEEDED ->
			{
				if (result.isEmpty() || failure.isPresent() || runId.isEmpty() || noDispatchProof.isPresent())
					throw new IllegalArgumentException("successful completion requires a result and run ID only");
				NestedRunResult successfulResult = result.orElseThrow();
				if (!runId.orElseThrow().equals(successfulResult.runId()) ||
					!orderedNativeEvidenceRefs.equals(successfulResult.nativeEventEvidence().stream().
						map(NativeEventEvidence::evidence).toList()))
					throw new IllegalArgumentException("successful completion differs from native result evidence");
			}
			case FAILED ->
			{
				if (failure.isEmpty() || result.isPresent())
					throw new IllegalArgumentException("failed completion requires a failure only");
				NestedRunFailure failedResult = failure.orElseThrow();
				if (!logicalCallId.equals(failedResult.logicalCallId()) || !runId.equals(failedResult.runId()) ||
					!orderedNativeEvidenceRefs.equals(failedResult.nativeEvidence()) ||
					!terminalResponseEvidence.equals(failedResult.terminalResponseEvidence()) ||
					!noDispatchProof.equals(failedResult.noNativeTurnDispatchProof()) ||
					(terminalResponseEvidence.isPresent() && noDispatchProof.isPresent()))
					throw new IllegalArgumentException("failed completion differs from native failure evidence");
			}
		}
	}

	/**
	 * Distinguishes a successful native call from an unsuccessful one.
	 */
	public enum Outcome
	{
		SUCCEEDED,
		FAILED
	}
}
