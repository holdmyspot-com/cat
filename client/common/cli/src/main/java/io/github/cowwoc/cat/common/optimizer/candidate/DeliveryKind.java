package io.github.cowwoc.cat.common.optimizer.candidate;

/** Whether a delivery changes any user-visible path. */
public enum DeliveryKind
{
	/** One or more paths change. */
	MUTATING,
	/** No paths change; a receipt is still required. */
	NO_OP
}
