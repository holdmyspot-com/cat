package io.github.cowwoc.cat.common.optimizer.candidate;

/** Ownership and cleanup state of one transaction-created directory. */
public enum DeliveryDirectoryPhase
{
	PLANNED,
	CAT_CREATED,
	EXTERNAL_PRESENT,
	REMOVED,
	CONCURRENT_CONTENT_PRESERVED
}
