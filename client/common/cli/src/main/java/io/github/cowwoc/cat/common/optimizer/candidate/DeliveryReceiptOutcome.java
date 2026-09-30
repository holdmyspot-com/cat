package io.github.cowwoc.cat.common.optimizer.candidate;

/** Archived whole-transaction outcome. */
public enum DeliveryReceiptOutcome
{
	APPLIED,
	ALREADY_APPLIED,
	ROLLED_BACK,
	CONCURRENT_EDIT_PRESERVED
}
