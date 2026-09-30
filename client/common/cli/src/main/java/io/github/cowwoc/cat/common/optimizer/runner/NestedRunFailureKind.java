package io.github.cowwoc.cat.common.optimizer.runner;

/**
 * NestedRunFailureKind values used by the native runner boundary.
 */
public enum NestedRunFailureKind
{
	/**
	 * START_FAILED value.
	 */
	START_FAILED,
	/**
	 * TRANSPORT_FAILED value.
	 */
	TRANSPORT_FAILED,
	/**
	 * INCOMPLETE_RESPONSE value.
	 */
	INCOMPLETE_RESPONSE,
	/**
	 * INTERRUPTED value.
	 */
	INTERRUPTED,
	/**
	 * RECEIPT_FAILED value.
	 */
	RECEIPT_FAILED,
	/**
	 * CLEANUP_FAILED value.
	 */
	CLEANUP_FAILED,
	/**
	 * NATIVE_STATE_UNKNOWN value.
	 */
	NATIVE_STATE_UNKNOWN
}
