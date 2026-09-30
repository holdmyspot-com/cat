package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.runner.ActiveCall;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunCompletion;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailure;
import java.util.Objects;

/** Retained native state of one assigned initial trial. */
public sealed interface InitialTrialCallReconciliation permits InitialTrialCallReconciliation.Settled,
	InitialTrialCallReconciliation.Active, InitialTrialCallReconciliation.Unknown
{
	/**
	 * A native completion retained and adopted for later semantic evaluation.
	 *
	 * @param executionRef indexed trial execution record
	 * @param completion validated native settlement
	 */
	record Settled(EvidenceRef executionRef, NestedRunCompletion completion)
		implements InitialTrialCallReconciliation
	{
		/**
		 * Validates the retained settlement.
		 *
		 * @param executionRef indexed trial execution record
		 * @param completion validated native settlement
		 */
		public Settled
		{
			Objects.requireNonNull(executionRef, "executionRef");
			Objects.requireNonNull(completion, "completion");
			if (!executionRef.collection().equals("trial-execution-records"))
				throw new IllegalArgumentException("Trial settlement requires an execution record");
		}
	}

	/**
	 * The original native trial is still running under a recovered handle.
	 *
	 * @param call caller-owned native handle
	 */
	record Active(ActiveCall call) implements InitialTrialCallReconciliation
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
	 * The runner cannot establish whether the original native trial completed.
	 *
	 * @param failure retained native uncertainty
	 */
	record Unknown(NestedRunFailure failure) implements InitialTrialCallReconciliation
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
