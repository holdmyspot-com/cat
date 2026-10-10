package io.github.cowwoc.cat.common.runner.test;

import io.github.cowwoc.cat.common.runner.HarnessRunnerScope;
import io.github.cowwoc.cat.common.runner.fakes.test.TestHarnessRunnerScope;
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
	 * Verifies callers receive the one mapper owned by a live run scope.
	 */
	@Test
	public void returnsSharedJsonMapper()
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope())
		{
			requireThat(scope.json() == scope.json(), "sameMapper").isTrue();
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
