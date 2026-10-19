package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;

/**
 * Exposes how promotion compares drawn test cases to the module's tests.
 */
@FunctionalInterface
public interface PromotionAccess
{
	/**
	 * Indicates whether two drawn test cases are the same test case, regardless of how they were drawn or marked.
	 *
	 * @param first a drawn test case
	 * @param second another drawn test case
	 * @return {@code true} if both have the same cell, prompt test, variable choices and turns
	 */
	boolean isSameTestCase(DrawnTestCase first, DrawnTestCase second);
}
