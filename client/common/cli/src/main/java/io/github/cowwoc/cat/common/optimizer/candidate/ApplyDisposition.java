package io.github.cowwoc.cat.common.optimizer.candidate;

/** The only successful reconciliation outcomes. */
public enum ApplyDisposition
{
	/** The selected candidate was made visible. */
	APPLIED,
	/** The selected candidate was already completely visible. */
	ALREADY_APPLIED
}
