package io.github.cowwoc.cat.common.optimizer.state;

/** Closed purposes that prohibit ordinary scheduling until a lifecycle transition consumes the proof. */
public enum QuiescencePurpose
{
	CONTRACT_REVISION,
	CERTIFICATION_FINALIZATION
}
