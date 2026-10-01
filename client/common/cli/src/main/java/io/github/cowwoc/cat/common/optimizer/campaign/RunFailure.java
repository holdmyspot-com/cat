package io.github.cowwoc.cat.common.optimizer.campaign;

/**
 * Why a run of a logical trial ended without an outcome.
 */
public enum RunFailure
{
	/**
	 * A provider error, crash or invalid judge result. It counts toward the infrastructure-failure limit.
	 */
	INFRASTRUCTURE,
	/**
	 * A rate-limit rejection. It throttles admissions and does not count toward the limit.
	 */
	RATE_LIMITED,
	/**
	 * CAT interrupted the run, for example at the end of a slice. It does not count toward the limit.
	 */
	INTERRUPTED
}
