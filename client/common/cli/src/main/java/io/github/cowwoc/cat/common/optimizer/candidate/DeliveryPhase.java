package io.github.cowwoc.cat.common.optimizer.candidate;

/** Durable whole-transaction phase. */
public enum DeliveryPhase
{
	PREPARING,
	PREPARED,
	APPLYING,
	APPLIED,
	ROLLING_BACK,
	ROLLED_BACK,
	CONCURRENT_EDIT_PRESERVED,
	RECOVERY_REQUIRED
}
