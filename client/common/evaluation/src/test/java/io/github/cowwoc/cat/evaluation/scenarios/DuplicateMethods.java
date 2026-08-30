package io.github.cowwoc.cat.evaluation.scenarios;

import org.testng.ITestContext;
import org.testng.annotations.Test;

/**
 * Supplies two same-named scenario checks to test ambiguity rejection.
 */
public final class DuplicateMethods
{
	/**
	 * Supplies one ambiguous check overload.
	 */
	@Test
	public void duplicate()
	{
	}

	/**
	 * Supplies another ambiguous check overload.
	 *
	 * @param context the TestNG context populated by the evaluator
	 */
	@Test
	public void duplicate(ITestContext context)
	{
	}
}
