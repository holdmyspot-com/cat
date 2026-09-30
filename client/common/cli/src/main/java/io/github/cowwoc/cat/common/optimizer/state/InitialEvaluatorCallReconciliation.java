package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.runner.ActiveCall;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunCompletion;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailure;
import java.util.Objects;

/** Retained native state of one assigned initial evaluator. */
public sealed interface InitialEvaluatorCallReconciliation permits
	InitialEvaluatorCallReconciliation.Settled, InitialEvaluatorCallReconciliation.Active,
	InitialEvaluatorCallReconciliation.Unknown
{
	/**
	 * A native completion retained and adopted before semantic judgment.
	 *
	 * @param executionRef indexed evaluator execution record
	 * @param completion validated native settlement
	 */
	record Settled(EvidenceRef executionRef, NestedRunCompletion completion)
		implements InitialEvaluatorCallReconciliation
	{
		/**
		 * Validates the retained settlement.
		 *
		 * @param executionRef indexed evaluator execution record
		 * @param completion validated native settlement
		 */
		public Settled
		{
			Objects.requireNonNull(executionRef, "executionRef");
			Objects.requireNonNull(completion, "completion");
			if (!executionRef.collection().equals("evaluator-execution-records"))
				throw new IllegalArgumentException("Evaluator settlement requires an execution record");
		}
	}

	/**
	 * The original native evaluator is still running under a recovered handle.
	 *
	 * @param call caller-owned native handle
	 */
	record Active(ActiveCall call) implements InitialEvaluatorCallReconciliation
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
	 * The runner cannot establish whether the original native evaluator completed.
	 *
	 * @param failure retained native uncertainty
	 */
	record Unknown(NestedRunFailure failure) implements InitialEvaluatorCallReconciliation
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
