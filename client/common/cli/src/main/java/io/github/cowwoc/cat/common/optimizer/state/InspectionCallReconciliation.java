package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.runner.NestedRunCompletion;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailure;
import java.util.Objects;

/**
 * Carries the first retained inspection's native state and validated campaign identity.
 */
public sealed interface InspectionCallReconciliation permits InspectionCallReconciliation.Settled,
	InspectionCallReconciliation.Active, InspectionCallReconciliation.Unknown
{
	/**
	 * A completion already adopted by the campaign.
	 *
	 * @param completion terminal native outcome
	 * @param logicalCallId durable scheduler call identity
	 */
	record Settled(NestedRunCompletion completion, String logicalCallId) implements InspectionCallReconciliation
	{
		/**
		 * Validates the terminal outcome.
		 *
		 * @param completion terminal native outcome
		 * @param logicalCallId durable scheduler call identity
		 */
		public Settled
		{
			Objects.requireNonNull(completion, "completion");
			if (logicalCallId == null || logicalCallId.isBlank())
				throw new IllegalArgumentException("Inspection logical call ID must not be blank");
		}
	}

	/**
	 * A retained handle with the identity needed to adopt its result.
	 *
	 * @param started validated inspection identity and caller-owned handle
	 */
	record Active(StartedInspectionCall started) implements InspectionCallReconciliation
	{
		/**
		 * Validates the active inspection.
		 *
		 * @param started validated inspection identity and caller-owned handle
		 */
		public Active
		{
			Objects.requireNonNull(started, "started");
		}
	}

	/**
	 * A retained call whose native outcome cannot yet be determined.
	 *
	 * @param failure native reconciliation failure
	 */
	record Unknown(NestedRunFailure failure) implements InspectionCallReconciliation
	{
		/**
		 * Validates the native failure.
		 *
		 * @param failure native reconciliation failure
		 */
		public Unknown
		{
			Objects.requireNonNull(failure, "failure");
		}
	}
}
