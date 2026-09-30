package io.github.cowwoc.cat.common.optimizer.candidate;

/** Whether a confirmed known source exists in the selected live project. */
public enum KnownSourcePresence
{
	/** The pathname is absent from the live project. */
	ABSENT,
	/** The pathname contains a regular file. */
	REGULAR
}
