package com.holdmyspot.cat.common.optimizer.contract;

/**
 * Indicates whether an assertion is one that the target prompt exists to make true, or one that protects an operation
 * the prompt must not harm.
 */
public enum AssertionKind
{
	/**
	 * The target prompt exists to make the assertion true, such as reading a long file whole.
	 */
	EFFECT,
	/**
	 * An agent passes the assertion almost always even without the target prompt, such as answering a search question
	 * correctly; it protects an operation that the prompt must not harm.
	 */
	GUARD
}
