package io.github.cowwoc.cat.common.optimizer.contract;

/**
 * States how a named dependence source is handled.
 */
public enum DependenceDisposition
{
	/**
 * Its absence is retained as evidence.
 */ ABSENT_WITH_EVIDENCE,
	/**
 * It is controlled by the procedure.
 */ CONTROLLED,
	/**
 * It is enforced as a stratum.
 */ STRATIFIED,
	/**
 * It prevents formal certification.
 */ UNRESOLVED
}
