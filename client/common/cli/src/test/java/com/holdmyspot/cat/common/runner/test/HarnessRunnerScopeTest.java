package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Tests {@link HarnessRunnerScope}.
 */
public final class HarnessRunnerScopeTest
{
	/**
	 * Creates the test fixture.
	 */
	public HarnessRunnerScopeTest()
	{
	}

	/**
	 * Verifies the mapper of a live run scope writes indented JSON.
	 */
	@Test
	public void writesIndentedJson()
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope())
		{
			requireThat(scope.json().writeValueAsString(scope.json().createObjectNode().put("name", "CAT")), "json").
				contains("\n");
		}
	}

	/**
	 * Verifies a closed scope rejects further service access.
	 */
	@Test(expectedExceptions = IllegalStateException.class)
	public void rejectsServiceAccessAfterClosure()
	{
		closedScope().json();
	}

	/**
	 * Creates and closes a scope before returning it for a post-close contract test.
	 *
	 * @return the closed scope
	 */
	private static HarnessRunnerScope closedScope()
	{
		HarnessRunnerScope scope = new TestHarnessRunnerScope();
		try (scope)
		{
		}
		return scope;
	}
}
