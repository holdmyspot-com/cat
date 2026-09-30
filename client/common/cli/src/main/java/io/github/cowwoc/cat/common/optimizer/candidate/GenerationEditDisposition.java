package io.github.cowwoc.cat.common.optimizer.candidate;

import java.util.Objects;

/** A gateway edit is either accepted with an exact receipt or rejected with call-bound evidence. */
public sealed interface GenerationEditDisposition permits GenerationEditDisposition.Accepted,
	GenerationEditDisposition.Rejected
{
	/**
	 * Accepted edit and its immutable receipt.
	 *
	 * @param receipt verified accepted edit
	 */
	record Accepted(GenerationEditReceipt receipt) implements GenerationEditDisposition
	{
		/**
		 * Requires a receipt.
		 *
		 * @param receipt verified accepted edit
		 */
		public Accepted
		{
			Objects.requireNonNull(receipt, "receipt");
		}
	}

	/**
	 * Rejected edit and its immutable denial.
	 *
	 * @param rejection call-bound denial
	 */
	record Rejected(GenerationEditRejection rejection) implements GenerationEditDisposition
	{
		/**
		 * Requires a rejection.
		 *
		 * @param rejection call-bound denial
		 */
		public Rejected
		{
			Objects.requireNonNull(rejection, "rejection");
		}
	}
}
