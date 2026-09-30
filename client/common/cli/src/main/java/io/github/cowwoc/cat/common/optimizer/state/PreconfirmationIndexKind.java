package io.github.cowwoc.cat.common.optimizer.state;

/**
 * Closed namespace for campaign-bound evidence produced before contract confirmation.
 */
public enum PreconfirmationIndexKind
{
	/** A contract preparation edge. */
	CONTRACT_PREPARATION,
	/** The initial target and Git inventory used to begin preparation. */
	CONTRACT_SOURCE_CAPTURE,
	/** The logical call that owns one contract inspection. */
	CONTRACT_INSPECTION_OWNER,
	/** A contract inspection attempt. */
	CONTRACT_INSPECTION_ATTEMPT,
	/** Complete conditions immediately before an unconfirmed inspection call. */
	CONTRACT_INSPECTION_BEFORE_OBSERVATION,
	/** A reviewed source change requiring a new preparation generation. */
	CONTRACT_SOURCE_CHANGE
}
