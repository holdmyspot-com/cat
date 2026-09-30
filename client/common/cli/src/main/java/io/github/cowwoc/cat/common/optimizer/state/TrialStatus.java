package io.github.cowwoc.cat.common.optimizer.state;

/** The durable state of one logical trial. */
public enum TrialStatus
{
	ASSIGNED,
	RUNNING,
	RETRYABLE,
	COMPLIANT,
	NONCOMPLIANT,
	INVALID,
	INFRASTRUCTURE_FAILED
}
