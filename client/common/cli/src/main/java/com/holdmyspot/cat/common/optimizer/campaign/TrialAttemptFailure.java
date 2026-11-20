package com.holdmyspot.cat.common.optimizer.campaign;

/**
 * Why a trial attempt ended without an outcome.
 */
public enum TrialAttemptFailure
{
	/**
	 * A provider error, crash or invalid judge result. It counts toward the infrastructure-failure limit.
	 */
	INFRASTRUCTURE,
	/**
	 * A rate-limit rejection, or a refusal of the request by the provider with HTTP status 403, which the attempt's
	 * detail names. The harness's shared hold throttles admissions, and the attempt does not count toward the limit.
	 */
	RATE_LIMITED,
	/**
	 * CAT interrupted the attempt, for example when its current work allowance ended. It does not count toward the limit.
	 */
	INTERRUPTED
}
