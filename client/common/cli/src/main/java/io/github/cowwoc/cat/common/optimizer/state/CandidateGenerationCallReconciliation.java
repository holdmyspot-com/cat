package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.runner.ActiveCall;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunCompletion;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailure;
import java.util.Objects;

/** Retained native state of one candidate generation attempt. */
public sealed interface CandidateGenerationCallReconciliation permits CandidateGenerationCallReconciliation.Settled,
	CandidateGenerationCallReconciliation.Active, CandidateGenerationCallReconciliation.Unknown
{
	/**
	 * A native completion retained and adopted for later candidate processing.
	 *
	 * @param executionRef indexed generation execution record
	 * @param completion validated native settlement
	 */
	record Settled(EvidenceRef executionRef, NestedRunCompletion completion)
		implements CandidateGenerationCallReconciliation
	{
		/**
		 * Validates the retained settlement.
		 *
		 * @param executionRef indexed generation execution record
		 * @param completion validated native settlement
		 */
		public Settled
		{
			Objects.requireNonNull(executionRef, "executionRef");
			Objects.requireNonNull(completion, "completion");
			if (!executionRef.collection().equals(CandidateGenerationExecutionRecord.COLLECTION))
				throw new IllegalArgumentException("Generation settlement requires an execution record");
		}
	}

	/**
	 * The original native generation is still running under a recovered handle.
	 *
	 * @param call caller-owned native handle
	 */
	record Active(ActiveCall call) implements CandidateGenerationCallReconciliation
	{
		/**
		 * Validates the recovered handle.
		 *
		 * @param call caller-owned native handle
		 */
		public Active
		{
			Objects.requireNonNull(call, "call");
		}
	}

	/**
	 * The runner cannot establish whether the original native generation completed.
	 *
	 * @param failure retained native uncertainty
	 */
	record Unknown(NestedRunFailure failure) implements CandidateGenerationCallReconciliation
	{
		/**
		 * Validates the uncertainty evidence.
		 *
		 * @param failure retained native uncertainty
		 */
		public Unknown
		{
			Objects.requireNonNull(failure, "failure");
		}
	}
}
